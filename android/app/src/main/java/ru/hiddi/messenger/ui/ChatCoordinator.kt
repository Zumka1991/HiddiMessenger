package ru.hiddi.messenger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.InMemoryVoiceRecorder
import ru.hiddi.messenger.security.SignalStateRepository
import ru.hiddi.messenger.security.TrustedSafetyNumberStore
import ru.hiddi.messenger.security.readSafetyQr
import ru.hiddi.messenger.security.safetyQrBitmap
import ru.hiddi.messenger.security.playVoicePcm
import ru.hiddi.messenger.security.sanitizeImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@androidx.compose.runtime.Composable
fun ChatScreen(profile: AccountProfile, requestedPeer: String?, resumeRevision: Int, onPeerOpened: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var recipient by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var foundUsers by remember { mutableStateOf(emptyList<String>()) }
    var searchError by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("Защищено · E2EE") }
    var attachmentInProgress by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    val api = remember { SignalMessagingApi(ru.hiddi.messenger.security.SignalStateRepository(context)) }
    val historyStore = remember { EncryptedChatHistory(context) }
    val attachmentStore = remember { EncryptedAttachmentStore(context) }
    val voiceRecorder = remember { InMemoryVoiceRecorder() }
    val trustedSafetyNumbers = remember { TrustedSafetyNumberStore(context) }
    var history by remember { mutableStateOf(emptyList<ChatHistoryItem>()) }
    var peers by remember { mutableStateOf(historyStore.peers()) }
    var historyRevision by remember { mutableIntStateOf(0) }
    var connection by remember { mutableStateOf(ServerConnection.CHECKING) }
    var showClearHistoryDialog by rememberSaveable { mutableStateOf(false) }
    var clearForBothSides by rememberSaveable { mutableStateOf(false) }
    var showSafetyNumberDialog by rememberSaveable { mutableStateOf(false) }
    var safetyNumber by rememberSaveable { mutableStateOf<String?>(null) }
    var safetyNumberTrusted by rememberSaveable { mutableStateOf(false) }
    val safetyQrPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val scanned = withContext(Dispatchers.IO) { readSafetyQr(context.contentResolver, uri) }
            val expected = safetyNumber?.replace(" ", "")
            if (scanned != null && scanned == expected && recipient != null) {
                trustedSafetyNumbers.trust(recipient!!, safetyNumber!!)
                safetyNumberTrusted = true
                status = "QR-код совпал · ключ подтверждён"
            } else {
                status = "QR-код не совпадает с ключом этого диалога"
            }
        }
    }
    fun confirmScannedSafetyQr(scanned: String?) {
        val expected = safetyNumber?.replace(" ", "")
        if (scanned != null && scanned == expected && recipient != null) {
            trustedSafetyNumbers.trust(recipient!!, safetyNumber!!)
            safetyNumberTrusted = true
            status = "QR-код совпал · ключ подтверждён"
        } else {
            status = "QR-код не совпадает с ключом этого диалога"
        }
    }
    val safetyQrCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap ?: return@rememberLauncherForActivityResult
        scope.launch {
            confirmScannedSafetyQr(withContext(Dispatchers.Default) { readSafetyQr(bitmap) })
        }
    }

    fun openConversation(nickname: String) {
        val peer = nickname.removePrefix("@").lowercase()
        historyStore.markRead(peer)
        recipient = peer
        history = historyStore.messagesWith(peer)
        historyRevision++
        draft = ""
        foundUsers = emptyList()
        searchError = null
        search = ""
        focusManager.clearFocus()
    }

    fun findUser() {
        val query = search.trim()
        if (query.removePrefix("@").length < 3) {
            foundUsers = emptyList()
            searchError = "Введите минимум 3 символа никнейма"
            return
        }
        searching = true
        foundUsers = emptyList()
        searchError = null
        scope.launch {
            try {
                val users = api.findUsers(profile, query)
                    .map { it.nickname }
                    .filterNot { it == profile.nickname }
                foundUsers = users
                searchError = if (users.isEmpty()) "Пользователи не найдены" else null
                status = if (users.isEmpty()) "Поиск завершён" else "Найдено: ${users.size}"
                focusManager.clearFocus()
            } catch (error: Exception) {
                foundUsers = emptyList()
                searchError = when (error.message) {
                    "invalid nickname query" -> "Никнейм содержит недопустимые символы"
                    else -> "Не удалось выполнить поиск"
                }
                status = searchError!!
            } finally {
                searching = false
            }
        }
    }

    fun refreshConnection() {
        connection = ServerConnection.CHECKING
        scope.launch {
            connection = if (api.serverReachable(profile)) ServerConnection.ONLINE else ServerConnection.OFFLINE
            status = when (connection) {
                ServerConnection.ONLINE -> "Сервер доступен"
                ServerConnection.OFFLINE -> "Нет связи с сервером"
                ServerConnection.CHECKING -> "Проверяем сервер…"
            }
        }
    }

    fun clearLocalConversation() {
        val target = recipient ?: return
        val descriptors = historyStore.clearConversation(target)
        descriptors.forEach { descriptor -> runCatching { attachmentStore.delete(descriptor.attachmentId) } }
        history = emptyList()
        peers = historyStore.peers()
        historyRevision++
        status = "История удалена с этого устройства"
    }

    fun clearCurrentConversation() {
        val target = recipient ?: return
        showClearHistoryDialog = false
        if (!clearForBothSides) {
            clearLocalConversation()
            return
        }
        scope.launch {
            try {
                api.deleteConversationForBoth(profile, target)
                clearLocalConversation()
                status = "История удалена у вас и будет очищена у @$target"
            } catch (error: Exception) {
                status = error.message ?: "Не удалось удалить историю у собеседника"
            }
        }
    }

    fun sendImage(uri: Uri) {
        val target = recipient ?: return
        if (attachmentInProgress) return
        attachmentInProgress = true
        status = "Подготавливаем и шифруем фото…"
        scope.launch {
            val uploadedIds = mutableListOf<String>()
            var messageSent = false
            try {
                val (full, preview) = withContext(Dispatchers.IO) {
                    val sanitized = sanitizeImage(context, uri)
                    try {
                        Pair(
                            attachmentStore.encrypt(
                                plain = sanitized.full,
                                kind = EncryptedAttachmentStore.IMAGE_KIND,
                                mimeType = EncryptedAttachmentStore.JPEG_MIME,
                            ),
                            attachmentStore.encrypt(
                                plain = sanitized.preview,
                                kind = EncryptedAttachmentStore.IMAGE_KIND,
                                mimeType = EncryptedAttachmentStore.JPEG_MIME,
                            ),
                        )
                    } finally {
                        sanitized.full.fill(0)
                        sanitized.preview.fill(0)
                    }
                }
                try {
                    status = "Загружаем зашифрованное превью…"
                    val previewId = api.uploadAttachment(profile, target, preview.ciphertext)
                    uploadedIds += previewId
                    status = "Загружаем зашифрованный оригинал…"
                    val fullId = api.uploadAttachment(profile, target, full.ciphertext)
                    uploadedIds += fullId
                    val descriptor = full.descriptor(fullId).copy(preview = preview.descriptor(previewId))
                    withContext(Dispatchers.IO) {
                        attachmentStore.saveCiphertext(previewId, preview.ciphertext)
                        attachmentStore.saveCiphertext(fullId, full.ciphertext)
                    }
                    val messageId = api.send(profile, target, EncryptedAttachmentStore.envelope(descriptor))
                    messageSent = true
                    val item = ChatHistoryItem(
                        peer = target,
                        text = "📷 Изображение",
                        outgoing = true,
                        time = Instant.now().toString(),
                        attachment = descriptor,
                        messageId = messageId,
                    )
                    historyStore.append(item)
                    history = history + item
                    peers = historyStore.peers()
                    historyRevision++
                    status = "Фото зашифровано и отправлено"
                } finally {
                    full.ciphertext.fill(0)
                    preview.ciphertext.fill(0)
                }
            } catch (error: Exception) {
                if (!messageSent) {
                    uploadedIds.forEach { id ->
                        runCatching { api.deleteAttachment(profile, id) }
                        runCatching { attachmentStore.delete(id) }
                    }
                }
                status = error.message ?: "Не удалось отправить фото"
            } finally {
                attachmentInProgress = false
            }
        }
    }

    fun startVoiceRecording() {
        if (attachmentInProgress || voiceRecording) return
        try {
            voiceRecorder.start(scope)
            voiceRecording = true
            status = "🔴 Идёт запись · нажмите квадрат для отправки"
        } catch (error: Exception) {
            voiceRecorder.cancel()
            voiceRecording = false
            status = error.message ?: "Не удалось начать запись"
        }
    }

    fun stopAndSendVoice() {
        val target = recipient ?: return
        if (!voiceRecording) return
        voiceRecording = false
        attachmentInProgress = true
        status = "Шифруем войс…"
        scope.launch {
            var attachmentId: String? = null
            var messageSent = false
            try {
                val recorded = voiceRecorder.stop()
                val prepared = withContext(Dispatchers.IO) {
                    try {
                        attachmentStore.encrypt(
                            plain = recorded.pcm,
                            kind = EncryptedAttachmentStore.VOICE_KIND,
                            mimeType = EncryptedAttachmentStore.AUDIO_MIME,
                            durationMs = recorded.durationMs,
                        )
                    } finally {
                        recorded.pcm.fill(0)
                    }
                }
                val uploadedId = api.uploadAttachment(profile, target, prepared.ciphertext)
                attachmentId = uploadedId
                val descriptor = prepared.descriptor(uploadedId)
                withContext(Dispatchers.IO) {
                    attachmentStore.saveCiphertext(uploadedId, prepared.ciphertext)
                }
                val messageId = api.send(profile, target, EncryptedAttachmentStore.envelope(descriptor))
                messageSent = true
                val item = ChatHistoryItem(
                    peer = target,
                    text = "🎙 Голосовое сообщение",
                    outgoing = true,
                    time = Instant.now().toString(),
                    attachment = descriptor,
                    messageId = messageId,
                )
                historyStore.append(item)
                history = history + item
                peers = historyStore.peers()
                historyRevision++
                status = "Войс зашифрован и отправлен"
            } catch (error: Exception) {
                voiceRecorder.cancel()
                if (!messageSent) {
                    attachmentId?.let { id ->
                        runCatching { api.deleteAttachment(profile, id) }
                        runCatching { attachmentStore.delete(id) }
                    }
                }
                status = error.message ?: "Не удалось отправить войс"
            } finally {
                attachmentInProgress = false
            }
        }
    }

    DisposableEffect(voiceRecorder) {
        onDispose { voiceRecorder.cancel() }
    }

    LaunchedEffect(requestedPeer) {
        requestedPeer?.let {
            openConversation(it)
            onPeerOpened()
        }
    }

    LaunchedEffect(recipient) {
        recipient?.let {
            historyStore.markRead(it)
            scope.launch { runCatching { api.markPeerMessagesRead(profile, it) } }
            history = historyStore.messagesWith(it)
            historyRevision++
        }
    }

    LaunchedEffect(resumeRevision, recipient) {
        if (MainActivity.isVisible) {
            recipient?.let {
                historyStore.markRead(it)
                scope.launch { runCatching { api.markPeerMessagesRead(profile, it) } }
                history = historyStore.messagesWith(it)
                historyRevision++
            }
        }
    }

    LaunchedEffect(profile.serverUrl) {
        connection = if (api.serverReachable(profile)) ServerConnection.ONLINE else ServerConnection.OFFLINE
    }

    DisposableEffect(profile.nickname) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    MessagingService.ACTION_MESSAGES_UPDATED -> {
                        peers = historyStore.peers()
                        recipient?.let {
                            if (MainActivity.isVisible) {
                                historyStore.markRead(it)
                                scope.launch { runCatching { api.markPeerMessagesRead(profile, it) } }
                            }
                            history = historyStore.messagesWith(it)
                        }
                        historyRevision++
                        status = "Новое зашифрованное сообщение"
                    }
                    MessagingService.ACTION_CONNECTION_CHANGED -> {
                        connection = ServerConnection.fromWire(
                            intent.getStringExtra(MessagingService.EXTRA_CONNECTION_STATE),
                        )
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(MessagingService.ACTION_MESSAGES_UPDATED)
                addAction(MessagingService.ACTION_CONNECTION_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(recipient) {
        while (recipient != null) {
            delay(5_000)
            val peer = recipient ?: break
            val outgoing = historyStore.messagesWith(peer).filter { it.outgoing && it.messageId != null }
            outgoing.forEach { item ->
                val messageId = item.messageId ?: return@forEach
                runCatching { api.messageStatus(profile, messageId) }.getOrNull()?.let { delivery ->
                    val wire = delivery.name.lowercase()
                    if (item.deliveryStatus != wire) historyStore.updateDeliveryStatus(messageId, wire)
                }
            }
            history = historyStore.messagesWith(peer)
            historyRevision++
        }
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        if (recipient == null) {
            ConversationsScreen(
                profile = profile,
                peers = peers,
                historyRevision = historyRevision,
                historyStore = historyStore,
                search = search,
                foundUsers = foundUsers,
                searchError = searchError,
                searching = searching,
                connection = connection,
                onSearchChange = {
                    search = it
                    foundUsers = emptyList()
                    searchError = null
                },
                onSearch = ::findUser,
                onRefreshConnection = ::refreshConnection,
                onOpenConversation = ::openConversation,
            )
        } else {
            ConversationScreen(
                recipient = recipient!!,
                history = history,
                draft = draft,
                status = status,
                attachmentStore = attachmentStore,
                attachmentInProgress = attachmentInProgress,
                voiceRecording = voiceRecording,
                onDraftChange = { draft = it },
                onBack = { recipient = null; draft = ""; focusManager.clearFocus() },
                onImageSelected = ::sendImage,
                onStartVoice = ::startVoiceRecording,
                onStopVoice = ::stopAndSendVoice,
                onVoicePermissionDenied = { status = "Без доступа к микрофону нельзя записать войс" },
                onClearHistory = { clearForBothSides = false; showClearHistoryDialog = true },
                onVerifyKey = {
                    val target = recipient ?: return@ConversationScreen
                    safetyNumber = null
                    safetyNumberTrusted = false
                    showSafetyNumberDialog = true
                    scope.launch {
                        safetyNumber = runCatching { api.safetyNumber(profile, target) }.getOrElse {
                            status = it.message ?: "Не удалось получить ключ собеседника"
                            "Ошибка получения кода"
                        }
                        safetyNumberTrusted = safetyNumber?.let { trustedSafetyNumbers.isTrusted(target, it) } == true
                    }
                },
                onSend = {
                    val target = recipient ?: return@ConversationScreen
                    val text = draft.trim()
                    if (text.isEmpty()) return@ConversationScreen
                    scope.launch {
                        try {
                            val messageId = api.send(profile, target, text)
                            val item = ChatHistoryItem(target, text, true, Instant.now().toString(), messageId = messageId)
                            historyStore.append(item)
                            history = history + item
                            peers = historyStore.peers()
                            historyRevision++
                            draft = ""
                            status = "Доставлено на сервер"
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось отправить"
                        }
                    }
                },
            )
        }
    }

    if (showClearHistoryDialog && recipient != null) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Очистить историю?") },
            text = {
                Column {
                    Text("Сообщения, превью и локальные копии вложений будут удалены с этого устройства.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = clearForBothSides, onCheckedChange = { clearForBothSides = it })
                        Text("Также удалить у @$recipient")
                    }
                    if (clearForBothSides) Text("Сервер удалит зашифрованные копии диалога, а второй клиент очистит историю при следующей синхронизации. Резервные копии не затрагиваются.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = ::clearCurrentConversation) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("Отмена") }
            },
        )
    }

    if (showSafetyNumberDialog && recipient != null) {
        AlertDialog(
            onDismissRequest = { showSafetyNumberDialog = false },
            title = { Text("Проверка ключа @$recipient") },
            text = {
                Column {
                    Text("Сверьте этот код у себя и у @$recipient по голосу или лично. Совпадение подтверждает ключи этого диалога.")
                    Spacer(Modifier.height(16.dp))
                    Text(safetyNumber ?: "Получаем код…", style = MaterialTheme.typography.titleMedium, letterSpacing = 1.sp)
                    safetyNumber?.takeUnless { it == "Ошибка получения кода" }?.let { code ->
                        Image(
                            bitmap = remember(code) { safetyQrBitmap(code).asImageBitmap() },
                            contentDescription = "QR-код проверки ключа",
                            modifier = Modifier.size(220.dp).align(Alignment.CenterHorizontally).padding(top = 14.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(if (safetyNumberTrusted) "✓ Этот ключ уже подтверждён на устройстве." else "Если код изменился неожиданно, не отправляйте секретные данные до проверки.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    safetyNumber?.takeUnless { it == "Ошибка получения кода" }?.let {
                        trustedSafetyNumbers.trust(recipient!!, it)
                        safetyNumberTrusted = true
                    }
                    showSafetyNumberDialog = false
                }) { Text(if (safetyNumberTrusted) "Готово" else "Ключ совпадает") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = { safetyQrCamera.launch(null) },
                        enabled = safetyNumber?.let { it != "Ошибка получения кода" } == true,
                    ) { Text("Снять QR") }
                    TextButton(
                        onClick = { safetyQrPicker.launch("image/*") },
                        enabled = safetyNumber?.let { it != "Ошибка получения кода" } == true,
                    ) { Text("Из фото") }
                }
            },
        )
    }
}
