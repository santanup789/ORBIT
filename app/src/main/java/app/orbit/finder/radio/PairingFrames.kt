package app.orbit.finder.radio

/** Bounded, ordered framing that works at the default BLE MTU (23 bytes). */
object PairingFrames {
    const val MAX_BYTES = 4096
    fun encode(value: String): List<ByteArray> {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES)
        val chunks = bytes.toList().chunked(16)
        return chunks.mapIndexed { index, chunk ->
            byteArrayOf((index shr 8).toByte(), index.toByte(), (chunks.size shr 8).toByte(), chunks.size.toByte()) + chunk.toByteArray()
        }
    }
    class Decoder {
        private val bytes = java.io.ByteArrayOutputStream()
        private var next = 0
        private var total = 0
        fun accept(frame: ByteArray): String? {
            require(frame.size in 5..20) { "Invalid BLE frame size" }
            val index = ((frame[0].toInt() and 255) shl 8) or (frame[1].toInt() and 255)
            val count = ((frame[2].toInt() and 255) shl 8) or (frame[3].toInt() and 255)
            require(count in 1..256 && index == next && (next == 0 || count == total)) { "Out-of-order BLE frame" }
            if (next == 0) total = count
            bytes.write(frame, 4, frame.size - 4)
            require(bytes.size() <= MAX_BYTES)
            next++
            return if (next == total) bytes.toByteArray().toString(Charsets.UTF_8).also { reset() } else null
        }
        fun reset() { bytes.reset(); next = 0; total = 0 }
    }
}
