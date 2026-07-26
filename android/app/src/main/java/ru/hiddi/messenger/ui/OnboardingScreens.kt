package ru.hiddi.messenger

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.AccountStore
import ru.hiddi.messenger.network.RegistrationApi
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import ru.hiddi.messenger.security.SignalCryptoBoundary
import ru.hiddi.messenger.security.readDeviceLinkQr

@Composable
fun WelcomeScreen(onRegister: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
                    ),
                ),
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(104.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Hiddi",
                fontSize = 42.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                "Закрытый мессенджер для своих",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Сообщения шифруются на ваших устройствах. Сервер хранит только шифротекст.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(30.dp))
            Button(
                onClick = onRegister,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Icon(Icons.Rounded.Key, contentDescription = null)
                Spacer(Modifier.size(10.dp))
                Text("Войти по приглашению", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Регистрация доступна только по одноразовому инвайту",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun RegistrationScreen(
    onBack: () -> Unit,
    onRegistered: (AccountProfile) -> Unit,
    onRecover: ((String, String) -> AccountProfile?)?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nickname by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var serverUrl by remember { mutableStateOf(BuildConfig.DEFAULT_SERVER_URL) }
    var message by remember { mutableStateOf<String?>(null) }
    var isRegistering by remember { mutableStateOf(false) }
    var recoveryKeyInput by remember { mutableStateOf("") }
    var generatedRecoveryKey by remember { mutableStateOf<String?>(null) }
    var pendingProfile by remember { mutableStateOf<AccountProfile?>(null) }

    fun linkDevice(payload: ru.hiddi.messenger.security.DeviceLinkPayload) {
        isRegistering = true
        scope.launch {
            try {
                val (device, linkedNickname) = RegistrationApi(SignalCryptoBoundary(AndroidKeystoreSecretStore(context)))
                    .linkDevice(payload.serverUrl, payload.code)
                val profile = AccountProfile(
                    payload.serverUrl,
                    linkedNickname,
                    device.accessToken,
                    device.deviceId,
                    device.deviceNumber,
                )
                AccountStore(context).save(profile)
                onRegistered(profile)
            } catch (error: Exception) {
                message = error.message ?: "Не удалось подключить аккаунт"
            } finally {
                isRegistering = false
            }
        }
    }

    val linkQrScanner = remember(context) {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build(),
        )
    }
    val linkQrImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val payload = uri?.let { readDeviceLinkQr(context.contentResolver, it) }
        if (payload == null) {
            message = "Не удалось прочитать QR-код привязки"
        } else {
            linkDevice(payload)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
            }
            Column {
                Text(
                    "Новое устройство",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Регистрация по приглашению",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Ключи будут созданы на этом телефоне",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Придумайте постоянный никнейм и вставьте одноразовый код администратора.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedButton(
                onClick = {
                    message = "Наведите камеру на QR-код Desktop"
                    linkQrScanner.startScan()
                        .addOnSuccessListener { barcode ->
                            val payload = barcode.rawValue?.let(::readDeviceLinkQr)
                            if (payload == null) {
                                message = "Это не QR-код привязки Hiddi"
                            } else {
                                linkDevice(payload)
                            }
                        }
                        .addOnCanceledListener {
                            message = null
                        }
                        .addOnFailureListener { error ->
                            message = error.message ?: "Не удалось открыть QR-сканер"
                        }
                },
                enabled = !isRegistering && pendingProfile == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Rounded.Security, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Подключить аккаунт с Desktop по QR")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { linkQrImage.launch("image/*") },
                enabled = !isRegistering && pendingProfile == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Выбрать QR из изображения") }
            Spacer(Modifier.height(18.dp))
            if (BuildConfig.DEBUG) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Сервер разработки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = nickname,
                onValueChange = {
                    nickname = it.filterNot(Char::isWhitespace).removePrefix("@").take(32)
                },
                label = { Text("Никнейм") },
                prefix = { Text("@") },
                supportingText = { Text("От 3 до 32 символов, изменить потом нельзя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.trim().take(256) },
                label = { Text("Инвайт-код") },
                supportingText = { Text("Код можно использовать только один раз") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            message?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    isRegistering = true
                    message = null
                    scope.launch {
                        try {
                            val crypto = SignalCryptoBoundary(AndroidKeystoreSecretStore(context))
                            val device = RegistrationApi(crypto).register(serverUrl, nickname, inviteCode)
                            val normalizedNickname = nickname.removePrefix("@").lowercase()
                            val profile = AccountProfile(
                                serverUrl.trimEnd('/'),
                                normalizedNickname,
                                device.accessToken,
                                device.deviceId,
                                device.deviceNumber,
                            )
                            generatedRecoveryKey = device.recoveryKey
                            pendingProfile = profile
                            message = "Сохраните ключ восстановления. Он показывается только сейчас."
                        } catch (error: Exception) {
                            message = error.message ?: "Не удалось зарегистрировать устройство"
                        } finally {
                            isRegistering = false
                        }
                    }
                },
                enabled = !isRegistering &&
                    pendingProfile == null &&
                    nickname.length in 3..32 &&
                    inviteCode.isNotBlank() &&
                    (serverUrl.startsWith("https://") ||
                        (BuildConfig.DEBUG && serverUrl.startsWith("http://"))),
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                if (isRegistering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                    Text("Создаём ключи…")
                } else {
                    Text("Создать защищённое устройство")
                }
            }
            generatedRecoveryKey?.let { key ->
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Ключ восстановления") },
                    supportingText = {
                        Text("Без этого ключа восстановить профиль на новом устройстве невозможно")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        copySensitiveText(context, "Ключ восстановления Hiddi", key)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Скопировать ключ")
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        pendingProfile?.let { profile ->
                            AccountStore(context).save(profile)
                            onRegistered(profile)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Я сохранил ключ")
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "Восстановление существующего профиля",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = recoveryKeyInput,
                onValueChange = { recoveryKeyInput = it.trim().take(256) },
                label = { Text("Ключ hiddi1…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    isRegistering = true
                    message = null
                    scope.launch {
                        try {
                            val crypto = SignalCryptoBoundary(AndroidKeystoreSecretStore(context))
                            val device = RegistrationApi(crypto)
                                .recover(serverUrl, nickname, recoveryKeyInput)
                            val profile = AccountProfile(
                                serverUrl.trimEnd('/'),
                                nickname.removePrefix("@").lowercase(),
                                device.accessToken,
                                device.deviceId,
                                device.deviceNumber,
                            )
                            AccountStore(context).save(profile)
                            onRegistered(profile)
                        } catch (error: Exception) {
                            message = error.message ?: "Не удалось восстановить профиль"
                        } finally {
                            isRegistering = false
                        }
                    }
                },
                enabled = !isRegistering &&
                    nickname.length in 3..32 &&
                    recoveryKeyInput.startsWith("hiddi1.") &&
                    (serverUrl.startsWith("https://") ||
                        (BuildConfig.DEBUG && serverUrl.startsWith("http://"))),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text("Восстановить профиль")
            }
            onRecover?.let { recover ->
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        val recovered = recover(serverUrl, nickname)
                        message = if (recovered == null) {
                            "Не удалось восстановить прежнее устройство"
                        } else {
                            "Устройство восстановлено"
                        }
                    },
                    enabled = nickname.length in 3..32,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text("Восстановить прежнюю сессию")
                }
            }
        }
    }
}
