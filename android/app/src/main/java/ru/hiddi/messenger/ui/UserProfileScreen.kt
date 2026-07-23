package ru.hiddi.messenger

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.network.UserSearchResult

@Composable
fun UserProfileScreen(
    account: AccountProfile,
    nickname: String,
    api: SignalMessagingApi,
    isContact: Boolean,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    onToggleContact: () -> Unit,
    onProfileLoaded: (UserSearchResult, ByteArray?) -> Unit,
) {
    var profile by remember(nickname) { mutableStateOf<UserSearchResult?>(null) }
    var avatar by remember(nickname) { mutableStateOf<ByteArray?>(null) }
    var error by remember(nickname) { mutableStateOf<String?>(null) }

    LaunchedEffect(account.accessToken, nickname) {
        try {
            val loaded = api.userProfile(account, nickname)
            profile = loaded
            avatar = loaded.avatarVersion?.let {
                runCatching { api.avatar(account, nickname) }.getOrNull()
            }
            onProfileLoaded(loaded, avatar)
        } catch (failure: Exception) {
            error = failure.message ?: "Не удалось загрузить профиль"
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
                "Профиль",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }

        when {
            error != null -> {
                Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
                    Text(error!!, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
            }
            profile == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                val loaded = requireNotNull(profile)
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(144.dp),
                    ) {
                        val bitmap = remember(avatar?.contentHashCode()) {
                            avatar?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Аватар @${loaded.nickname}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(CircleShape),
                            )
                        } else {
                            Box(contentAlignment = Alignment.Center) {
                                HiddiAvatar(
                                    loaded.displayName.ifBlank { loaded.nickname },
                                    132,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        loaded.displayName.ifBlank { "@${loaded.nickname}" },
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "@${loaded.nickname}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(22.dp))
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(18.dp)) {
                            Text("О себе", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(7.dp))
                            Text(
                                loaded.bio.ifBlank { "Пользователь пока ничего о себе не написал." },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (loaded.bio.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(7.dp))
                        Text(
                            "Личные сообщения защищаются Signal E2EE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onToggleContact,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) {
                        Text(if (isContact) "Удалить из контактов" else "Добавить в контакты")
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onMessage,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Spacer(Modifier.size(9.dp))
                        Text("Написать")
                    }
                }
            }
        }
    }
}
