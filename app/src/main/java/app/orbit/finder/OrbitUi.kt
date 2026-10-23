package app.orbit.finder

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orbit.finder.core.*
import app.orbit.finder.qr.qrBitmap
import kotlin.math.cos
import kotlin.math.sin

object OrbitStyle {
    val Night = Color(0xFF030D1E)
    val Surface = Color(0xFF0B1931)
    val Cyan = Color(0xFF38DDF5)
    val Violet = Color(0xFF9382FF)
    val Muted = Color(0xFF9CAFD0)
    val Line = Color(0xFF243958)
    val Mint = Color(0xFF3EE9D0)
}

@Composable fun OrbitBackdrop(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(OrbitStyle.Surface, OrbitStyle.Night, Color(0xFF050A17))))) {
        content()
    }
}

@Composable fun OrbitBrand(back: (() -> Unit)? = null, settings: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(66.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Image(painterResource(R.drawable.orbit_mark), null, Modifier.size(37.dp))
            Text("ORBIT", fontSize = 26.sp, letterSpacing = 7.sp, fontWeight = FontWeight.Light, color = Color.White)
        }
        if (back != null) IconButton(back, Modifier.align(Alignment.CenterStart)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OrbitStyle.Muted) }
        if (settings != null) IconButton(settings, Modifier.align(Alignment.CenterEnd)) { Icon(Icons.Outlined.Settings, "Settings", tint = OrbitStyle.Muted) }
    }
}

@Composable fun OrbitPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(OrbitStyle.Surface.copy(.88f))
        .border(1.dp, OrbitStyle.Line, RoundedCornerShape(24.dp)).padding(18.dp), content = content)
}

@Composable fun OrbitAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    if (primary) Button(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = OrbitStyle.Cyan, contentColor = OrbitStyle.Night)) {
        Icon(icon, null, Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    } else OutlinedButton(onClick, modifier.heightIn(min = 56.dp), enabled = enabled, shape = shape,
        border = BorderStroke(1.dp, OrbitStyle.Violet.copy(.65f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
        Icon(icon, null, Modifier.size(21.dp)); Spacer(Modifier.width(9.dp)); Text(label, fontWeight = FontWeight.Medium, fontSize = 15.sp)
    }
}

@Composable fun OrbitPerson(name: String, subtitle: String, live: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(OrbitStyle.Surface.copy(.8f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).border(1.5.dp, OrbitStyle.Violet, CircleShape).background(OrbitStyle.Violet.copy(.08f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(28.dp), tint = OrbitStyle.Cyan)
        }
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (live) OrbitStyle.Mint else OrbitStyle.Muted, CircleShape))
                Spacer(Modifier.width(7.dp)); Text(subtitle, color = if (live) OrbitStyle.Mint else OrbitStyle.Muted, fontSize = 13.sp)
            }
        }
    }
}

@Composable fun OrbitModeTabs(gps: Boolean, bluetooth: () -> Unit, outdoor: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(OrbitStyle.Night).border(1.dp, OrbitStyle.Line, RoundedCornerShape(28.dp)).padding(4.dp)) {
        listOf(false to "Bluetooth", true to "Outdoor GPS").forEach { (value, label) ->
            val selected = value == gps
            TextButton(if (value) outdoor else bluetooth, Modifier.weight(1f).heightIn(min = 44.dp),
                colors = ButtonDefaults.textButtonColors(containerColor = if (selected) OrbitStyle.Surface else Color.Transparent, contentColor = if (selected) OrbitStyle.Cyan else OrbitStyle.Muted)) {
                Icon(if (value) Icons.Outlined.NearMe else Icons.Outlined.Bluetooth, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 13.sp)
            }
        }
    }
}

/** Live instrument, not an illustration: no target blip or compass labels without a measured bearing. */
@Composable fun OrbitRadar(modifier: Modifier = Modifier, reducedMotion: Boolean, signal: Int? = null, direction: Float? = null, searching: Boolean = true) {
    val transition = rememberInfiniteTransition(label = "orbit radar")
    val phase by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "scan sweep")
    val angle = if (reducedMotion) 0f else phase
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().semantics { contentDescription = if (direction == null) "Radar visualization. Sweep does not indicate a measured direction." else "Measured GPS direction $direction degrees from phone top" }) {
            val r = size.minDimension * .46f
            drawCircle(Brush.radialGradient(listOf(OrbitStyle.Cyan.copy(.17f), OrbitStyle.Cyan.copy(.025f), Color.Transparent), center, r), r)
            // Instrument grid is drawn at device resolution, so it stays crisp as the radar resizes.
            for (i in -6..6) {
                val offset = r * i / 6f
                val extent = kotlin.math.sqrt((r*r-offset*offset).coerceAtLeast(0f))
                drawLine(OrbitStyle.Cyan.copy(.035f), Offset(center.x+offset,center.y-extent), Offset(center.x+offset,center.y+extent))
                drawLine(OrbitStyle.Cyan.copy(.035f), Offset(center.x-extent,center.y+offset), Offset(center.x+extent,center.y+offset))
            }
            for (i in 1..4) drawCircle(OrbitStyle.Cyan.copy(if (i == 4) .35f else .18f), r * i / 4f, style = Stroke(if (i == 4) 1.5.dp.toPx() else 1.dp.toPx()))
            drawCircle(OrbitStyle.Violet.copy(.25f), r * 1.045f, style = Stroke(1.dp.toPx()))
            drawLine(OrbitStyle.Line, Offset(center.x-r, center.y), Offset(center.x+r, center.y))
            drawLine(OrbitStyle.Line, Offset(center.x, center.y-r), Offset(center.x, center.y+r))
            for (i in 0 until 60) {
                val a = Math.toRadians(i * 6.0 - 90)
                val length = if (i % 5 == 0) .045f else .014f
                drawLine(if (i % 15 == 0) OrbitStyle.Cyan else OrbitStyle.Line,
                    Offset(center.x + cos(a).toFloat()*r*(1-length), center.y + sin(a).toFloat()*r*(1-length)),
                    Offset(center.x + cos(a).toFloat()*r, center.y + sin(a).toFloat()*r), if (i % 15 == 0) 3.dp.toPx() else 1.dp.toPx())
            }
            if (direction == null && searching) rotate(angle) {
                drawArc(Brush.sweepGradient(listOf(Color.Transparent, OrbitStyle.Cyan.copy(.22f), Color.Transparent), center), -55f, 55f, true,
                    Offset(center.x-r,center.y-r), Size(r*2,r*2))
                drawLine(OrbitStyle.Cyan.copy(.6f), center, Offset(center.x+r,center.y), 1.dp.toPx())
            }
            if (signal != null) {
                val strength = ((signal + 100) / 60f).coerceIn(.1f, 1f)
                drawArc(OrbitStyle.Cyan.copy(.06f), 90f, 360f * strength, false, Offset(center.x-r,center.y-r), Size(r*2,r*2), style=Stroke(14.dp.toPx(), cap=StrokeCap.Round))
                drawArc(OrbitStyle.Cyan.copy(.10f), 90f, 360f * strength, false, Offset(center.x-r,center.y-r), Size(r*2,r*2), style=Stroke(7.dp.toPx(), cap=StrokeCap.Round))
                drawArc(OrbitStyle.Cyan, 90f, 360f * strength, false, Offset(center.x-r,center.y-r), Size(r*2,r*2), style=Stroke(3.dp.toPx(), cap=StrokeCap.Round))
            }
            drawCircle(OrbitStyle.Cyan.copy(.12f), 25.dp.toPx())
            drawCircle(OrbitStyle.Night, 13.dp.toPx())
            drawCircle(OrbitStyle.Cyan, 13.dp.toPx(), style=Stroke(1.5.dp.toPx()))
            drawCircle(OrbitStyle.Cyan, 5.dp.toPx())
        }
        if (direction != null) {
            Icon(Icons.Outlined.Navigation, "Direction arrow", Modifier.size(116.dp).graphicsLayer { rotationZ = direction }, tint = OrbitStyle.Cyan)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun OrbitHome(s: OrbitState, vm: OrbitViewModel, nearby: () -> Unit, scanner: () -> Unit) {
    var add by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Friend?>(null) }
    val knownNearby = s.friends.count { it.presence == Presence.NEARBY }
    val available = unpairedNearby(s.nearby, s.friends)
    OrbitBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
            OrbitBrand(settings = { vm.navigate(Screen.SETTINGS) })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("A little closer.", fontSize = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp))
                Text("Find your people, your way.", color = OrbitStyle.Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 5.dp, bottom = 23.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(OrbitStyle.Surface).clickable { details = !details }.padding(start = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Bluetooth, null, tint = OrbitStyle.Cyan, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f).padding(12.dp)) {
                        Text(if (s.status.contains("failed", true) || s.status.contains("could not", true)) "Bluetooth needs attention" else if (knownNearby > 0) "$knownNearby friend nearby" else "Discover nearby friends", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                        Text("${s.profileName} · tap for connection status", color = OrbitStyle.Muted, fontSize = 11.sp)
                    }
                    IconButton(nearby) { Icon(Icons.Outlined.Refresh, "Refresh nearby devices", tint = OrbitStyle.Cyan) }
                }
                if (details) Text(s.status, color = OrbitStyle.Cyan, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
                if (s.pairingStatus.isNotBlank()) Text(s.pairingStatus, color = OrbitStyle.Mint, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                if (s.pairingPending != null) TextButton({ vm.cancelPairing() }) { Text("Cancel request") }
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nearby devices", fontSize = 23.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${available.size} available", color = OrbitStyle.Cyan, fontSize = 12.sp)
                }
                if (available.isEmpty()) OrbitPanel(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("No unpaired devices nearby", fontWeight = FontWeight.Medium, fontSize = 14.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(nearby) { Icon(Icons.Outlined.Refresh, "Refresh nearby devices", tint = OrbitStyle.Cyan) }
                    }
                }
                available.sortedBy { it.shortId }.forEach { p ->
                    val saved = s.friends.firstOrNull { it.id.startsWith(p.shortId) }
                    OrbitPanel(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.PhoneAndroid, null, tint = OrbitStyle.Cyan)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(saved?.name ?: "ORBIT · ${p.shortId.take(8)}", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                                Text("Available to connect · Bluetooth", color = OrbitStyle.Mint, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OrbitAction(
                            if (s.pairingPending == p.shortId) "Request pending…" else "Connect",
                            Icons.Outlined.PersonAdd,
                            Modifier.fillMaxWidth(), enabled = s.pairingPending == null
                        ) { vm.addNearby(p) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("YOUR FRIENDS", letterSpacing = 2.sp, fontSize = 11.sp, color = OrbitStyle.Muted, modifier = Modifier.weight(1f))
                    Text("${s.friends.size} saved", color = OrbitStyle.Muted, fontSize = 11.sp)
                }
                if (s.friends.isEmpty()) OrbitPanel(Modifier.fillMaxWidth()) {
                    Text("Your first connection", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("Tap Connect on a nearby phone above, then ask them to accept. QR pairing is also available.", color = OrbitStyle.Muted, modifier = Modifier.padding(top = 8.dp))
                    TextButton({ add = true }) { Text("Add your first friend") }
                }
                s.friends.forEach { f ->
                    var menu by remember(f.id) { mutableStateOf(false) }
                    val live = f.presence == Presence.NEARBY
                    OrbitPanel(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).border(1.dp, OrbitStyle.Violet, CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.PhoneAndroid, null, tint = OrbitStyle.Cyan) }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(f.name, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (f.trust == TrustState.PENDING) "Waiting for acceptance" else if (live) "Nearby now" else "Not nearby · try online GPS", color = if (live) OrbitStyle.Mint else OrbitStyle.Muted, fontSize = 12.sp)
                            }
                            Box {
                                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Options for ${f.name}", tint = OrbitStyle.Muted) }
                                DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Remove friend") }, onClick = { menu = false; remove = f }) }
                            }
                        }
                        Spacer(Modifier.height(15.dp))
                        OrbitAction(if (f.trust == TrustState.PENDING) "Retry pairing" else "Find ${f.name}", if (f.trust == TrustState.PENDING) Icons.Outlined.Refresh else Icons.Outlined.Radar, Modifier.fillMaxWidth()) { vm.beginFinding(f.id) }
                    }
                }
            }
            OrbitAction("Add friend", Icons.Outlined.PersonAdd, Modifier.fillMaxWidth().padding(top = 12.dp), primary = false) { add = true }
            Text("Private pairing. Shared only by choice.", color = OrbitStyle.Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
        }
    }
    if (add) ModalBottomSheet(onDismissRequest = { add = false }, containerColor = OrbitStyle.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 30.dp)) {
            Text("Make a connection", fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
            Text("Tap Connect in Nearby devices for a direct request. Or scan a QR to verify the other phone’s identity first.", color = OrbitStyle.Muted, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            OrbitAction("Scan friend’s QR", Icons.Outlined.QrCodeScanner, Modifier.fillMaxWidth()) { add = false; scanner() }
            Spacer(Modifier.height(12.dp))
            OrbitAction("Show my QR", Icons.Outlined.QrCode2, Modifier.fillMaxWidth(), primary = false) { add = false; vm.navigate(Screen.MY_QR) }
            Text("GPS setup is automatic after pairing. No extra QR scan.", color = OrbitStyle.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp))
        }
    }
    remove?.let { f -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remove ${f.name}?") }, text = { Text("You’ll need to pair again to find each other. Active GPS sharing will stop.") }, confirmButton = { TextButton({ vm.forget(f.id); remove = null }) { Text("Remove") } }, dismissButton = { TextButton({ remove = null }) { Text("Keep friend") } }) }
}

@Composable fun OrbitFinding(s: OrbitState, vm: OrbitViewModel) {
    val f = s.friends.firstOrNull { it.id == s.selectedId }
    val rssi = f?.rssi?.takeIf { f.presence == Presence.NEARBY }
    var help by remember { mutableStateOf(false) }
    OrbitBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitBrand(back = { vm.stopFinding() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                OrbitPerson(f?.name ?: "Your friend", if (rssi != null) "Nearby over Bluetooth" else "Looking for their signal", rssi != null, Modifier.padding(top = 8.dp, bottom = 18.dp))
                OrbitModeTabs(false, {}, { vm.openGps() })
                OrbitRadar(Modifier.fillMaxWidth().padding(top = 18.dp).widthIn(max = 360.dp), s.reducedMotion, signal = rssi)
                Text(if (rssi == null) "Searching" else when { rssi >= -55 -> "Very strong"; rssi >= -67 -> "Strong signal"; rssi >= -78 -> "Getting a signal"; else -> "Weak signal" }, fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = OrbitStyle.Cyan, textAlign = TextAlign.Center)
                Text(if (rssi != null) "Bluetooth proximity · ${rssi} dBm" else "Keep both phones open in ORBIT", color = OrbitStyle.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 7.dp))
                Text("Signal strength, not distance or direction", color = OrbitStyle.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 7.dp, bottom = 20.dp))
                OrbitPanel(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.NearMe, null, tint = OrbitStyle.Cyan)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Want a map & direction?", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Text("Use Outdoor GPS with internet on both phones.", color = OrbitStyle.Muted, fontSize = 12.sp)
                        }
                        IconButton({ vm.openGps() }) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Open outdoor GPS", tint = OrbitStyle.Cyan) }
                    }
                }
                TextButton({ help = true }) { Text("Having trouble connecting?", color = OrbitStyle.Muted, fontSize = 12.sp) }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OrbitAction("Stop", Icons.Outlined.Stop, Modifier.weight(1f), primary = false) { vm.stopFinding() }
                OrbitAction("Retry", Icons.Outlined.Refresh, Modifier.weight(1f)) { vm.startNearby() }
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("Find a nearby signal") }, text = { Text("1. Keep ORBIT open on both phones.\n2. Enable Bluetooth, Location and Nearby permission.\n3. Tap Retry once and allow time to scan.\n\nCurrent status: ${s.status}\n\nThe radar sweep is an animation, not your friend’s bearing. Play sound and Bluetooth direction are not supported yet.") }, confirmButton = { TextButton({ help = false; vm.startNearby() }) { Text("Retry connection") } }, dismissButton = { TextButton({ help = false }) { Text("Close") } })
}

@Composable fun OrbitQr(s: OrbitState, vm: OrbitViewModel) {
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(9 * 60_000L); refresh++ } }
    val payload = remember(s.onlineUid, s.gpsPublicKey, s.profileName, refresh) { vm.qrPayload() }
    val bitmap = remember(payload) { qrBitmap(payload) }
    OrbitBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            OrbitBrand(back = { vm.navigate(Screen.HOME) })
            Text("Your invitation", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp))
            Text("Ask your friend to scan. You choose to accept.", color = OrbitStyle.Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            Image(bitmap.asImageBitmap(), "Pairing QR for ${s.profileName}", Modifier.widthIn(max = 310.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(28.dp)).background(Color.White).padding(18.dp))
            Text(s.profileName, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp))
            Text("Refreshes automatically · valid for 10 minutes", color = OrbitStyle.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp, bottom = 25.dp))
            OrbitPanel(Modifier.fillMaxWidth()) {
                Text("Connect beyond Bluetooth", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("GPS details are exchanged automatically with paired friends nearby. You do not need to scan again.", color = OrbitStyle.Muted, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                OrbitAction(if (s.onlinePreparing) "Preparing…" else "Retry online setup", Icons.Outlined.Link, Modifier.fillMaxWidth(), enabled = !s.onlinePreparing) { vm.retryGpsSetup() }
                Text(s.onlineMessage, color = if (s.onlineUid.isNotBlank()) OrbitStyle.Mint else OrbitStyle.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
            }
            Text("Preparing a link never starts location sharing.", color = OrbitStyle.Muted, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 18.dp))
        }
    }
}
