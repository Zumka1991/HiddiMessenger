package ru.hiddi.messenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.hiddi.messenger.network.AccountStore
import ru.hiddi.messenger.network.GroupMlsCoordinator
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.SignalStateRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps one long-poll connection alive while the user has enabled Hiddi on this device.
 * Signal decryption and notification text are produced locally; the server only sees an
 * opaque encrypted envelope.
 */
class MessagingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var groupPollingJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (pollingJob?.isActive != true) {
            publishConnection(STATE_CONNECTING)
            pollingJob = serviceScope.launch { poll() }
        }
        if (groupPollingJob?.isActive != true) {
            groupPollingJob = serviceScope.launch { pollGroups() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        pollingJob?.cancel()
        groupPollingJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    private suspend fun poll() {
        val profile = AccountStore(this).read() ?: run {
            stopSelf()
            return
        }
        val api = SignalMessagingApi(SignalStateRepository(this))
        val history = EncryptedChatHistory(this)
        val attachments = EncryptedAttachmentStore(this)
        var retryDelay = 1_000L

        downloadPendingAttachments(profile, api, history, attachments)

        while (serviceScope.isActive) {
            try {
                api.pendingConversationDeletions(profile).forEach { peer ->
                    history.clearConversation(peer).forEach { descriptor ->
                        runCatching { attachments.delete(descriptor.attachmentId) }
                    }
                    sendBroadcast(Intent(ACTION_MESSAGES_UPDATED).setPackage(packageName))
                }
                if (api.waitForIncoming(profile)) {
                    val messages = api.inbox(profile)
                    messages.forEach { message ->
                        val descriptor = runCatching {
                            EncryptedAttachmentStore.parseEnvelope(message.text)
                        }.getOrNull()
                        history.append(
                            ChatHistoryItem(
                                peer = message.senderNickname,
                                text = when (descriptor?.kind) {
                                    EncryptedAttachmentStore.IMAGE_KIND -> "📷 Изображение"
                                    EncryptedAttachmentStore.VOICE_KIND -> "🎙 Голосовое сообщение"
                                    else -> message.text
                                },
                                outgoing = false,
                                time = message.createdAt,
                                unread = true,
                                attachment = descriptor,
                            ),
                        )
                        descriptor?.let {
                            listOfNotNull(it.preview, it).forEach { part ->
                                runCatching { downloadAttachment(profile, api, attachments, part.attachmentId) }
                                    .onFailure { Log.w(TAG, "Attachment download will be retried") }
                            }
                        }
                        if (!MainActivity.isVisible) showMessageNotification(message.senderNickname)
                    }
                    if (messages.isNotEmpty()) {
                        sendBroadcast(Intent(ACTION_MESSAGES_UPDATED).setPackage(packageName))
                    }
                }
                publishConnection(STATE_ONLINE)
                retryDelay = 1_000L
            } catch (error: Exception) {
                Log.w(TAG, "Background receive failed: ${error.javaClass.simpleName}")
                publishConnection(STATE_OFFLINE)
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun pollGroups() {
        val profile = AccountStore(this).read() ?: return
        val api = SignalMessagingApi(SignalStateRepository(this))
        val coordinator = GroupMlsCoordinator(this, api)
        var retryDelay = 1_000L
        // Startup prepares the native provider and publishes a KeyPackage first.
        delay(2_000)
        while (serviceScope.isActive) {
            try {
                val changed = coordinator.synchronize(profile)
                if (changed.isNotEmpty()) {
                    sendBroadcast(Intent(ACTION_GROUPS_UPDATED).setPackage(packageName))
                    if (!MainActivity.isVisible) showGroupNotification()
                }
                retryDelay = 1_000L
                if (!api.waitForGroupEvent(profile)) delay(1_000)
            } catch (error: Exception) {
                Log.w(TAG, "Background MLS receive failed: ${error.javaClass.simpleName}")
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun downloadPendingAttachments(
        profile: ru.hiddi.messenger.network.AccountProfile,
        api: SignalMessagingApi,
        history: EncryptedChatHistory,
        attachments: EncryptedAttachmentStore,
    ) {
        history.pendingIncomingAttachments().forEach { descriptor ->
            runCatching { downloadAttachment(profile, api, attachments, descriptor.attachmentId) }
                .onFailure { Log.w(TAG, "Pending attachment is not available yet") }
        }
    }

    private suspend fun downloadAttachment(
        profile: ru.hiddi.messenger.network.AccountProfile,
        api: SignalMessagingApi,
        attachments: EncryptedAttachmentStore,
        attachmentId: String,
    ) {
        if (!attachments.exists(attachmentId)) {
            attachments.saveCiphertext(attachmentId, api.downloadAttachment(profile, attachmentId))
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CONNECTION_CHANNEL,
                "Соединение Hiddi",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Фоновый приём зашифрованных сообщений"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                MESSAGE_CHANNEL,
                "Новые сообщения",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Уведомления о новых сообщениях Hiddi"
            },
        )
    }

    private fun publishConnection(state: String) {
        sendBroadcast(
            Intent(ACTION_CONNECTION_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_CONNECTION_STATE, state),
        )
        getSystemService(NotificationManager::class.java)
            .notify(FOREGROUND_NOTIFICATION_ID, foregroundNotification(state))
    }

    private fun foregroundNotification(state: String = STATE_CONNECTING) = NotificationCompat.Builder(this, CONNECTION_CHANNEL)
        .setSmallIcon(R.drawable.ic_hiddi_notification)
        .setContentTitle("Hiddi")
        .setContentText(
            when (state) {
                STATE_ONLINE -> "Защищённое подключение активно"
                STATE_OFFLINE -> "Нет связи с сервером — повторяем подключение"
                else -> "Проверяем защищённое подключение…"
            },
        )
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .setSilent(true)
        .setShowWhen(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun showMessageNotification(sender: String) {
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_hiddi_notification)
            .setContentTitle("Hiddi")
            .setContentText("Новое защифрованное сообщение от @$sender")
            .setContentIntent(openAppIntent(sender))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        getSystemService(NotificationManager::class.java).notify(nextNotificationId.incrementAndGet(), notification)
    }

    private fun showGroupNotification() {
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_hiddi_notification)
            .setContentTitle("Hiddi")
            .setContentText("Новое сообщение в защищённой группе")
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(nextNotificationId.incrementAndGet(), notification)
    }

    private fun openAppIntent(peer: String? = null): PendingIntent = PendingIntent.getActivity(
        this,
        peer?.hashCode() ?: 0,
        Intent(this, MainActivity::class.java)
            .setAction(peer?.let { "ru.hiddi.messenger.OPEN_CHAT.$it" })
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { peer?.let { putExtra(EXTRA_OPEN_PEER, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_MESSAGES_UPDATED = "ru.hiddi.messenger.MESSAGES_UPDATED"
        const val ACTION_GROUPS_UPDATED = "ru.hiddi.messenger.GROUPS_UPDATED"
        const val ACTION_CONNECTION_CHANGED = "ru.hiddi.messenger.CONNECTION_CHANGED"
        const val EXTRA_OPEN_PEER = "ru.hiddi.messenger.extra.OPEN_PEER"
        const val EXTRA_CONNECTION_STATE = "ru.hiddi.messenger.extra.CONNECTION_STATE"
        const val STATE_CONNECTING = "connecting"
        const val STATE_ONLINE = "online"
        const val STATE_OFFLINE = "offline"
        private const val CONNECTION_CHANNEL = "hiddi_connection_v1"
        private const val MESSAGE_CHANNEL = "hiddi_messages_v1"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val TAG = "HiddiMessaging"
        private val nextNotificationId = AtomicInteger(2000)
    }
}
