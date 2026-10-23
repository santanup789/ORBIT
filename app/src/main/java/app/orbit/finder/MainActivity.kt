package app.orbit.finder

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.orbit.finder.core.*
import app.orbit.finder.qr.QrScanner
import app.orbit.finder.qr.qrBitmap
import app.orbit.finder.online.GpsScreen
import java.text.DateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

private val Midnight = Color(0xFF070B1A)
private val Panel = Color(0xFF10172B)
private val Cyan = Color(0xFF57E6FF)
private val Violet = Color(0xFF8B7CFF)
private val Muted = Color(0xFF9AA8C7)

class MainActivity : ComponentActivity() {
    private val vm: OrbitViewModel get() = (application as OrbitApplication).model
    override fun onResume() {
        super.onResume()
        if (vm.state.value.screen != Screen.ONBOARDING) vm.refreshNearby()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { OrbitTheme { OrbitApp(vm) } }
    }
    override fun onStop() { vm.foregroundEnded(); super.onStop() }
}

@Composable private fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(background = Midnight, surface = Panel, primary = Cyan, secondary = Violet, onBackground = Color.White, onSurface = Color.White), content = content)
}

@Composable private fun OrbitApp(vm: OrbitViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = s.screen != Screen.HOME && s.screen != Screen.ONBOARDING) {
        when (s.screen) {
            Screen.GPS -> { vm.stopGps(); vm.navigate(Screen.FINDING) }
            Screen.FINDING -> vm.stopFinding()
            Screen.DIAGNOSTICS -> vm.navigate(Screen.SETTINGS)
            else -> vm.navigate(Screen.HOME)
        }
    }
    val activity = androidx.activity.compose.LocalActivity.current
    val keepAwake = s.screen in setOf(Screen.GPS, Screen.FINDING, Screen.MY_QR, Screen.SCAN_QR)
    DisposableEffect(activity, keepAwake) {
        if (keepAwake) activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val nearbyPermissions = remember { vm.requiredPermissions() }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (!it) vm.setPairingStatus("Notifications disabled. Pairing requests still appear inside ORBIT.") }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.all { v -> v }) {
            vm.refreshNearby()
            if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.setStatus("Allow Nearby devices and precise Location in App settings. GPS coordinates are collected only during a GPS sharing session.")
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.navigate(Screen.SCAN_QR) else vm.setStatus("Camera permission denied") }
    LaunchedEffect(s.screen) { if (s.screen == Screen.HOME || s.screen == Screen.FINDING) nearbyLauncher.launch(nearbyPermissions) }
    Surface(Modifier.fillMaxSize(), color = Midnight) {
        when (s.screen) {
            Screen.ONBOARDING -> Onboarding(s, vm)
            Screen.HOME -> OrbitHome(s, vm, { nearbyLauncher.launch(nearbyPermissions) }, { cameraLauncher.launch(Manifest.permission.CAMERA) })
            Screen.MY_QR -> OrbitQr(s, vm)
            Screen.SCAN_QR -> Scanner(s, vm)
            Screen.FINDING -> OrbitFinding(s, vm)
            Screen.GPS -> GpsScreen(s, vm)
            Screen.SETTINGS -> Settings(s, vm)
            Screen.DIAGNOSTICS -> Diagnostics(s, vm)
        }
    }
    s.requests.firstOrNull()?.let { request ->
        AlertDialog(onDismissRequest = {}, title = { Text("Pairing request") },
            text = { Text("${request.peer.name} wants to connect. Accept only if you expect this request. Names alone are not verified.\n\nCompare this identity with the sender’s pending-request screen:\n${request.peer.id.chunked(4).joinToString(" ")}\n\nAccept saves this friend; it does not share your location.") },
            confirmButton = { TextButton({ vm.decidePairing(request.connection, true) }) { Text("Accept") } },
            dismissButton = { TextButton({ vm.decidePairing(request.connection, false) }) { Text("Decline") } })
    }
    s.pendingGpsLink?.let { peer ->
        AlertDialog(onDismissRequest = { vm.acceptGpsLink(false) }, title = { Text("Link GPS with ${peer.name}?") },
            text = { Text("This signed QR belongs to your saved friend. Update their online identity? This does not start location sharing. QR linking is optional; nearby friends set up GPS automatically.") },
            confirmButton = { TextButton({ vm.acceptGpsLink(true) }) { Text("Save GPS link") } },
            dismissButton = { TextButton({ vm.acceptGpsLink(false) }) { Text("Cancel") } })
    }
}

@Composable private fun AppHeader(title: String, subtitle: String? = null, back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) Text("‹", fontSize = 38.sp, color = Cyan, modifier = Modifier.clickable(onClick = back).padding(end = 14.dp).semantics { contentDescription = "Back" })
        Column { Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp); if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp) }
    }
}

@Composable private fun Onboarding(s: OrbitState, vm: OrbitViewModel) {
    var name by remember { mutableStateOf(s.profileName) }; var icon by remember { mutableStateOf(s.profileIcon) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); focusManager.clearFocus(force = true); keyboard?.hide() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp)); RadarLogo(); Spacer(Modifier.height(24.dp))
        Text("ORBIT", fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp)
        Text("Bluetooth nearby. Optional GPS over internet.", color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp)); OutlinedTextField(name, { name = it.take(40) }, label = { Text("Your display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp)); Text("Choose your signal", color = Muted)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { DeviceIcon.entries.forEach { i -> IconChoice(i, i == icon) { icon = i } } }
        Spacer(Modifier.height(28.dp)); InfoCard("PRIVATE BY DESIGN", "Bluetooth pairing stays nearby. Optional GPS uses encrypted Firebase sharing after both people opt in.")
        Spacer(Modifier.height(12.dp)); InfoCard("FOREGROUND FIRST", "Both people keep ORBIT open. Bluetooth finds nearby devices; precise distance and direction appear only when supported by both phones.")
        Spacer(Modifier.height(24.dp)); Button({ vm.completeOnboarding(name, icon) }, Modifier.fillMaxWidth().height(54.dp)) { Text("ENTER ORBIT") }
    }
}

@Composable private fun IconChoice(icon: DeviceIcon, selected: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(62.dp).background(if (selected) Cyan.copy(.16f) else Panel, CircleShape).border(1.dp, if (selected) Cyan else Color(0xFF26324F), CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Text(icon.symbol, color = if (selected) Cyan else Muted, fontSize = 28.sp) }
}


@Composable private fun Scanner(s: OrbitState, vm: OrbitViewModel) {
    Box(Modifier.fillMaxSize().background(Midnight)) {
        QrScanner(Modifier.fillMaxSize(), { vm.acceptQr(it) }, { vm.setStatus(it) })
        Box(Modifier.align(Alignment.Center).size(270.dp).border(2.dp, Cyan, RoundedCornerShape(28.dp)))
        Column(Modifier.fillMaxWidth().statusBarsPadding().background(Midnight.copy(.8f))) { AppHeader("SCAN QR", "Align an ORBIT code inside the frame") { vm.navigate(Screen.HOME) } }
        if (s.status != "Ready") Text(s.status, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp).background(Midnight.copy(.9f), RoundedCornerShape(12.dp)).padding(14.dp), color = Cyan)
    }
}


@Composable private fun Settings(s: OrbitState, vm: OrbitViewModel) {
    var editName by remember { mutableStateOf(false) }
    var name by remember(s.profileName) { mutableStateOf(s.profileName) }
    if (editName) AlertDialog(
        onDismissRequest = { editName = false }, title = { Text("Device name") },
        text = { Column {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name in ORBIT") }, singleLine = true,
                supportingText = { Text("${name.length}/40") })
            TextButton({ vm.updateDeviceName(null); editName = false }) { Text("Use default: ${s.defaultDeviceName}") }
            Text("Used in new pairing requests and QR codes. Friends’ saved names are not changed automatically.", color = Muted, fontSize = 12.sp)
        } },
        confirmButton = { TextButton({ vm.updateDeviceName(name); editName = false }, enabled = name.trim().isNotEmpty() && name.none { it.isISOControl() }) { Text("Save") } },
        dismissButton = { TextButton({ editName = false }) { Text("Cancel") } }
    )
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        AppHeader("SETTINGS") { vm.navigate(Screen.HOME) }
        Text("ORBIT ${BuildConfig.VERSION_NAME}", color = Muted, modifier = Modifier.padding(horizontal = 20.dp))
        Card(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().clickable { name = s.profileName; editName = true }) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Device name", fontWeight = FontWeight.Bold)
                    Text(s.profileName, color = Cyan)
                    Text(if (s.usesDefaultName) "Default device name" else "Custom name", color = Muted, fontSize = 12.sp)
                }
                TextButton({ name = s.profileName; editName = true }) { Text("Edit") }
            }
        }
        OutlinedButton({ vm.stopNearby() }, Modifier.padding(horizontal = 20.dp)) { Text("STOP NEARBY SESSION") }
        SettingSwitch("Distance units", if (s.metric) "Metres" else "Feet", s.metric) { vm.setMetric(it) }
        SettingSwitch("Haptic guidance", "Only for trustworthy measured distance", s.haptics) { vm.setHaptics(it) }
        OutlinedButton({ vm.previewHaptic() }, Modifier.padding(horizontal = 20.dp)) { Text("HAPTIC PREVIEW") }
        SettingSwitch("Reduced motion", "Slows decorative scanning animation", s.reducedMotion) { vm.setReducedMotion(it) }
        Card(Modifier.padding(20.dp).fillMaxWidth().clickable { vm.navigate(Screen.DIAGNOSTICS) }) { Row(Modifier.padding(18.dp)) { Column(Modifier.weight(1f)) { Text("Device diagnostics", fontWeight = FontWeight.Bold); Text("Radio APIs, ranging capability and errors", color = Muted, fontSize = 12.sp) }; Text("›", color = Cyan, fontSize = 26.sp) } }
    }
}
@Composable private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, set: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 12.sp) }; Switch(checked, set) } }

@Composable private fun Diagnostics(s: OrbitState, vm: OrbitViewModel) {
    val c = s.capabilities
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AppHeader("DIAGNOSTICS", "Runtime capability report") { vm.navigate(Screen.SETTINGS) }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Card(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Firebase connection", fontWeight = FontWeight.Bold)
                    Text(s.firebaseStatus, color = Muted, fontSize = 12.sp)
                    OutlinedButton(onClick = { vm.checkFirebase() }, enabled = !s.firebaseChecking) {
                        Text(if (s.firebaseChecking) "CHECKING…" else "CHECK FIREBASE CONNECTION")
                    }
                    Text("Diagnostic only. Does not share your location or friends.", color = Muted, fontSize = 12.sp)
                }
            }
            Diagnostic("Bluetooth LE", c.ble, if (c.bleAdvertising) "Scan + advertise" else "Scanning only")
            Diagnostic("Camera / offline QR", c.camera, "Bundled decoder")
            Diagnostic("UWB hardware", c.uwbHardware, "Runtime available: ${c.uwbAvailable ?: "not exposed"}")
            Diagnostic("UWB distance", c.uwbDistance == true, c.uwbDistance?.toString() ?: "Unavailable")
            Diagnostic("UWB azimuth", c.uwbAzimuth == true, c.uwbAzimuth?.toString() ?: "Unavailable")
            Diagnostic("UWB elevation", c.uwbElevation == true, c.uwbElevation?.toString() ?: "Unavailable")
            Diagnostic("Wi‑Fi Aware", c.wifiAware, "Peer transport")
            Diagnostic("Wi‑Fi RTT", c.wifiRtt, "Distance only")
            Diagnostic("Android 16 Ranging API", c.androidRangingApi, if (c.androidRangingApi) "Adapter eligible" else "Requires Android 16")
            Diagnostic("Bluetooth Channel Sounding", c.bluetoothChannelSounding == true, when (c.bluetoothChannelSounding) { true -> "Measured distance eligible"; false -> "Not supported on this phone"; null -> "Capability not reported" })
            Diagnostic("Android BLE RSSI ranging", c.bluetoothRssiRanging == true, when (c.bluetoothRssiRanging) { true -> "Approximate proximity only"; false -> "Not supported"; null -> "Capability not reported" })
            Diagnostic("Vibrator", c.vibrator, if (c.amplitudeControl) "Amplitude control" else "Basic effects")
            c.notes.forEach { Text(it, color = Color(0xFFFFBA7A), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(20.dp)); InfoCard("SESSION STATUS", "No active precise-ranging session. Session keys and radio parameters must be exchanged fresh with an authenticated paired phone.")
        }
    }
}
@Composable private fun Diagnostic(name: String, yes: Boolean, detail: String) { Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(if (yes) Cyan else Color(0xFF65708C), CircleShape)); Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(name); Text(detail, color = Muted, fontSize = 12.sp) }; Text(if (yes) "SUPPORTED" else "NO", color = if (yes) Cyan else Muted, fontSize = 11.sp) } }
@Composable private fun InfoCard(title: String, body: String) { Card(Modifier.padding(horizontal = 20.dp).fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel)) { Column(Modifier.padding(18.dp)) { Text(title, color = Cyan, fontSize = 11.sp, letterSpacing = 1.5.sp); Spacer(Modifier.height(7.dp)); Text(body, color = Muted, fontSize = 13.sp, lineHeight = 19.sp) } } }
@Composable private fun RadarLogo() { Canvas(Modifier.size(120.dp)) { val c=center; drawCircle(Cyan.copy(.12f), size.minDimension/2); drawCircle(Cyan, size.minDimension*.34f, style=Stroke(2.dp.toPx())); drawCircle(Violet, 8.dp.toPx(), Offset(c.x+26.dp.toPx(),c.y-17.dp.toPx())); drawCircle(Cyan, 5.dp.toPx(), c) } }
private fun signalLabel(rssi: Int) = when { rssi >= -55 -> "VERY CLOSE SIGNAL"; rssi >= -67 -> "NEAR SIGNAL"; rssi >= -78 -> "MODERATE SIGNAL"; else -> "WEAK SIGNAL" }
