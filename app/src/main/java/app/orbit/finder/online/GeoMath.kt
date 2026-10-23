package app.orbit.finder.online

import kotlin.math.*

data class GeoFix(val lat: Double, val lon: Double, val accuracy: Float, val time: Long, val provider: String = "GPS") {
    fun valid() = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && accuracy.isFinite() && accuracy > 0 && accuracy <= 10000
    fun fresh(now: Long) = valid() && now - time in 0..20_000
}
data class GeoGuide(val metres: Double, val bearing: Double, val uncertainty: Float, val directionReliable: Boolean)
object GeoMath {
    fun normalize(degrees: Double) = (degrees % 360 + 360) % 360
    fun guide(a: GeoFix, b: GeoFix, now: Long): GeoGuide? {
        if (!a.fresh(now) || !b.fresh(now)) return null
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = (sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)).coerceIn(0.0, 1.0)
        val metres = 6371008.8 * 2 * atan2(sqrt(h), sqrt(1 - h))
        val bearing = normalize(Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl))))
        val error = a.accuracy + b.accuracy
        return GeoGuide(metres, bearing, error, a.accuracy <= 30 && b.accuracy <= 30 && metres > max(5f, error))
    }
}
