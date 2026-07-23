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
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.GroupAdd
import androidx.compose.material3.AlertDialog
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.network.UserSearchResult
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.InMemoryVoiceRecorder
import ru.hiddi.messenger.security.LocalGroupChat
import ru.hiddi.messenger.security.SignalStateRepository
import ru.hiddi.messenger.security.playVoicePcm
import ru.hiddi.messenger.security.sanitizeImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@androidx.compose.runtime.Composable
fun ConversationsScreen(
    profile: AccountProfile,
    peers: List<String>,
    historyRevision: Int,
    historyStore: EncryptedChatHistory,
    search: String,
    foundUsers: List<UserSearchResult>,
    searchError: String?,
    searching: Boolean,
    connection: ServerConnection,
    groups: List<LocalGroupChat>,
    groupBusy: Boolean,
    selfProfile: UserSearchResult?,
    selfAvatar: ByteArray?,
    knownProfiles: Map<String, UserSearchResult>,
    knownAvatars: Map<String, ByteArray>,
    onSearchChange: (String) -> Unit,
    onSearch: () -> Unit,
    onRefreshConnection: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onCreateGroup: (String) -> Unit,
    onOpenGroup: (ByteArray) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val avatarBitmap = remember(selfAvatar?.contentHashCode()) {
                selfAvatar?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (avatarBitmap != null) {
                Image(
                    bitmap = avatarBitmap.asImageBitmap(),
                    contentDescription = "Мой аватар",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
            } else {
                HiddiAvatar(selfProfile?.displayName?.ifBlank { "H" } ?: "H", 48)
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    selfProfile?.displayName?.ifBlank { "Hiddi" } ?: "Hiddi",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("@${profile.nickname}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Настройки", tint = MaterialTheme.colorScheme.onSurfaceVariant)
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

            if (searching) {
                Spacer(Modifier.height(10.dp))
                Text("Ищем пользователей…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            searchError?.let { error ->
                Spacer(Modifier.height(10.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            foundUsers.forEach { user ->
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = { onOpenProfile(user.nickname) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(
                            user.displayName.ifBlank { user.nickname },
                            knownAvatars[user.nickname],
                            44,
                        )
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                user.displayName.ifBlank { "@${user.nickname}" },
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                user.bio.ifBlank { "@${user.nickname} · начать защищённый диалог" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { onCreateGroup(user.nickname) },
                            enabled = !groupBusy,
                        ) {
                            Icon(
                                Icons.Rounded.GroupAdd,
                                contentDescription = "Создать MLS-группу",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "Открыть профиль",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Диалоги", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefreshConnection) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Проверить связь", tint = MaterialTheme.colorScheme.primary)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(9.dp).clip(CircleShape).background(
                            when (connection) {
                                ServerConnection.ONLINE -> Color(0xFF53DC88)
                                ServerConnection.OFFLINE -> MaterialTheme.colorScheme.error
                                ServerConnection.CHECKING -> MaterialTheme.colorScheme.secondary
                            },
                        ),
                    )
                    Spacer(Modifier.size(5.dp))
                    Text(
                        when (connection) {
                            ServerConnection.ONLINE -> "Сервер доступен"
                            ServerConnection.OFFLINE -> "Нет связи"
                            ServerConnection.CHECKING -> "Проверяем…"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (peers.isEmpty() && groups.isEmpty()) {
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
                    Text("Здесь появятся ваши диалоги и группы", fontWeight = FontWeight.Medium)
                    Text("Найдите человека по никнейму", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(groups, key = { "group:${it.groupId.contentHashCode()}:$historyRevision" }) { group ->
                    GroupConversationRow(
                        profileNickname = profile.nickname,
                        group = group,
                        onClick = { onOpenGroup(group.groupId) },
                    )
                }
                items(peers, key = { "$it:$historyRevision" }) { peer ->
                    val lastMessage = historyStore.messagesWith(peer).lastOrNull()
                    ConversationRow(
                        peer = peer,
                        profile = knownProfiles[peer],
                        avatar = knownAvatars[peer],
                        lastMessage = lastMessage,
                        unreadCount = historyStore.unreadCount(peer),
                        onClick = { onOpenConversation(peer) },
                        onOpenProfile = { onOpenProfile(peer) },
                    )
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GroupConversationRow(
    profileNickname: String,
    group: LocalGroupChat,
    onClick: () -> Unit,
) {
    val others = group.members.filterNot { it == profileNickname }
    val title = others.joinToString { "@$it" }.ifBlank { "Защищённая группа" }
    val lastMessage = group.messages.lastOrNull()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                Spacer(Modifier.height(4.dp))
                Text(
                    lastMessage?.let { (if (it.outgoing) "Вы: " else "@${it.sender}: ") + it.text }
                        ?: "MLS · сквозное шифрование",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Rounded.Lock, contentDescription = "MLS E2EE", tint = MaterialTheme.colorScheme.secondary)
        }
    }
}

@androidx.compose.runtime.Composable
private fun ConversationRow(
    peer: String,
    profile: UserSearchResult?,
    avatar: ByteArray?,
    lastMessage: ChatHistoryItem?,
    unreadCount: Int,
    onClick: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickable(onClick = onOpenProfile)) {
                ProfileAvatar(profile?.displayName?.ifBlank { peer } ?: peer, avatar, 54)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        profile?.displayName?.ifBlank { "@$peer" } ?: "@$peer",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
