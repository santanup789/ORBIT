package app.orbit.finder.radio

/** Bounded backoff shared by automatic recovery and user-triggered retries. */
internal class ScanRecovery {
    var failures = 0
        private set
    var retryAt = 0L
        private set
    fun failed(now: Long): Boolean {
        failures++
        retryAt = now + 30_000L
        return failures <= 2
    }
    fun remaining(now: Long) = (retryAt - now).coerceAtLeast(0L)
    fun reset() { failures = 0; retryAt = 0L }
}
