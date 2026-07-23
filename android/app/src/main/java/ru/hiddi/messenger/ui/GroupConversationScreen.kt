package ru.hiddi.messenger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.hiddi.messenger.security.GroupChatMessage
import ru.hiddi.messenger.security.LocalGroupChat

@Composable
fun GroupConversationScreen(
    profileNickname: String,
    group: LocalGroupChat,
    draft: String,
    status: String,
    sending: Boolean,
    onDraftChange: (String) -> Unit,
    onBack: () -> Unit,
    onInviteMember: (String) -> Unit,
    onChangeMemberRole: (String, String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDeleteGroup: () -> Unit,
    onDeleteMessage: (GroupChatMessage, Boolean) -> Unit,
    onSend: () -> Unit,
) {
    val listState = rememberLazyListState()
    val others = group.members.filterNot { it == profileNickname }
    val title = others.joinToString { "@$it" }.ifBlank { "Защищённая группа" }
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
    var selectedForDeletion by remember { mutableStateOf<GroupChatMessage?>(null) }
    var inviteNickname by remember { mutableStateOf("") }
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
                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
                val longPressModifier = if (message.outgoing && message.messageId != null) {
                    Modifier.pointerInput(message.messageId) {
                        detectTapGestures(onLongPress = { selectedForActions = message })
                    }
                } else {
                    Modifier
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        modifier = longPressModifier,
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (message.outgoing) 18.dp else 5.dp,
                            bottomEnd = if (message.outgoing) 5.dp else 18.dp,
                        ),
                        color = if (message.outgoing) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ) {
                        Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                            if (!message.outgoing) {
                                Text(
                                    "@${message.sender}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(message.text)
                        }
                    }
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
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Сообщение в группу") },
                enabled = !sending,
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
            IconButton(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Отправить",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showInviteDialog) {
        AlertDialog(
            onDismissRequest = { showInviteDialog = false },
            title = { Text("Пригласить в группу") },
            text = {
                OutlinedTextField(
                    value = inviteNickname,
                    onValueChange = { inviteNickname = it },
                    label = { Text("Никнейм") },
                    prefix = { Text("@") },
                    singleLine = true,
                )
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
        AlertDialog(
            onDismissRequest = { selectedForActions = null },
            title = { Text("Действия с сообщением") },
            text = {
                Column {
                    androidx.compose.material3.TextButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Редактировать — скоро", modifier = Modifier.fillMaxWidth())
                    }
                    androidx.compose.material3.TextButton(
                        onClick = {
                            selectedForActions = null
                            selectedForDeletion = message
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Удалить…",
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { selectedForActions = null }) {
                    Text("Отмена")
                }
            },
        )
    }
    selectedForDeletion?.let { message ->
        AlertDialog(
            onDismissRequest = { selectedForDeletion = null },
            title = { Text("Удалить сообщение?") },
            text = {
                Text(
                    "Удаление у всех будет отправлено участникам внутри OpenMLS. " +
                        "Уже сохранённые копии и скриншоты стереть невозможно.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        selectedForDeletion = null
                        onDeleteMessage(message, true)
                    },
                ) { Text("У всех") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        selectedForDeletion = null
                        onDeleteMessage(message, false)
                    },
                ) { Text("Только у меня") }
            },
        )
    }
}
