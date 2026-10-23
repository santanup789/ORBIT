package app.orbit.finder.online

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.finder.*
import app.orbit.finder.core.Presence
import kotlin.math.roundToInt

internal fun turnInstruction(angle: Double): String {
    val degrees = GeoMath.normalize(angle)
    return when {
        degrees <= 15 || degrees >= 345 -> "Straight ahead"
        degrees < 165 -> "Turn right ${degrees.roundToInt()}°"
        degrees <= 195 -> "Behind you"
        else -> "Turn left ${(360 - degrees).roundToInt()}°"
    }
}

@Composable fun GpsScreen(s: OrbitState, vm: OrbitViewModel) {
    val friend = s.friends.firstOrNull { it.id == s.selectedId }
    val gps = s.gps
    val linked = !friend?.firebaseUid.isNullOrBlank()
    var confirm by remember { mutableStateOf(false) }
    var useMap by remember { mutableStateOf(false) }
    var showMap by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) { permissionMessage = ""; showMap = useMap; vm.startGps(useMap) }
        else permissionMessage = "Allow precise Location in Android settings, then try again."
    }
    val guide = if (gps.connected && gps.mutual) gps.own?.let { a -> gps.peer?.let { b -> GeoMath.guide(a, b, gps.now) } } else null
    val headingFresh = gps.heading != null && gps.now - gps.headingTime in 0..3000
    val arrow = if (guide?.directionReliable == true && headingFresh) GeoMath.normalize(guide.bearing - gps.heading!!) else null
    val overlap = guide != null && guide.metres <= guide.uncertainty
    OrbitBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitBrand(back = { vm.stopGps(); vm.navigate(Screen.FINDING) })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                OrbitPerson(friend?.name ?: "Your friend", if (gps.active && gps.connected && gps.mutual) "Sharing securely · online" else if (gps.active) "Connecting your locations" else "Find each other outdoors", gps.active && gps.connected && gps.mutual, Modifier.padding(top = 8.dp, bottom = 18.dp))
                OrbitModeTabs(true, { vm.stopGps(); vm.navigate(Screen.FINDING) }, {})
                if (gps.active) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!showMap, { showMap = false }, { Text("Direction") }, leadingIcon = { Icon(Icons.Outlined.Navigation, null, Modifier.size(16.dp)) })
                        FilterChip(showMap, { showMap = true }, { Text("Map") }, leadingIcon = { Icon(Icons.Outlined.Map, null, Modifier.size(16.dp)) })
                    }
                }
                if (gps.active && showMap) {
                    if (s.mapEnabled && gps.own?.fresh(gps.now) == true) {
                        Spacer(Modifier.height(10.dp))
                        OrbitMap(gps.own!!, gps.peer?.takeIf { it.fresh(gps.now) && gps.mutual && gps.connected }, gps.connected)
                    } else OrbitPanel(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                        Icon(Icons.Outlined.Map, null, tint = OrbitStyle.Cyan, modifier = Modifier.size(32.dp))
                        Text(if (!s.mapEnabled) "Map is off for this session" else "Waiting for a fresh location", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                        Text(if (!s.mapEnabled) "Stop and start again with map tiles enabled. Direction still works when fixes are reliable." else "Move outdoors with a clear view of the sky. Your map appears when a fresh fix arrives.", color = OrbitStyle.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                } else {
                    OrbitRadar(Modifier.padding(top = 15.dp).widthIn(max = if (gps.active) 300.dp else 200.dp).fillMaxWidth(), s.reducedMotion, direction = arrow?.toFloat(), searching = gps.active)
                }
                Text(when {
                    gps.retryable -> "Connection problem"
                    !gps.active -> if (linked) "Ready to find" else "Setting up GPS"
                    overlap -> "Too close to tell"
                    guide != null -> if (s.metric) "≈ ${guide.metres.roundToInt()} m" else "≈ ${(guide.metres * 3.28084).roundToInt()} ft"
                    else -> "Waiting for a fix"
                }, color = if (guide != null && !overlap) OrbitStyle.Cyan else Color.White, fontSize = if (guide != null && !overlap) 54.sp else 30.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Text(when {
                    arrow != null -> turnInstruction(arrow)
                    overlap -> "Your GPS accuracy areas overlap"
                    !gps.active -> "Distance, direction & a shared map"
                    !gps.connected -> "Internet needed for live GPS"
                    !gps.mutual -> "Ask your friend to start GPS too"
                    else -> "Direction is not reliable yet"
                }, color = if (arrow != null) OrbitStyle.Cyan else OrbitStyle.Muted, fontSize = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
                if (!gps.active) OrbitPanel(Modifier.fillMaxWidth()) {
                    if (gps.retryable) Text(gps.status, color = OrbitStyle.Cyan, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
                    Text(if (linked) "A shared start. A private connection." else "No QR scan needed", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(if (linked) "Both of you tap Start GPS and keep ORBIT open. Use this outdoors; Bluetooth is better for nearby indoor signals." else "Keep both updated phones nearby with ORBIT open and internet on. Your existing pairing securely sets up GPS once; after that you can find each other beyond Bluetooth range.", color = OrbitStyle.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp))
                    if (!linked) {
                        Text(s.onlineMessage, color = OrbitStyle.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        TextButton({ vm.retryGpsSetup() }, enabled = !s.onlinePreparing && s.pairingPending == null) { Text(if (s.onlinePreparing || s.pairingPending != null) "Setting up…" else "Retry setup") }
                    }
                } else {
                    OrbitPanel(Modifier.fillMaxWidth()) {
                        Text(gps.status, color = OrbitStyle.Mint, fontSize = 13.sp)
                        if (guide != null) Text("Combined reported accuracy ± ${guide.uncertainty.roundToInt()} m", color = OrbitStyle.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(if (arrow != null) "Hold your phone flat, top edge forward. This is a straight-line bearing, not a walking route." else if (overlap) "GPS can’t distinguish your positions accurately. Use Bluetooth proximity and look around." else "Use fresh outdoor fixes and hold your phone flat. The arrow stays hidden until it can be trusted.", color = OrbitStyle.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        TextButton({ details = !details }, contentPadding = PaddingValues(0.dp)) { Text(if (details) "Hide signal details" else "Signal details", fontSize = 12.sp) }
                        if (details) {
                            gps.own?.let { Text("You: ${it.provider} · ±${it.accuracy.roundToInt()} m · ${((gps.now-it.time).coerceAtLeast(0)/1000)}s ago", color = OrbitStyle.Muted, fontSize = 12.sp) }
                            gps.peer?.let { Text("Friend: ±${it.accuracy.roundToInt()} m · ${((gps.now-it.time).coerceAtLeast(0)/1000)}s ago", color = OrbitStyle.Muted, fontSize = 12.sp) }
                            Text(if (friend?.presence == Presence.NEARBY) "Bluetooth: nearby" else "Bluetooth: searching", color = OrbitStyle.Muted, fontSize = 12.sp)
                        }
                    }
                }
                if (permissionMessage.isNotBlank()) Text(permissionMessage, color = OrbitStyle.Cyan, modifier = Modifier.padding(12.dp))
                Text("Sharing stops when you leave ORBIT or lock your phone.", color = OrbitStyle.Muted, textAlign = TextAlign.Center, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
            }
            if (gps.active && gps.retryable) OrbitAction("Retry", Icons.Outlined.Refresh, Modifier.fillMaxWidth()) {
                vm.stopGps(); confirm = true
            }
            OrbitAction(if (gps.active) "Stop GPS sharing" else if (gps.retryable) "Retry" else "Start GPS", if (gps.active) Icons.Outlined.Stop else if (gps.retryable) Icons.Outlined.Refresh else Icons.Outlined.NearMe,
                Modifier.fillMaxWidth().padding(vertical = 12.dp), primary = !gps.active, enabled = gps.active || linked) {
                if (gps.active) vm.stopGps() else confirm = true
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Share with ${friend?.name}?") },
        text = { Column {
            Text("Both phones must start. Your live coordinates are encrypted and sent through Firebase. Sharing stops when you leave ORBIT, lock the phone, or after 15 minutes.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(useMap, { useMap = it })
                Text("Show the map. OpenStreetMap receives requests for the area you view.", fontSize = 13.sp)
            }
        } }, confirmButton = { TextButton({ confirm = false; launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Share while open") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Not now") } })
}
