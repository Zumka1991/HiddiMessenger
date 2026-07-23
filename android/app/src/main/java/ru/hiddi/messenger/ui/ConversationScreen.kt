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
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.InMemoryVoiceRecorder
import ru.hiddi.messenger.security.SignalStateRepository
import ru.hiddi.messenger.security.playVoicePcm
import ru.hiddi.messenger.security.sanitizeImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@androidx.compose.runtime.Composable
fun ConversationScreen(
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
    onClearHistory: () -> Unit,
    onVerifyKey: () -> Unit,
    onSend: () -> Unit,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var menuExpanded by remember { mutableStateOf(false) }
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
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "Меню диалога", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Проверить ключ") },
                        leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                        onClick = { menuExpanded = false; onVerifyKey() },
                    )
                    DropdownMenuItem(
                        text = { Text("Очистить историю") },
                        leadingIcon = { Icon(Icons.Rounded.Close, contentDescription = null) },
                        onClick = { menuExpanded = false; onClearHistory() },
                    )
                }
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
