package app.orbit.finder

import app.orbit.finder.radio.ScanRecovery
import org.junit.Assert.*
import org.junit.Test

class ScanRecoveryTest {
    @Test fun cooldownPreventsRapidRetry() {
        val r = ScanRecovery()
        assertTrue(r.failed(1_000))
        assertEquals(30_000L, r.remaining(1_000))
        assertEquals(1L, r.remaining(30_999))
        assertEquals(0L, r.remaining(31_000))
    }
    @Test fun automaticRecoveryIsBounded() {
        val r = ScanRecovery()
        assertTrue(r.failed(0)); assertTrue(r.failed(30_000))
        assertFalse(r.failed(60_000)); assertFalse(r.failed(90_000))
    }
    @Test fun successfulDiscoveryOrRadioStopResetsRecovery() {
        val r = ScanRecovery()
        r.failed(0); r.failed(30_000); r.failed(60_000)
        r.reset()
        assertEquals(0L, r.remaining(60_000)); assertEquals(0, r.failures)
        assertTrue(r.failed(60_001))
    }
}
