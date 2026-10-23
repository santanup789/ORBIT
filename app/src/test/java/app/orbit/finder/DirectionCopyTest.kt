package app.orbit.finder

import app.orbit.finder.online.turnInstruction
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectionCopyTest {
    @Test fun turnCopyMatchesMeasuredBearing() {
        assertEquals("Straight ahead", turnInstruction(0.0))
        assertEquals("Turn left 32°", turnInstruction(328.0))
        assertEquals("Turn right 32°", turnInstruction(32.0))
        assertEquals("Behind you", turnInstruction(180.0))
        assertEquals("Straight ahead", turnInstruction(359.0))
        assertEquals("Turn left 90°", turnInstruction(-90.0))
    }
}
