package com.tracking.familyorbit

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.tracking.familyorbit.core.FamilyOrbitApi
import com.tracking.familyorbit.core.FamilyOrbitTheme
import com.tracking.familyorbit.core.LocalNetworkAccess
import com.tracking.familyorbit.core.OrbitAlert
import com.tracking.familyorbit.core.OrbitChild
import com.tracking.familyorbit.core.OrbitDashboard
import com.tracking.familyorbit.core.OrbitHistoryDay
import com.tracking.familyorbit.core.OrbitHistoryPoint
import com.tracking.familyorbit.core.OrbitMessage
import com.tracking.familyorbit.core.OrbitZone
import com.tracking.familyorbit.core.OrbitLime
import com.tracking.familyorbit.core.OrbitMint
import com.tracking.familyorbit.core.OrbitMark
import com.tracking.familyorbit.core.OrbitMuted
import com.tracking.familyorbit.core.OrbitNavy
import com.tracking.familyorbit.core.OrbitSurface
import com.tracking.familyorbit.core.OrbitText
import com.tracking.familyorbit.core.OrbitUnauthorizedException
import com.tracking.familyorbit.core.SecureTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF071A27.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF071A27.toInt()),
        )
        setContent {
            FamilyOrbitTheme {
                FamilyOrbitParentApp(
                    SecureTokenStore(this, "guardian_access_token"),
                    SecureTokenStore(this, "guardian_refresh_token"),
                )
            }
        }
    }
}

@Composable
private fun FamilyOrbitParentApp(tokenStore: SecureTokenStore, refreshTokenStore: SecureTokenStore) {
    val context = LocalContext.current
    val api = remember(context) { FamilyOrbitApi(BuildConfig.API_BASE_URL, context) }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val sessionLock = remember { Any() }
    var token by remember { mutableStateOf(tokenStore.get()) }

    LaunchedEffect(context) {
        if (
            LocalNetworkAccess.isRequiredFor(BuildConfig.API_BASE_URL) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        ) {
            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    LaunchedEffect(token) {
        val accessToken = tokenStore.get() ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { pushToken ->
                Thread { runCatching { api.registerGuardianPush(accessToken, pushToken, Build.MODEL) } }.start()
            }
        }
    }

    fun <T> withAccessToken(request: (String) -> T): T = synchronized(sessionLock) {
        val accessToken = tokenStore.get() ?: throw OrbitUnauthorizedException("再ログインしてください")
        try {
            request(accessToken)
        } catch (_: OrbitUnauthorizedException) {
            val refreshToken = refreshTokenStore.get() ?: throw OrbitUnauthorizedException("再ログインしてください")
            val renewed = api.refresh(refreshToken)
            tokenStore.put(renewed.accessToken)
            if (renewed.refreshToken.isNotBlank()) refreshTokenStore.put(renewed.refreshToken)
            request(renewed.accessToken)
        }
    }

    if (token == null) {
        LoginScreen(
            onLogin = { email, password ->
                val result = api.login(email, password)
                tokenStore.put(result.accessToken)
                if (result.refreshToken.isNotBlank()) refreshTokenStore.put(result.refreshToken)
                token = result.accessToken
            },
        )
    } else {
        ParentDashboard(
            loadDashboard = { withAccessToken(api::dashboard) },
            createChildAndCode = { name, pauseRestricted ->
                val child = withAccessToken { accessToken -> api.createChild(accessToken, name) }
                withAccessToken { accessToken -> api.createPairingCode(accessToken, child.id, pauseRestricted) }
            },
            createPairingCode = { childId, pauseRestricted -> withAccessToken { accessToken -> api.createPairingCode(accessToken, childId, pauseRestricted) } },
            deleteChild = { childId -> withAccessToken { accessToken -> api.deleteChild(accessToken, childId) } },
            deleteAccount = { withAccessToken(api::deleteAccount) },
            loadHistoryDays = { childId -> withAccessToken { accessToken -> api.historyDays(accessToken, childId) } },
            loadHistory = { childId, from, to -> withAccessToken { accessToken -> api.history(accessToken, childId, from, to) } },
            sendMessage = { childId, clientMessageId, body ->
                withAccessToken { accessToken -> api.sendMessage(accessToken, childId, clientMessageId, body) }
            },
            loadMessages = { childId -> withAccessToken { accessToken -> api.messages(accessToken, childId) } },
            saveZone = { zone, name, latitude, longitude, radius, childIds ->
                withAccessToken { accessToken ->
                    if (zone == null) api.createZone(accessToken, name, latitude, longitude, radius, childIds)
                    else api.updateZone(accessToken, zone.id, name, latitude, longitude, radius, childIds)
                }
            },
            deleteZone = { zoneId -> withAccessToken { accessToken -> api.deleteZone(accessToken, zoneId) } },
            onLogout = {
                tokenStore.clear()
                refreshTokenStore.clear()
                token = null
            },
        )
    }
}

@Composable
private fun LoginScreen(onLogin: suspend (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val canSubmit = email.isNotBlank() && password.length >= 8 && !loading
    val submitLogin: () -> Unit = {
        if (canSubmit) {
            focusManager.clearFocus()
            loading = true
            error = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { onLogin(email.trim(), password) } }
                    .onFailure { error = it.message ?: "ログインできませんでした" }
                loading = false
            }
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = OrbitText,
        unfocusedTextColor = OrbitText,
        focusedContainerColor = OrbitNavy.copy(alpha = 0.54f),
        unfocusedContainerColor = OrbitNavy.copy(alpha = 0.38f),
        cursorColor = OrbitLime,
        focusedBorderColor = OrbitLime,
        unfocusedBorderColor = Color.White.copy(alpha = 0.16f),
        focusedLabelColor = OrbitLime,
        unfocusedLabelColor = OrbitMuted,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = OrbitNavy,
        contentColor = OrbitText,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .size(300.dp)
                    .offset(x = 150.dp, y = (-170).dp)
                    .background(
                        Brush.radialGradient(listOf(OrbitMint.copy(alpha = 0.17f), Color.Transparent)),
                        CircleShape,
                    ),
            )
            Box(
                Modifier
                    .size(260.dp)
                    .align(Alignment.BottomStart)
                    .offset(x = (-150).dp, y = 130.dp)
                    .background(
                        Brush.radialGradient(listOf(OrbitLime.copy(alpha = 0.10f), Color.Transparent)),
                        CircleShape,
                    ),
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OrbitMark(Modifier.size(50.dp))
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Family Orbit",
                            color = OrbitText,
                            fontSize = 21.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                        Text(
                            "FAMILY SAFETY",
                            color = OrbitMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                        )
                    }
                    Surface(
                        color = OrbitLime.copy(alpha = 0.10f),
                        contentColor = OrbitLime,
                        shape = CircleShape,
                        border = BorderStroke(1.dp, OrbitLime.copy(alpha = 0.34f)),
                    ) {
                        Text(
                            "保護者用",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                Spacer(Modifier.height(34.dp))
                Text(
                    "家族の今を、\nひと目で。",
                    color = OrbitText,
                    fontSize = 34.sp,
                    lineHeight = 40.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "現在地・移動履歴・安全エリアを、\nひとつの画面でやさしく確認できます。",
                    color = OrbitMuted,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 12.dp),
                )

                Spacer(Modifier.height(26.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = OrbitSurface.copy(alpha = 0.94f)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text("おかえりなさい", color = OrbitText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "保護者アカウントでログイン",
                            color = OrbitMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it; error = null },
                            label = { Text("メールアドレス") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            label = { Text("パスワード") },
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Text(if (passwordVisible) "隠す" else "表示", fontWeight = FontWeight.Bold)
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitLogin() }),
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                            colors = fieldColors,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        error?.let {
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                                contentColor = MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            ) {
                                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        Button(
                            onClick = submitLogin,
                            enabled = canSubmit,
                            modifier = Modifier.fillMaxWidth().height(54.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = OrbitLime,
                                contentColor = OrbitNavy,
                                disabledContainerColor = OrbitLime.copy(alpha = 0.12f),
                                disabledContentColor = OrbitMuted.copy(alpha = 0.72f),
                            ),
                        ) {
                            Text(if (loading) "確認中…" else "ログイン", fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(OrbitMint))
                    Spacer(Modifier.width(8.dp))
                    Text("同意のある、見える位置共有", color = OrbitMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "本サービスは緊急通報用ではありません。位置の精度や到達時間を保証するものではありません。",
                    color = OrbitMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                )
            }
        }
    }
}

private enum class ParentTab(val label: String, val glyph: String) {
    Map("現在地", "◉"), History("履歴", "↗"), Zones("安全エリア", "⌂"), Alerts("通知", "●")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParentDashboard(
    loadDashboard: () -> OrbitDashboard,
    createChildAndCode: (String, Boolean) -> FamilyOrbitApi.PairingCode,
    createPairingCode: (String, Boolean) -> FamilyOrbitApi.PairingCode,
    deleteChild: (String) -> Unit,
    deleteAccount: () -> Unit,
    loadHistoryDays: (String) -> List<OrbitHistoryDay>,
    loadHistory: (String, String, String) -> List<OrbitHistoryPoint>,
    sendMessage: (String, String, String) -> OrbitMessage,
    loadMessages: (String) -> List<OrbitMessage>,
    saveZone: (OrbitZone?, String, Double, Double, Double, List<String>) -> OrbitZone,
    deleteZone: (String) -> Unit,
    onLogout: () -> Unit,
) {
    var dashboard by remember { mutableStateOf<OrbitDashboard?>(null) }
    var selectedIndex by remember { mutableIntStateOf(0) }
    var tab by remember { mutableStateOf(ParentTab.Map) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showFamilySheet by remember { mutableStateOf(false) }

    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { loadDashboard() } }
                .onSuccess {
                    dashboard = it
                    if (selectedIndex !in it.children.indices) selectedIndex = 0
                    statusMessage = null
                }
                .onFailure { statusMessage = it.message ?: "サーバーに接続できません" }
            delay(5_000)
        }
    }

    val selected = dashboard?.children?.getOrNull(selectedIndex) ?: dashboard?.children?.firstOrNull()
    Scaffold(
        containerColor = OrbitNavy,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = OrbitNavy),
                title = {
                    Column {
                        Text("Family Orbit", fontWeight = FontWeight.Bold)
                        Text(dashboard?.familyName ?: "読み込み中", color = OrbitMuted, style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    TextButton(onClick = { showFamilySheet = true }, modifier = Modifier.height(48.dp)) {
                        Text("家族管理", color = OrbitLime, fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = OrbitSurface) {
                ParentTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Text(item.glyph, fontSize = 20.sp) },
                        label = { Text(item.label, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            statusMessage?.let {
                Surface(color = Color(0xFF4B3D23), modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(12.dp), color = Color(0xFFFFDC91), style = MaterialTheme.typography.bodySmall)
                }
            }
            val current = dashboard
            if (current == null) {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    if (statusMessage == null) CircularProgressIndicator(color = OrbitLime)
                    else {
                        Text("家族データを取得できません", fontWeight = FontWeight.Bold)
                        TextButton(onClick = { reloadKey++ }) { Text("再試行", color = OrbitLime) }
                    }
                }
            } else when (tab) {
                ParentTab.Map -> MapHome(current, selected, selectedIndex, sendMessage, loadMessages) { selectedIndex = it }
                ParentTab.History -> HistoryScreen(selected, loadHistoryDays, loadHistory)
                ParentTab.Zones -> ZonesScreen(current, saveZone, deleteZone) {
                    runCatching { withContext(Dispatchers.IO) { loadDashboard() } }.onSuccess { dashboard = it }
                }
                ParentTab.Alerts -> AlertsScreen(current.alerts)
            }
        }
    }

    dashboard?.let { currentDashboard -> if (showFamilySheet) {
        ModalBottomSheet(
            onDismissRequest = { showFamilySheet = false },
            containerColor = OrbitNavy,
            contentColor = OrbitText,
        ) {
            FamilyManagementSheet(
                dashboard = currentDashboard,
                createChildAndCode = createChildAndCode,
                createPairingCode = createPairingCode,
                deleteChild = deleteChild,
                deleteAccount = deleteAccount,
                refreshDashboard = loadDashboard,
                onDashboardChanged = { dashboard = it; selectedIndex = 0 },
                onExit = {
                    showFamilySheet = false
                    onLogout()
                },
            )
        }
    } }
}

@Composable
private fun FamilyManagementSheet(
    dashboard: OrbitDashboard,
    createChildAndCode: (String, Boolean) -> FamilyOrbitApi.PairingCode,
    createPairingCode: (String, Boolean) -> FamilyOrbitApi.PairingCode,
    deleteChild: (String) -> Unit,
    deleteAccount: () -> Unit,
    refreshDashboard: () -> OrbitDashboard,
    onDashboardChanged: (OrbitDashboard) -> Unit,
    onExit: () -> Unit,
) {
    var newChildName by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf<FamilyOrbitApi.PairingCode?>(null) }
    var pairingChildName by remember { mutableStateOf("") }
    var pauseRestricted by remember { mutableStateOf(false) }
    var loadingId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var childPendingDeletion by remember { mutableStateOf<OrbitChild?>(null) }
    var accountPendingDeletion by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.92f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text("家族・端末管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        Text("子ども用アプリを安全に接続します", color = OrbitMuted, modifier = Modifier.padding(top = 5.dp, bottom = 18.dp))

        Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(22.dp)) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(17.dp)).background(OrbitLime.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Text("家", color = OrbitLime, fontSize = 20.sp, fontWeight = FontWeight.Black) }
                Column(Modifier.padding(start = 14.dp)) {
                    Text(dashboard.familyName, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("保護者 · ${dashboard.children.size}人のプロフィール", color = OrbitMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        pairingCode?.let { code ->
            val formatted = if (code.code.length == 6) "${code.code.take(3)}  ${code.code.takeLast(3)}" else code.code
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                colors = CardDefaults.cardColors(containerColor = OrbitSurface),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, OrbitLime.copy(alpha = 0.38f)),
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("リンクコード", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Spacer(Modifier.weight(1f))
                        Surface(color = OrbitLime, contentColor = OrbitNavy, shape = CircleShape) {
                            Text("10分有効", fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
                        }
                    }
                    Text(pairingChildName, color = OrbitMuted, modifier = Modifier.padding(top = 7.dp))
                    Text(
                        formatted,
                        color = OrbitText,
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).semantics { contentDescription = "リンクコード ${code.code.toList().joinToString(" ")}" },
                    )
                    Text("Family Orbit Link の6桁コード欄へ入力してください", color = OrbitMuted, style = MaterialTheme.typography.bodySmall)
                    if (code.pauseRestricted) {
                        Text("このコードはLinkアプリ内の共有停止を制限します", color = OrbitLime, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            colors = CardDefaults.cardColors(containerColor = OrbitSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("子どもの端末を接続", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("10分有効・1回限りのコードを発行します。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(OrbitNavy.copy(alpha = 0.65f)).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("子どもによる共有停止を制限", fontWeight = FontWeight.Bold)
                        Text("Linkアプリ内の一時停止・接続解除を無効にします。OSの権限変更やアプリ削除は防げません。", color = OrbitMuted, style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = pauseRestricted, onCheckedChange = { pauseRestricted = it })
                }
                if (dashboard.children.isEmpty()) {
                    Text("まず下のフォームから子どもを追加してください。", color = OrbitMint, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                } else {
                    dashboard.children.forEach { child ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(OrbitNavy.copy(alpha = 0.65f)).padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(OrbitLime), contentAlignment = Alignment.Center) {
                                Text(child.name.take(1), color = OrbitNavy, fontWeight = FontWeight.Black)
                            }
                            Column(Modifier.padding(start = 11.dp).weight(1f)) {
                                Text(child.name, fontWeight = FontWeight.Bold)
                                Text(if (child.connectivity == "online") "接続済み · 再発行できます" else "未接続またはオフライン", color = OrbitMuted, style = MaterialTheme.typography.labelSmall)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                TextButton(
                                    enabled = loadingId == null,
                                    onClick = {
                                        loadingId = child.id
                                        error = null
                                        scope.launch {
                                            runCatching { withContext(Dispatchers.IO) { createPairingCode(child.id, pauseRestricted) } }
                                                .onSuccess { pairingCode = it; pairingChildName = child.name }
                                                .onFailure { error = it.message ?: "コードを発行できませんでした" }
                                            loadingId = null
                                        }
                                    },
                                ) { Text(if (loadingId == child.id) "発行中…" else "コード発行", color = OrbitLime, fontWeight = FontWeight.Bold) }
                                TextButton(
                                    enabled = loadingId == null,
                                    onClick = { childPendingDeletion = child },
                                ) { Text("削除", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            colors = CardDefaults.cardColors(containerColor = OrbitSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("＋ 子どもを追加", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                OutlinedTextField(
                    value = newChildName,
                    onValueChange = { newChildName = it; error = null },
                    label = { Text("表示名（例：あおい）") },
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Button(
                    enabled = newChildName.isNotBlank() && loadingId == null,
                    onClick = {
                        val name = newChildName.trim()
                        loadingId = "new"
                        error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    val code = createChildAndCode(name, pauseRestricted)
                                    code to refreshDashboard()
                                }
                            }.onSuccess { (code, refreshed) ->
                                pairingCode = code
                                pairingChildName = name
                                newChildName = ""
                                onDashboardChanged(refreshed)
                            }.onFailure { error = it.message ?: "子どもを追加できませんでした" }
                            loadingId = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrbitLime, contentColor = OrbitNavy),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(if (loadingId == "new") "追加しています…" else "追加してコードを発行", fontWeight = FontWeight.ExtraBold) }
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(top = 14.dp).background(MaterialTheme.colorScheme.error.copy(alpha = 0.10f), RoundedCornerShape(14.dp)).padding(13.dp)) }
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            colors = CardDefaults.cardColors(containerColor = OrbitSurface),
            shape = RoundedCornerShape(22.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("家族アカウントの削除", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("誤って登録した場合は、位置履歴・端末・通知を含む家族データを完全に削除できます。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
                OutlinedButton(
                    onClick = { accountPendingDeletion = true },
                    enabled = loadingId == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.65f)),
                ) { Text("家族アカウントを削除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            }
        }
        TextButton(onClick = onExit, modifier = Modifier.fillMaxWidth().height(54.dp).padding(top = 10.dp, bottom = 18.dp)) {
            Text("ログアウト", color = MaterialTheme.colorScheme.error)
        }
    }

    childPendingDeletion?.let { child ->
        AlertDialog(
            onDismissRequest = { if (loadingId == null) childPendingDeletion = null },
            title = { Text("${child.name}を削除しますか？") },
            text = { Text("ペアリング済み端末を無効にし、位置履歴・通知・安全エリア状態も削除します。この操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    enabled = loadingId == null,
                    onClick = {
                        loadingId = "delete:${child.id}"
                        error = null
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    deleteChild(child.id)
                                    refreshDashboard()
                                }
                            }.onSuccess { refreshed ->
                                onDashboardChanged(refreshed)
                                if (pairingChildName == child.name) {
                                    pairingCode = null
                                    pairingChildName = ""
                                }
                                childPendingDeletion = null
                            }.onFailure { error = it.message ?: "子どもプロフィールを削除できませんでした" }
                            loadingId = null
                        }
                    },
                ) { Text(if (loadingId == "delete:${child.id}") "削除中…" else "完全に削除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { childPendingDeletion = null }, enabled = loadingId == null) { Text("キャンセル") } },
            containerColor = OrbitSurface,
        )
    }

    if (accountPendingDeletion) {
        AlertDialog(
            onDismissRequest = { if (loadingId == null) accountPendingDeletion = false },
            title = { Text("家族アカウントを削除しますか？") },
            text = { Text("すべての子ども、位置履歴、端末、安全エリア、通知が完全に削除されます。復元はできません。") },
            confirmButton = {
                TextButton(
                    enabled = loadingId == null,
                    onClick = {
                        loadingId = "delete-account"
                        error = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { deleteAccount() } }
                                .onSuccess { accountPendingDeletion = false; onExit() }
                                .onFailure { error = it.message ?: "アカウントを削除できませんでした" }
                            loadingId = null
                        }
                    },
                ) { Text(if (loadingId == "delete-account") "削除中…" else "家族データを削除", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { accountPendingDeletion = false }, enabled = loadingId == null) { Text("キャンセル") } },
            containerColor = OrbitSurface,
        )
    }
}

@Composable
private fun MapHome(
    dashboard: OrbitDashboard,
    selected: OrbitChild?,
    selectedIndex: Int,
    sendMessage: (String, String, String) -> OrbitMessage,
    loadMessages: (String) -> List<OrbitMessage>,
    onSelect: (Int) -> Unit,
) {
    var showMessage by remember(selected?.id) { mutableStateOf(false) }
    if (selected == null) {
        EmptyState("子どもが登録されていません", "家族管理から子どもを追加し、Linkアプリと接続してください。")
        return
    }
    if (selected.location == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState("位置情報がまだありません", "子ども用アプリの接続と位置情報の許可を確認してください。")
            Button(onClick = { showMessage = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("${selected.name}へメッセージを送る", fontWeight = FontWeight.Bold)
            }
        }
        if (showMessage) MessageDialog(selected, sendMessage, loadMessages) { showMessage = false }
        return
    }
    val location = selected.location ?: return
    val point = LatLng(location.latitude, location.longitude)
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(point, 13.8f) }
    LaunchedEffect(selected.id) {
        withContext(Dispatchers.Main.immediate) {
            camera.animate(CameraUpdateFactory.newLatLngZoom(point, 13.8f))
        }
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(dashboard.children.withIndex().toList()) { indexed ->
                    val active = selectedIndex == indexed.index
                    OutlinedButton(
                        onClick = { onSelect(indexed.index) },
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (active) OrbitLime else OrbitSurface),
                        modifier = Modifier.height(48.dp),
                    ) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (indexed.value.connectivity == "online") OrbitMint else OrbitMuted))
                        Spacer(Modifier.width(8.dp))
                        Text(indexed.value.name, color = if (active) OrbitNavy else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth().height(380.dp).padding(horizontal = 18.dp),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = OrbitSurface),
            ) {
                Box(Modifier.fillMaxSize()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = camera,
                        uiSettings = MapUiSettings(compassEnabled = false, mapToolbarEnabled = false, zoomControlsEnabled = false),
                    ) {
                        dashboard.zones.forEach { zone ->
                            Circle(
                                center = LatLng(zone.latitude, zone.longitude),
                                radius = zone.radiusMeters,
                                fillColor = OrbitMint.copy(alpha = 0.14f),
                                strokeColor = OrbitMint.copy(alpha = 0.8f),
                                strokeWidth = 3f,
                            )
                        }
                        Circle(
                            center = point,
                            radius = location.accuracy.coerceAtLeast(8.0),
                            fillColor = OrbitLime.copy(alpha = 0.16f),
                            strokeColor = OrbitLime,
                            strokeWidth = 2f,
                        )
                        Marker(state = MarkerState(point), title = selected.name, snippet = "精度 ±${location.accuracy.toInt()}m")
                    }
                    Surface(
                        color = OrbitNavy.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(9.dp).clip(CircleShape).background(if (selected.connectivity == "online") OrbitMint else OrbitMuted))
                            Spacer(Modifier.width(8.dp))
                            Text(if (selected.connectivity == "online") "共有中" else "オフライン", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item { ChildStatusCard(selected) { showMessage = true } }
    }
    if (showMessage) MessageDialog(selected, sendMessage, loadMessages) { showMessage = false }
}

@Composable
private fun ChildStatusCard(child: OrbitChild, onMessage: () -> Unit) {
    val location = child.location ?: return
    Card(
        modifier = Modifier.fillMaxWidth().padding(18.dp),
        colors = CardDefaults.cardColors(containerColor = OrbitSurface),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(OrbitLime),
                    contentAlignment = Alignment.Center,
                ) { Text(child.name.take(1), color = OrbitNavy, fontWeight = FontWeight.Black, fontSize = 20.sp) }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text(child.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("最終更新 ${location.recordedAt}", color = OrbitMuted)
                }
                val batteryPercent = (location.batteryLevel * 100).toInt()
                Text("$batteryPercent%", color = if (batteryPercent < 20) MaterialTheme.colorScheme.error else OrbitMint, fontWeight = FontWeight.Bold)
            }
            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = Color.White.copy(alpha = 0.08f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("位置精度", "±${location.accuracy.toInt()} m")
                Stat("通信", if (child.connectivity == "online") "オンライン" else "15分以上なし")
                Stat("充電", if (location.isCharging) "充電中" else "未接続")
            }
            Button(onClick = onMessage, modifier = Modifier.fillMaxWidth().padding(top = 18.dp).height(50.dp)) {
                Text("メッセージを送る", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MessageDialog(
    child: OrbitChild,
    sendMessage: (String, String, String) -> OrbitMessage,
    loadMessages: (String) -> List<OrbitMessage>,
    onDismiss: () -> Unit,
) {
    var body by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf<List<OrbitMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMessageId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(child.id) {
        while (true) {
            runCatching { withContext(Dispatchers.IO) { loadMessages(child.id) } }
                .onSuccess { messages = it; error = null }
                .onFailure { error = it.message }
            delay(5_000)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${child.name}へメッセージ") },
        text = {
            Column(Modifier.fillMaxWidth().height(430.dp).verticalScroll(rememberScrollState())) {
                Text("本文は子どものロック画面にも表示されます。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = body,
                    onValueChange = {
                        if (it.codePointCount(0, it.length) <= 200) {
                            body = it
                            clientMessageId = null
                        }
                    },
                    label = { Text("メッセージ（200文字まで）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Text("${body.codePointCount(0, body.length)}/200", color = OrbitMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.End))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("送信履歴", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
                if (messages.isEmpty()) Text("まだメッセージはありません", color = OrbitMuted)
                messages.forEach { message ->
                    Card(colors = CardDefaults.cardColors(containerColor = OrbitNavy), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(message.body)
                            Text(messageStatus(message), color = if (message.readAt != null) OrbitMint else OrbitMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = body.trim().isNotEmpty() && !loading,
                onClick = {
                    if (loading) return@Button
                    loading = true; error = null
                    val text = body.trim()
                    val requestId = clientMessageId ?: UUID.randomUUID().toString().also { clientMessageId = it }
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { sendMessage(child.id, requestId, text) } }
                            .onSuccess { sent -> body = ""; clientMessageId = null; messages = listOf(sent) + messages.filterNot { it.id == sent.id } }
                            .onFailure { error = it.message ?: "送信できませんでした" }
                        loading = false
                    }
                },
            ) { Text(if (loading) "送信中…" else "送信") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        containerColor = OrbitSurface,
    )
}

private fun messageStatus(message: OrbitMessage): String = when {
    message.readAt != null -> "既読 ${message.readAt}"
    message.deliveryState == "pushed" -> "通知送信済み ${message.pushedAt ?: message.createdAt}"
    message.deliveryState == "failed" -> "送信失敗・再試行待ち ${message.createdAt}"
    else -> "送信待ち ${message.createdAt}"
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, color = OrbitMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun HistoryScreen(
    child: OrbitChild?,
    loadDays: (String) -> List<OrbitHistoryDay>,
    loadPoints: (String, String, String) -> List<OrbitHistoryPoint>,
) {
    var days by remember(child?.id) { mutableStateOf<List<OrbitHistoryDay>>(emptyList()) }
    var points by remember(child?.id) { mutableStateOf<List<OrbitHistoryPoint>>(emptyList()) }
    var selectedDay by remember(child?.id) { mutableStateOf<OrbitHistoryDay?>(null) }
    var loading by remember(child?.id) { mutableStateOf(child != null) }
    var error by remember(child?.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(child?.id) {
        val current = child ?: return@LaunchedEffect
        loading = true
        runCatching { withContext(Dispatchers.IO) { loadDays(current.id) } }
            .onSuccess { days = it; error = null }
            .onFailure { error = it.message ?: "履歴を取得できませんでした" }
        loading = false
    }

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("30日間の履歴", "${child?.name ?: "家族"}の実際の位置記録です") }
        if (loading) item { CircularProgressIndicator(color = OrbitLime, modifier = Modifier.padding(24.dp)) }
        error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (!loading && error == null && days.isEmpty()) item { EmptyState("履歴はまだありません", "Linkアプリから位置が送信されると日付ごとに表示されます。") }
        selectedDay?.let { day ->
            item {
                if (points.isEmpty()) {
                    Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), modifier = Modifier.fillMaxWidth()) {
                        Text("${day.date} の位置記録を読み込んでいます…", color = OrbitMuted, modifier = Modifier.padding(18.dp))
                    }
                } else {
                    val route = points.map { LatLng(it.latitude, it.longitude) }
                    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(route.first(), 13f) }
                    Card(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().height(330.dp)) {
                        GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = camera, uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false)) {
                            Polyline(points = route, color = OrbitLime, width = 10f)
                            Marker(state = MarkerState(route.first()), title = "開始")
                            if (route.size > 1) Marker(state = MarkerState(route.last()), title = "終了")
                        }
                    }
                }
            }
        }
        items(days) { day ->
            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(OrbitLime.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Text("↗", color = OrbitLime) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(day.date, fontWeight = FontWeight.Bold)
                        Text("${day.pointCount}地点 · ${day.firstRecordedAt}〜${day.lastRecordedAt}", color = OrbitMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = {
                        selectedDay = day
                        points = emptyList()
                        val (from, to) = dayBounds(day.date)
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { child?.let { loadPoints(it.id, from, to) } ?: emptyList() } }
                                .onSuccess { points = it; error = null }
                                .onFailure { error = it.message ?: "履歴を取得できませんでした" }
                        }
                    }) { Text("表示", color = OrbitMint) }
                }
            }
        }
        item { Text("履歴はサーバーで30日後に自動削除されます。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun dayBounds(date: String): Pair<String, String> {
    val zone = TimeZone.getTimeZone("Asia/Tokyo")
    val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.JAPAN).apply { timeZone = zone }
    val instantFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = zone }
    val start = dayFormat.parse(date) ?: java.util.Date()
    val calendar = Calendar.getInstance(zone).apply { time = start; add(Calendar.DAY_OF_MONTH, 1) }
    return instantFormat.format(start) to instantFormat.format(calendar.time)
}

@Composable
private fun ZonesScreen(
    dashboard: OrbitDashboard,
    saveZone: (OrbitZone?, String, Double, Double, Double, List<String>) -> OrbitZone,
    deleteZone: (String) -> Unit,
    onChanged: suspend () -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<OrbitZone?>(null) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("安全エリア", "出入りを検知して保護者へ通知します") }
        if (dashboard.zones.isEmpty()) item { Text("安全エリアはまだありません。地図から追加できます。", color = OrbitMuted) }
        items(dashboard.zones) { zone ->
            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(OrbitMint.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { Text("⌂", color = OrbitMint, fontSize = 22.sp) }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(zone.name, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("半径 ${zone.radiusMeters.toInt()}m · 出入りを通知", color = OrbitMuted)
                    }
                    TextButton(onClick = { editing = zone; showEditor = true }) { Text("編集", color = OrbitLime, fontWeight = FontWeight.Bold) }
                }
            }
        }
        item {
            Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("＋ 安全エリアを追加", fontWeight = FontWeight.Bold) }
        }
        item { Text("設定できる半径は100〜5,000mです。端末とサーバーの両方で判定します。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall) }
    }
    if (showEditor) ZoneEditorDialog(dashboard, editing, saveZone, deleteZone, onChanged) { showEditor = false }
}

@Composable
private fun ZoneEditorDialog(
    dashboard: OrbitDashboard,
    zone: OrbitZone?,
    saveZone: (OrbitZone?, String, Double, Double, Double, List<String>) -> OrbitZone,
    deleteZone: (String) -> Unit,
    onChanged: suspend () -> Unit,
    onDismiss: () -> Unit,
) {
    val fallback = dashboard.children.firstNotNullOfOrNull { it.location }?.let { LatLng(it.latitude, it.longitude) } ?: LatLng(35.6812, 139.7671)
    var name by remember(zone?.id) { mutableStateOf(zone?.name.orEmpty()) }
    var center by remember(zone?.id) { mutableStateOf(zone?.let { LatLng(it.latitude, it.longitude) } ?: fallback) }
    var radius by remember(zone?.id) { mutableStateOf((zone?.radiusMeters ?: 300.0).toFloat()) }
    var childIds by remember(zone?.id) { mutableStateOf(zone?.childIds?.toSet() ?: dashboard.children.map { it.id }.toSet()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(center, 14f) }

    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(if (zone == null) "安全エリアを追加" else "安全エリアを編集") },
        text = {
            Column(Modifier.fillMaxWidth().height(520.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(60) }, label = { Text("名前") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("地図をタップして中心を指定", color = OrbitMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp, bottom = 7.dp))
                Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().height(230.dp)) {
                    GoogleMap(modifier = Modifier.fillMaxSize(), cameraPositionState = camera, onMapClick = { center = it }, uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false)) {
                        Circle(center = center, radius = radius.toDouble(), fillColor = OrbitMint.copy(alpha = 0.15f), strokeColor = OrbitMint)
                        Marker(state = MarkerState(center), title = name.ifBlank { "安全エリア" })
                    }
                }
                Text("半径 ${radius.toInt()}m", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Slider(value = radius, onValueChange = { radius = it }, valueRange = 100f..5000f)
                Text("対象の子ども", fontWeight = FontWeight.Bold)
                dashboard.children.forEach { child ->
                    OutlinedButton(
                        onClick = { childIds = if (child.id in childIds) childIds - child.id else childIds + child.id },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (child.id in childIds) OrbitLime.copy(alpha = 0.18f) else Color.Transparent),
                    ) { Text((if (child.id in childIds) "✓ " else "") + child.name) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                if (zone != null) {
                    TextButton(
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { deleteZone(zone.id) }; onChanged() }
                                    .onSuccess { onDismiss() }
                                    .onFailure { error = it.message ?: "削除できませんでした" }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    ) { Text("この安全エリアを削除", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = name.trim().isNotEmpty() && childIds.isNotEmpty() && !loading,
                onClick = {
                    loading = true; error = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) { saveZone(zone, name.trim(), center.latitude, center.longitude, radius.toDouble(), childIds.toList()) }
                            onChanged()
                        }.onSuccess { onDismiss() }.onFailure { error = it.message ?: "保存できませんでした" }
                        loading = false
                    }
                },
            ) { Text(if (loading) "保存中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !loading) { Text("キャンセル") } },
        containerColor = OrbitSurface,
    )
}

@Composable
private fun AlertsScreen(alerts: List<OrbitAlert>) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(OrbitLime.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Text("●", color = OrbitLime, fontSize = 22.sp)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Text("通知", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    Text("家族の変化を、見逃さない。", color = OrbitMuted)
                }
                Surface(color = OrbitLime, contentColor = OrbitNavy, shape = CircleShape) {
                    Text("${alerts.size}件", fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                }
            }
        }
        if (alerts.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = OrbitSurface),
                    shape = RoundedCornerShape(26.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 34.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.size(92.dp).clip(CircleShape).background(OrbitMint.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                            Text("✓", color = OrbitMint, fontSize = 38.sp, fontWeight = FontWeight.Black)
                        }
                        Text("新しい通知はありません", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp))
                        Text(
                            "位置共有の停止や安全エリアへの出入り、\n端末のオフラインをここでお知らせします。",
                            color = OrbitMuted,
                            textAlign = TextAlign.Center,
                            lineHeight = 21.sp,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("⌖" to "安全エリア", "◉" to "共有状態", "▣" to "端末状態").forEach { (glyph, label) ->
                                Column(
                                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(OrbitNavy.copy(alpha = 0.58f)).padding(vertical = 11.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(glyph, color = OrbitMint, fontSize = 18.sp)
                                    Text(label, color = OrbitMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item { Text("最近の通知", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        items(alerts) { alert ->
            Card(colors = CardDefaults.cardColors(containerColor = OrbitSurface), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(alertColor(alert.type).copy(alpha = 0.13f)), contentAlignment = Alignment.Center) {
                        Text(alertGlyph(alert.type), color = alertColor(alert.type), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Row(Modifier.fillMaxWidth()) {
                            Text(alertCategory(alert.type).uppercase(), color = alertColor(alert.type), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                            Spacer(Modifier.weight(1f))
                            Text(alert.occurredAt, color = OrbitMuted, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(alert.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                        Text(alert.message, color = OrbitMuted, lineHeight = 20.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        item { Text("通知には氏名や座標を含めず、アプリを開いた後に安全に詳細を取得します。", color = OrbitMuted, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun alertGlyph(type: String): String = when {
    type.contains("zone") -> "⌖"
    type.contains("battery") -> "▰"
    type.contains("tracking") -> "⊘"
    type.contains("offline") || type.contains("device") -> "⌁"
    else -> "●"
}

@Composable
private fun alertColor(type: String): Color = when {
    type.contains("zone") -> OrbitMint
    type.contains("battery") || type.contains("tracking") || type.contains("offline") -> MaterialTheme.colorScheme.error
    else -> OrbitLime
}

private fun alertCategory(type: String): String = when {
    type.contains("zone") -> "安全エリア"
    type.contains("battery") -> "バッテリー"
    type.contains("tracking") -> "位置共有"
    type.contains("offline") || type.contains("device") -> "端末"
    else -> "お知らせ"
}

@Composable
private fun ScreenTitle(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(subtitle, color = OrbitMuted, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(OrbitSurface), contentAlignment = Alignment.Center) { Text("◎", color = OrbitLime, fontSize = 36.sp) }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
            Text(body, color = OrbitMuted, modifier = Modifier.padding(top = 8.dp).semantics { contentDescription = body })
        }
    }
}
