package ru.hiddi.messenger

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import ru.hiddi.messenger.network.AccountProfile
import ru.hiddi.messenger.network.AccountStore
import ru.hiddi.messenger.network.GroupMlsCoordinator
import ru.hiddi.messenger.network.RegistrationApi
import ru.hiddi.messenger.network.SignalMessagingApi
import ru.hiddi.messenger.security.AndroidKeystoreSecretStore
import ru.hiddi.messenger.security.SignalCryptoBoundary
import ru.hiddi.messenger.security.ChatHistoryItem
import ru.hiddi.messenger.security.EncryptedAttachmentStore
import ru.hiddi.messenger.security.EncryptedChatHistory
import ru.hiddi.messenger.security.InMemoryVoiceRecorder
import ru.hiddi.messenger.security.NativeMlsBridge
import ru.hiddi.messenger.security.LocalSessionWiper
import ru.hiddi.messenger.security.SignalStateRepository
import ru.hiddi.messenger.security.playVoicePcm
import ru.hiddi.messenger.security.sanitizeImage

class MainActivity : ComponentActivity() {
    private var requestedPeer by mutableStateOf<String?>(null)
    private var resumeRevision by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeMlsBridge.preparePersistentStorage(applicationContext)
        requestedPeer = intent.getStringExtra(MessagingService.EXTRA_OPEN_PEER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST)
        }
        setContent { HiddiApp(requestedPeer, resumeRevision, onPeerOpened = { requestedPeer = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPeer = intent.getStringExtra(MessagingService.EXTRA_OPEN_PEER)
    }

    override fun onResume() {
        super.onResume()
        isVisible = true
        resumeRevision++
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    companion object {
        @Volatile
        internal var isVisible = false
        private const val NOTIFICATION_PERMISSION_REQUEST = 100
    }
}

@androidx.compose.runtime.Composable
private fun HiddiApp(requestedPeer: String?, resumeRevision: Int, onPeerOpened: () -> Unit) {
    var showRegistration by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val accountStore = remember { AccountStore(context) }
    var account by remember { mutableStateOf(accountStore.read()) }
    val hasLegacyToken = remember { accountStore.hasLegacyToken() }
    LaunchedEffect(account?.nickname) {
        account?.let { current ->
            ContextCompat.startForegroundService(context, Intent(context, MessagingService::class.java))
            runCatching {
                withContext(Dispatchers.IO) {
                    val api = SignalMessagingApi(SignalStateRepository(context))
                    GroupMlsCoordinator(context, api).prepare(current)
                }
            }.getOrNull()?.let { prepared ->
                if (prepared != account) account = prepared
            }
        }
    }
    MaterialTheme(colorScheme = hiddiColors, typography = hiddiTypography) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            if (account != null) {
                ChatScreen(
                    account!!,
                    requestedPeer,
                    resumeRevision,
                    onPeerOpened,
                    onLogout = {
                        context.stopService(Intent(context, MessagingService::class.java))
                        LocalSessionWiper(context).wipe()
                        (context as? android.app.Activity)?.finishAndRemoveTask()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    },
                )
            } else if (showRegistration) {
                RegistrationScreen(
                    onBack = { showRegistration = false },
                    onRegistered = { account = it },
                    onRecover = if (hasLegacyToken) { server, nickname -> accountStore.recoverLegacy(server, nickname)?.also { account = it } } else null,
                )
            } else {
                WelcomeScreen(onRegister = { showRegistration = true })
            }
        }
    }
}
