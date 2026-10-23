package app.orbit.finder

import app.orbit.finder.radio.PairingFrames
import org.junit.Assert.*
import org.junit.Test

class PairingFramesTest {
    @Test fun roundTripsAtDefaultMtu() {
        listOf("x", "A".repeat(16), "B".repeat(17), "🔒 मराठी ".repeat(30), "x".repeat(4096)).forEach { source ->
            val decoder = PairingFrames.Decoder()
            val frames = PairingFrames.encode(source)
            assertTrue(frames.all { it.size <= 20 })
            val completed = frames.mapNotNull { decoder.accept(it) }
            assertEquals(listOf(source), completed)
        }
    }
    @Test fun supportsConsecutiveMessages() {
        val decoder = PairingFrames.Decoder()
        val messages = listOf("request".repeat(100), "accept".repeat(50), "done")
        assertEquals(messages, messages.flatMap { PairingFrames.encode(it) }.mapNotNull { decoder.accept(it) })
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsReorderedFrames() { PairingFrames.Decoder().accept(PairingFrames.encode("x".repeat(50))[1]) }
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedMessages() { PairingFrames.encode("x".repeat(4097)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsTruncatedFrame() { PairingFrames.Decoder().accept(byteArrayOf(0, 0, 0, 1)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsChangedCount() {
        val frames = PairingFrames.encode("x".repeat(50)); val decoder = PairingFrames.Decoder()
        decoder.accept(frames[0]); frames[1][3] = 5; decoder.accept(frames[1])
    }
}
