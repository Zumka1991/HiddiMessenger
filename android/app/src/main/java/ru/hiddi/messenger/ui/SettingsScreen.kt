package ru.hiddi.messenger

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.network.UserSearchResult
import ru.hiddi.messenger.security.sanitizeImage

@Composable
fun SettingsScreen(
    account: AccountProfile,
    api: SignalMessagingApi,
    onBack: () -> Unit,
    onProfileChanged: (UserSearchResult, ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<UserSearchResult?>(null) }
    var displayName by remember { mutableStateOf("") }
    var bio by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf<ByteArray?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
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
            Text(
                "Настройки",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .size(118.dp)
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
                        HiddiAvatar(profile?.displayName?.ifBlank { account.nickname } ?: account.nickname, 108)
                    }
                }
            }
            TextButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !saving,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text(if (avatar == null) "Добавить аватар" else "Сменить аватар")
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
                    Text("Удалить аватар", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(12.dp))
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
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = "@${account.nickname}",
                onValueChange = {},
                readOnly = true,
                label = { Text("Никнейм") },
                supportingText = { Text("Постоянный адрес для поиска и сообщений") },
                modifier = Modifier.fillMaxWidth(),
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
            )
            Spacer(Modifier.height(18.dp))
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
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
            Spacer(Modifier.height(24.dp))
        }
    }
}
