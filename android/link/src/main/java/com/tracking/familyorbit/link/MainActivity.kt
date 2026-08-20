package com.tracking.familyorbit.link

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.tracking.familyorbit.core.FamilyOrbitApi
import com.tracking.familyorbit.core.FamilyOrbitTheme
import com.tracking.familyorbit.core.LocalNetworkAccess
import com.tracking.familyorbit.core.OrbitDanger
import com.tracking.familyorbit.core.OrbitLime
import com.tracking.familyorbit.core.OrbitMark
import com.tracking.familyorbit.core.OrbitMint
import com.tracking.familyorbit.core.OrbitMuted
import com.tracking.familyorbit.core.OrbitMessage
import com.tracking.familyorbit.core.OrbitNavy
import com.tracking.familyorbit.core.OrbitSurface
import com.tracking.familyorbit.core.OrbitText
import com.tracking.familyorbit.core.OrbitUnauthorizedException
import com.tracking.familyorbit.core.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF071A27.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF071A27.toInt()),
        )
        val incomingCode = intent?.data?.getQueryParameter("code")
        val incomingMessageId = intent?.getStringExtra("messageId") ?: intent?.getStringExtra("itemId")
        if (intent?.getStringExtra("type") == "family_removed") FamilyRemovalState.mark(this)
        setContent {
            FamilyOrbitTheme {
                LinkApp(this, incomingCode, incomingMessageId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

@Composable
private fun LinkApp(context: Context, incomingCode: String?, incomingMessageId: String?) {
    val tokenStore = remember { SecureTokenStore(context, "child_device_token") }
    val state = remember { LinkState(context) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var paired by remember { mutableStateOf(tokenStore.get() != null) }
    var removedNotice by remember { mutableStateOf(state.removedFromFamily) }

    LaunchedEffect(context) {
        if (
            LocalNetworkAccess.isRequiredFor(BuildConfig.API_BASE_URL) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    LaunchedEffect(paired) {
        val deviceToken = tokenStore.get() ?: return@LaunchedEffect
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { pushToken ->
                Thread { runCatching { FamilyOrbitApi(BuildConfig.API_BASE_URL, context).registerDevicePush(deviceToken, pushToken) } }.start()
            }
        }
    }

    LaunchedEffect(paired) {
        while (paired) {
            delay(1_000)
            if (tokenStore.get() == null) {
                removedNotice = state.removedFromFamily
                paired = false
            }
        }
    }

    if (!paired) {
        PairingScreen(context, incomingCode, removedNotice) { token, childId, pauseRestricted ->
            tokenStore.put(token)
            state.childId = childId
            state.pauseRestricted = pauseRestricted
            state.trackingActive = pauseRestricted && hasFineLocation(context)
            state.removedFromFamily = false
            removedNotice = false
            if (state.trackingActive) {
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
            }
            paired = true
        }
    } else {
        TrackingStatusScreen(
            context = context,
            tokenStore = tokenStore,
            state = state,
            incomingMessageId = incomingMessageId,
            onRemoved = {
                FamilyRemovalState.mark(context)
                removedNotice = true
                paired = false
            },
            onUnpair = {
                tokenStore.clear()
                EncryptedLocationQueue(context).clear()
                state.trackingActive = false
                state.childId = null
                state.pauseRestricted = false
                state.removedFromFamily = false
                removedNotice = false
                paired = false
            },
        )
    }
}

@Composable
private fun PairingScreen(context: Context, incomingCode: String?, removedNotice: Boolean, onPaired: (String, String, Boolean) -> Unit) {
    var code by remember { mutableStateOf(incomingCode.orEmpty().filter(Char::isDigit).take(6)) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var permissionStep by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val foregroundPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionStep = if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) 1 else 0
    }
    val backgroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionStep = if (granted) 2 else 1
    }
    val notificationsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = OrbitNavy,
        contentColor = OrbitText,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OrbitMark(Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        "Family Orbit Link",
                        color = OrbitText,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        "位置共有用アプリ",
                        color = OrbitLime,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                "位置共有の状態は、いつでもこの画面で確認できます。接続コードによっては、アプリ内の停止操作が保護者設定で制限されます。",
                color = OrbitMuted,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 16.dp, bottom = 22.dp),
            )

            if (removedNotice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OrbitDanger.copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("家族から削除されました", color = OrbitDanger, fontWeight = FontWeight.Bold)
                        Text("位置共有とメッセージ受信は停止しました。再び参加するには、保護者から新しい接続コードを受け取ってください。", color = OrbitText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("1　位置情報を許可", color = OrbitLime, fontWeight = FontWeight.Bold)
                    Text("移動中だけでなく、画面を閉じた時も家族へ現在地を共有するために使います。広告には利用しません。", color = OrbitMuted, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            foregroundPermissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                            if (Build.VERSION.SDK_INT >= 33) notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (permissionStep >= 1) "アプリ使用中：許可済み" else "位置情報を許可", fontWeight = FontWeight.Bold) }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && permissionStep >= 1) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) { Text(if (permissionStep >= 2) "常に許可：設定済み" else "バックグラウンド共有を許可") }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("2　保護者と接続", color = OrbitMint, fontWeight = FontWeight.Bold)
                    Text("保護者アプリに表示された6桁コードを入力してください。コードは10分間・1回だけ有効です。", color = OrbitMuted, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter(Char::isDigit).take(6) },
                        label = { Text("6桁の接続コード") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = OrbitDanger, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            loading = true
                            error = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) { FamilyOrbitApi(BuildConfig.API_BASE_URL, context).pair(code, Build.MODEL) }
                                }.onSuccess { onPaired(it.deviceToken, it.childId, it.pauseRestricted) }
                                    .onFailure { error = it.message ?: "接続できませんでした" }
                                loading = false
                            }
                        },
                        enabled = code.length == 6 && !loading,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(if (loading) "接続中…" else "家族と接続する", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TrackingStatusScreen(
    context: Context,
    tokenStore: SecureTokenStore,
    state: LinkState,
    incomingMessageId: String?,
    onRemoved: () -> Unit,
    onUnpair: () -> Unit,
) {
    var active by remember { mutableStateOf(state.trackingActive) }
    var finePermission by remember { mutableStateOf(hasFineLocation(context)) }
    var backgroundPermission by remember { mutableStateOf(hasBackgroundLocation(context)) }
    var online by remember { mutableStateOf(isOnline(context)) }
    var serverReachable by remember { mutableStateOf<Boolean?>(null) }
    var lastSentAt by remember { mutableStateOf(state.lastSentAt) }
    var lastAccuracy by remember { mutableStateOf(state.lastAccuracy) }
    var pendingLocationCount by remember { mutableIntStateOf(state.pendingLocationCount) }
    var uploadError by remember { mutableStateOf(state.lastUploadError) }
    var tick by remember { mutableIntStateOf(0) }
    var messages by remember { mutableStateOf<List<OrbitMessage>>(emptyList()) }
    var messageError by remember { mutableStateOf<String?>(null) }
    var openedMessage by remember { mutableStateOf<OrbitMessage?>(null) }
    var unpairing by remember { mutableStateOf(false) }
    var pauseRestricted by remember { mutableStateOf(state.pauseRestricted) }
    val api = remember(context) { FamilyOrbitApi(BuildConfig.API_BASE_URL, context) }
    val scope = rememberCoroutineScope()
    val foregroundPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        finePermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    val backgroundPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        backgroundPermission = granted
    }
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            finePermission = hasFineLocation(context)
            backgroundPermission = hasBackgroundLocation(context)
            online = isOnline(context)
            lastSentAt = state.lastSentAt
            lastAccuracy = state.lastAccuracy
            pendingLocationCount = state.pendingLocationCount
            uploadError = state.lastUploadError
            active = state.trackingActive
            pauseRestricted = state.pauseRestricted
            if (pauseRestricted && finePermission && !active) {
                state.trackingActive = true
                active = true
                ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
            }
            tick++
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val deviceToken = tokenStore.get()
            if (deviceToken != null) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        api.deviceConfig(deviceToken) to api.deviceMessages(deviceToken)
                    }
                }.onSuccess { (config, receivedMessages) ->
                    state.pauseRestricted = config.pauseRestricted
                    pauseRestricted = config.pauseRestricted
                    messages = receivedMessages
                    messageError = null
                    serverReachable = true
                }
                    .onFailure {
                        if (it is OrbitUnauthorizedException) {
                            onRemoved()
                        } else {
                            serverReachable = false
                            messageError = it.message ?: "Family Orbitサーバーに接続できませんでした。"
                        }
                    }
            }
            delay(15_000)
        }
    }

    LaunchedEffect(messages, incomingMessageId) {
        if (openedMessage == null && incomingMessageId != null) {
            openedMessage = messages.firstOrNull { it.id == incomingMessageId }
        }
    }

    LaunchedEffect(openedMessage?.id) {
        val message = openedMessage ?: return@LaunchedEffect
        val deviceToken = tokenStore.get() ?: return@LaunchedEffect
        if (message.readAt == null) {
            runCatching { withContext(Dispatchers.IO) { api.markMessageRead(deviceToken, message.id) } }
                .onSuccess { read ->
                    openedMessage = read
                    messages = messages.map { if (it.id == read.id) read else it }
                }
                .onFailure { if (it is OrbitUnauthorizedException) onRemoved() }
        }
    }

    val status = when {
        !finePermission -> "位置情報がオフ"
        active -> "位置を共有中"
        else -> "共有を一時停止中"
    }
    val accent = when {
        !finePermission -> OrbitDanger
        active -> OrbitLime
        else -> Color(0xFFFFC96B)
    }

    Scaffold(
        containerColor = OrbitNavy,
        topBar = {
            TopAppBar(
                title = { Text("Family Orbit Link", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OrbitNavy),
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(26.dp))
            Box(
                Modifier.size(212.dp).clip(CircleShape).background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(156.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (active && finePermission) "●" else "Ⅱ", color = accent, fontSize = 34.sp)
                        Text(status, color = accent, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            Text(
                if (active) "家族の保護者画面に現在地を送信しています" else "現在地は送信されていません",
                color = OrbitMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 22.dp, bottom = 20.dp),
            )

            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatusRow("位置情報", if (finePermission) "許可済み" else "許可が必要", finePermission)
                    StatusRow("バックグラウンド", if (backgroundPermission) "常に許可" else "アプリ使用中のみ", backgroundPermission)
                    StatusRow(
                        "通信",
                        when {
                            !online -> "オフライン・端末に保存中"
                            serverReachable == false -> "サーバーに接続できません"
                            serverReachable == true -> "オンライン"
                            else -> "サーバーを確認中"
                        },
                        online && serverReachable == true,
                    )
                    StatusRow("最終送信", if (lastSentAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastSentAt)) else "まだありません", lastSentAt > 0)
                    StatusRow(
                        "サーバー送信",
                        when {
                            !finePermission -> "位置情報の許可待ち"
                            pendingLocationCount > 0 -> "再送待ち ${pendingLocationCount}件"
                            lastSentAt > 0 -> "最新"
                            else -> "待機中"
                        },
                        finePermission && pendingLocationCount == 0 && lastSentAt > 0,
                    )
                    if (lastAccuracy > 0) StatusRow("位置精度", "±${lastAccuracy.toInt()}m", lastAccuracy <= 100)
                }
            }

            uploadError?.let {
                Text(it, color = OrbitDanger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
            }

            if (!finePermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OrbitDanger.copy(alpha = 0.13f)),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("位置情報の許可が必要です", color = OrbitDanger, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("現在地の共有は停止しています。許可するまで家族へ位置情報は送信されません。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        Button(
                            onClick = { foregroundPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).height(50.dp),
                        ) { Text("位置情報を許可", fontWeight = FontWeight.Bold) }
                        TextButton(onClick = openAppSettings, modifier = Modifier.fillMaxWidth()) { Text("端末設定を開く") }
                    }
                }
            } else if (!backgroundPermission) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFC96B).copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(22.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("バックグラウンド共有が未設定です", color = Color(0xFFFFC96B), fontWeight = FontWeight.Bold)
                        Text("画面を閉じた後も共有するには、位置情報を「常に許可」に変更してください。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        OutlinedButton(
                            onClick = {
                                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) backgroundPermissionRequest.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                else openAppSettings()
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                        ) { Text("バックグラウンド共有を設定") }
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("保護者からのメッセージ", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    if (messages.isEmpty()) {
                        Text("新しいメッセージはありません", color = OrbitMuted, modifier = Modifier.padding(top = 8.dp))
                    } else messages.forEach { message ->
                        Card(
                            onClick = { openedMessage = message },
                            colors = CardDefaults.cardColors(containerColor = OrbitNavy),
                            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                        ) {
                            Column(Modifier.padding(13.dp)) {
                                Text(message.body, fontWeight = FontWeight.SemiBold)
                                Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(message.createdAt, color = OrbitMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                    if (message.readAt == null) Text("未読", color = OrbitLime, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    messageError?.let { Text(it, color = OrbitDanger, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                    Text("受信履歴は30日後に自動削除されます。", color = OrbitMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            if (pauseRestricted) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OrbitLime.copy(alpha = 0.11f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                ) {
                    Column(Modifier.padding(17.dp)) {
                        Text("共有停止は保護者設定で制限中", color = OrbitLime, fontWeight = FontWeight.Bold)
                        Text("このLinkアプリから位置共有の一時停止や家族との接続解除はできません。OSの位置情報設定は端末の利用者が確認できます。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_REFRESH),
                    )
                },
                enabled = active && finePermission,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("現在地を今すぐ送信", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(10.dp))
            if (active && !pauseRestricted) {
                OutlinedButton(
                    onClick = {
                        context.startService(Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_PAUSE))
                        state.trackingActive = false
                        active = false
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFFC96B)),
                ) { Text("位置共有を一時停止", fontWeight = FontWeight.Bold) }
            } else if (!active) {
                Button(
                    onClick = {
                        if (finePermission && tokenStore.get() != null) {
                            state.trackingActive = true
                            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START))
                            active = true
                        }
                    },
                    enabled = finePermission,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("位置共有を開始", fontWeight = FontWeight.Bold) }
            }
            Text(if (pauseRestricted) "共有中は端末の通知欄にも常時表示されます。OS権限の変更や通信停止は保護者へ通知されます。" else "共有中は端末の通知欄にも常時表示されます。停止や権限変更は保護者へ通知されます。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(28.dp))
            if (!pauseRestricted) OutlinedButton(
                onClick = {
                    val deviceToken = tokenStore.get() ?: return@OutlinedButton
                    if (unpairing) return@OutlinedButton
                    unpairing = true
                    messageError = null
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { api.unpairDevice(deviceToken) } }
                            .onSuccess {
                                context.stopService(Intent(context, TrackingService::class.java))
                                onUnpair()
                            }
                            .onFailure { messageError = it.message ?: "端末の接続を解除できませんでした" }
                        unpairing = false
                    }
                },
                enabled = !unpairing,
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(bottom = 4.dp),
            ) { Text(if (unpairing) "解除中…" else "この端末の接続を解除") }
        }
    }

    openedMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { openedMessage = null },
            title = { Text("保護者からのメッセージ") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(message.body, fontSize = 17.sp)
                    Text(message.createdAt, color = OrbitMuted, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { openedMessage = null }) { Text("閉じる") } },
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String, good: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(if (good) OrbitMint else Color(0xFFFFC96B)))
        Text(label, color = OrbitMuted, modifier = Modifier.padding(start = 10.dp).weight(1f))
        Text(value, fontWeight = FontWeight.Bold, textAlign = TextAlign.End)
    }
}

private fun hasFineLocation(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun hasBackgroundLocation(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun isOnline(context: Context): Boolean {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = manager.activeNetwork ?: return false
    val capabilities = manager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
