package ru.hiddi.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Cursor
import java.io.File
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
                        MessengerScreen(it)
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
private fun MessengerScreen(session: HiddiSession) {
    var online by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf(DesktopSection.Chats) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<HiddiProfile>()) }
    var selected by remember { mutableStateOf<HiddiProfile?>(null) }
    var listWidth by remember { mutableStateOf(320.dp) }
    val messages = remember(session) { mutableStateListOf<ChatEntry>().also { it += session.history() } }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val realtime = remember(session) { DesktopRealtime(session.server, session.accessToken) }

    suspend fun synchronizeInbox() {
        val incoming = runCatching { withContext(Dispatchers.IO) { session.syncInbox() } }.getOrDefault(emptyList())
        incoming.forEach { entry -> if (messages.none { it.messageId == entry.messageId }) messages += entry }
    }

    DisposableEffect(session) {
        realtime.connect()
        onDispose {
            realtime.close()
            session.close()
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
    LaunchedEffect(realtime) {
        for (event in realtime.events) {
            when (event) {
                DesktopRealtime.Event.SyncRequired -> synchronizeInbox()
                DesktopRealtime.Event.Disconnected -> {
                    delay(500)
                    realtime.connect()
                }
            }
        }
    }
    LaunchedEffect(session) {
        while (true) {
            synchronizeInbox()
            delay(15_000)
        }
    }
    LaunchedEffect(session) {
        while (true) {
            messages.toList().forEach { entry ->
                entry.messageId?.takeIf { entry.outgoing }?.let { messageId ->
                    val status = runCatching { withContext(Dispatchers.IO) { session.updateDeliveryStatus(messageId) } }.getOrNull()
                    val currentIndex = messages.indexOfFirst { it.messageId == messageId }
                    val current = messages.getOrNull(currentIndex)
                    if (status != null && current != null && status != current.deliveryStatus) {
                        messages[currentIndex] = current.copy(deliveryStatus = status)
                    }
                }
            }
            delay(1_500)
        }
    }

    Surface(color = Ink, contentColor = Color(0xFFEAF3F7), modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            DesktopNavigation(section, online, session.nickname) { section = it }
            Column(Modifier.width(listWidth).fillMaxHeight().background(Panel)) {
                when (section) {
                    DesktopSection.Chats -> ChatListPane(messages, selected) { profile -> selected = profile }
                    DesktopSection.Contacts -> ContactsPane(query, { query = it }, results, selected) { profile ->
                        selected = profile
                        section = DesktopSection.Chats
                    }
                    DesktopSection.Settings -> DesktopSettingsPane(session, online)
                }
            }
            ResizeHandle { delta -> listWidth = (listWidth + with(density) { delta.toDp() }).clamp(280.dp, 480.dp) }
            if (section == DesktopSection.Settings) {
                DesktopSettingsDetail(session, Modifier.weight(1f).fillMaxHeight())
            } else {
                selected?.let { profile ->
                    ChatPane(
                        profile = profile,
                        messages = messages.filter { it.peer == profile.nickname },
                        onSend = { text, report ->
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { session.send(profile.nickname, text) }
                                }.onSuccess {
                                    messages += it
                                    report(null)
                                }.onFailure { report(it.message ?: "Не удалось отправить") }
                            }
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
private fun ChatListPane(
    messages: List<ChatEntry>,
    selected: HiddiProfile?,
    onSelect: (HiddiProfile) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val conversations = messages.groupBy { it.peer }
        .map { (peer, entries) -> peer to entries.maxBy { it.createdAt } }
        .filter { (peer, last) -> query.isBlank() || peer.contains(query.trim().removePrefix("@"), ignoreCase = true) || last.text.contains(query, ignoreCase = true) }
        .sortedByDescending { it.second.createdAt }
    Text("Чаты", fontSize = 27.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 24.dp, end = 20.dp, top = 24.dp, bottom = 14.dp))
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("Поиск") },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMuted) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = PanelRaised,
            unfocusedContainerColor = PanelRaised,
            focusedBorderColor = Mint.copy(alpha = 0.45f),
            unfocusedBorderColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
    if (conversations.isEmpty()) {
        Text("Диалогов пока нет. Найдите близкого человека во вкладке «Контакты».", color = TextMuted, modifier = Modifier.padding(20.dp))
    } else {
        LazyColumn(Modifier.fillMaxSize().padding(top = 14.dp, start = 12.dp, end = 12.dp)) {
            items(conversations, key = { it.first }) { (peer, last) ->
                val profile = HiddiProfile(peer, "", "")
                ConversationRow(profile, last, selected?.nickname == peer) { onSelect(profile) }
            }
        }
    }
}

@Composable
private fun ConversationRow(profile: HiddiProfile, last: ChatEntry, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Color(0xFF1A3333) else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(14.dp)) {
            Avatar(profile, 48.dp)
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
    selected: HiddiProfile?,
    onOpen: (HiddiProfile) -> Unit,
) {
    Text("Контакты", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp))
    OutlinedTextField(query, onQueryChange, placeholder = { Text("Поиск по @nickname") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
    Text(if (query.length < 2) "Введите хотя бы 2 символа" else "НАЙДЕННЫЕ ЛЮДИ", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(18.dp, 16.dp, 18.dp, 8.dp))
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = HiddiProfile::nickname) { profile -> UserRow(profile, selected?.nickname == profile.nickname) { onOpen(profile) } }
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
private fun DesktopSettingsDetail(session: HiddiSession, modifier: Modifier = Modifier) {
    Column(modifier.background(Ink).padding(42.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Профиль и безопасность", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("@${session.nickname}", color = Mint, fontSize = 18.sp)
        Surface(color = PanelRaised, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Это устройство", fontWeight = FontWeight.Bold)
                Text("Linux · устройство ${session.deviceNumber}", color = TextMuted)
                Text("Signal-ключи защищены локальным паролем и не покидают компьютер.", color = TextMuted, fontSize = 13.sp)
            }
        }
    }
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
private fun UserRow(profile: HiddiProfile, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .background(if (selected) PanelRaised else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Avatar(profile)
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
private fun Avatar(profile: HiddiProfile, size: Dp = 42.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color(0xFF243B43)),
        contentAlignment = Alignment.Center,
    ) {
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

@Composable
private fun ChatPane(
    profile: HiddiProfile,
    messages: List<ChatEntry>,
    onSend: (String, (String?) -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(profile.nickname) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (draft.isBlank() || sending) return
        val text = draft
        sending = true
        error = null
        onSend(text) { failure ->
            sending = false
            error = failure
            if (failure == null) draft = ""
        }
    }
    Column(modifier.background(Color(0xFF090F15))) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Color(0xFF111923)).padding(horizontal = 28.dp, vertical = 17.dp),
        ) {
            Avatar(profile, 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    profile.displayName.ifBlank { "@${profile.nickname}" },
                    fontWeight = FontWeight.Bold,
                )
                Text("В сети", color = Mint, fontSize = 12.sp)
            }
            IconButton(onClick = {}) { Icon(Icons.Rounded.Search, contentDescription = "Поиск", tint = Mint) }
            IconButton(onClick = {}) { Icon(Icons.Rounded.MoreVert, contentDescription = "Меню", tint = TextMuted) }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom),
            contentPadding = PaddingValues(horizontal = 34.dp, vertical = 24.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    Surface(
                        color = if (message.outgoing) Color(0xFF1D5B50) else PanelRaised,
                        shape = RoundedCornerShape(if (message.outgoing) 20.dp else 18.dp),
                        modifier = Modifier.widthIn(max = 520.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                            horizontalAlignment = if (message.outgoing) Alignment.End else Alignment.Start,
                        ) {
                            Text(message.text)
                            MessageMeta(message)
                        }
                    }
                }
            }
        }
        error?.let { ErrorText(it) }
        Surface(color = Color(0xFF101822), modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 20.dp),
            ) {
                Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Сообщение") },
                enabled = !sending,
                minLines = 1,
                maxLines = 5,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFF18212B),
                    unfocusedContainerColor = Color(0xFF18212B),
                    focusedBorderColor = Mint.copy(alpha = 0.75f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                ),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.key != Key.Enter) return@onPreviewKeyEvent false
                    if (event.isCtrlPressed) {
                        draft += "\n"
                    } else {
                        submit()
                    }
                    true
                },
                        )
                        Text("Enter — отправить · Ctrl+Enter — новая строка", color = TextMuted, fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp, top = 5.dp))
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    enabled = draft.isNotBlank() && !sending,
                    onClick = ::submit,
                    shape = CircleShape,
                    modifier = Modifier.size(46.dp),
                ) {
                    if (sending) Text("…") else Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Отправить")
                }
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

@Preview
@Composable
private fun HiddiPreview() {
    HiddiTheme {
        EmptyChat(Modifier.size(900.dp, 600.dp))
    }
}
