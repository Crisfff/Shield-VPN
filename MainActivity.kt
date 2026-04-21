package com.shield.vpn

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.wireguard.android.backend.GoBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class DeviceInfo(
    val fingerprint: String,
    val deviceName: String
)

enum class State {
    OFF, CONNECTING, ON
}

class MainActivity : ComponentActivity() {

    private lateinit var deviceInfo: DeviceInfo

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d("VPN_PERMISSION", "Permiso VPN concedido")
            } else {
                Log.d("VPN_PERMISSION", "Permiso VPN denegado")
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d("NOTIFICATION_PERMISSION", "Permiso notificaciones: $granted")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        createVpnNotificationChannel(this)
        requestNotificationPermissionIfNeeded()

        val vpnIntent = GoBackend.VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        }

        deviceInfo = buildDeviceInfo(this)

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color(0xFF130C2D)
            ) {
                ShieldVpnApp(
                    context = this,
                    deviceInfo = deviceInfo
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@Composable
fun ShieldVpnApp(
    context: Context,
    deviceInfo: DeviceInfo
) {
    var screen by remember { mutableStateOf("loading") }
    var activeId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        activeId = loadSavedSessionId(context)
        screen = if (activeId.isNullOrBlank()) "login" else "home"
    }

    when (screen) {
        "loading" -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF130C2D),
                                Color(0xFF130C2D),
                                Color(0xFF0D081F),
                                Color(0xFF050507)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color(0xFF6BE6C1),
                    strokeWidth = 2.dp
                )
            }
        }

        "login" -> {
            LoginScreen(
                deviceInfo = deviceInfo,
                onLoginSuccess = { id ->
                    saveSessionId(context, id)
                    updateUserAppStatus(context, id)
                    activeId = id
                    screen = "home"
                }
            )
        }

        else -> {
            HomeScreen(
                context = context,
                sessionId = activeId.orEmpty(),
                deviceInfo = deviceInfo,
                onForceLogout = {
                    stopVpnService(context)
                    clearSessionId(context)
                    activeId = null
                    screen = "login"
                }
            )
        }
    }
}

@Composable
fun LoginScreen(
    deviceInfo: DeviceInfo,
    onLoginSuccess: (String) -> Unit
) {
    var idInput by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }

    val bg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF130C2D),
            Color(0xFF130C2D),
            Color(0xFF0D081F),
            Color(0xFF050507)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .padding(24.dp)
    ) {
        Text(
            text = "ID: ${if (idInput.isBlank()) "------" else idInput}",
            color = Color(0xFF8D98B6).copy(alpha = 0.75f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp)
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(30.dp))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(30.dp))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Shield VPN",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "ID de acceso",
                        color = Color(0xFFB8C1D9),
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = idInput,
                        onValueChange = {
                            idInput = it.filter { c -> c.isDigit() }.take(6)
                            error = ""
                        },
                        placeholder = {
                            Text(
                                text = "Ingrese 6 dígitos",
                                color = Color(0xFF7E89A6)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number
                        ),
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF6BE6C1),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            cursorColor = Color(0xFF6BE6C1),
                            focusedContainerColor = Color(0xFF1A1436),
                            unfocusedContainerColor = Color(0xFF1A1436)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "Código de acceso",
                        color = Color(0xFFB8C1D9),
                        fontSize = 15.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it
                                .filter { c -> c.isLetterOrDigit() }
                                .uppercase(Locale.US)
                                .take(12)
                            error = ""
                        },
                        placeholder = {
                            Text(
                                text = "Ingrese 12 caracteres",
                                color = Color(0xFF7E89A6)
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            capitalization = KeyboardCapitalization.Characters
                        ),
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF6BE6C1),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            cursorColor = Color(0xFF6BE6C1),
                            focusedContainerColor = Color(0xFF1A1436),
                            unfocusedContainerColor = Color(0xFF1A1436)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "${code.length}/12",
                        color = Color(0xFF8D97B0),
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.End)
                    )

                    if (error.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = error,
                            color = Color(0xFFFF6B6B),
                            fontSize = 13.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (idInput.length != 6) {
                                error = "El ID debe tener 6 dígitos"
                                return@Button
                            }

                            if (code.length != 12) {
                                error = "La clave debe tener 12 caracteres"
                                return@Button
                            }

                            loading = true
                            error = ""

                            validateLogin(
                                id = idInput,
                                accessKey = code,
                                deviceInfo = deviceInfo,
                                onSuccess = {
                                    loading = false
                                    onLoginSuccess(idInput)
                                },
                                onError = { msg: String ->
                                    loading = false
                                    error = msg
                                }
                            )
                        },
                        enabled = !loading,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6BFFC8),
                            contentColor = Color(0xFF081018)
                        ),
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color(0xFF081018),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Entrar",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    context: Context,
    sessionId: String,
    deviceInfo: DeviceInfo,
    onForceLogout: () -> Unit
) {
    var state by remember { mutableStateOf(if (loadVpnState(context)) State.ON else State.OFF) }

    var fakeDownload by remember { mutableIntStateOf(124) }
    var fakeUpload by remember { mutableIntStateOf(1980) }

    var downRateText by remember { mutableStateOf("↓ 0 B/s") }
    var upRateText by remember { mutableStateOf("↑ 0 B/s") }
    var realIp by remember { mutableStateOf("--.--.--.--") }
    var remoteBlocked by remember { mutableStateOf(false) }
    var subscriptionEnd by remember { mutableLongStateOf(0L) }
    var planExpired by remember { mutableStateOf(false) }
    var vpnError by remember { mutableStateOf("") }
    var wgConfig by remember { mutableStateOf("") }
    var assignedConfName by remember { mutableStateOf("") }
    var lastKnownEnd by remember { mutableStateOf<Long?>(null) }

    var showUpdateDialog by remember { mutableStateOf(false) }
    var updateTitle by remember { mutableStateOf("") }
    var updateMessage by remember { mutableStateOf("") }
    var updateUrl by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        checkForAppUpdate(context, sessionId) { title: String, message: String, url: String ->
            updateTitle = title
            updateMessage = message
            updateUrl = url
            showUpdateDialog = true
        }
    }

    val bg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF130C2D),
            Color(0xFF130C2D),
            Color(0xFF0D081F),
            Color(0xFF050507)
        )
    )

    val transition = rememberInfiniteTransition(label = "vpn")

    val pulse1 by transition.animateFloat(
        initialValue = 0.78f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse1"
    )

    val pulse2 by transition.animateFloat(
        initialValue = 0.84f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse2"
    )

    val ringSweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringSweep"
    )

    val switchOffsetY by animateDpAsState(
        targetValue = when (state) {
            State.OFF -> 44.dp
            State.CONNECTING -> 8.dp
            State.ON -> (-28).dp
        },
        animationSpec = tween(550, easing = FastOutSlowInEasing),
        label = "switchOffset"
    )

    val iconRotation = remember { Animatable(0f) }

    LaunchedEffect(sessionId) {
        getOrAssignConfigForUser(
            id = sessionId,
            onSuccess = { configText: String, confName: String ->
                wgConfig = sanitizeWireGuardConfig(configText)
                assignedConfName = confName

                if (wgConfig.isBlank()) {
                    vpnError = "La configuración asignada está vacía"
                    state = State.OFF
                } else {
                    vpnError = ""
                }
            },
            onError = { msg: String ->
                vpnError = msg
                state = State.OFF
            }
        )
    }

    LaunchedEffect(Unit) {
        while (true) {
            val vpnSavedState = loadVpnState(context)

            if (!vpnSavedState && state != State.OFF) {
                state = State.OFF
                realIp = "--.--.--.--"
                downRateText = "↓ 0 B/s"
                upRateText = "↑ 0 B/s"
                vpnError = ""
            }

            if (vpnSavedState && state == State.OFF && wgConfig.isNotBlank() && !planExpired) {
                state = State.ON
            }

            delay(1000)
        }
    }

    LaunchedEffect(state) {
        if (state == State.CONNECTING) {
            iconRotation.snapTo(0f)
            iconRotation.animateTo(
                targetValue = 360f,
                animationSpec = tween(900, easing = LinearEasing)
            )
        } else {
            iconRotation.snapTo(0f)
        }
    }

    LaunchedEffect(state) {
        if (state == State.ON) {
            if (realIp == "--.--.--.--") {
                realIp = fetchPublicIp()
            }

            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()

            while (state == State.ON) {
                delay(1000)

                val currentRx = TrafficStats.getTotalRxBytes()
                val currentTx = TrafficStats.getTotalTxBytes()

                val rxDiff = if (lastRx >= 0 && currentRx >= 0) {
                    (currentRx - lastRx).coerceAtLeast(0L)
                } else 0L

                val txDiff = if (lastTx >= 0 && currentTx >= 0) {
                    (currentTx - lastTx).coerceAtLeast(0L)
                } else 0L

                downRateText = "↓ ${formatSpeed(rxDiff)}"
                upRateText = "↑ ${formatSpeed(txDiff)}"

                fakeDownload = listOf(96, 110, 124, 138, 146, 152).random()
                fakeUpload = listOf(1980, 2140, 2364, 2488, 2520, 2675).random()

                lastRx = currentRx
                lastTx = currentTx
            }
        } else {
            downRateText = "↓ 0 B/s"
            upRateText = "↑ 0 B/s"
        }
    }

    LaunchedEffect(sessionId) {
        while (true) {
            validateActiveDeviceAndSubscription(
                id = sessionId,
                deviceFingerprint = deviceInfo.fingerprint,
                onResult = { valid: Boolean, end: Long, expired: Boolean ->
                    remoteBlocked = !valid
                    planExpired = expired

                    if (lastKnownEnd != null && lastKnownEnd != end) {
                        resetSubscriptionWarning(context)
                        cancelSubscriptionWarning(context)
                        scheduleSubscriptionWarning(context, end)
                    }

                    if (lastKnownEnd == null) {
                        scheduleSubscriptionWarning(context, end)
                    }

                    lastKnownEnd = end
                    subscriptionEnd = end

                    if (!valid) {
                        stopVpnService(context)
                        clearSessionId(context)
                        onForceLogout()
                    }

                    if (expired && state == State.ON) {
                        stopVpnService(context)
                        state = State.OFF
                        realIp = "--.--.--.--"
                        downRateText = "↓ 0 B/s"
                        upRateText = "↑ 0 B/s"
                    }
                }
            )
            delay(10000)
        }
    }

    val renewalText = if (subscriptionEnd > 0L) {
        "Renueva: ${formatDateTime(subscriptionEnd)}"
    } else {
        "Renueva: --/--/---- --:--"
    }

    val statusText = when {
        remoteBlocked -> "Blocked"
        planExpired -> "Plan Expired"
        state == State.OFF -> "Not Connected"
        state == State.CONNECTING -> "Connecting..."
        else -> "Connected"
    }

    val buttonText = when (state) {
        State.OFF -> "START"
        State.CONNECTING -> "WAIT"
        State.ON -> "STOP"
    }

    val hintText = when {
        remoteBlocked -> "Sesión transferida a otro dispositivo"
        planExpired -> "Su plan ha expirado"
        vpnError.isNotBlank() -> vpnError
        state == State.OFF -> "Tap to connect"
        state == State.CONNECTING -> "Establishing secure tunnel..."
        else -> "Tap to disconnect"
    }

    val statusColor = when {
        remoteBlocked -> Color(0xFFFF6B6B)
        planExpired -> Color(0xFFFFB36B)
        vpnError.isNotBlank() -> Color(0xFFFFB36B)
        state == State.ON -> Color(0xFFD7FFF0)
        state == State.CONNECTING -> Color(0xFFE8FFF8)
        else -> Color.White
    }

    val switchTopColor = when (state) {
        State.OFF -> Color(0xFF8F98B5)
        State.CONNECTING -> Color(0xFFB8FF8A)
        State.ON -> Color(0xFFB1FF9B)
    }

    val switchBottomColor = when (state) {
        State.OFF -> Color(0xFF5B6584)
        State.CONNECTING -> Color(0xFF57EFCB)
        State.ON -> Color(0xFF39DBA8)
    }

    val switchTextColor = when (state) {
        State.OFF -> Color.White
        State.CONNECTING, State.ON -> Color(0xFF06241B)
    }

    val powerIconRes = when (state) {
        State.ON -> R.drawable.power_on
        State.OFF, State.CONNECTING -> R.drawable.power_off
    }

    val locationText = if (assignedConfName.isNotBlank()) assignedConfName else "Location"

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(brush = bg)
    ) {
        Image(
            painter = painterResource(id = R.drawable.world_map),
            contentDescription = "World Map",
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .offset(y = 210.dp)
                .alpha(0.15f)
        )

        HeaderAmbientGlow()

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 24.dp, top = 60.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Shield VPN",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.width(8.dp))

            Image(
                painter = painterResource(id = R.drawable.vpn_icon),
                contentDescription = "Icon",
                modifier = Modifier.size(35.dp)
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 50.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = renewalText,
                color = Color(0xFF6BE6C1),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "ID: $sessionId",
                color = Color(0xFF8D98B6).copy(alpha = 0.75f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(160.dp))

            Text(
                text = statusText,
                color = statusColor,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            if (state == State.ON && !remoteBlocked && !planExpired) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = realIp,
                    color = Color(0xFF97A6C4),
                    fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = downRateText,
                        color = Color(0xFFAEBAD3),
                        fontSize = 12.sp
                    )
                    Text(
                        text = "   •   ",
                        color = Color(0xFF5F6B86),
                        fontSize = 12.sp
                    )
                    Text(
                        text = upRateText,
                        color = Color(0xFFAEBAD3),
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0x15FFFFFF))
                    .border(
                        width = 1.dp,
                        color = Color(0x28FFFFFF),
                        shape = RoundedCornerShape(40.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Text(
                    text = locationText,
                    color = Color(0xFFDCE2F0),
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Box(
                modifier = Modifier.size(320.dp),
                contentAlignment = Alignment.Center
            ) {
                when (state) {
                    State.OFF -> {
                        Canvas(modifier = Modifier.size(255.dp)) {
                            val center = Offset(size.width / 2, size.height / 2)

                            drawCircle(
                                color = Color(0x12FFFFFF),
                                radius = size.minDimension / 2.2f,
                                center = center
                            )

                            drawCircle(
                                color = Color(0x40AFC0D8),
                                radius = size.minDimension / 2.55f,
                                center = center,
                                style = Stroke(width = 4f)
                            )
                        }
                    }

                    State.CONNECTING -> {
                        Canvas(modifier = Modifier.size(290.dp)) {
                            val center = Offset(size.width / 2, size.height / 2)

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0x5539E5C1),
                                        Color(0x1E35D8B7),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = size.minDimension / 2
                                ),
                                radius = (size.minDimension / 2.25f) * pulse2,
                                center = center
                            )

                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFFB8FF8A),
                                        Color(0xFF4BF2C9),
                                        Color(0xFF31D9A7),
                                        Color(0xFFB8FF8A)
                                    ),
                                    center = center
                                ),
                                radius = (size.minDimension / 2.8f) * pulse1,
                                center = center,
                                style = Stroke(width = 10f, cap = StrokeCap.Round)
                            )

                            drawArc(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(0xAA9AFFC0),
                                        Color(0xFF4FF1CC),
                                        Color.Transparent
                                    ),
                                    center = center
                                ),
                                startAngle = ringSweep,
                                sweepAngle = 220f,
                                useCenter = false,
                                style = Stroke(width = 6f, cap = StrokeCap.Round)
                            )

                            drawCircle(
                                color = Color(0x8848F3C8),
                                radius = (size.minDimension / 2.45f) * pulse1,
                                center = center,
                                style = Stroke(width = 2f)
                            )
                        }
                    }

                    State.ON -> {
                        Canvas(modifier = Modifier.size(290.dp)) {
                            val center = Offset(size.width / 2, size.height / 2)

                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0x2A44EFC5),
                                        Color(0x1041D9BB),
                                        Color.Transparent
                                    ),
                                    center = center,
                                    radius = size.minDimension / 2
                                ),
                                radius = size.minDimension / 2.6f,
                                center = center
                            )

                            drawCircle(
                                brush = Brush.sweepGradient(
                                    colors = listOf(
                                        Color(0xFFAAFF97),
                                        Color(0xFF63F2CB),
                                        Color(0xFF39D9A8),
                                        Color(0xFFAAFF97)
                                    ),
                                    center = center
                                ),
                                radius = size.minDimension / 2.75f,
                                center = center,
                                style = Stroke(width = 9f, cap = StrokeCap.Round)
                            )

                            drawCircle(
                                color = Color(0x6647F0C8),
                                radius = size.minDimension / 2.42f,
                                center = center,
                                style = Stroke(width = 2f)
                            )
                        }
                    }
                }

                SwitchBaseRail()

                Box(
                    modifier = Modifier
                        .size(190.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFF1B1438),
                                    Color(0xFF0E0A20)
                                )
                            )
                        )
                )

                Box(
                    modifier = Modifier
                        .size(width = 108.dp, height = 184.dp)
                        .offset(y = switchOffsetY)
                        .clip(RoundedCornerShape(54.dp))
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    switchTopColor,
                                    switchBottomColor
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = if (state == State.OFF) Color(0x44FFFFFF) else Color(0x99FFFFFF),
                            shape = RoundedCornerShape(54.dp)
                        )
                        .clickable(enabled = !remoteBlocked && !planExpired) {
                            when {
                                planExpired -> {
                                    state = State.OFF
                                    stopVpnService(context)
                                }

                                state == State.OFF -> {
                                    if (wgConfig.isBlank()) {
                                        vpnError = "Sin configuración asignada"
                                        return@clickable
                                    }

                                    vpnError = ""
                                    state = State.CONNECTING

                                    val startIntent = Intent(context, VpnForegroundService::class.java).apply {
                                        action = VpnForegroundService.ACTION_START_VPN_SERVICE
                                        putExtra(VpnForegroundService.EXTRA_WG_CONFIG, wgConfig)
                                        putExtra(VpnForegroundService.EXTRA_LOCATION, locationText)
                                    }

                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        context.startForegroundService(startIntent)
                                    } else {
                                        context.startService(startIntent)
                                    }

                                    scope.launch(Dispatchers.IO) {
                                        delay(1500)
                                        val ip = fetchPublicIp()

                                        withContext(Dispatchers.Main) {
                                            realIp = ip
                                            state = State.ON
                                            saveVpnState(context, true)
                                        }
                                    }
                                }

                                state == State.CONNECTING -> {}

                                state == State.ON -> {
                                    stopVpnService(context)
                                    state = State.OFF
                                    realIp = "--.--.--.--"
                                    downRateText = "↓ 0 B/s"
                                    upRateText = "↑ 0 B/s"
                                    saveVpnState(context, false)
                                }
                            }
                        }
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 24.dp, height = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    when (state) {
                                        State.OFF -> Color(0x22000000)
                                        State.CONNECTING, State.ON -> Color(0xFF0B6D58)
                                    }
                                )
                        )

                        Text(
                            text = buttonText,
                            color = switchTextColor,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Box(
                            modifier = Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state == State.OFF) Color(0x22FFFFFF) else Color(0x33000000)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = powerIconRes),
                                contentDescription = "Power Icon",
                                modifier = Modifier
                                    .size(30.dp)
                                    .rotate(iconRotation.value)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(width = 24.dp, height = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.Transparent)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(34.dp))

            Text(
                text = hintText,
                color = Color(0xFF8D98B6),
                fontSize = 13.sp
            )
        }

        if (showUpdateDialog) {
            Dialog(onDismissRequest = { }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFF130C2D),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(20.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = updateTitle,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = updateMessage,
                            color = Color.Gray,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                                context.startActivity(intent)
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6BE6C1)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "Actualizar",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = "Más tarde",
                            color = Color.Gray,
                            modifier = Modifier.clickable {
                                showUpdateDialog = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeaderAmbientGlow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xAA080612),
                            Color(0x660C0918),
                            Color.Transparent
                        )
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x0F6BFFC8),
                            Color(0x086BFFC8),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@Composable
fun SwitchBaseRail() {
    Box(
        modifier = Modifier
            .size(width = 118.dp, height = 235.dp)
            .clip(RoundedCornerShape(60.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0F0A22),
                        Color(0xFF140E2A),
                        Color(0xFF09060F)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color(0x20FFFFFF),
                shape = RoundedCornerShape(60.dp)
            )
    )
}

fun validateLogin(
    id: String,
    accessKey: String,
    deviceInfo: DeviceInfo,
    onSuccess: () -> Unit,
    onError: (String) -> Unit
) {
    FirebaseDatabase.getInstance()
        .getReference("Id")
        .child(id)
        .get()
        .addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) {
                onError("ID no encontrado")
                return@addOnSuccessListener
            }

            val dbKey = snapshot.child("clave").getValue(String::class.java).orEmpty()
            val active = snapshot.child("activo").getValue(Boolean::class.java) ?: true

            if (!active) {
                onError("Acceso desactivado")
                return@addOnSuccessListener
            }

            if (dbKey != accessKey) {
                onError("Clave incorrecta")
                return@addOnSuccessListener
            }

            val now = System.currentTimeMillis()
            val currentStart = snapshot.child("subscriptionStart").getValue(Long::class.java) ?: 0L
            val currentEnd = snapshot.child("subscriptionEnd").getValue(Long::class.java) ?: 0L

            val updates = hashMapOf<String, Any>(
                "deviceFingerprint" to deviceInfo.fingerprint,
                "deviceName" to deviceInfo.deviceName,
                "lastLogin" to ServerValue.TIMESTAMP,
                "status" to "active"
            )

            if (currentStart <= 0L || currentEnd <= 0L) {
                updates["subscriptionStart"] = now
                updates["subscriptionEnd"] = now + THIRTY_DAYS_MS
            }

            FirebaseDatabase.getInstance()
                .getReference("Id")
                .child(id)
                .updateChildren(updates)
                .addOnSuccessListener { onSuccess() }
                .addOnFailureListener { e ->
                    onError("Error al actualizar el dispositivo: ${e.message}")
                }
        }
        .addOnFailureListener { e ->
            onError("Error al validar: ${e.message}")
        }
}

fun getOrAssignConfigForUser(
    id: String,
    onSuccess: (configText: String, confName: String) -> Unit,
    onError: (String) -> Unit
) {
    val userRef = FirebaseDatabase.getInstance()
        .getReference("Id")
        .child(id)

    userRef.get()
        .addOnSuccessListener { userSnapshot ->
            val existingConfig = sanitizeWireGuardConfig(
                userSnapshot.child("wgConfig").getValue(String::class.java).orEmpty()
            )
            val existingName = userSnapshot.child("assignedConfName").getValue(String::class.java).orEmpty()
            val existingKey = userSnapshot.child("assignedConfKey").getValue(String::class.java).orEmpty()

            if (existingConfig.isNotBlank() && existingKey.isNotBlank()) {
                onSuccess(existingConfig, existingName)
                return@addOnSuccessListener
            }

            val availableRef = FirebaseDatabase.getInstance()
                .getReference("Conf Disponibles")

            availableRef.get()
                .addOnSuccessListener { confSnapshot ->
                    if (!confSnapshot.exists() || confSnapshot.childrenCount == 0L) {
                        onError("No hay configuraciones disponibles")
                        return@addOnSuccessListener
                    }

                    val firstConf = confSnapshot.children.firstOrNull()
                    if (firstConf == null) {
                        onError("No se pudo leer una configuración disponible")
                        return@addOnSuccessListener
                    }

                    val confKey = firstConf.key.orEmpty()
                    val confName = firstConf.child("name").getValue(String::class.java).orEmpty()
                    val confText = sanitizeWireGuardConfig(
                        firstConf.child("config").getValue(String::class.java).orEmpty()
                    )

                    if (confKey.isBlank() || confText.isBlank()) {
                        onError("La configuración disponible está vacía o incompleta")
                        return@addOnSuccessListener
                    }

                    val updates = hashMapOf<String, Any?>(
                        "/Id/$id/assignedConfKey" to confKey,
                        "/Id/$id/assignedConfName" to confName,
                        "/Id/$id/wgConfig" to confText,
                        "/Conf Disponibles/$confKey" to null
                    )

                    FirebaseDatabase.getInstance()
                        .reference
                        .updateChildren(updates)
                        .addOnSuccessListener {
                            onSuccess(confText, confName)
                        }
                        .addOnFailureListener { e ->
                            onError("Fallo moviendo la .conf: ${e.message}")
                        }
                }
                .addOnFailureListener { e ->
                    onError("Fallo leyendo Conf Disponibles: ${e.message}")
                }
        }
        .addOnFailureListener { e ->
            onError("Fallo leyendo el usuario: ${e.message}")
        }
}

fun validateActiveDeviceAndSubscription(
    id: String,
    deviceFingerprint: String,
    onResult: (valid: Boolean, subscriptionEnd: Long, expired: Boolean) -> Unit
) {
    FirebaseDatabase.getInstance()
        .getReference("Id")
        .child(id)
        .get()
        .addOnSuccessListener { snapshot: DataSnapshot ->
            val active = snapshot.child("activo").getValue(Boolean::class.java) ?: true
            val savedFingerprint = snapshot.child("deviceFingerprint").getValue(String::class.java).orEmpty()
            val subscriptionEnd = snapshot.child("subscriptionEnd").getValue(Long::class.java) ?: 0L
            val expired = subscriptionEnd > 0L && System.currentTimeMillis() > subscriptionEnd

            val validDevice = active &&
                    savedFingerprint.isNotBlank() &&
                    savedFingerprint == deviceFingerprint

            onResult(validDevice, subscriptionEnd, expired)
        }
        .addOnFailureListener {
            onResult(true, 0L, false)
        }
}

fun buildDeviceInfo(context: Context): DeviceInfo {
    val androidId = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    ).orEmpty()

    val raw = "$androidId|${Build.BRAND}|${Build.MODEL}|${Build.DEVICE}"
    val fingerprint = sha256(raw).take(32)
    val deviceName = "${Build.BRAND} ${Build.MODEL}".trim()

    return DeviceInfo(
        fingerprint = fingerprint,
        deviceName = deviceName
    )
}

fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return bytes.joinToString("") { "%02x".format(it) }
}

fun saveSessionId(context: Context, id: String) {
    context.getSharedPreferences("shield_vpn_session", Context.MODE_PRIVATE)
        .edit()
        .putString("session_id", id)
        .apply()
}

fun loadSavedSessionId(context: Context): String? {
    return context.getSharedPreferences("shield_vpn_session", Context.MODE_PRIVATE)
        .getString("session_id", null)
}

fun clearSessionId(context: Context) {
    context.getSharedPreferences("shield_vpn_session", Context.MODE_PRIVATE)
        .edit()
        .remove("session_id")
        .apply()
}

fun saveVpnState(context: Context, isOn: Boolean) {
    context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
        .edit()
        .putBoolean("is_on", isOn)
        .apply()
}

fun loadVpnState(context: Context): Boolean {
    return context.getSharedPreferences("vpn_state", Context.MODE_PRIVATE)
        .getBoolean("is_on", false)
}

fun formatDateTime(timestamp: Long): String {
    return try {
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        sdf.format(Date(timestamp))
    } catch (_: Exception) {
        "--/--/---- --:--"
    }
}

fun sanitizeWireGuardConfig(raw: String): String {
    var config = raw.trim()

    config = config
        .removePrefix("\"")
        .removeSuffix("\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")

    config = config
        .replace("[Interface]", "\n[Interface]\n")
        .replace("[Peer]", "\n[Peer]\n")

    val keys = listOf(
        "Address",
        "PrivateKey",
        "DNS",
        "MTU",
        "PublicKey",
        "AllowedIPs",
        "Endpoint",
        "PersistentKeepalive"
    )

    keys.forEach { key ->
        config = config.replace(Regex("""\s+$key\s*="""), "\n$key =")
    }

    config = config
        .replace(Regex("""\n{2,}"""), "\n")
        .trim()

    return config
}

suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
    try {
        val url = URL(PUBLIC_IP_URL)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }

        connection.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().ifBlank { "--.--.--.--" }
        }
    } catch (e: Exception) {
        Log.e("PUBLIC_IP", "No se pudo obtener IP pública: ${e.message}", e)
        "--.--.--.--"
    }
}

fun formatSpeed(bytesPerSecond: Long): String {
    val bps = bytesPerSecond.coerceAtLeast(0L).toDouble()
    return when {
        bps >= 1024 * 1024 -> String.format(Locale.US, "%.2f Mb/s", bps / (1024 * 1024))
        bps >= 1024 -> String.format(Locale.US, "%.0f Kb/s", bps / 1024)
        else -> String.format(Locale.US, "%.0f B/s", bps)
    }
}

fun stopVpnService(context: Context) {
    val stopIntent = Intent(context, VpnForegroundService::class.java).apply {
        action = VpnForegroundService.ACTION_STOP_VPN_SERVICE
    }
    context.startService(stopIntent)
    saveVpnState(context, false)
}

fun createVpnNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = android.app.NotificationChannel(
            VPN_CHANNEL_ID,
            "Shield VPN",
            android.app.NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notificación de conexión VPN"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}

fun updateUserAppStatus(
    context: Context,
    sessionId: String
) {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)

    val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode.toInt()
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode
    }

    val versionName = packageInfo.versionName ?: "1.0"

    FirebaseDatabase.getInstance()
        .getReference("Id")
        .child(sessionId)
        .updateChildren(
            mapOf(
                "installedVersionCode" to versionCode,
                "installedVersionName" to versionName,
                "appStatus" to "estable"
            )
        )
}

fun checkForAppUpdate(
    context: Context,
    sessionId: String,
    onUpdateAvailable: (title: String, message: String, url: String) -> Unit
) {
    val ref = FirebaseDatabase.getInstance().getReference("AppUpdate")

    ref.get().addOnSuccessListener { snapshot ->
        val active = snapshot.child("active").getValue(Boolean::class.java) ?: false
        if (!active) return@addOnSuccessListener

        val latestVersion = snapshot.child("latestVersionCode").getValue(Int::class.java) ?: 1
        val title = snapshot.child("title").getValue(String::class.java) ?: "Actualización"
        val message = snapshot.child("message").getValue(String::class.java)
            ?: "Nueva versión disponible."
        val url = snapshot.child("downloadUrl").getValue(String::class.java).orEmpty()

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val currentVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }

        if (currentVersion < latestVersion) {
            FirebaseDatabase.getInstance()
                .getReference("Id")
                .child(sessionId)
                .child("appStatus")
                .setValue("desactualizada")

            if (url.isNotBlank()) {
                onUpdateAvailable(title, message, url)
            }
        } else {
            FirebaseDatabase.getInstance()
                .getReference("Id")
                .child(sessionId)
                .child("appStatus")
                .setValue("estable")
        }
    }
}

fun scheduleSubscriptionWarning(context: Context, subscriptionEnd: Long) {
    if (subscriptionEnd <= 0L) return

    val now = System.currentTimeMillis()
    val triggerAt = subscriptionEnd - (24L * 60L * 60L * 1000L)
    val delayMs = (triggerAt - now).coerceAtLeast(0L)

    val workRequest = OneTimeWorkRequestBuilder<SubscriptionWarningWorker>()
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .setInputData(
            workDataOf(
                SubscriptionWarningWorker.KEY_SUBSCRIPTION_END to subscriptionEnd
            )
        )
        .addTag("subscription_warning_work")
        .build()

    WorkManager.getInstance(context)
        .enqueueUniqueWork(
            "subscription_warning_work",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
}

fun cancelSubscriptionWarning(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork("subscription_warning_work")
}

fun resetSubscriptionWarning(context: Context) {
    context.getSharedPreferences("shield_vpn_alerts", Context.MODE_PRIVATE)
        .edit()
        .remove("last_expiration_warning_timestamp")
        .apply()
}

const val THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L
const val VPN_CHANNEL_ID = "shield_vpn_channel"
const val VPN_NOTIFICATION_ID = 1001
const val SUBSCRIPTION_WARNING_NOTIFICATION_ID = 1002
const val PUBLIC_IP_URL = "https://api.ipify.org"
