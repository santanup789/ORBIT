package app.orbit.finder.online

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat

class GpsSensors(private val context: Context, private val fix: (GeoFix) -> Unit,
                 private val heading: (Double?, Long) -> Unit, private val status: (String) -> Unit) : LocationListener, SensorEventListener {
    private val locations = context.getSystemService(LocationManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var latest: Location? = null
    private var accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var running = false
    fun start(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status("Allow precise Location permission to use GPS."); return false
        }
        if (!androidx.core.location.LocationManagerCompat.isLocationEnabled(locations)) { status("Turn on Android Location for GPS mode."); return false }
        stop(); running = true
        try {
            var enabled = false
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
                if (locations.isProviderEnabled(provider)) {
                    locations.requestLocationUpdates(provider, 3000L, 0f, this, Looper.getMainLooper()); enabled = true
                }
            }
            if (!enabled) { stop(); status("No location provider is enabled."); return false }
            val rotation = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotation != null) sensors.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
            else status("Compass sensor unavailable; distance and map still work.")
            return true
        } catch (_: SecurityException) { stop(); status("Location permission was removed."); return false }
    }
    override fun onLocationChanged(location: Location) {
        if (!running || location.isFromMockProvider || !location.hasAccuracy()) return
        val age = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        if (age !in 0..20_000) return
        val value = GeoFix(location.latitude, location.longitude, location.accuracy,
            System.currentTimeMillis() - age, location.provider ?: "Location")
        if (!value.valid()) return
        val previous = latest
        if (previous != null && (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) < 5_000_000_000 && location.accuracy > previous.accuracy * 1.5f) return
        latest = location; fix(value)
    }
    override fun onProviderDisabled(provider: String) { if (running) { latest = null; heading(null, 0); status("Location provider disabled; waiting for a fresh fix.") } }
    override fun onProviderEnabled(provider: String) {}
    @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onAccuracyChanged(sensor: Sensor?, value: Int) { accuracy = value }
    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val location = latest ?: return
        if (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM) { heading(null, 0); return }
        val matrix = FloatArray(9); val adjusted = FloatArray(9); val angles = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        val rotation = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
        val axes = when(rotation) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, axes.first, axes.second, adjusted)
        SensorManager.getOrientation(adjusted, angles)
        // Phone top edge is meaningful when held reasonably flat, not vertical.
        if (kotlin.math.abs(angles[1]) > 1.05 || kotlin.math.abs(angles[2]) > 1.05) { heading(null, 0); return }
        val declination = GeomagneticField(location.latitude.toFloat(), location.longitude.toFloat(), location.altitude.toFloat(), System.currentTimeMillis()).declination
        heading(GeoMath.normalize(Math.toDegrees(angles[0].toDouble()) + declination), System.currentTimeMillis())
    }
    fun stop() { running = false; locations.removeUpdates(this); sensors.unregisterListener(this); latest = null; accuracy = 0; heading(null, 0) }
}
