package ru.hiddi.desktop

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val Ink = Color(0xFF0B1016)
private val Panel = Color(0xFF121A23)
private val PanelRaised = Color(0xFF19242F)
private val Mint = Color(0xFF58E0B8)
private val TextMuted = Color(0xFF91A0AE)
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
                    PairingScreen(api) { unlocked ->
                        session = unlocked
                        screen = AppScreen.Messenger
                    }
                AppScreen.Unlock ->
                    UnlockScreen(api) { unlocked ->
                        session = unlocked
                        screen = AppScreen.Messenger
                    }
                AppScreen.Messenger ->
                    session?.let { MessengerScreen(it) }
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
            ),
        content = content,
    )
}

@Composable
private fun PairingScreen(api: HiddiApi, onReady: (HiddiSession) -> Unit) {
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
                "Код действует 10 минут и используется один раз.",
    ) {
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text("Сервер") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
    }
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
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<HiddiProfile>()) }
    var selected by remember { mutableStateOf<HiddiProfile?>(null) }
    val messages = remember { mutableStateListOf<ChatEntry>() }
    val scope = rememberCoroutineScope()

    DisposableEffect(session) {
        onDispose { session.close() }
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

    Row(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.width(340.dp).fillMaxHeight().background(Panel)) {
            AccountHeader(session, online)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Поиск по никнейму") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
            Text(
                if (query.length < 2) "Введите хотя бы 2 символа" else "ЛЮДИ",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(results, key = HiddiProfile::nickname) { profile ->
                    UserRow(profile, selected?.nickname == profile.nickname) {
                        selected = profile
                    }
                }
            }
        }
        Divider(color = Color.White.copy(alpha = 0.06f), modifier = Modifier.width(1.dp).fillMaxHeight())
        selected?.let { profile ->
            ChatPane(
                profile = profile,
                messages = messages.filter { it.peer == profile.nickname },
                onSend = { text, report ->
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { session.send(profile.nickname, text) }
                        }.onSuccess {
                            messages += ChatEntry(profile.nickname, text, outgoing = true)
                            report(null)
                        }.onFailure { report(it.message ?: "Не удалось отправить") }
                    }
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        } ?: EmptyChat(Modifier.weight(1f).fillMaxHeight())
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
private fun Avatar(profile: HiddiProfile) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF243B43)),
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
    Column(modifier.background(Ink)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Panel).padding(16.dp),
        ) {
            Avatar(profile)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    profile.displayName.ifBlank { "@${profile.nickname}" },
                    fontWeight = FontWeight.Bold,
                )
                Text("@${profile.nickname}", color = TextMuted, fontSize = 12.sp)
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(20.dp),
        ) {
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
                        color = if (message.outgoing) Color(0xFF174B42) else PanelRaised,
                        shape = RoundedCornerShape(18.dp),
                    ) {
                        Text(message.text, modifier = Modifier.padding(13.dp))
                    }
                }
            }
        }
        error?.let { ErrorText(it) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(Panel).padding(14.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Сообщение") },
                enabled = !sending,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = draft.isNotBlank() && !sending,
                onClick = {
                    val text = draft
                    sending = true
                    error = null
                    onSend(text) { failure ->
                        sending = false
                        error = failure
                        if (failure == null) draft = ""
                    }
                },
            ) {
                Text(if (sending) "…" else "Отправить")
            }
        }
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
