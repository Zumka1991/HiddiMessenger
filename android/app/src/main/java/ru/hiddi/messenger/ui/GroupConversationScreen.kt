package ru.hiddi.messenger

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import ru.hiddi.messenger.security.GroupChatMessage
import ru.hiddi.messenger.security.LocalGroupChat
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore

@Composable
fun GroupConversationScreen(
    profileNickname: String,
    group: LocalGroupChat,
    draft: String,
    status: String,
    sending: Boolean,
    contacts: List<String>,
    attachmentStore: EncryptedAttachmentStore,
    voiceRecording: Boolean,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onInviteMember: (String) -> Unit,
    onChangeMemberRole: (String, String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteGroup: () -> Unit,
    onDeleteMessage: (GroupChatMessage, Boolean) -> Unit,
    onImageSelected: (Uri) -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onVoicePermissionDenied: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val title = group.name
    val ownRole = group.memberDetails.firstOrNull { it.nickname == profileNickname }?.role
    val isOwner = ownRole == "owner"
    val canManage = ownRole == "owner" || ownRole == "admin"
    var menuExpanded by remember { mutableStateOf(false) }
    var showInviteDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var selectedMemberForRemoval by remember { mutableStateOf<String?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedForActions by remember { mutableStateOf<GroupChatMessage?>(null) }
    var inviteNickname by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onImageSelected)
    }
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onStartVoice() else onVoicePermissionDenied()
    }
    fun startVoiceWithPermission() {
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            onStartVoice()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(group.messages.size) {
        if (group.messages.isNotEmpty()) {
            delay(60)
            listState.animateScrollToItem(group.messages.lastIndex)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Group, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "OpenMLS · ${group.members.size} участника",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (canManage) {
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Управление группой")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Пригласить участника") },
                            leadingIcon = { Icon(Icons.Rounded.PersonAdd, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showInviteDialog = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Участники и роли") },
                            leadingIcon = { Icon(Icons.Rounded.Group, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                showMembersDialog = true
                            },
                        )
                        if (isOwner) {
                            DropdownMenuItem(
                                text = { Text("Очистить локальную историю") },
                                leadingIcon = { Icon(Icons.Rounded.DeleteSweep, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showClearDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Удалить группу") },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    showDeleteDialog = true
                                },
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(group.messages) { message ->
                Column(Modifier.fillMaxWidth()) {
                    if (!message.outgoing) {
                        Text(
                            "@${message.sender}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
                        )
                    }
                    MessageBubble(
                        item = ChatHistoryItem(
                            peer = message.sender,
                            text = message.text,
                            outgoing = message.outgoing,
                            time = message.time,
                            attachment = message.attachment,
                            messageId = message.messageId,
                        ),
                        attachmentStore = attachmentStore,
                        onLongPress = {
                            if (message.outgoing && message.messageId != null) {
                                selectedForActions = message
                            }
                        },
                    )
                }
            }
        }

        Text(
            status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { imagePicker.launch("image/*") },
                enabled = !sending && !voiceRecording,
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = "Прикрепить изображение",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Сообщение в группу") },
                enabled = !sending && !voiceRecording,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(22.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(6.dp))
            if (draft.isNotBlank()) {
                IconButton(onClick = onSend, enabled = !sending && !voiceRecording) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Отправить",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconButton(
                    onClick = {
                        if (voiceRecording) onStopVoice() else startVoiceWithPermission()
                    },
                    enabled = !sending,
                ) {
                    Icon(
                        if (voiceRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = if (voiceRecording) "Остановить и отправить" else "Записать войс",
                        tint = if (voiceRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    if (showInviteDialog) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Пригласить в группу") },
            text = {
                Column {
                    val availableContacts = contacts.filterNot {
                        it == profileNickname || it in group.members
                    }
                    if (availableContacts.isNotEmpty()) {
                        Text("Из контактов", style = MaterialTheme.typography.labelLarge)
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(availableContacts, key = { it }) { nickname ->
                                Surface(
                                    onClick = {
                                        showInviteDialog = false
                                        inviteNickname = ""
                                        onInviteMember(nickname)
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "@$nickname",
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                    }
                    OutlinedTextField(
                        value = inviteNickname,
                        onValueChange = { inviteNickname = it },
                        label = { Text("Или введите никнейм") },
                        prefix = { Text("@") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val nickname = inviteNickname.trim().removePrefix("@").lowercase()
                        if (nickname.isNotEmpty()) {
                            showInviteDialog = false
                            inviteNickname = ""
                            onInviteMember(nickname)
                        }
                    },
                    enabled = inviteNickname.trim().removePrefix("@").length >= 3,
                ) { Text("Пригласить") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showInviteDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
    if (showMembersDialog) {
        AlertDialog(
            onDismissRequest = { showMembersDialog = false },
            title = { Text("Участники и роли") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    group.memberDetails.forEach { member ->
                        val mayRemove = member.nickname != profileNickname &&
                            (ownRole == "owner" && member.role != "owner" ||
                                ownRole == "admin" && member.role == "member")
                        Column {
                            Text(
                                "@${member.nickname} · ${
                                    when (member.role) {
                                        "owner" -> "владелец"
                                        "admin" -> "администратор"
                                        else -> "участник"
                                    }
                                }",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Row {
                                if (isOwner && member.nickname != profileNickname && member.role != "owner") {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            showMembersDialog = false
                                            onChangeMemberRole(
                                                member.nickname,
                                                if (member.role == "admin") "member" else "admin",
                                            )
                                        },
                                    ) {
                                        Text(
                                            if (member.role == "admin") {
                                                "Снять админа"
                                            } else {
                                                "Сделать админом"
                                            },
                                        )
                                    }
                                }
                                if (mayRemove) {
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            showMembersDialog = false
                                            selectedMemberForRemoval = member.nickname
                                        },
                                    ) {
                                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showMembersDialog = false }) {
                    Text("Готово")
                }
            },
        )
    }
    selectedMemberForRemoval?.let { nickname ->
        AlertDialog(
            onDismissRequest = { selectedMemberForRemoval = null },
            title = { Text("Удалить @$nickname?") },
            text = {
                Text(
                    "OpenMLS сменит эпоху. После применения Remove Commit участник " +
                        "не сможет читать новые сообщения группы.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        selectedMemberForRemoval = null
                        onRemoveMember(nickname)
                    },
                ) { Text("Удалить") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = { selectedMemberForRemoval = null },
                ) { Text("Отмена") }
            },
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Очистить историю?") },
            text = { Text("Пока история будет удалена только с этого устройства.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showClearDialog = false
                    onClearHistory()
                }) { Text("Очистить") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showClearDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить группу?") },
            text = {
                Text("Группа перестанет принимать новые сообщения. История других участников не исчезнет без подтверждённой команды удаления.")
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteGroup()
                }) { Text("Удалить") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            },
        )
    }
    selectedForActions?.let { message ->
        MessageActionDialog(
            preview = message.text,
            canDeleteForEveryone = message.outgoing,
            onDeleteLocal = {
                selectedForActions = null
                onDeleteMessage(message, false)
            },
            onDeleteEveryone = {
                selectedForActions = null
                onDeleteMessage(message, true)
            },
            onDismiss = { selectedForActions = null },
        )
    }
}
