package ru.hiddi.messenger

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.RegistrationApi
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.network.UserSearchResult
import ru.hiddi.messenger.security.sanitizeImage
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import ru.hiddi.messenger.security.PrivacySettingsStore
import ru.hiddi.messenger.security.SignalCryptoBoundary
import ru.hiddi.messenger.security.deviceLinkQrBitmap

@Composable
fun SettingsScreen(
    account: AccountProfile,
    api: SignalMessagingApi,
    onBack: () -> Unit,
    onProfileChanged: (UserSearchResult, ByteArray?) -> Unit,
    onLogout: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val privacySettings = remember { PrivacySettingsStore(context) }
    var invisibleMode by remember { mutableStateOf(privacySettings.invisibleMode()) }
    var profile by remember { mutableStateOf<UserSearchResult?>(null) }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkCode by remember { mutableStateOf<String?>(null) }
    var creatingLinkCode by remember { mutableStateOf(false) }
    var recoveryKey by remember { mutableStateOf<String?>(null) }
    var creatingRecoveryKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        loading = true
        try {
            val loaded = api.currentProfile(account)
            val image = loaded.avatarVersion?.let {
                runCatching { api.avatar(account, loaded.nickname) }.getOrNull()
            }
            profile = loaded
            displayName = loaded.displayName
            bio = loaded.bio
            avatar = image
            onProfileChanged(loaded, image)
        } catch (error: Exception) {
            status = error.message ?: "Не удалось загрузить профиль"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(account.accessToken) {
        reload()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (saving) return@rememberLauncherForActivityResult
        saving = true
        status = "Удаляем метаданные и подготавливаем аватар…"
        scope.launch {
            var preview: ByteArray? = null
            try {
                preview = withContext(Dispatchers.IO) {
                    sanitizeImage(context, uri).let { sanitized ->
                        sanitized.full.fill(0)
                        sanitized.preview
                    }
                }
                api.uploadAvatar(account, preview)
                avatar = preview.copyOf()
                val updated = api.currentProfile(account)
                profile = updated
                onProfileChanged(updated, avatar)
                status = "Аватар обновлён"
            } catch (error: Exception) {
                status = error.message ?: "Не удалось обновить аватар"
            } finally {
                preview?.fill(0)
                saving = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column {
                Text(
                    "Настройки",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Профиль и безопасность",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (loading && profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .size(86.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !saving) { imagePicker.launch("image/*") },
                    ) {
                        val bitmap = remember(avatar?.contentHashCode()) {
                            avatar?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Аватар",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                HiddiAvatar(
                                    profile?.displayName?.ifBlank { account.nickname }
                                        ?: account.nickname,
                                    78,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            displayName.ifBlank { "@${account.nickname}" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "@${account.nickname}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(
                            onClick = { imagePicker.launch("image/*") },
                            enabled = !saving,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(Modifier.size(5.dp))
                            Text(if (avatar == null) "Добавить фото" else "Сменить фото")
                        }
                        if (avatar != null) {
                            TextButton(
                                onClick = {
                                    if (saving) return@TextButton
                                    saving = true
                                    scope.launch {
                                        try {
                                            api.deleteAvatar(account)
                                            avatar = null
                                            val updated = api.currentProfile(account)
                                            profile = updated
                                            onProfileChanged(updated, null)
                                            status = "Аватар удалён"
                                        } catch (error: Exception) {
                                            status = error.message ?: "Не удалось удалить аватар"
                                        } finally {
                                            saving = false
                                        }
                                    }
                                },
                                enabled = !saving,
                            ) {
                                Text(
                                    "Удалить",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "ПРОФИЛЬ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            )
            OutlinedTextField(
                value = displayName,
                onValueChange = { candidate ->
                    if (candidate.length <= 64 && candidate.none { it.isISOControl() }) {
                        displayName = candidate
                    }
                },
                label = { Text("Видимое имя") },
                supportingText = { Text("${displayName.length}/64") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = "@${account.nickname}",
                onValueChange = {},
                readOnly = true,
                label = { Text("Никнейм") },
                supportingText = { Text("Постоянный адрес для поиска и сообщений") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = bio,
                onValueChange = { candidate ->
                    if (candidate.length <= 250) bio = candidate
                },
                label = { Text("О себе") },
                supportingText = { Text("${bio.length}/250") },
                minLines = 4,
                maxLines = 7,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        "Имя, описание и аватар видны серверу и другим зарегистрированным " +
                            "пользователям. Сообщения и ключи по-прежнему защищены E2EE.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Режим невидимки", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Не показывать статус «в сети» и набор текста",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = invisibleMode,
                        onCheckedChange = { enabled ->
                            invisibleMode = enabled
                            ContextCompat.startForegroundService(
                                context,
                                Intent(context, MessagingService::class.java)
                                    .setAction(MessagingService.ACTION_SET_INVISIBLE)
                                    .putExtra(MessagingService.EXTRA_INVISIBLE, enabled),
                            )
                        },
                    )
                }
            }
            status?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (saving) return@Button
                    saving = true
                    status = "Сохраняем профиль…"
                    scope.launch {
                        try {
                            val updated = api.updateProfile(account, displayName, bio)
                            profile = updated
                            displayName = updated.displayName
                            bio = updated.bio
                            onProfileChanged(updated, avatar)
                            status = "Профиль сохранён"
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось сохранить профиль"
                        } finally {
                            saving = false
                        }
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Сохранить")
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "ВОССТАНОВЛЕНИЕ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    if (creatingRecoveryKey) return@OutlinedButton
                    creatingRecoveryKey = true
                    scope.launch {
                        try {
                            recoveryKey = RegistrationApi(
                                SignalCryptoBoundary(AndroidKeystoreSecretStore(context)),
                            ).createRecoveryKey(account)
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось создать ключ восстановления"
                        } finally {
                            creatingRecoveryKey = false
                        }
                    }
                },
                enabled = !saving && !loggingOut && !creatingRecoveryKey,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null)
                Spacer(Modifier.size(9.dp))
                Text(
                    if (creatingRecoveryKey) "Создаём ключ…"
                    else "Создать новый ключ восстановления",
                )
            }
            Text(
                "Новый ключ заменяет предыдущий. Hiddi покажет его только один раз.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "УСТРОЙСТВА",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 8.dp),
            )
            OutlinedButton(
                onClick = {
                    if (creatingLinkCode) return@OutlinedButton
                    creatingLinkCode = true
                    scope.launch {
                        try {
                            linkCode = api.createDeviceLinkCode(account).code
                            showLinkDialog = true
                        } catch (error: Exception) {
                            status = error.message ?: "Не удалось создать код привязки"
                        } finally {
                            creatingLinkCode = false
                        }
                    }
                },
                enabled = !saving && !loggingOut && !creatingLinkCode,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Computer, contentDescription = null)
                Spacer(Modifier.size(9.dp))
                Text(if (creatingLinkCode) "Создаём код…" else "Привязать компьютер")
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                enabled = !saving && !loggingOut,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.size(9.dp))
                Text(
                    "Выйти с этого устройства",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLinkDialog && linkCode != null) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Привязать Linux-клиент") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Сохраните этот QR-код как скриншот и выберите его в Hiddi Desktop. " +
                            "Код действует 10 минут и используется один раз. " +
                            "Компьютер создаст собственные Signal-ключи; приватные ключи телефона " +
                            "никуда не передаются. Не пересылайте QR-код другим людям.",
                    )
                    Image(
                        bitmap = deviceLinkQrBitmap(account.serverUrl, linkCode!!).asImageBitmap(),
                        contentDescription = "QR-код привязки компьютера",
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(220.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                linkCode!!,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        copySensitiveText(context, "Код привязки Hiddi", linkCode!!)
                        Toast.makeText(context, "Код скопирован", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Копировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) {
                    Text("Закрыть")
                }
            },
        )
    }

    recoveryKey?.let { key ->
        AlertDialog(
            onDismissRequest = { recoveryKey = null },
            title = { Text("Ключ восстановления") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Сохраните ключ вне телефона. Любой, кто получит его, сможет добавить " +
                            "новое устройство к вашему аккаунту.",
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        SelectionContainer {
                            Text(
                                key,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(14.dp),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        copySensitiveText(context, "Ключ восстановления Hiddi", key)
                        Toast.makeText(context, "Ключ скопирован", Toast.LENGTH_SHORT).show()
                    },
                ) {
                    Text("Копировать")
                }
            },
            dismissButton = {
                TextButton(onClick = { recoveryKey = null }) {
                    Text("Я сохранил")
                }
            },
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!loggingOut) showLogoutDialog = false
            },
            title = { Text("Удалить сессию устройства?") },
            text = {
                Text(
                    "Сервер отзовёт токен этого устройства. Затем Hiddi безвозвратно " +
                        "удалит с телефона локальные ключи, сообщения, контакты и вложения. " +
                        "Для повторного входа понадобится новый инвайт.",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !loggingOut,
                    onClick = {
                        loggingOut = true
                        status = "Отзываем сессию устройства…"
                        scope.launch {
                            try {
                                api.deleteCurrentDevice(account)
                                onLogout()
                            } catch (error: Exception) {
                                status = error.message ?: "Не удалось удалить сессию устройства"
                                loggingOut = false
                                showLogoutDialog = false
                            }
                        }
                    },
                ) {
                    Text(
                        if (loggingOut) "Удаляем…" else "Выйти и удалить",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !loggingOut,
                    onClick = { showLogoutDialog = false },
                ) {
                    Text("Отмена")
                }
            },
        )
    }
}
