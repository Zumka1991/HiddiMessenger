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
import ru.hiddi.messenger.network.RealtimeConnection
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.PrivacySettingsStore
import ru.hiddi.messenger.security.SignalStateRepository
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps one long-poll connection alive while the user has enabled Hiddi on this device.
 * Signal decryption and notification text are produced locally; the server only sees an
 * opaque encrypted envelope.
 */
class MessagingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var realtimeJob: Job? = null
    private var realtimeConnection: RealtimeConnection? = null
    @Volatile private var watchedPeer: String? = null
    @Volatile private var invisibleMode: Boolean = false

    override fun onCreate() {
        super.onCreate()
        invisibleMode = PrivacySettingsStore(this).invisibleMode()
        createNotificationChannels()
        startForeground(FOREGROUND_NOTIFICATION_ID, foregroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WATCH_PRESENCE -> {
                watchedPeer =
                    intent.getStringExtra(EXTRA_PEER)
                        ?.trim()
                        ?.removePrefix("@")
                        ?.lowercase()
                        ?.takeIf(String::isNotBlank)
                watchedPeer?.let { realtimeConnection?.subscribePresence(it) }
            }
            ACTION_TYPING -> {
                val peer = intent.getStringExtra(EXTRA_PEER)
                if (!peer.isNullOrBlank()) {
                    realtimeConnection?.sendTyping(
                        peer,
                        intent.getBooleanExtra(EXTRA_TYPING, false) && !invisibleMode,
                    )
                }
            }
            ACTION_SET_INVISIBLE -> {
                invisibleMode = intent.getBooleanExtra(EXTRA_INVISIBLE, false)
                PrivacySettingsStore(this).setInvisibleMode(invisibleMode)
                realtimeConnection?.setVisible(!invisibleMode)
            }
        }
        if (realtimeJob?.isActive != true) {
            publishConnection(STATE_CONNECTING)
            realtimeJob = serviceScope.launch { runRealtime() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        realtimeJob?.cancel()
        realtimeConnection?.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    private suspend fun runRealtime() {
        val profile = AccountStore(this).read() ?: run {
            stopSelf()
            return
        }
        val repository = SignalStateRepository(this).also {
            it.migrateEncryptionFormat()
        }
        val api = SignalMessagingApi(repository)
        val history = EncryptedChatHistory(this)
        val attachments = EncryptedAttachmentStore(this)
        val groupCoordinator = GroupMlsCoordinator(this, api)
        var retryDelay = 1_000L

        downloadPendingAttachments(profile, api, history, attachments)

        while (serviceScope.isActive) {
            val connection = RealtimeConnection()
            realtimeConnection = connection
            publishConnection(STATE_CONNECTING)
            connection.connect(profile, invisibleMode)
            try {
                var connected = false
                while (serviceScope.isActive) {
                    when (val event = connection.events.receive()) {
                        RealtimeConnection.Event.Connected -> {
                            connected = true
                            retryDelay = 1_000L
                            publishConnection(STATE_ONLINE)
                            watchedPeer?.let(connection::subscribePresence)
                        }
                        is RealtimeConnection.Event.Presence -> {
                            sendBroadcast(
                                Intent(ACTION_PRESENCE_CHANGED)
                                    .setPackage(packageName)
                                    .putExtra(EXTRA_PEER, event.nickname)
                                    .putExtra(EXTRA_ONLINE, event.online),
                            )
                        }
                        is RealtimeConnection.Event.Typing -> {
                            sendBroadcast(
                                Intent(ACTION_TYPING_CHANGED)
                                    .setPackage(packageName)
                                    .putExtra(EXTRA_PEER, event.nickname)
                                    .putExtra(EXTRA_TYPING, event.typing),
                            )
                        }
                        is RealtimeConnection.Event.SyncRequired -> {
                            synchronize(
                                profile = profile,
                                api = api,
                                history = history,
                                attachments = attachments,
                                groupCoordinator = groupCoordinator,
                            )
                            if (event.kind == "profile" || event.kind == "sync_required") {
                                sendBroadcast(Intent(ACTION_PROFILES_UPDATED).setPackage(packageName))
                            }
                        }
                        is RealtimeConnection.Event.Disconnected -> {
                            event.cause?.let {
                                Log.w(TAG, "Realtime disconnected: ${it.javaClass.simpleName}")
                            }
                            break
                        }
                    }
                }
                if (!connected) {
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Realtime synchronization failed: ${error.javaClass.simpleName}")
                retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
            } finally {
                connection.close()
                if (realtimeConnection === connection) realtimeConnection = null
            }
            publishConnection(STATE_OFFLINE)
            delay(retryDelay)
        }
    }

    private suspend fun synchronize(
        profile: ru.hiddi.messenger.network.AccountProfile,
        api: SignalMessagingApi,
        history: EncryptedChatHistory,
        attachments: EncryptedAttachmentStore,
        groupCoordinator: GroupMlsCoordinator,
    ) {
        var messagesChanged = false
        api.pendingMessageDeletions(profile).forEach { deletion ->
            history.deleteMessage(deletion.messageId).forEach { descriptor ->
                runCatching { attachments.delete(descriptor.attachmentId) }
            }
            api.acknowledgeMessageDeletion(profile, deletion.deletionId)
            messagesChanged = true
        }
        api.pendingConversationDeletions(profile).forEach { deletion ->
            history.clearConversation(deletion.peerNickname).forEach { descriptor ->
                runCatching { attachments.delete(descriptor.attachmentId) }
            }
            api.acknowledgeConversationDeletion(profile, deletion.deletionId)
            messagesChanged = true
        }
        api.inbox(profile).forEach { message ->
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
                    messageId = message.messageId,
                ),
            )
            api.acknowledgeMessage(profile, message.messageId)
            descriptor?.let {
                listOfNotNull(it.preview, it).forEach { part ->
                    runCatching { downloadAttachment(profile, api, attachments, part.attachmentId) }
                        .onFailure { Log.w(TAG, "Attachment download will be retried") }
                }
            }
            if (!MainActivity.isVisible) showMessageNotification(message.senderNickname)
            messagesChanged = true
        }
        if (messagesChanged) {
            sendBroadcast(Intent(ACTION_MESSAGES_UPDATED).setPackage(packageName))
        }

        // MLS state may need a separate recovery after an interrupted epoch update.
        // Do not let that prevent direct messages and the realtime socket from working.
        runCatching { groupCoordinator.synchronize(profile) }
            .onSuccess { changedGroups ->
                if (changedGroups.isNotEmpty()) {
                    sendBroadcast(Intent(ACTION_GROUPS_UPDATED).setPackage(packageName))
                    if (!MainActivity.isVisible) showGroupNotification()
                }
            }
            .onFailure { error ->
                Log.w(TAG, "MLS group sync deferred: ${error.javaClass.simpleName}")
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
        const val ACTION_PROFILES_UPDATED = "ru.hiddi.messenger.PROFILES_UPDATED"
        const val ACTION_CONNECTION_CHANGED = "ru.hiddi.messenger.CONNECTION_CHANGED"
        const val ACTION_WATCH_PRESENCE = "ru.hiddi.messenger.WATCH_PRESENCE"
        const val ACTION_TYPING = "ru.hiddi.messenger.TYPING"
        const val ACTION_SET_INVISIBLE = "ru.hiddi.messenger.SET_INVISIBLE"
        const val ACTION_PRESENCE_CHANGED = "ru.hiddi.messenger.PRESENCE_CHANGED"
        const val ACTION_TYPING_CHANGED = "ru.hiddi.messenger.TYPING_CHANGED"
        const val EXTRA_OPEN_PEER = "ru.hiddi.messenger.extra.OPEN_PEER"
        const val EXTRA_CONNECTION_STATE = "ru.hiddi.messenger.extra.CONNECTION_STATE"
        const val EXTRA_PEER = "ru.hiddi.messenger.extra.PEER"
        const val EXTRA_TYPING = "ru.hiddi.messenger.extra.TYPING"
        const val EXTRA_INVISIBLE = "ru.hiddi.messenger.extra.INVISIBLE"
        const val EXTRA_ONLINE = "ru.hiddi.messenger.extra.ONLINE"
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
