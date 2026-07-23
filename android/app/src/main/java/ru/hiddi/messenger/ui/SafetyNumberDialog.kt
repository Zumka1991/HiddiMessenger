package ru.hiddi.messenger

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.hiddi.messenger.security.safetyQrBitmap

@Composable
fun SafetyNumberDialog(
    recipient: String,
    safetyNumber: String?,
    trusted: Boolean,
    onConfirm: () -> Unit,
    onTakeQrPhoto: () -> Unit,
    onPickQrPhoto: () -> Unit,
    onDismiss: () -> Unit,
) {
    val codeReady = safetyNumber?.takeUnless { it == "Ошибка получения кода" }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = 720.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            Icons.Rounded.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp).size(28.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Проверка шифрования",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "@$recipient",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Text(
                    "Сверьте код лично или по голосу. Если он одинаковый на обоих устройствах, " +
                        "ключи этого диалога не были подменены.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (codeReady != null) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                        ) {
                            Image(
                                bitmap = remember(codeReady) {
                                    safetyQrBitmap(codeReady).asImageBitmap()
                                },
                                contentDescription = "QR-код проверки ключа",
                                modifier = Modifier.padding(10.dp).size(190.dp),
                            )
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        safetyNumber ?: "Получаем код…",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(14.dp),
                    )
                }

                Text(
                    if (trusted) {
                        "✓ Этот ключ уже подтверждён на устройстве."
                    } else {
                        "Если код неожиданно изменился, не отправляйте секретные данные до проверки."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (trusted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                Button(
                    onClick = onConfirm,
                    enabled = codeReady != null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (trusted) "Готово" else "Ключ совпадает")
                }
                OutlinedButton(
                    onClick = onTakeQrPhoto,
                    enabled = codeReady != null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Сканировать QR камерой")
                }
                TextButton(
                    onClick = onPickQrPhoto,
                    enabled = codeReady != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Выбрать QR из фото")
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Закрыть")
                }
            }
        }
    }
}
