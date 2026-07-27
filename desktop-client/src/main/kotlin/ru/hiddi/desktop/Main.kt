package ru.hiddi.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Cursor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.jetbrains.skia.Image as SkiaImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Ink = Color(0xFF0B1016)
private val Panel = Color(0xFF121A23)
private val PanelRaised = Color(0xFF19242F)
private val Mint = Color(0xFF58E0B8)
private val TextMuted = Color(0xFF91A0AE)
private val HiddiTypography = Typography().let { base ->
    val family = FontFamily.SansSerif
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}
private const val PRODUCTION_SERVER = "https://hiddi.myaifriend.su"

fun main() = application {
    val vault =
        remember {
            Vault(
                Path.of(
                    System.getProperty("user.home"),
                    ".local",
                    "share",
                    "hiddi-desktop",
                    Vault.FILE_NAME,
                ),
            )
        }
    val api = remember { HiddiApi(vault) }
    var session by remember { mutableStateOf<HiddiSession?>(null) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var screen by remember {
        mutableStateOf<AppScreen>(if (vault.exists()) AppScreen.Unlock else AppScreen.Pairing)
    }

    Window(
        onCloseRequest = {
            session?.close()
            exitApplication()
        },
        title = "Hiddi",
    ) {
        HiddiTheme {
            when (screen) {
                AppScreen.Pairing ->
                    PairingScreen(api, onRegister = { screen = AppScreen.Registration }) { unlocked ->
                        session = unlocked
                        screen = AppScreen.Messenger
                    }
                AppScreen.Registration ->
                    RegistrationScreen(api) { unlocked, key ->
                        session = unlocked
                        recoveryKey = key
                        screen = AppScreen.Messenger
                    }
                AppScreen.Unlock ->
                    UnlockScreen(api) { unlocked ->
                        session = unlocked
                        screen = AppScreen.Messenger
                    }
                AppScreen.Messenger ->
                    session?.let {
                        MessengerScreen(it) {
                            session = null
                            recoveryKey = null
                            screen = AppScreen.Pairing
                        }
                        recoveryKey?.let { key -> RecoveryKeyDialog(key) { recoveryKey = null } }
                    }
            }
        }
    }
}

@Composable
private fun HiddiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Mint,
                background = Ink,
                surface = Panel,
                onPrimary = Ink,
                onBackground = Color(0xFFEAF3F7),
                onSurface = Color(0xFFEAF3F7),
                onSurfaceVariant = TextMuted,
            ),
        typography = HiddiTypography,
        content = content,
    )
}

@Composable
private fun PairingScreen(api: HiddiApi, onRegister: () -> Unit, onReady: (HiddiSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(PRODUCTION_SERVER) }
    var code by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName()) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthLayout(
        eyebrow = "НОВОЕ УСТРОЙСТВО",
        title = "Привязать Linux",
        subtitle =
            "На Android откройте Настройки → Устройства → Привязать компьютер. " +
                "Выберите скриншот QR-кода или вставьте одноразовый код вручную.",
    ) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Сервер") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = {
                runCatching {
                    val image = chooseQrImage() ?: return@OutlinedButton
                    readDeviceLinkQr(image)
                }.onSuccess { qr ->
                    server = qr.serverUrl
                    code = qr.code
                    error = null
                }.onFailure { failure ->
                    error = failure.message ?: "Не удалось прочитать QR-код"
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Выбрать QR-код из изображения")
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.trim() },
            label = { Text("Код привязки") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it.take(64) },
            label = { Text("Название компьютера") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль локальных ключей") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = { Text("Повторите пароль") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { ErrorText(it) }
        PrimaryButton("Привязать компьютер", busy) {
            error = null
            if (password.length < 8) {
                error = "Пароль должен содержать хотя бы 8 символов"
            } else if (password != confirmation) {
                error = "Пароли не совпадают"
            } else {
                busy = true
                scope.launch {
                    val passphrase = password.toCharArray()
                    val sessionPassphrase = passphrase.copyOf()
                    runCatching {
                        withContext(Dispatchers.IO) {
                            api.pair(server, code, deviceName, passphrase)
                            api.unlock(sessionPassphrase)
                        }
                    }.onSuccess {
                        password = ""
                        confirmation = ""
                        onReady(it)
                    }.onFailure {
                        sessionPassphrase.fill('\u0000')
                        error = it.message ?: "Не удалось привязать устройство"
                    }
                    passphrase.fill('\u0000')
                    busy = false
                }
            }
        }
        OutlinedButton(onClick = onRegister, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Создать новый аккаунт")
        }
    }
}

@Composable
private fun RegistrationScreen(api: HiddiApi, onReady: (HiddiSession, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var server by remember { mutableStateOf(PRODUCTION_SERVER) }
    var nickname by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(defaultDeviceName()) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AuthLayout("НОВЫЙ АККАУНТ", "Создать Hiddi", "Инвайт создаёт новый независимый аккаунт на этом компьютере.") {
        OutlinedTextField(server, { server = it }, label = { Text("Сервер") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(nickname, { nickname = it.trim().take(32) }, label = { Text("Никнейм") }, prefix = { Text("@") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(inviteCode, { inviteCode = it.trim() }, label = { Text("Инвайт") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(deviceName, { deviceName = it.take(64) }, label = { Text("Название компьютера") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Пароль локальных ключей") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Повторите пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
        error?.let { ErrorText(it) }
        PrimaryButton("Создать аккаунт", busy) {
            if (password.length < 8) error = "Пароль должен содержать хотя бы 8 символов"
            else if (password != confirmation) error = "Пароли не совпадают"
            else {
                busy = true
                error = null
                scope.launch {
                    val passphrase = password.toCharArray()
                    val sessionPassphrase = passphrase.copyOf()
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val result = api.register(server, nickname, inviteCode, deviceName, passphrase)
                            api.unlock(sessionPassphrase) to result.recoveryKey
                        }
                    }.onSuccess { (session, key) -> onReady(session, key) }
                        .onFailure { error = it.message ?: "Не удалось создать аккаунт" }
                    passphrase.fill('\u0000')
                    busy = false
                }
            }
        }
    }
}

@Composable
private fun RecoveryKeyDialog(key: String, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ключ восстановления") },
        text = { Text("Сохраните ключ вне компьютера. Hiddi покажет его только сейчас:\n\n$key") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Я сохранил") } },
    )
}

private fun chooseQrImage(): File? {
    val dialog = FileDialog(null as Frame?, "Выберите QR-код Hiddi", FileDialog.LOAD)
    dialog.isVisible = true
    val filename = dialog.file ?: return null
    return File(dialog.directory, filename)
}

@Composable
private fun UnlockScreen(api: HiddiApi, onReady: (HiddiSession) -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AuthLayout(
        eyebrow = "HIDDI DESKTOP",
        title = "С возвращением",
        subtitle = "Введите пароль, которым зашифрованы Signal-ключи на этом компьютере.",
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Пароль локальных ключей") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { ErrorText(it) }
        PrimaryButton("Открыть Hiddi", busy) {
            busy = true
            error = null
            scope.launch {
                val passphrase = password.toCharArray()
                runCatching {
                    withContext(Dispatchers.IO) { api.unlock(passphrase) }
                }.onSuccess {
                    password = ""
                    onReady(it)
                }.onFailure {
                    passphrase.fill('\u0000')
                    error = it.message ?: "Не удалось открыть хранилище"
                }
                busy = false
            }
        }
    }
}

@Composable
private fun AuthLayout(
    eyebrow: String,
    title: String,
    subtitle: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Surface(
            color = Panel,
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.width(520.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.padding(36.dp),
            ) {
                AppMark()
                Spacer(Modifier.height(6.dp))
                Text(eyebrow, color = Mint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(title, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = TextMuted, lineHeight = 21.sp)
                Spacer(Modifier.height(4.dp))
                content()
            }
        }
    }
}

@Composable
private fun AppMark() {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Mint),
        contentAlignment = Alignment.Center,
    ) {
        Text("H", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun PrimaryButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
        modifier = Modifier.fillMaxWidth().height(50.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = Ink,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorText(message: String) {
    Text(message, color = Color(0xFFFF8D91), fontSize = 13.sp)
}

@Composable
private fun MessengerScreen(session: HiddiSession, onLoggedOut: () -> Unit) {
    var online by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(DesktopSection.Chats) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<HiddiProfile>()) }
    var selected by remember { mutableStateOf<HiddiProfile?>(null) }
    var listWidth by remember { mutableStateOf(320.dp) }
    var blockedUsers by remember { mutableStateOf(emptySet<String>()) }
    var contacts by remember(session) { mutableStateOf(session.contacts()) }
    var groups by remember(session) { mutableStateOf(emptyList<DesktopGroup>()) }
    var groupsReady by remember(session) { mutableStateOf(false) }
    var selectedGroupId by remember(session) { mutableStateOf<String?>(null) }
    var groupError by remember { mutableStateOf<String?>(null) }
    var peerOnline by remember { mutableStateOf(false) }
    var peerTyping by remember { mutableStateOf(false) }
    var typingRevision by remember { mutableStateOf(0) }
    var invisibleMode by remember(session) { mutableStateOf(session.invisibleMode()) }
    val messages = remember(session) {
        mutableStateListOf<ChatEntry>().also { it += session.history().boundedHistoryWindow() }
    }
    val peerProfiles = remember(session) { mutableStateMapOf<String, HiddiProfile>() }
    val peerAvatars = remember(session) { mutableStateMapOf<String, ByteArray>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val realtime = remember(session) { DesktopRealtime(session.server, session.accessToken) }

    suspend fun synchronizeInbox() {
        val snapshot = runCatching {
            withContext(Dispatchers.IO) {
                session.syncInbox()
                session.history()
            }
        }.getOrNull() ?: return
        messages.clear()
        messages.addAll(snapshot.boundedHistoryWindow())
    }

    suspend fun refreshDeliveryStatuses(peers: Set<String>) {
        peers.forEach { peer ->
            val statuses =
                runCatching {
                    withContext(Dispatchers.IO) {
                        session.updateDeliveryStatuses(peer)
                    }
                }.getOrNull() ?: return@forEach
            messages.indices.forEach { index ->
                val current = messages[index]
                val messageId = current.messageId ?: return@forEach
                val next = statuses[messageId] ?: return@forEach
                if (current.deliveryStatus != next) {
                    messages[index] = current.copy(deliveryStatus = next)
                }
            }
        }
    }

    DisposableEffect(session) {
        onDispose {
            realtime.close()
            session.close()
        }
    }
    // Sole owner of the realtime connection. Keyed only on the socket so that
    // selecting another chat cannot cancel a pending reconnect: connect() is a
    // no-op while the socket is live, and heartbeat() retires a half-open one.
    LaunchedEffect(realtime) {
        while (true) {
            realtime.connect(invisibleMode)
            delay(5_000)
            realtime.heartbeat()
        }
    }
    LaunchedEffect(session) {
        runCatching {
            withContext(Dispatchers.IO) {
                session.synchronizeHistoryToOwnDevices()
            }
        }
    }
    LaunchedEffect(session) {
        blockedUsers =
            runCatching { withContext(Dispatchers.IO) { session.blockedUsers() } }
                .getOrDefault(emptySet())
    }
    LaunchedEffect(session) {
        runCatching {
            withContext(Dispatchers.IO) {
                session.prepareGroups()
                session.groups()
            }
        }.onSuccess {
            groups = it
            groupsReady = true
            groupError = null
        }.onFailure {
            groupsReady = false
            groupError = it.message ?: "Не удалось запустить OpenMLS"
        }
    }
    LaunchedEffect(session) {
        while (true) {
            online = runCatching { withContext(Dispatchers.IO) { session.isOnline() } }.getOrDefault(false)
            delay(15_000)
        }
    }
    LaunchedEffect(query) {
        delay(250)
        results =
            runCatching { withContext(Dispatchers.IO) { session.search(query) } }
                .getOrDefault(emptyList())
    }
    val knownPeers = (messages.map(ChatEntry::peer) + contacts).distinct().sorted()
    LaunchedEffect(knownPeers) {
        knownPeers.forEach { peer ->
            val profile =
                runCatching { withContext(Dispatchers.IO) { session.userProfile(peer) } }
                    .getOrNull()
                    ?: return@forEach
            peerProfiles[peer] = profile
            if (profile.avatarVersion != null) {
                runCatching { withContext(Dispatchers.IO) { session.avatar(peer) } }
                    .getOrNull()
                    ?.let { peerAvatars[peer] = it }
            } else {
                peerAvatars.remove(peer)
            }
        }
    }
    LaunchedEffect(selected?.nickname, peerProfiles[selected?.nickname]) {
        selected?.nickname?.let { peerProfiles[it] }?.let { selected = it }
    }
    LaunchedEffect(selected?.nickname) {
        peerOnline = false
        peerTyping = false
        selected?.let { realtime.subscribePresence(it.nickname) }
    }
    // As on Android, the open conversation reports its read state when selected
    // and whenever a new incoming message lands in it.
    val incomingFromSelected =
        selected?.nickname?.let { peer -> messages.count { !it.outgoing && it.peer == peer } } ?: 0
    LaunchedEffect(selected?.nickname, incomingFromSelected) {
        val peer = selected?.nickname ?: return@LaunchedEffect
        runCatching { withContext(Dispatchers.IO) { session.markConversationRead(peer) } }
    }
    // Reads selected/invisibleMode straight from their MutableState, so the loop
    // never has to be restarted — and never loses a queued event to cancellation.
    LaunchedEffect(realtime) {
        for (event in realtime.events) {
            when (event) {
                DesktopRealtime.Event.Connected -> {
                    selected?.let { realtime.subscribePresence(it.nickname) }
                }
                DesktopRealtime.Event.SyncRequired -> {
                    synchronizeInbox()
                    refreshDeliveryStatuses(
                        selected?.nickname?.let(::setOf).orEmpty(),
                    )
                }
                is DesktopRealtime.Event.Presence -> {
                    if (event.nickname == selected?.nickname) {
                        peerOnline = event.online
                    }
                }
                is DesktopRealtime.Event.Typing -> {
                    if (event.nickname == selected?.nickname) {
                        peerTyping = event.typing
                        val revision = ++typingRevision
                        if (event.typing) {
                            scope.launch {
                                delay(3_000)
                                if (typingRevision == revision) peerTyping = false
                            }
                        }
                    }
                }
                DesktopRealtime.Event.Disconnected -> {
                    peerOnline = false
                    peerTyping = false
                }
            }
        }
    }
    LaunchedEffect(session) {
        while (true) {
            synchronizeInbox()
            // WebSocket is the instant path. This bounded fallback covers a
            // half-open socket after VPN/network changes without a long lag.
            delay(3_000)
        }
    }
    LaunchedEffect(session) {
        while (true) {
            delay(2_000)
            if (!groupsReady) continue
            runCatching { withContext(Dispatchers.IO) { session.syncGroups() } }
                .onSuccess {
                    groups = it
                    groupError = null
                }
                .onFailure { groupError = it.message ?: "Не удалось синхронизировать группы" }
        }
    }
    LaunchedEffect(session) {
        while (true) {
            refreshDeliveryStatuses(
                messages.asSequence()
                    .filter(ChatEntry::outgoing)
                    .map(ChatEntry::peer)
                    .toSet(),
            )
            delay(3_000)
        }
    }

    Surface(color = Ink, contentColor = Color(0xFFEAF3F7), modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            DesktopNavigation(section, online, session.nickname) { section = it }
            Column(Modifier.width(listWidth).fillMaxHeight().background(Panel)) {
                when (section) {
                    DesktopSection.Chats -> ChatListPane(messages, selected, peerProfiles, peerAvatars) { profile -> selected = profile }
                    DesktopSection.Groups -> GroupListPane(
                        groups = groups,
                        selectedGroupId = selectedGroupId,
                        error = groupError,
                        onSelect = { selectedGroupId = it },
                        onCreate = { name, nickname, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        session.createGroup(name, nickname)
                                        session.groups()
                                    }
                                }.onSuccess {
                                    groups = it
                                    selectedGroupId = it.lastOrNull()?.id
                                    report(null)
                                }.onFailure { report(it.message ?: "Не удалось создать группу") }
                            }
                        },
                    )
                    DesktopSection.Contacts -> ContactsPane(
                        query,
                        { query = it },
                        results,
                        contacts,
                        peerProfiles,
                        peerAvatars,
                        selected,
                    ) { profile ->
                        selected = profile
                        section = DesktopSection.Chats
                    }
                    DesktopSection.Settings -> DesktopSettingsPane(session, online)
                }
            }
            ResizeHandle { delta -> listWidth = (listWidth + with(density) { delta.toDp() }).clamp(280.dp, 480.dp) }
            if (section == DesktopSection.Settings) {
                DesktopSettingsDetail(
                    session = session,
                    invisibleMode = invisibleMode,
                    onInvisibleModeChange = { enabled ->
                        invisibleMode = enabled
                        realtime.setVisible(!enabled)
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    session.setInvisibleMode(enabled)
                                }
                            }.onFailure {
                                invisibleMode = !enabled
                                realtime.setVisible(enabled)
                            }
                        }
                    },
                    onLoggedOut = onLoggedOut,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            } else if (section == DesktopSection.Groups) {
                groups.firstOrNull { it.id == selectedGroupId }?.let { group ->
                    GroupChatPane(
                        group = group,
                        ownNickname = session.nickname,
                        onSend = { text, replyTo, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        session.sendGroupText(group.id, text, replyTo)
                                        session.groups()
                                    }
                                }.onSuccess {
                                    groups = it
                                    report(null)
                                }.onFailure { report(it.message ?: "Не удалось отправить") }
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } ?: EmptyGroup(Modifier.weight(1f).fillMaxHeight(), groupError)
            } else {
                selected?.let { profile ->
                    ChatPane(
                        profile = profile,
                        profileAvatar = peerAvatars[profile.nickname],
                        messages = messages.filter { it.peer == profile.nickname },
                        ownNickname = session.nickname,
                        isContact = profile.nickname in contacts,
                        isBlocked = profile.nickname in blockedUsers,
                        peerOnline = peerOnline,
                        peerTyping = peerTyping,
                        onTypingChange = { typing ->
                            realtime.sendTyping(profile.nickname, typing && !invisibleMode)
                        },
                        onSend = { text, replyTo, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        session.send(profile.nickname, text, replyTo)
                                    }
                                }.onSuccess {
                                    messages += it
                                    report(null)
                                }.onFailure { report(it.message ?: "Не удалось отправить") }
                            }
                        },
                        onSendImage = { file, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        session.sendImage(profile.nickname, file)
                                    }
                                }.onSuccess {
                                    messages += it
                                    report(null)
                                }.onFailure {
                                    report(it.message ?: "Не удалось отправить изображение")
                                }
                            }
                        },
                        onSendVoice = { pcm, durationMs, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        session.sendVoice(profile.nickname, pcm, durationMs)
                                    }
                                }.onSuccess {
                                    messages += it
                                    report(null)
                                }.onFailure {
                                    pcm.fill(0)
                                    report(it.message ?: "Не удалось отправить голосовое")
                                }
                            }
                        },
                        onLoadAttachment = { descriptor ->
                            withContext(Dispatchers.IO) {
                                session.attachmentBytes(descriptor)
                            }
                        },
                        onLoadSafetyNumber = {
                            withContext(Dispatchers.IO) { session.safetyNumber(profile.nickname) }
                        },
                        onTrustSafetyNumber = { value ->
                            withContext(Dispatchers.IO) {
                                session.trustSafetyNumber(profile.nickname, value)
                            }
                        },
                        onClearConversation = { forBoth ->
                            withContext(Dispatchers.IO) {
                                session.clearConversation(profile.nickname, forBoth)
                            }
                            messages.removeAll { it.peer == profile.nickname }
                        },
                        onDeleteMessage = { messageId, forEveryone, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        session.deleteMessage(messageId, forEveryone)
                                    }
                                }.onSuccess {
                                    messages.removeAll { it.messageId == messageId }
                                    report(null)
                                }.onFailure {
                                    report(it.message ?: "Не удалось удалить сообщение")
                                }
                            }
                        },
                        onSetBlocked = { blocked ->
                            withContext(Dispatchers.IO) {
                                session.setBlocked(profile.nickname, blocked)
                            }
                            blockedUsers =
                                if (blocked) {
                                    blockedUsers + profile.nickname
                                } else {
                                    blockedUsers - profile.nickname
                            }
                        },
                        onSetContact = { added ->
                            withContext(Dispatchers.IO) {
                                session.setContact(profile.nickname, added)
                            }
                            contacts =
                                if (added) contacts + profile.nickname
                                else contacts - profile.nickname
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                } ?: EmptyChat(Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

private fun Dp.clamp(min: Dp, max: Dp): Dp = coerceIn(min, max)

@Composable
private fun ResizeHandle(onDrag: (Float) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.width(12.dp).fillMaxHeight()
            .background(Color.White.copy(alpha = 0.035f))
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); onDrag(amount.x) } },
    ) { Box(Modifier.width(2.dp).height(46.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f))) }
}

@Composable
private fun DesktopNavigation(selected: DesktopSection, online: Boolean, nickname: String, onSelect: (DesktopSection) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.width(92.dp).fillMaxHeight().background(Color(0xFF0D141B)).padding(vertical = 20.dp, horizontal = 10.dp),
    ) {
        AppMark()
        Spacer(Modifier.height(22.dp))
        DesktopNavButton("Чаты", Icons.Rounded.ChatBubble, selected == DesktopSection.Chats) { onSelect(DesktopSection.Chats) }
        DesktopNavButton("Группы", Icons.Rounded.Group, selected == DesktopSection.Groups) { onSelect(DesktopSection.Groups) }
        DesktopNavButton("Контакты", Icons.Rounded.Contacts, selected == DesktopSection.Contacts) { onSelect(DesktopSection.Contacts) }
        Spacer(Modifier.weight(1f))
        DesktopNavButton("Настройки", Icons.Rounded.Settings, selected == DesktopSection.Settings) { onSelect(DesktopSection.Settings) }
        Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF223C43)), contentAlignment = Alignment.Center) {
            Text(nickname.firstOrNull()?.uppercase() ?: "H", color = Mint, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (online) Mint else Color(0xFF66717C)))
    }
}

@Composable
private fun DesktopNavButton(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (selected) Color(0xFF173C37) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) Mint else TextMuted, modifier = Modifier.size(23.dp))
        Spacer(Modifier.height(5.dp))
        Text(label, color = if (selected) Mint else TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun CorporateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = 1,
) {
    var focused by remember { mutableStateOf(false) }
    val foreground = if (enabled) Color(0xFFEAF3F7) else TextMuted.copy(alpha = 0.55f)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = LocalTextStyle.current.copy(color = foreground, fontSize = 14.sp),
        cursorBrush = SolidColor(Mint),
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Surface(
                color = Color(0xFF18222D),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(
                    1.dp,
                    if (focused) Mint.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.08f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = 16.dp,
                        vertical = if (singleLine) 0.dp else 14.dp,
                    ),
                ) {
                    leadingIcon?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = if (focused) Mint else TextMuted,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(
                        contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                color = TextMuted.copy(alpha = if (enabled) 0.82f else 0.45f),
                                fontSize = 14.sp,
                            )
                        }
                        innerTextField()
                    }
                }
            }
        },
    )
}

@Composable
private fun ChatListPane(
    messages: List<ChatEntry>,
    selected: HiddiProfile?,
    profiles: Map<String, HiddiProfile>,
    avatars: Map<String, ByteArray>,
    onSelect: (HiddiProfile) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val conversations = messages.groupBy { it.peer }
        .map { (peer, entries) -> peer to entries.maxBy { it.createdAt } }
        .filter { (peer, last) -> query.isBlank() || peer.contains(query.trim().removePrefix("@"), ignoreCase = true) || last.text.contains(query, ignoreCase = true) }
        .sortedByDescending { it.second.createdAt }
    Text("Чаты", fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 14.dp))
    CorporateTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = "Поиск",
        leadingIcon = Icons.Rounded.Search,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).height(48.dp),
    )
    if (conversations.isEmpty()) {
        Text("Диалогов пока нет. Найдите близкого человека во вкладке «Контакты».", color = TextMuted, modifier = Modifier.padding(20.dp))
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(top = 14.dp, start = 12.dp, end = 12.dp)) {
            items(conversations, key = { it.first }) { (peer, last) ->
                val profile = profiles[peer] ?: HiddiProfile(peer, "", "")
                ConversationRow(profile, avatars[peer], last, selected?.nickname == peer) { onSelect(profile) }
            }
        }
    }
}

@Composable
private fun GroupListPane(
    groups: List<DesktopGroup>,
    selectedGroupId: String?,
    error: String?,
    onSelect: (String) -> Unit,
    onCreate: (String, String, (String?) -> Unit) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 18.dp, top = 24.dp, bottom = 14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("Группы", fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("OpenMLS · сквозное шифрование", color = Mint, fontSize = 11.sp)
        }
        FilledIconButton(
            onClick = { showCreate = true },
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = Ink),
        ) {
            Text("+", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    error?.let {
        Text(it, color = Color(0xFFFF8D91), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp))
    }
    if (groups.isEmpty()) {
        Text(
            "Защищённых групп пока нет. Создайте первую и пригласите участника по @nickname.",
            color = TextMuted,
            modifier = Modifier.padding(20.dp),
        )
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp, start = 12.dp, end = 12.dp)) {
            items(groups, key = DesktopGroup::id) { group ->
                val last = group.messages.lastOrNull()
                Surface(
                    color = if (selectedGroupId == group.id) Color(0xFF1A3333) else Color.Transparent,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onSelect(group.id) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(14.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF203B43)),
                        ) {
                            Icon(Icons.Rounded.Group, contentDescription = null, tint = Mint)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(group.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                last?.let { (if (it.outgoing) "Вы: " else "@${it.sender}: ") + it.text }
                                    ?: "${group.members.size} участника",
                                color = TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        last?.let { Text(formatMessageTime(it.createdAt), color = TextMuted, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { if (!creating) showCreate = false },
            icon = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = Mint) },
            title = { Text("Новая защищённая группа") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CorporateTextField(
                        value = name,
                        onValueChange = { if (it.length <= 80) name = it },
                        placeholder = "Название группы",
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    )
                    CorporateTextField(
                        value = nickname,
                        onValueChange = { nickname = it },
                        placeholder = "@nickname первого участника",
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    )
                    Text(
                        "Название и сообщения шифруются OpenMLS. Сервер видит только маршрутизацию участников.",
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                    createError?.let { ErrorText(it) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !creating && name.isNotBlank() && nickname.isNotBlank(),
                    onClick = {
                        creating = true
                        createError = null
                        onCreate(name, nickname) { failure ->
                            creating = false
                            createError = failure
                            if (failure == null) {
                                showCreate = false
                                name = ""
                                nickname = ""
                            }
                        }
                    },
                ) { Text(if (creating) "Создаём…" else "Создать") }
            },
            dismissButton = {
                TextButton(enabled = !creating, onClick = { showCreate = false }) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun GroupChatPane(
    group: DesktopGroup,
    ownNickname: String,
    onSend: (String, ReplyReference?, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(group.id) { mutableStateOf("") }
    var sending by remember(group.id) { mutableStateOf(false) }
    var error by remember(group.id) { mutableStateOf<String?>(null) }
    var replyingTo by remember(group.id) { mutableStateOf<ReplyReference?>(null) }
    var messageMenuId by remember(group.id) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    fun submit() {
        if (sending || draft.isBlank()) return
        val text = draft
        val reply = replyingTo
        sending = true
        error = null
        onSend(text, reply) { failure ->
            sending = false
            error = failure
            if (failure == null) {
                draft = ""
                replyingTo = null
            }
        }
    }
    LaunchedEffect(group.id, group.messages.size) {
        if (group.messages.isNotEmpty()) listState.animateScrollToItem(group.messages.lastIndex)
    }
    Column(modifier.background(Color(0xFF090F15))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF111923)).padding(horizontal = 28.dp, vertical = 17.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF203B43)),
            ) {
                Icon(Icons.Rounded.Group, contentDescription = null, tint = Mint)
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(
                    "OpenMLS · ${group.members.size} участников · владелец @${group.owner}",
                    color = Mint,
                    fontSize = 12.sp,
                )
            }
            Icon(Icons.Rounded.Lock, contentDescription = "Сквозное шифрование", tint = Mint)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp),
            contentPadding = PaddingValues(vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            items(group.messages, key = { it.messageId ?: "${it.sender}:${it.createdAt}" }) { message ->
                Column(
                    horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (!message.outgoing) {
                        Text("@${message.sender}", color = Mint, fontSize = 11.sp, modifier = Modifier.padding(start = 9.dp, bottom = 3.dp))
                    }
                    Box {
                        Surface(
                            color = if (message.outgoing) Color(0xFF1E5B51) else Color(0xFF192530),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.clickable(enabled = message.messageId != null) {
                                messageMenuId = message.messageId
                            },
                        ) {
                            Column(Modifier.widthIn(max = 520.dp).padding(horizontal = 15.dp, vertical = 11.dp)) {
                                message.replyTo?.let { DesktopReplyQuote(it) }
                                Text(message.text, color = Color(0xFFEAF3F7))
                                Text(
                                    formatMessageTime(message.createdAt),
                                    color = TextMuted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = message.messageId != null && messageMenuId == message.messageId,
                            onDismissRequest = { messageMenuId = null },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Ответить") },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null)
                                },
                                onClick = {
                                    val id = message.messageId ?: return@DropdownMenuItem
                                    messageMenuId = null
                                    replyingTo = ReplyReference(
                                        messageId = id,
                                        sender = message.sender,
                                        preview = ReplyReference.preview(message.text),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        error?.let { ErrorText(it) }
        replyingTo?.let { DesktopReplyBar(it) { replyingTo = null } }
        Surface(color = Color(0xFF101822), modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 20.dp),
            ) {
                CorporateTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = "Сообщение в группу",
                    singleLine = false,
                    maxLines = 5,
                    enabled = !sending,
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp, max = 120.dp)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isCtrlPressed) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                )
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    enabled = !sending && draft.isNotBlank(),
                    onClick = ::submit,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = Ink),
                    modifier = Modifier.size(50.dp),
                ) {
                    if (sending) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyGroup(modifier: Modifier, error: String?) {
    Box(modifier.background(Color(0xFF090F15)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Group, contentDescription = null, tint = Mint, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp))
            Text("Выберите или создайте MLS-группу", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            error?.let { Text(it, color = Color(0xFFFF8D91), modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

@Composable
private fun ConversationRow(
    profile: HiddiProfile,
    avatar: ByteArray?,
    last: ChatEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) Color(0xFF1A3333) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Avatar(profile, 48.dp, avatar)
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text("@${profile.nickname}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (last.outgoing) "Вы: " else "") + last.text.replace('\n', ' '), color = TextMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatMessageTime(last.createdAt), color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ContactsPane(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<HiddiProfile>,
    contacts: Set<String>,
    profiles: Map<String, HiddiProfile>,
    avatars: Map<String, ByteArray>,
    selected: HiddiProfile?,
    onOpen: (HiddiProfile) -> Unit,
) {
    Text("Контакты", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
    CorporateTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Поиск по @nickname",
        leadingIcon = Icons.Rounded.Search,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
    )
    val visibleProfiles =
        if (query.trim().removePrefix("@").length < 2) {
            contacts.sorted().map { profiles[it] ?: HiddiProfile(it, "", "") }
        } else {
            results
        }
    Text(
        if (query.trim().removePrefix("@").length < 2) "МОИ КОНТАКТЫ" else "НАЙДЕННЫЕ ЛЮДИ",
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(18.dp, 16.dp, 18.dp, 8.dp),
    )
    LazyColumn(Modifier.fillMaxSize()) {
        if (visibleProfiles.isEmpty()) {
            item {
                Text(
                    if (query.length < 2) "Здесь появятся добавленные пользователи" else "Пользователи не найдены",
                    color = TextMuted,
                    modifier = Modifier.padding(18.dp),
                )
            }
        }
        items(visibleProfiles, key = HiddiProfile::nickname) { profile ->
            UserRow(profile, avatars[profile.nickname], selected?.nickname == profile.nickname) {
                onOpen(profile)
            }
        }
    }
}

@Composable
private fun DesktopSettingsPane(session: HiddiSession, online: Boolean) {
    Text("Настройки", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
    Surface(color = PanelRaised, shape = RoundedCornerShape(18.dp), modifier = Modifier.padding(16.dp).fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("${if (online) "В сети" else "Нет соединения"}", color = if (online) Mint else Color(0xFFFF8D91), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("@${session.nickname} · устройство ${session.deviceNumber}", color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DesktopSettingsDetail(
    session: HiddiSession,
    invisibleMode: Boolean,
    onInvisibleModeChange: (Boolean) -> Unit,
    onLoggedOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var profile by remember(session) { mutableStateOf<HiddiProfile?>(null) }
    var displayName by remember(session) { mutableStateOf("") }
    var bio by remember(session) { mutableStateOf("") }
    var avatar by remember(session) { mutableStateOf<ByteArray?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var linkCode by remember { mutableStateOf<String?>(null) }
    var linkQr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    suspend fun reload() {
        val loaded = withContext(Dispatchers.IO) { session.profile() }
        val image =
            loaded.avatarVersion?.let {
                runCatching { withContext(Dispatchers.IO) { session.avatar(loaded.nickname) } }
                    .getOrNull()
            }
        profile = loaded
        displayName = loaded.displayName
        bio = loaded.bio
        avatar = image
    }

    LaunchedEffect(session) {
        runCatching { reload() }
            .onFailure { status = it.message ?: "Не удалось загрузить профиль" }
    }

    val avatarBitmap =
        remember(avatar?.contentHashCode()) {
            avatar?.let { bytes ->
                runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
            }
        }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(42.dp),
        modifier = modifier.background(Ink),
    ) {
        item {
            Text("Профиль и безопасность", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("@${session.nickname}", color = Mint, fontSize = 18.sp)
        }
        item {
            Surface(
                color = PanelRaised,
                shape = RoundedCornerShape(22.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(20.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(92.dp).clip(CircleShape)
                            .background(Color(0xFF243B43)),
                    ) {
                        if (avatarBitmap != null) {
                            Image(
                                bitmap = avatarBitmap,
                                contentDescription = "Аватар",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                displayName.ifBlank { session.nickname }
                                    .firstOrNull()?.uppercase() ?: "H",
                                color = Mint,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            displayName.ifBlank { "@${session.nickname}" },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text("@${session.nickname}", color = Mint)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    val file = chooseAvatarImage() ?: return@OutlinedButton
                                    busy = true
                                    status = "Подготавливаем аватар…"
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                sanitizeAvatar(file).also(session::uploadAvatar)
                                            }
                                        }.onSuccess {
                                            avatar = it
                                            status = "Аватар обновлён"
                                            runCatching { reload() }
                                        }.onFailure {
                                            status = it.message ?: "Не удалось обновить аватар"
                                        }
                                        busy = false
                                    }
                                },
                            ) {
                                Text(if (avatar == null) "Добавить фото" else "Сменить фото")
                            }
                            if (avatar != null) {
                                TextButton(
                                    enabled = !busy,
                                    onClick = {
                                        busy = true
                                        scope.launch {
                                            runCatching {
                                                withContext(Dispatchers.IO) { session.deleteAvatar() }
                                            }.onSuccess {
                                                avatar = null
                                                profile = profile?.copy(avatarVersion = null)
                                                status = "Аватар удалён"
                                            }.onFailure {
                                                status = it.message ?: "Не удалось удалить аватар"
                                            }
                                            busy = false
                                        }
                                    },
                                ) { Text("Удалить", color = Color(0xFFFF8D91)) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("ПРОФИЛЬ", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            CorporateTextField(
                value = displayName,
                onValueChange = { if (it.length <= 64) displayName = it },
                placeholder = "Видимое имя",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            )
            Spacer(Modifier.height(12.dp))
            CorporateTextField(
                value = bio,
                onValueChange = { if (it.length <= 250) bio = it },
                placeholder = "О себе",
                enabled = !busy,
                singleLine = false,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    status = "Сохраняем профиль…"
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                session.updateProfile(displayName, bio)
                            }
                        }.onSuccess {
                            profile = it
                            displayName = it.displayName
                            bio = it.bio
                            status = "Профиль сохранён"
                        }.onFailure {
                            status = it.message ?: "Не удалось сохранить профиль"
                        }
                        busy = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Ink),
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("Сохранить", fontWeight = FontWeight.Bold)
                }
            }
        }
        item {
            Surface(
                color = PanelRaised,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Режим невидимки", fontWeight = FontWeight.Bold)
                            Text(
                                "Не показывать статус «в сети» и набор текста",
                                color = TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Switch(
                            checked = invisibleMode,
                            onCheckedChange = onInvisibleModeChange,
                        )
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                    Text("Это устройство", fontWeight = FontWeight.Bold)
                    Text("Linux · устройство ${session.deviceNumber}", color = TextMuted)
                    Text(
                        "Signal-ключи и история защищены локальным паролем и не покидают компьютер.",
                        color = TextMuted,
                        fontSize = 13.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val (code, _) = session.createDeviceLinkCode()
                                            val png = ByteArrayOutputStream().also { ImageIO.write(createDeviceLinkQr(session.server, code), "png", it) }.toByteArray()
                                            code to SkiaImage.makeFromEncoded(png).toComposeImageBitmap()
                                        }
                                    }.onSuccess { (code, image) ->
                                        linkCode = code
                                        linkQr = image
                                        showLinkDialog = true
                                    }.onFailure { status = it.message ?: "Не удалось создать QR-код" }
                                    busy = false
                                }
                            },
                        ) { Text("Подключить Android") }
                        TextButton(enabled = !busy, onClick = { showLogoutDialog = true }) {
                            Text("Выйти с устройства", color = Color(0xFFFF8D91))
                        }
                    }
                }
            }
        }
        status?.let { message ->
            item { Text(message, color = TextMuted, fontSize = 13.sp) }
        }
    }
    if (showLinkDialog && linkQr != null) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Подключить Android") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("На телефоне выберите подключение существующего аккаунта и отсканируйте QR. Код одноразовый.")
                    Spacer(Modifier.height(14.dp))
                    Image(linkQr!!, "QR-код привязки Android", modifier = Modifier.size(320.dp).background(Color.White).padding(10.dp))
                    linkCode?.let { Text(it, color = TextMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            },
            confirmButton = { TextButton(onClick = { showLinkDialog = false }) { Text("Готово") } },
        )
    }
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showLogoutDialog = false },
            title = { Text("Выйти с этого компьютера?") },
            text = { Text("Устройство будет отозвано, а локальные ключи, история и кэш вложений удалены без возможности восстановления.") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { session.logoutCurrentDevice() } }
                            .onSuccess { showLogoutDialog = false; onLoggedOut() }
                            .onFailure { status = it.message ?: "Не удалось выйти"; busy = false }
                    }
                }) { Text("Выйти", color = Color(0xFFFF8D91)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showLogoutDialog = false }) { Text("Отмена") } },
        )
    }
}

private fun chooseAvatarImage(): File? {
    val dialog = FileDialog(null as Frame?, "Выберите аватар Hiddi", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) ||
            name.endsWith(".png", true) ||
            name.endsWith(".webp", true)
    }
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

private fun chooseChatImage(): File? {
    val dialog = FileDialog(null as Frame?, "Отправить изображение", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name ->
        name.endsWith(".jpg", true) ||
            name.endsWith(".jpeg", true) ||
            name.endsWith(".png", true) ||
            name.endsWith(".webp", true) ||
            name.endsWith(".gif", true) ||
            name.endsWith(".bmp", true)
    }
    dialog.isVisible = true
    return dialog.file?.let { File(dialog.directory, it) }
}

private fun sanitizeAvatar(file: File): ByteArray {
    val source = ImageIO.read(file) ?: error("Не удалось прочитать изображение")
    val side = minOf(source.width, source.height)
    require(side > 0) { "Изображение пустое" }
    val target = BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB)
    val graphics = target.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BICUBIC,
        )
        val x = (source.width - side) / 2
        val y = (source.height - side) / 2
        graphics.drawImage(source, 0, 0, 512, 512, x, y, x + side, y + side, null)
    } finally {
        graphics.dispose()
    }
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    try {
        var quality = 0.9f
        while (quality >= 0.45f) {
            val output = ByteArrayOutputStream()
            ImageIO.createImageOutputStream(output).use { stream ->
                writer.output = stream
                val params = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = quality
                }
                writer.write(null, IIOImage(target, null, null), params)
            }
            val bytes = output.toByteArray()
            if (bytes.size <= 512 * 1024) return bytes
            bytes.fill(0)
            quality -= 0.1f
        }
    } finally {
        writer.dispose()
        source.flush()
        target.flush()
    }
    error("Не удалось уменьшить аватар до 512 КиБ")
}

@Composable
private fun AccountHeader(session: HiddiSession, online: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(18.dp),
    ) {
        AppMark()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "@${session.nickname}",
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("Linux · устройство ${session.deviceNumber}", color = TextMuted, fontSize = 12.sp)
        }
        Box(
            Modifier.size(10.dp)
                .clip(CircleShape)
                .background(if (online) Mint else Color(0xFF66717C)),
        )
    }
}

@Composable
private fun UserRow(
    profile: HiddiProfile,
    avatar: ByteArray?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .background(if (selected) PanelRaised else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(profile, avatar = avatar)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                profile.displayName.ifBlank { "@${profile.nickname}" },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "@${profile.nickname}",
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Avatar(profile: HiddiProfile, size: Dp = 42.dp, avatar: ByteArray? = null) {
    val bitmap =
        remember(avatar?.contentHashCode()) {
            avatar?.let { bytes ->
                runCatching {
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()
            }
        }
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF243B43)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Аватар @${profile.nickname}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                profile.displayName.ifBlank { profile.nickname }
                    .firstOrNull()
                    ?.uppercase()
                    ?: "H",
                color = Mint,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun UserProfileDialog(
    profile: HiddiProfile,
    avatar: ByteArray?,
    online: Boolean,
    typing: Boolean,
    isContact: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSetContact: (Boolean) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Профиль пользователя", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Avatar(profile, 96.dp, avatar)
                Spacer(Modifier.height(16.dp))
                Text(
                    profile.displayName.ifBlank { "@${profile.nickname}" },
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("@${profile.nickname}", color = TextMuted, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        typing -> "печатает…"
                        online -> "В сети"
                        else -> "Не в сети"
                    },
                    color = if (typing || online) Mint else TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(18.dp))
                Surface(
                    color = PanelRaised,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("О СЕБЕ", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        Text(
                            profile.bio.ifBlank { "Пользователь пока ничего о себе не написал." },
                            color = if (profile.bio.isBlank()) TextMuted else Color(0xFFEAF3F7),
                            fontSize = 14.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF102522))
                            .padding(14.dp),
                ) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = Mint)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Signal E2EE", fontWeight = FontWeight.SemiBold)
                        Text("Личные сообщения защищены", color = TextMuted, fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSetContact(!isContact) },
                enabled = !busy,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = if (isContact) PanelRaised else Mint,
                        contentColor = if (isContact) Color(0xFFEAF3F7) else Ink,
                    ),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (isContact) "Удалить из контактов" else "Добавить в контакты")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text("Закрыть")
            }
        },
        containerColor = Color(0xFF151C26),
    )
}

@Composable
private fun ChatPane(
    profile: HiddiProfile,
    profileAvatar: ByteArray?,
    messages: List<ChatEntry>,
    ownNickname: String,
    isContact: Boolean,
    isBlocked: Boolean,
    peerOnline: Boolean,
    peerTyping: Boolean,
    onTypingChange: (Boolean) -> Unit,
    onSend: (String, ReplyReference?, (String?) -> Unit) -> Unit,
    onSendImage: (File, (String?) -> Unit) -> Unit,
    onSendVoice: (ByteArray, Long, (String?) -> Unit) -> Unit,
    onLoadAttachment: suspend (AttachmentDescriptor) -> ByteArray,
    onLoadSafetyNumber: suspend () -> SafetyNumberInfo,
    onTrustSafetyNumber: suspend (String) -> Unit,
    onClearConversation: suspend (Boolean) -> Unit,
    onDeleteMessage: (String, Boolean, (String?) -> Unit) -> Unit,
    onSetBlocked: suspend (Boolean) -> Unit,
    onSetContact: suspend (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(profile.nickname) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var attachmentBusy by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    var pendingVoice by remember(profile.nickname) { mutableStateOf<RecordedDesktopVoice?>(null) }
    var recordingSeconds by remember { mutableStateOf(0L) }
    var voiceLevel by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var messageMenuId by remember(profile.nickname) { mutableStateOf<String?>(null) }
    var replyingTo by remember(profile.nickname) { mutableStateOf<ReplyReference?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var clearForBoth by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var showSafetyDialog by remember { mutableStateOf(false) }
    var showProfileDialog by remember(profile.nickname) { mutableStateOf(false) }
    var safetyInfo by remember { mutableStateOf<SafetyNumberInfo?>(null) }
    var actionBusy by remember { mutableStateOf(false) }
    var refocusRevision by remember(profile.nickname) { mutableStateOf(0) }
    var forceScrollRevision by remember(profile.nickname) { mutableStateOf(0) }
    var handledForceScrollRevision by remember(profile.nickname) { mutableStateOf(0) }
    val actionScope = rememberCoroutineScope()
    val voiceRecorder = remember(profile.nickname) { InMemoryDesktopVoiceRecorder() }
    val latestPendingVoice by rememberUpdatedState(pendingVoice)
    val inputFocusRequester = remember(profile.nickname) { FocusRequester() }
    val messageListState = rememberLazyListState()
    val isAtNewestMessage by remember {
        derivedStateOf {
            val layout = messageListState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index
            lastVisible == null || lastVisible >= layout.totalItemsCount - 2
        }
    }
    if (showProfileDialog) {
        UserProfileDialog(
            profile = profile,
            avatar = profileAvatar,
            online = peerOnline,
            typing = peerTyping,
            isContact = isContact,
            busy = actionBusy,
            onDismiss = { if (!actionBusy) showProfileDialog = false },
            onSetContact = { added ->
                actionBusy = true
                actionScope.launch {
                    runCatching { onSetContact(added) }
                        .onFailure { error = it.message ?: "Не удалось изменить контакты" }
                    actionBusy = false
                }
            },
        )
    }
    fun submit() {
        if (draft.isBlank() || sending || attachmentBusy || voiceRecording || pendingVoice != null || isBlocked) return
        val text = draft
        val reply = replyingTo
        onTypingChange(false)
        sending = true
        error = null
        onSend(text, reply) { failure ->
            sending = false
            error = failure
            refocusRevision++
            if (failure == null) {
                draft = ""
                replyingTo = null
                forceScrollRevision++
            }
        }
    }
    fun selectAndSendImage() {
        if (sending || attachmentBusy || voiceRecording || pendingVoice != null || isBlocked) return
        val file = chooseChatImage() ?: return
        attachmentBusy = true
        error = null
        onSendImage(file) { failure ->
            attachmentBusy = false
            error = failure
            refocusRevision++
            if (failure == null) forceScrollRevision++
        }
    }
    fun stopVoiceRecording() {
        if (!voiceRecording || attachmentBusy) return
        voiceRecording = false
        attachmentBusy = true
        error = null
        actionScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { voiceRecorder.stop() }
            }.onSuccess { recorded ->
                pendingVoice?.pcm?.fill(0)
                pendingVoice = recorded
                attachmentBusy = false
            }.onFailure {
                voiceRecorder.cancel()
                attachmentBusy = false
                error = it.message ?: "Не удалось завершить запись"
                refocusRevision++
            }
        }
    }

    fun sendPendingVoice() {
        val recorded = pendingVoice ?: return
        if (sending || attachmentBusy || isBlocked) return
        attachmentBusy = true
        error = null
        onSendVoice(recorded.pcm, recorded.durationMs) { failure ->
            attachmentBusy = false
            error = failure
            pendingVoice = null
            refocusRevision++
            if (failure == null) forceScrollRevision++
        }
    }

    fun cancelVoice() {
        if (voiceRecording) {
            voiceRecording = false
            voiceRecorder.cancel()
        }
        pendingVoice?.pcm?.fill(0)
        pendingVoice = null
        voiceLevel = 0f
        error = null
        refocusRevision++
    }
    LaunchedEffect(profile.nickname, messages.size, forceScrollRevision) {
        val forced = forceScrollRevision != handledForceScrollRevision
        if (messages.isNotEmpty() && (forced || isAtNewestMessage)) {
            messageListState.animateScrollToItem(messages.size)
        }
        handledForceScrollRevision = forceScrollRevision
    }
    LaunchedEffect(profile.nickname) {
        if (messages.isNotEmpty()) messageListState.scrollToItem(messages.size)
    }
    LaunchedEffect(profile.nickname, refocusRevision, sending, pendingVoice) {
        if (!sending && !attachmentBusy && !voiceRecording && pendingVoice == null && !isBlocked) {
            inputFocusRequester.requestFocus()
        }
    }
    LaunchedEffect(profile.nickname, draft, isBlocked) {
        if (draft.isNotBlank() && !isBlocked) {
            onTypingChange(true)
            delay(1_800)
            onTypingChange(false)
        } else {
            onTypingChange(false)
        }
    }
    DisposableEffect(profile.nickname) {
        onDispose {
            onTypingChange(false)
            voiceRecorder.cancel()
            latestPendingVoice?.pcm?.fill(0)
        }
    }
    LaunchedEffect(voiceRecording) {
        recordingSeconds = 0
        val startedAt = System.currentTimeMillis()
        while (voiceRecording) {
            delay(45)
            voiceLevel = voiceRecorder.level
            recordingSeconds = (System.currentTimeMillis() - startedAt) / 1_000
            if (recordingSeconds >= InMemoryDesktopVoiceRecorder.MAX_DURATION_MS / 1_000) {
                stopVoiceRecording()
            }
        }
        voiceLevel = 0f
    }
    Column(modifier.background(Color(0xFF090F15))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF111923)).padding(horizontal = 28.dp, vertical = 17.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier.weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showProfileDialog = true }
                        .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Box {
                    Avatar(profile, 46.dp, profileAvatar)
                    Box(
                        Modifier.align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF111923))
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (peerOnline && !isBlocked) Mint else Color(0xFF66717C)),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        profile.displayName.ifBlank { "@${profile.nickname}" },
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        when {
                            isBlocked -> "В игноре"
                            peerTyping -> "печатает…"
                            peerOnline -> "В сети"
                            else -> "Не в сети"
                        },
                        color =
                            when {
                                isBlocked -> Color(0xFFFF8D91)
                                peerTyping || peerOnline -> Mint
                                else -> TextMuted
                            },
                        fontSize = 12.sp,
                    )
                }
            }
            IconButton(onClick = {}) { Icon(Icons.Rounded.Search, contentDescription = "Поиск", tint = Mint) }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Меню", tint = TextMuted)
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    Text(
                        "БЕЗОПАСНОСТЬ",
                        color = Mint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    DropdownMenuItem(
                        text = { Text("Проверить E2EE") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Security, contentDescription = null, tint = Mint)
                        },
                        onClick = {
                            menuExpanded = false
                            safetyInfo = null
                            showSafetyDialog = true
                            actionBusy = true
                            actionScope.launch {
                                runCatching { onLoadSafetyNumber() }
                                    .onSuccess { safetyInfo = it }
                                    .onFailure {
                                        error = it.message ?: "Не удалось получить код безопасности"
                                        showSafetyDialog = false
                                    }
                                actionBusy = false
                            }
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Очистить чат") },
                        leadingIcon = {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = TextMuted)
                        },
                        onClick = {
                            menuExpanded = false
                            clearForBoth = false
                            showClearDialog = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isBlocked) "Разблокировать" else "Заблокировать") },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Block,
                                contentDescription = null,
                                tint = if (isBlocked) Mint else Color(0xFFFF7C8B),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            showBlockDialog = true
                        },
                    )
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().background(Color(0xFF071017)),
        ) {
            val backgroundBitmap =
                remember {
                    object {}.javaClass.getResourceAsStream("/chat_background.webp")
                        ?.use { input ->
                            runCatching {
                                SkiaImage.makeFromEncoded(input.readBytes()).toComposeImageBitmap()
                            }.getOrNull()
                        }
                }
            if (backgroundBitmap != null) {
                Image(
                    bitmap = backgroundBitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.34f,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            LazyColumn(
                state = messageListState,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
                contentPadding = PaddingValues(horizontal = 34.dp, vertical = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(color = Color(0xFF202934), shape = RoundedCornerShape(14.dp)) {
                            Text("Сегодня", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp))
                        }
                    }
                }
                if (messages.isEmpty()) {
                    item {
                        Text(
                            "Начните защищённый диалог. Сейчас Linux-клиент умеет безопасно " +
                                "отправлять сообщения на основное устройство собеседника.",
                            color = TextMuted,
                        )
                    }
                }
                items(messages) { message ->
                    Row(
                        horizontalArrangement =
                            if (message.outgoing) Arrangement.End else Arrangement.Start,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box {
                            Surface(
                                color = if (message.outgoing) Color(0xFF1D5B50) else PanelRaised,
                                shape = RoundedCornerShape(if (message.outgoing) 20.dp else 18.dp),
                                modifier =
                                    Modifier.widthIn(max = 520.dp)
                                        .clickable(enabled = message.messageId != null) {
                                            messageMenuId = message.messageId
                                        },
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                    horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
                                ) {
                                    message.replyTo?.let { DesktopReplyQuote(it) }
                                    when (message.attachment?.kind) {
                                        DesktopAttachmentStore.IMAGE_KIND ->
                                            DesktopAttachmentImage(
                                                descriptor = message.attachment,
                                                onLoadAttachment = onLoadAttachment,
                                            )
                                        DesktopAttachmentStore.VOICE_KIND ->
                                            DesktopVoiceAttachment(
                                                descriptor = message.attachment,
                                                onLoadAttachment = onLoadAttachment,
                                            )
                                        else -> Text(message.text)
                                    }
                                    MessageMeta(message)
                                }
                            }
                            DropdownMenu(
                                expanded = message.messageId != null && messageMenuId == message.messageId,
                                onDismissRequest = { messageMenuId = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ответить") },
                                    leadingIcon = {
                                        Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null)
                                    },
                                    onClick = {
                                        val id = message.messageId ?: return@DropdownMenuItem
                                        messageMenuId = null
                                        replyingTo = ReplyReference(
                                            messageId = id,
                                            sender = if (message.outgoing) ownNickname else message.peer,
                                            preview = ReplyReference.preview(message.text),
                                        )
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Удалить у себя") },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                                    },
                                    onClick = {
                                        val id = message.messageId ?: return@DropdownMenuItem
                                        messageMenuId = null
                                        onDeleteMessage(id, false) { failure -> error = failure }
                                    },
                                )
                                if (message.outgoing) {
                                    DropdownMenuItem(
                                        text = { Text("Удалить у всех", color = Color(0xFFFF9DA4)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.DeleteSweep,
                                                contentDescription = null,
                                                tint = Color(0xFFFF7C8B),
                                            )
                                        },
                                        onClick = {
                                            val id = message.messageId ?: return@DropdownMenuItem
                                            messageMenuId = null
                                            onDeleteMessage(id, true) { failure -> error = failure }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        error?.let { ErrorText(it) }
        replyingTo?.let { DesktopReplyBar(it) { replyingTo = null } }
        Surface(color = Color(0xFF101822), modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 20.dp),
            ) {
                IconButton(
                    enabled = !sending && !attachmentBusy && !voiceRecording && pendingVoice == null && !isBlocked,
                    onClick = ::selectAndSendImage,
                ) {
                    Icon(
                        Icons.Rounded.Image,
                        contentDescription = "Отправить изображение",
                        tint = Mint,
                    )
                }
                IconButton(
                    enabled = !sending && !attachmentBusy && pendingVoice == null && !isBlocked,
                    onClick = {
                        if (voiceRecording) {
                            stopVoiceRecording()
                        } else {
                            error = null
                            runCatching { voiceRecorder.start() }
                                .onSuccess {
                                    draft = ""
                                    onTypingChange(false)
                                    voiceRecording = true
                                }
                                .onFailure {
                                    voiceRecorder.cancel()
                                    error = it.message ?: "Не удалось открыть микрофон"
                                }
                        }
                    },
                ) {
                    Icon(
                        if (voiceRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription =
                            if (voiceRecording) {
                                "Остановить запись"
                            } else {
                                "Записать голосовое"
                            },
                        tint = if (voiceRecording) Color(0xFFFF7C8B) else Mint,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    if (voiceRecording || pendingVoice != null) {
                        Surface(
                            color = if (voiceRecording) Color(0xFF241C24) else Color(0xFF17242A),
                            shape = RoundedCornerShape(18.dp),
                            border =
                                BorderStroke(
                                    1.dp,
                                    if (voiceRecording) {
                                        Color(0xFFFF7C8B).copy(alpha = 0.55f)
                                    } else {
                                        Mint.copy(alpha = 0.45f)
                                    },
                                ),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 18.dp),
                            ) {
                                if (voiceRecording) {
                                    Box(
                                        Modifier.size(9.dp).clip(CircleShape)
                                            .background(Color(0xFFFF5B69)),
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Mic,
                                        contentDescription = null,
                                        tint = Mint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                val voiceDurationSeconds =
                                    if (voiceRecording) {
                                        recordingSeconds
                                    } else {
                                        (pendingVoice?.durationMs ?: 0L) / 1_000
                                    }
                                Text(
                                    "%s · %d:%02d".format(
                                        if (voiceRecording) "Запись" else "Готово",
                                        voiceDurationSeconds / 60,
                                        voiceDurationSeconds % 60,
                                    ),
                                    color = if (voiceRecording) Color(0xFFFFC5CA) else Mint,
                                )
                                Spacer(Modifier.weight(1f))
                                DesktopVoiceWaveform(
                                    seed = pendingVoice?.pcm?.contentHashCode() ?: 0,
                                    color = if (voiceRecording) Color(0xFFFF9DA4) else Mint,
                                    level = if (voiceRecording) voiceLevel else null,
                                    modifier = Modifier.width(130.dp).height(28.dp),
                                )
                                TextButton(onClick = ::cancelVoice) { Text("Удалить", color = Color(0xFFFF9DA4)) }
                                if (!voiceRecording) {
                                    FilledIconButton(
                                        enabled = !attachmentBusy,
                                        onClick = ::sendPendingVoice,
                                        colors =
                                            IconButtonDefaults.filledIconButtonColors(
                                                containerColor = Mint,
                                                contentColor = Ink,
                                            ),
                                        modifier = Modifier.size(38.dp),
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Rounded.Send,
                                            contentDescription = "Отправить голосовое",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else {
                                    Text("■ остановить", color = TextMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        CorporateTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            placeholder =
                                when {
                                    isBlocked -> "Пользователь заблокирован"
                                    attachmentBusy -> "Шифруем и отправляем вложение…"
                                    else -> "Сообщение"
                                },
                            enabled = !sending && !attachmentBusy && pendingVoice == null && !isBlocked,
                            singleLine = false,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp, max = 124.dp)
                                .focusRequester(inputFocusRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown ||
                                        event.key != Key.Enter
                                    ) {
                                        return@onPreviewKeyEvent false
                                    }
                                    if (event.isCtrlPressed) {
                                        draft += "\n"
                                    } else {
                                        submit()
                                    }
                                    true
                                },
                        )
                    }
                    Text(
                        when {
                            voiceRecording -> "Голос шифруется до загрузки на сервер"
                            pendingVoice != null -> "Войс готов — отправьте или удалите"
                            attachmentBusy -> "На сервер отправляется только шифротекст"
                            else -> "Enter — отправить · Ctrl+Enter — новая строка"
                        },
                        color = TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 5.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(
                    enabled =
                        draft.isNotBlank() && !sending && !attachmentBusy &&
                            !voiceRecording && pendingVoice == null && !isBlocked,
                    onClick = {
                        if (pendingVoice != null) sendPendingVoice() else submit()
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = Mint,
                        contentColor = Ink,
                        disabledContainerColor = Color(0xFF27313B),
                        disabledContentColor = TextMuted.copy(alpha = 0.55f),
                    ),
                    modifier = Modifier.padding(top = 1.dp).size(50.dp),
                ) {
                    if (sending || attachmentBusy) {
                        CircularProgressIndicator(
                            color = TextMuted,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Отправить",
                            modifier = Modifier.offset(x = 1.dp).size(22.dp),
                        )
                    }
                }
            }
        }
    }
    if (showSafetyDialog) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) showSafetyDialog = false },
            icon = { Icon(Icons.Rounded.Security, contentDescription = null, tint = Mint) },
            title = { Text("Проверка E2EE") },
            text = {
                val info = safetyInfo
                if (info == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Получаем код безопасности…")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "Сравните этот код с кодом на устройстве @${profile.nickname}.",
                            color = TextMuted,
                        )
                        Surface(color = PanelRaised, shape = RoundedCornerShape(14.dp)) {
                            Text(
                                info.value,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 16.sp,
                                lineHeight = 25.sp,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                        Text(
                            if (info.trusted) "Код уже подтверждён" else "Подтвердите только после сравнения",
                            color = if (info.trusted) Mint else Color(0xFFFFC66D),
                        )
                    }
                }
            },
            confirmButton = {
                val info = safetyInfo
                TextButton(
                    enabled = info != null && !actionBusy,
                    onClick = {
                        if (info == null || info.trusted) {
                            showSafetyDialog = false
                        } else {
                            actionBusy = true
                            actionScope.launch {
                                runCatching { onTrustSafetyNumber(info.value) }
                                    .onSuccess {
                                        safetyInfo = info.copy(trusted = true)
                                        showSafetyDialog = false
                                    }
                                    .onFailure {
                                        error = it.message ?: "Не удалось подтвердить код"
                                    }
                                actionBusy = false
                            }
                        }
                    },
                ) {
                    Text(if (info?.trusted == true) "Готово" else "Подтвердить")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = { showSafetyDialog = false },
                ) { Text("Закрыть") }
            },
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) showClearDialog = false },
            icon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = TextMuted) },
            title = { Text("Очистить диалог?") },
            text = {
                Column {
                    Text("Сообщения будут удалены с этого устройства.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable(enabled = !actionBusy) {
                            clearForBoth = !clearForBoth
                        },
                    ) {
                        Checkbox(
                            checked = clearForBoth,
                            onCheckedChange = { clearForBoth = it },
                            enabled = !actionBusy,
                        )
                        Text("Также удалить у @${profile.nickname}")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = {
                        actionBusy = true
                        actionScope.launch {
                            runCatching { onClearConversation(clearForBoth) }
                                .onSuccess { showClearDialog = false }
                                .onFailure { error = it.message ?: "Не удалось очистить диалог" }
                            actionBusy = false
                        }
                    },
                ) { Text("Очистить", color = Color(0xFFFF8D91)) }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = { showClearDialog = false },
                ) { Text("Отмена") }
            },
        )
    }
    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { if (!actionBusy) showBlockDialog = false },
            icon = {
                Icon(
                    Icons.Rounded.Block,
                    contentDescription = null,
                    tint = if (isBlocked) Mint else Color(0xFFFF7C8B),
                )
            },
            title = { Text(if (isBlocked) "Разблокировать?" else "Заблокировать?") },
            text = {
                Text(
                    if (isBlocked) {
                        "@${profile.nickname} снова сможет отправлять вам сообщения."
                    } else {
                        "Сообщения от @${profile.nickname} больше не будут приниматься."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = {
                        actionBusy = true
                        actionScope.launch {
                            runCatching { onSetBlocked(!isBlocked) }
                                .onSuccess {
                                    showBlockDialog = false
                                    refocusRevision++
                                }
                                .onFailure { error = it.message ?: "Не удалось изменить блокировку" }
                            actionBusy = false
                        }
                    },
                ) { Text(if (isBlocked) "Разблокировать" else "Заблокировать") }
            },
            dismissButton = {
                TextButton(
                    enabled = !actionBusy,
                    onClick = { showBlockDialog = false },
                ) { Text("Отмена") }
            },
        )
    }
}

@Composable
private fun DesktopAttachmentImage(
    descriptor: AttachmentDescriptor,
    onLoadAttachment: suspend (AttachmentDescriptor) -> ByteArray,
) {
    var showFullImage by remember(descriptor.attachmentId) { mutableStateOf(false) }
    val preview = rememberDesktopAttachmentBitmap(descriptor.preview ?: descriptor, onLoadAttachment)
    Box(
        modifier =
            Modifier.width(300.dp).height(190.dp).clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF101923))
                .clickable(enabled = preview != null) { showFullImage = true },
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                    color = Mint,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text("Расшифровываем фото…", color = TextMuted, fontSize = 12.sp)
            }
        } else {
            Image(
                bitmap = preview,
                contentDescription = "Открыть изображение",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Surface(
                color = Color.Black.copy(alpha = 0.48f),
                shape = CircleShape,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Сквозное шифрование",
                    tint = Mint,
                    modifier = Modifier.padding(6.dp).size(14.dp),
                )
            }
        }
    }
    if (showFullImage) {
        Window(
            onCloseRequest = { showFullImage = false },
            title = "Hiddi — изображение",
            state = rememberWindowState(placement = WindowPlacement.Fullscreen),
            undecorated = true,
        ) {
            val full = rememberDesktopAttachmentBitmap(descriptor, onLoadAttachment)
            Box(
                Modifier.fillMaxSize().background(Color(0xFF05090D)),
                contentAlignment = Alignment.Center,
            ) {
                if (full == null) {
                    CircularProgressIndicator(color = Mint)
                } else {
                    Image(
                        bitmap = full,
                        contentDescription = "Зашифрованное изображение",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                    )
                }
                FilledIconButton(
                    onClick = { showFullImage = false },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.55f),
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.align(Alignment.TopEnd).padding(18.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть")
                }
            }
        }
    }
}

@Composable
private fun rememberDesktopAttachmentBitmap(
    descriptor: AttachmentDescriptor,
    onLoadAttachment: suspend (AttachmentDescriptor) -> ByteArray,
) =
    produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = descriptor.attachmentId,
    ) {
        value =
            runCatching {
                val bytes = onLoadAttachment(descriptor)
                try {
                    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                } finally {
                    bytes.fill(0)
                }
            }.getOrNull()
    }.value

@Composable
private fun DesktopVoiceAttachment(
    descriptor: AttachmentDescriptor,
    onLoadAttachment: suspend (AttachmentDescriptor) -> ByteArray,
) {
    val scope = rememberCoroutineScope()
    var playing by remember(descriptor.attachmentId) { mutableStateOf(false) }
    var playbackError by remember(descriptor.attachmentId) { mutableStateOf(false) }
    val seconds = ((descriptor.durationMs ?: 0L) / 1_000).coerceAtLeast(0)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.widthIn(min = 210.dp).padding(vertical = 2.dp),
    ) {
        FilledIconButton(
            enabled = !playing,
            onClick = {
                playing = true
                playbackError = false
                scope.launch {
                    runCatching {
                        val pcm = onLoadAttachment(descriptor)
                        try {
                            withContext(Dispatchers.IO) { playDesktopVoicePcm(pcm) }
                        } finally {
                            pcm.fill(0)
                        }
                    }.onFailure { playbackError = true }
                    playing = false
                }
            },
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = Mint,
                    contentColor = Ink,
                    disabledContainerColor = Mint.copy(alpha = 0.55f),
                ),
            modifier = Modifier.size(42.dp),
        ) {
            if (playing) {
                CircularProgressIndicator(
                    color = Ink,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Воспроизвести")
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                if (playbackError) "Не удалось воспроизвести" else "Голосовое",
                fontWeight = FontWeight.SemiBold,
                color = if (playbackError) Color(0xFFFF9DA4) else Color.Unspecified,
            )
            DesktopVoiceWaveform(
                seed = descriptor.attachmentId.hashCode(),
                color = Mint,
                modifier = Modifier.width(128.dp).height(22.dp).padding(vertical = 3.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(5.dp))
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "Сквозное шифрование",
                    tint = Mint,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun DesktopVoiceWaveform(
    seed: Int,
    color: Color,
    modifier: Modifier = Modifier,
    level: Float? = null,
) {
    Canvas(modifier) {
        val bars = 26
        val gap = size.width / (bars * 2f - 1f)
        repeat(bars) { index ->
            val noise = (((seed * 31 + index * 17) ushr 3) and 15) / 15f
            val factor = level?.let { ((index % 5 + 1) / 5f * it).coerceAtLeast(0.12f) }
                ?: (0.22f + noise * 0.72f)
            val barHeight = (size.height * factor).coerceAtLeast(3.dp.toPx())
            drawLine(
                color = color.copy(alpha = if (level == null) 0.72f else 1f),
                start = Offset(index * gap * 2 + gap / 2, (size.height - barHeight) / 2),
                end = Offset(index * gap * 2 + gap / 2, (size.height + barHeight) / 2),
                strokeWidth = gap.coerceAtMost(4.dp.toPx()),
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The quoted message a reply points at. Renders from the snapshot carried in the
 * reply itself, so it stays readable when the original is outside the local
 * history window.
 */
@Composable
private fun DesktopReplyQuote(reply: ReplyReference) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Box(
            Modifier.width(3.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Mint),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("@${reply.sender}", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                reply.preview.ifBlank { "Вложение" },
                color = TextMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Sits above the composer while a reply is being written. */
@Composable
private fun DesktopReplyBar(reply: ReplyReference, onCancel: () -> Unit) {
    Surface(color = Color(0xFF16202B), modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
                .padding(start = 30.dp, end = 18.dp, top = 9.dp, bottom = 9.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.Reply,
                contentDescription = null,
                tint = Mint,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ответ @${reply.sender}", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    reply.preview.ifBlank { "Вложение" },
                    color = TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = "Отменить ответ", tint = TextMuted)
            }
        }
    }
}

@Composable
private fun MessageMeta(message: ChatEntry) {
    val time = remember(message.createdAt) { formatMessageTime(message.createdAt) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp)) {
        Text(time, color = TextMuted, fontSize = 11.sp)
        if (message.outgoing) {
            Spacer(Modifier.width(5.dp))
            DeliveryChecks(message.deliveryStatus)
        }
    }
}

private fun formatMessageTime(time: Long): String =
    DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))

@Composable
private fun DeliveryChecks(status: String) {
    val color = if (status == "read") Mint else Color(0xFFD4E2DD).copy(alpha = 0.78f)
    val double = status == "delivered" || status == "read"
    Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
        fun mark(offset: Float) {
            drawLine(color, Offset(1f + offset, 6f), Offset(5f + offset, 10f), strokeWidth = 1.8f, cap = StrokeCap.Round)
            drawLine(color, Offset(5f + offset, 10f), Offset(12f + offset, 2f), strokeWidth = 1.8f, cap = StrokeCap.Round)
        }
        mark(if (double) 0f else 3.5f)
        if (double) mark(5f)
    }
}

@Composable
private fun EmptyChat(modifier: Modifier = Modifier) {
    Box(modifier.background(Ink), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Hiddi Desktop", fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Найдите пользователя слева, чтобы начать диалог", color = TextMuted)
        }
    }
}

private fun defaultDeviceName(): String {
    val host = System.getenv("HOSTNAME")?.takeIf(String::isNotBlank) ?: "Linux"
    return "Linux · ${host.take(48)}"
}

private const val DESKTOP_HISTORY_WINDOW_PER_PEER = 150

private fun List<ChatEntry>.boundedHistoryWindow(): List<ChatEntry> =
    groupBy(ChatEntry::peer)
        .values
        .flatMap { it.takeLast(DESKTOP_HISTORY_WINDOW_PER_PEER) }
        .sortedBy(ChatEntry::createdAt)

@Preview
@Composable
private fun HiddiPreview() {
    HiddiTheme {
        EmptyChat(Modifier.size(900.dp, 600.dp))
    }
}
