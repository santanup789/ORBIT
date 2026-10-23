package app.orbit.finder

import app.orbit.finder.online.*
import org.junit.Assert.*
import org.junit.Test

class GeoMathTest {
    private val now = 100000L
    private fun fix(lat: Double, lon: Double, error: Float = 5f, time: Long = now) = GeoFix(lat, lon, error, time)
    @Test fun cardinalBearingsAndDistance() {
        val origin = fix(0.0, 0.0)
        val east = GeoMath.guide(origin, fix(0.0, 0.001), now)!!
        assertEquals(111.195, east.metres, 0.01); assertEquals(90.0, east.bearing, 0.01)
        assertEquals(0.0, GeoMath.guide(origin, fix(0.001, 0.0), now)!!.bearing, 0.01)
        assertEquals(180.0, GeoMath.guide(origin, fix(-0.001, 0.0), now)!!.bearing, 0.01)
        assertEquals(270.0, GeoMath.guide(origin, fix(0.0, -0.001), now)!!.bearing, 0.01)
        assertTrue(east.directionReliable)
    }
    @Test fun staleFutureAndInvalidFixesRejected() {
        assertNull(GeoMath.guide(fix(0.0, 0.0, time = now - 20001), fix(1.0, 1.0), now))
        assertNull(GeoMath.guide(fix(0.0, 0.0, time = now + 1), fix(1.0, 1.0), now))
        assertNull(GeoMath.guide(fix(Double.NaN, 0.0), fix(1.0, 1.0), now))
        assertNull(GeoMath.guide(fix(91.0, 0.0), fix(1.0, 1.0), now))
    }
    @Test fun overlappingAccuracyDisablesDirection() {
        val guide = GeoMath.guide(fix(0.0, 0.0), fix(0.0, 0.00001), now)!!
        assertFalse(guide.directionReliable)
        assertFalse(GeoMath.guide(fix(0.0, 0.0, 100f), fix(0.0, 1.0), now)!!.directionReliable)
    }
    @Test fun datelineAndNormalization() {
        val guide = GeoMath.guide(fix(0.0, 179.999), fix(0.0, -179.999), now)!!
        assertEquals(222.39, guide.metres, 0.02); assertEquals(90.0, guide.bearing, 0.01)
        assertEquals(350.0, GeoMath.normalize(-10.0), 0.0)
    }
}
