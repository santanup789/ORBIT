package app.orbit.finder

import app.orbit.finder.core.Measurement
import app.orbit.finder.core.MeasurementSource
import org.junit.Assert.*
import org.junit.Test

class MeasurementTest {
    @Test fun staleMeasurementsAreRejected() {
        val m = Measurement(MeasurementSource.UWB, 1_000, 2f, null, .1f, null, true, "test")
        assertFalse(m.isFresh(4_001))
        assertTrue(m.isFresh(3_999))
    }
    @Test fun directionAndDistanceAreIndependent() {
        val m = Measurement(MeasurementSource.WIFI_RTT, 10, 2f, null, 1f, null, true, "distance only")
        assertNotNull(m.distanceMetres)
        assertNull(m.azimuthDegrees)
    }
}
