package app.orbit.finder.online

import android.Manifest
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.orbit.finder.*
import app.orbit.finder.core.Presence
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import java.io.File
import kotlin.math.roundToInt


@Composable internal fun OrbitMap(own: GeoFix, peer: GeoFix?, online: Boolean) {
    val context = LocalContext.current
    val map = remember {
        Configuration.getInstance().apply {
            userAgentValue = "ORBIT/${BuildConfig.VERSION_NAME} (Android; app.orbit.finder; prototype)"
            osmdroidBasePath = File(context.cacheDir, "osm")
            osmdroidTileCache = File(context.cacheDir, "osm/tiles")
            expirationOverrideDuration = 7 * 24 * 60 * 60_000L
        }
        MapView(context).apply {
            setTileSource(XYTileSource("OpenStreetMap", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/"), "© OpenStreetMap contributors"))
            setMultiTouchControls(true); controller.setZoom(17.0)
            controller.setCenter(GeoPoint(own.lat, own.lon))
        }
    }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(260.dp).clipToBounds(), update = { view ->
        view.setUseDataConnection(online)
        val version = own.time to peer?.time
        if (view.tag == version) return@AndroidView
        view.tag = version
        view.overlays.clear()
        fun marker(fix: GeoFix, title: String, color: Int) {
            val point = GeoPoint(fix.lat, fix.lon)
            view.overlays.add(Polygon(view).apply {
                points = Polygon.pointsAsCircle(point, fix.accuracy.toDouble())
                fillPaint.color = color; outlinePaint.color = color; outlinePaint.strokeWidth = 2f
            })
            view.overlays.add(Marker(view).apply {
                position = point; this.title = title; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (title == "You") 0xFF008BA6.toInt() else 0xFF7655D0.toInt())
                    setStroke(3, AndroidColor.WHITE); setSize(28, 28)
                }
            })
        }
        marker(own, "You", 0x3357E6FF)
        peer?.let {
            marker(it, "Friend", 0x558B7CFF)
            view.overlays.add(Polyline(view).apply { setPoints(listOf(GeoPoint(own.lat, own.lon), GeoPoint(it.lat, it.lon))); outlinePaint.color = AndroidColor.BLUE; outlinePaint.strokeWidth = 3f })
        }
        view.invalidate()
    })
    Text("© OpenStreetMap contributors • map tiles need internet", fontSize = 11.sp)
    Text("You: cyan dot • Friend: violet dot • Circles: reported accuracy", fontSize = 11.sp)
    Row {
        TextButton({ map.controller.animateTo(GeoPoint(own.lat, own.lon)) }) { Text("MY LOCATION") }
        if (peer != null) TextButton({ map.controller.animateTo(GeoPoint(peer.lat, peer.lon)) }) { Text("FRIEND") }
    }
}
