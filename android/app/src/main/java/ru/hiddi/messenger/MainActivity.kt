package ru.hiddi.messenger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.AccountStore
import ru.hiddi.messenger.network.RegistrationApi
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import ru.hiddi.messenger.security.SignalCryptoBoundary
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.InMemoryVoiceRecorder
import ru.hiddi.messenger.security.playVoicePcm
import ru.hiddi.messenger.security.sanitizeImage

class MainActivity : ComponentActivity() {
    private var requestedPeer by mutableStateOf<String?>(null)
    private var resumeRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedPeer = intent.getStringExtra(MessagingService.EXTRA_OPEN_PEER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
        setContent { HiddiApp(requestedPeer, resumeRevision, onPeerOpened = { requestedPeer = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPeer = intent.getStringExtra(MessagingService.EXTRA_OPEN_PEER)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        resumeRevision++
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    companion object {
        @Volatile
        internal var isVisible = false
        private const val NOTIFICATION_PERMISSION_REQUEST = 100
    }
}

@androidx.compose.runtime.Composable
private fun HiddiApp(requestedPeer: String?, resumeRevision: Int, onPeerOpened: () -> Unit) {
    var showRegistration by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val accountStore = remember { AccountStore(context) }
    var account by remember { mutableStateOf(accountStore.read()) }
    val hasLegacyToken = remember { accountStore.hasLegacyToken() }
    LaunchedEffect(account?.nickname) {
        if (account != null) {
            ContextCompat.startForegroundService(context, Intent(context, MessagingService::class.java))
        }
    }
    MaterialTheme(colorScheme = hiddiColors) {
        if (account != null) {
            ChatScreen(account!!, requestedPeer, resumeRevision, onPeerOpened)
        } else if (showRegistration) {
            RegistrationScreen(
                onBack = { showRegistration = false },
                onRegistered = { account = it },
                onRecover = if (hasLegacyToken) { server, nickname -> accountStore.recoverLegacy(server, nickname)?.also { account = it } } else null,
            )
        } else {
            WelcomeScreen(onRegister = { showRegistration = true })
        }
    }
}

@androidx.compose.runtime.Composable
private fun WelcomeScreen(onRegister: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hiddi Messenger", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        Text("Закрытый мессенджер для знакомых. Содержимое сообщений шифруется на устройствах.")
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRegister, modifier = Modifier.fillMaxWidth()) {
            Text("Зарегистрироваться по приглашению")
        }
    }
}

@androidx.compose.runtime.Composable
private fun RegistrationScreen(
    onBack: () -> Unit,
    onRegistered: (AccountProfile) -> Unit,
    onRecover: ((String, String) -> AccountProfile?)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nickname by rememberSaveable { mutableStateOf("") }
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var serverUrl by rememberSaveable { mutableStateOf("http://127.0.0.1:3000") }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var isRegistering by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Регистрация", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Адрес сервера") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("Никнейм") },
            prefix = { Text("@") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = { Text("Код приглашения") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                isRegistering = true
                message = null
                scope.launch {
                    try {
                        val crypto = SignalCryptoBoundary(AndroidKeystoreSecretStore(context))
                        val device = RegistrationApi(crypto).register(serverUrl, nickname, inviteCode)
                        val profile = AccountProfile(serverUrl, nickname, device.accessToken)
                        AccountStore(context).save(profile)
                        onRegistered(profile)
                        message = "Устройство зарегистрировано: @${nickname.removePrefix("@")}"
                    } catch (error: Exception) {
                        message = error.message ?: "Не удалось зарегистрировать устройство"
                    } finally {
                        isRegistering = false
                    }
                }
            },
            enabled = !isRegistering && nickname.isNotBlank() && inviteCode.isNotBlank() &&
                (serverUrl.startsWith("https://") || (BuildConfig.DEBUG && serverUrl.startsWith("http://"))),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRegistering) "Создаём защищённое устройство…" else "Создать защищённое устройство")
        }
        onRecover?.let { recover ->
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val recovered = recover(serverUrl, nickname)
                    message = if (recovered == null) "Не удалось восстановить прежнее устройство" else "Устройство восстановлено"
                },
                enabled = nickname.isNotBlank() && (serverUrl.startsWith("https://") || (BuildConfig.DEBUG && serverUrl.startsWith("http://"))),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Подключить ранее зарегистрированное устройство") }
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Назад") }
    }
}

@androidx.compose.runtime.Composable
private fun ChatScreen(profile: AccountProfile, requestedPeer: String?, resumeRevision: Int, onPeerOpened: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var recipient by rememberSaveable { mutableStateOf<String?>(null) }
    var search by rememberSaveable { mutableStateOf("") }
    var foundUser by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("Защищено · E2EE") }
    var attachmentInProgress by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    val api = remember { SignalMessagingApi(ru.hiddi.messenger.security.SignalStateRepository(context)) }
    val historyStore = remember { EncryptedChatHistory(context) }
    val attachmentStore = remember { EncryptedAttachmentStore(context) }
    val voiceRecorder = remember { InMemoryVoiceRecorder() }
    var history by remember { mutableStateOf(emptyList<ChatHistoryItem>()) }
    var peers by remember { mutableStateOf(historyStore.peers()) }
    var historyRevision by remember { mutableIntStateOf(0) }

    fun openConversation(nickname: String) {
        val peer = nickname.removePrefix("@").lowercase()
        historyStore.markRead(peer)
        recipient = peer
        history = historyStore.messagesWith(peer)
        historyRevision++
        draft = ""
        foundUser = null
        search = ""
        focusManager.clearFocus()
    }

    fun findUser() {
        scope.launch {
            try {
                val nickname = api.findUser(profile, search).nickname
                foundUser = nickname
                status = "@$nickname найден"
                focusManager.clearFocus()
            } catch (error: Exception) {
                foundUser = null
                status = error.message ?: "Пользователь не найден"
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
                    api.send(profile, target, EncryptedAttachmentStore.envelope(descriptor))
                    messageSent = true
                    val item = ChatHistoryItem(
                        peer = target,
                        text = "📷 Изображение",
                        outgoing = true,
                        time = Instant.now().toString(),
                        attachment = descriptor,
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
                api.send(profile, target, EncryptedAttachmentStore.envelope(descriptor))
                messageSent = true
                val item = ChatHistoryItem(
                    peer = target,
                    text = "🎙 Голосовое сообщение",
                    outgoing = true,
                    time = Instant.now().toString(),
                    attachment = descriptor,
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
            history = historyStore.messagesWith(it)
            historyRevision++
        }
    }

    LaunchedEffect(resumeRevision, recipient) {
        if (MainActivity.isVisible) {
            recipient?.let {
                historyStore.markRead(it)
                history = historyStore.messagesWith(it)
                historyRevision++
            }
        }
    }

    DisposableEffect(profile.nickname) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                peers = historyStore.peers()
                recipient?.let {
                    if (MainActivity.isVisible) historyStore.markRead(it)
                    history = historyStore.messagesWith(it)
                }
                historyRevision++
                status = "Новое зашифрованное сообщение"
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(MessagingService.ACTION_MESSAGES_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
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
                foundUser = foundUser,
                onSearchChange = { search = it; foundUser = null },
                onSearch = ::findUser,
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
                onSend = {
                    val target = recipient ?: return@ConversationScreen
                    val text = draft.trim()
                    if (text.isEmpty()) return@ConversationScreen
                    scope.launch {
                        try {
                            api.send(profile, target, text)
                            val item = ChatHistoryItem(target, text, true, Instant.now().toString())
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
}

@androidx.compose.runtime.Composable
private fun ConversationsScreen(
    profile: AccountProfile,
    peers: List<String>,
    historyRevision: Int,
    historyStore: EncryptedChatHistory,
    search: String,
    foundUser: String?,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HiddiAvatar("H", 48)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Hiddi", fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Text("@${profile.nickname}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = { }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Column(Modifier.padding(horizontal = 16.dp)) {
            OutlinedTextField(
                value = search,
                onValueChange = onSearchChange,
                placeholder = { Text("Поиск по @nickname") },
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (search.isNotBlank()) {
                        IconButton(onClick = onSearch) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Найти", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (search.isNotBlank()) onSearch() }),
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            foundUser?.let { user ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = { onOpenConversation(user) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        HiddiAvatar(user, 44)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("@$user", fontWeight = FontWeight.SemiBold)
                            Text("Начать защищённый диалог", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Сообщения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(5.dp))
                    Text("E2EE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Здесь появятся ваши диалоги", fontWeight = FontWeight.Medium)
                    Text("Найдите человека по никнейму", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(peers, key = { "$it:$historyRevision" }) { peer ->
                    val lastMessage = historyStore.messagesWith(peer).lastOrNull()
                    ConversationRow(
                        peer = peer,
                        lastMessage = lastMessage,
                        unreadCount = historyStore.unreadCount(peer),
                        onClick = { onOpenConversation(peer) },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ConversationRow(peer: String, lastMessage: ChatHistoryItem?, unreadCount: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            HiddiAvatar(peer, 54)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("@$peer", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    lastMessage?.let {
                        Text(
                            messageTime(it.time),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (unreadCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    lastMessage?.let { (if (it.outgoing) "Вы: " else "") + it.text } ?: "Защищённый диалог",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (unreadCount > 0) {
                Spacer(Modifier.size(10.dp))
                Box(
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (unreadCount > 99) "99+" else unreadCount.toString(),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontSize = if (unreadCount > 99) 9.sp else 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun ConversationScreen(
    recipient: String,
    history: List<ChatHistoryItem>,
    draft: String,
    status: String,
    attachmentStore: EncryptedAttachmentStore,
    attachmentInProgress: Boolean,
    voiceRecording: Boolean,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onImageSelected: (Uri) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onVoicePermissionDenied: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onImageSelected)
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartVoice() else onVoicePermissionDenied()
    }
    fun startVoiceWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onStartVoice()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(recipient, history.size) {
        if (history.isNotEmpty()) listState.animateScrollToItem(history.lastIndex)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            HiddiAvatar(recipient, 44)
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text("@$recipient", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("сквозное шифрование", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
            IconButton(onClick = { }) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Меню", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.background, Color(0xFF0D1520)),
                ),
            ),
        ) {
            val dotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.055f)
            Canvas(Modifier.fillMaxSize()) {
                val gap = 54.dp.toPx()
                var y = gap / 2
                var row = 0
                while (y < size.height) {
                    var x = if (row % 2 == 0) gap / 2 else gap
                    while (x < size.width) {
                        drawCircle(dotColor, radius = 1.4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(x, y))
                        x += gap
                    }
                    y += gap
                    row++
                }
            }

            if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)) {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("Начало защищённого диалога", style = MaterialTheme.typography.labelLarge)
                            Text("Только вы двое можете читать сообщения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(history) { item -> MessageBubble(item, attachmentStore) }
                }
            }
        }

        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { imagePicker.launch("image/*") },
                    enabled = !attachmentInProgress && !voiceRecording,
                ) {
                    if (attachmentInProgress) {
                        Text("…", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(Icons.Rounded.Add, contentDescription = "Прикрепить изображение", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    enabled = !voiceRecording,
                    placeholder = { Text("Сообщение") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank()) onSend() }),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                voiceRecording -> Color(0xFFE95B67)
                                draft.isNotBlank() -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        )
                        .clickable(enabled = !attachmentInProgress) {
                            when {
                                draft.isNotBlank() -> onSend()
                                voiceRecording -> onStopVoice()
                                else -> startVoiceWithPermission()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = when {
                            draft.isNotBlank() -> Icons.AutoMirrored.Rounded.Send
                            voiceRecording -> Icons.Rounded.Stop
                            else -> Icons.Rounded.Mic
                        },
                        contentDescription = when {
                            draft.isNotBlank() -> "Отправить"
                            voiceRecording -> "Остановить и отправить"
                            else -> "Записать голосовое"
                        },
                        tint = when {
                            voiceRecording -> Color.White
                            draft.isNotBlank() -> MaterialTheme.colorScheme.onPrimary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun MessageBubble(item: ChatHistoryItem, attachmentStore: EncryptedAttachmentStore) {
    val isImage = item.attachment?.kind == EncryptedAttachmentStore.IMAGE_KIND
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (item.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = if (isImage) 340.dp else 320.dp),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (item.outgoing) 20.dp else 5.dp,
                bottomEnd = if (item.outgoing) 5.dp else 20.dp,
            ),
            color = if (item.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(if (isImage) 4.dp else 0.dp)) {
                when (item.attachment?.kind) {
                    EncryptedAttachmentStore.IMAGE_KIND -> Box {
                        AttachmentImage(item.attachment, attachmentStore)
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            color = Color.Black.copy(alpha = 0.58f),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text(
                                messageTime(item.time),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                    EncryptedAttachmentStore.VOICE_KIND -> Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        VoiceAttachment(item.attachment, attachmentStore)
                        MessageTime(item.time)
                    }
                    else -> Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(item.text, style = MaterialTheme.typography.bodyLarge)
                        MessageTime(item.time)
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.ColumnScope.MessageTime(value: String) {
    Text(
        messageTime(value),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.End).padding(top = 2.dp),
    )
}

@androidx.compose.runtime.Composable
private fun AttachmentImage(
    descriptor: ru.hiddi.messenger.security.AttachmentDescriptor,
    attachmentStore: EncryptedAttachmentStore,
) {
    val previewDescriptor = descriptor.preview ?: descriptor
    val bitmap = rememberAttachmentBitmap(previewDescriptor, attachmentStore)
    var showFullImage by remember(descriptor.attachmentId) { mutableStateOf(false) }
    if (bitmap == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Загружаем фото…", style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Открыть зашифрованное изображение",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(17.dp))
                .clickable { showFullImage = true },
        )
    }

    if (showFullImage) {
        Dialog(
            onDismissRequest = { showFullImage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val fullBitmap = rememberAttachmentBitmap(descriptor, attachmentStore)
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (fullBitmap == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("Загружаем оригинал…", color = Color.White)
                    }
                } else {
                    Image(
                        bitmap = fullBitmap,
                        contentDescription = "Зашифрованное изображение",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                IconButton(
                    onClick = { showFullImage = false },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                        .size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberAttachmentBitmap(
    descriptor: ru.hiddi.messenger.security.AttachmentDescriptor,
    attachmentStore: EncryptedAttachmentStore,
): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, descriptor.attachmentId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!attachmentStore.exists(descriptor.attachmentId)) return@runCatching null
                val bytes = attachmentStore.decrypt(descriptor)
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } finally {
                    bytes.fill(0)
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@androidx.compose.runtime.Composable
private fun VoiceAttachment(
    descriptor: ru.hiddi.messenger.security.AttachmentDescriptor,
    attachmentStore: EncryptedAttachmentStore,
) {
    val scope = rememberCoroutineScope()
    var playing by remember(descriptor.attachmentId) { mutableStateOf(false) }
    val seconds = ((descriptor.durationMs ?: 0L) / 1_000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = !playing && attachmentStore.exists(descriptor.attachmentId)) {
                    scope.launch {
                        playing = true
                        try {
                            val pcm = withContext(Dispatchers.IO) { attachmentStore.decrypt(descriptor) }
                            try {
                                playVoicePcm(pcm)
                            } finally {
                                pcm.fill(0)
                            }
                        } finally {
                            playing = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Text("…", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp)
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Воспроизвести", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text("Голосовое", fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(5.dp))
                Icon(Icons.Rounded.Lock, contentDescription = "Зашифровано", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HiddiAvatar(seed: String, size: Int) {
    val letter = seed.removePrefix("@").firstOrNull()?.uppercase() ?: "H"
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(
            Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color(0xFF06151C), fontWeight = FontWeight.ExtraBold, fontSize = (size * 0.38f).sp)
    }
}

private fun messageTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrElse {
    Regex("""(\d{2}):(\d{2})(?::\d{2})?""").find(value)?.let { match ->
        "${match.groupValues[1]}:${match.groupValues[2]}"
    } ?: ""
}

private val hiddiColors = darkColorScheme(
    primary = Color(0xFF63DFF6),
    onPrimary = Color(0xFF042F38),
    primaryContainer = Color(0xFF164753),
    onPrimaryContainer = Color(0xFFD5F7FF),
    secondary = Color(0xFFA99BFF),
    background = Color(0xFF080D14),
    surface = Color(0xFF111925),
    surfaceVariant = Color(0xFF1B2735),
    onSurface = Color(0xFFEAF0F8),
    onSurfaceVariant = Color(0xFFAEB9C8),
    onBackground = Color(0xFFEAF0F8),
    error = Color(0xFFFF6F7D),
)
