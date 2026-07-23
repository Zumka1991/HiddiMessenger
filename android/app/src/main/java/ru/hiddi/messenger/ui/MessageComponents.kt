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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.input.pointer.pointerInput
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
fun MessageBubble(
    item: ChatHistoryItem,
    attachmentStore: EncryptedAttachmentStore,
    onLongPress: ((ChatHistoryItem) -> Unit)? = null,
) {
    val isImage = item.attachment?.kind == EncryptedAttachmentStore.IMAGE_KIND
    val longPressModifier = if (item.outgoing && item.messageId != null && onLongPress != null) {
        Modifier.pointerInput(item.messageId) {
            detectTapGestures(onLongPress = { onLongPress(item) })
        }
    } else {
        Modifier
    }
    Box(Modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.align(if (item.outgoing) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = if (isImage) 340.dp else 320.dp)
                .then(longPressModifier),
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (item.outgoing) 20.dp else 5.dp,
                bottomEnd = if (item.outgoing) 5.dp else 20.dp,
            ),
            color = if (item.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(Modifier.padding(if (isImage) 4.dp else 0.dp)) {
                when (item.attachment?.kind) {
                    EncryptedAttachmentStore.IMAGE_KIND -> Box {
                        AttachmentImage(item.attachment, attachmentStore)
                        Surface(
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                            color = Color.Black.copy(alpha = 0.58f),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            MessageMeta(item, Color.White, Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                    EncryptedAttachmentStore.VOICE_KIND -> Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        VoiceAttachment(item.attachment, attachmentStore)
                        MessageTime(item)
                    }
                    else -> Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                        Text(item.text, style = MaterialTheme.typography.bodyLarge)
                        MessageTime(item)
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun androidx.compose.foundation.layout.ColumnScope.MessageTime(item: ChatHistoryItem) {
    MessageMeta(item, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.align(Alignment.End).padding(top = 2.dp))
}

@androidx.compose.runtime.Composable
private fun MessageMeta(item: ChatHistoryItem, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(messageTime(item.time), style = MaterialTheme.typography.labelSmall, color = color)
        if (item.outgoing) {
            Spacer(Modifier.size(3.dp))
            val read = item.deliveryStatus == "read"
            Icon(
                imageVector = if (read) Icons.Rounded.DoneAll else Icons.Rounded.Check,
                contentDescription = when (item.deliveryStatus) {
                    "read" -> "Прочитано"
                    "delivered" -> "Доставлено на устройство"
                    else -> "Отправлено на сервер"
                },
                modifier = Modifier.size(15.dp),
                tint = if (read) MaterialTheme.colorScheme.primary else color,
            )
        }
    }
}

@androidx.compose.runtime.Composable
private fun AttachmentImage(
    descriptor: ru.hiddi.messenger.security.AttachmentDescriptor,
    attachmentStore: EncryptedAttachmentStore,
) {
    val previewDescriptor = descriptor.preview ?: descriptor
    val bitmap = rememberAttachmentBitmap(previewDescriptor, attachmentStore)
    var showFullImage by remember(descriptor.attachmentId) { mutableStateOf(false) }
    if (bitmap == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                Text("Загружаем фото…", style = MaterialTheme.typography.labelMedium)
            }
        }
    } else {
        Image(
            bitmap = bitmap,
            contentDescription = "Открыть зашифрованное изображение",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(17.dp))
                .clickable { showFullImage = true },
        )
    }

    if (showFullImage) {
        Dialog(
            onDismissRequest = { showFullImage = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val fullBitmap = rememberAttachmentBitmap(descriptor, attachmentStore)
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (fullBitmap == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text("Загружаем оригинал…", color = Color.White)
                    }
                } else {
                    Image(
                        bitmap = fullBitmap,
                        contentDescription = "Зашифрованное изображение",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                IconButton(
                    onClick = { showFullImage = false },
                    modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)
                        .size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun rememberAttachmentBitmap(
    descriptor: ru.hiddi.messenger.security.AttachmentDescriptor,
    attachmentStore: EncryptedAttachmentStore,
): androidx.compose.ui.graphics.ImageBitmap? {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, descriptor.attachmentId) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (!attachmentStore.exists(descriptor.attachmentId)) return@runCatching null
                val bytes = attachmentStore.decrypt(descriptor)
                try {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } finally {
                    bytes.fill(0)
                }
            }.getOrNull()
        }
    }
    return bitmap
}

@androidx.compose.runtime.Composable
private fun VoiceAttachment(
    descriptor: ru.hiddi.messenger.security.AttachmentDescriptor,
    attachmentStore: EncryptedAttachmentStore,
) {
    val scope = rememberCoroutineScope()
    var playing by remember(descriptor.attachmentId) { mutableStateOf(false) }
    val seconds = ((descriptor.durationMs ?: 0L) / 1_000).coerceAtLeast(0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(enabled = !playing && attachmentStore.exists(descriptor.attachmentId)) {
                    scope.launch {
                        playing = true
                        try {
                            val pcm = withContext(Dispatchers.IO) { attachmentStore.decrypt(descriptor) }
                            try {
                                playVoicePcm(pcm)
                            } finally {
                                pcm.fill(0)
                            }
                        } finally {
                            playing = false
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (playing) {
                Text("…", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp)
            } else {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Воспроизвести", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.size(12.dp))
        Column {
            Text("Голосовое", fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(5.dp))
                Icon(Icons.Rounded.Lock, contentDescription = "Зашифровано", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun HiddiAvatar(seed: String, size: Int) {
    val letter = seed.removePrefix("@").firstOrNull()?.uppercase() ?: "H"
    Box(
        modifier = Modifier.size(size.dp).clip(CircleShape).background(
            Brush.linearGradient(
                listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = Color(0xFF06151C), fontWeight = FontWeight.ExtraBold, fontSize = (size * 0.38f).sp)
    }
}

@androidx.compose.runtime.Composable
fun ProfileAvatar(seed: String, image: ByteArray?, size: Int) {
    val bitmap = remember(image?.contentHashCode()) {
        image?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Аватар",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size.dp).clip(CircleShape),
        )
    } else {
        HiddiAvatar(seed, size)
    }
}

fun messageTime(value: String): String = runCatching {
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrElse {
    Regex("""(\d{2}):(\d{2})(?::\d{2})?""").find(value)?.let { match ->
        "${match.groupValues[1]}:${match.groupValues[2]}"
    } ?: ""
}
