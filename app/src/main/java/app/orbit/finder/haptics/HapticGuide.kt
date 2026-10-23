package app.orbit.finder.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.*

class HapticGuide(context: Context) {
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var job: Job? = null
    private var band = -1
    private var arrivalCount = 0

    fun update(scope: CoroutineScope, metres: Float?, trustworthy: Boolean, enabled: Boolean) {
        if (!enabled || !trustworthy || metres == null) { stop(); return }
        val next = when {
            metres < .75f -> 4; metres < 2f -> 3; metres < 5f -> 2; metres < 10f -> 1; else -> 0
        }
        if (band == next && job?.isActive == true) return
        band = next; job?.cancel()
        job = scope.launch {
            if (next == 4) {
                arrivalCount++
                if (arrivalCount >= 3) vibrate(longArrayOf(0, 80, 80, 160), intArrayOf(0, 110, 0, 180))
                return@launch
            }
            arrivalCount = 0
            val delayMs = listOf(2000L, 1200L, 700L, 350L)[next]
            while (isActive) { pulse(70); delay(delayMs) }
        }
    }
    fun preview() { pulse(140) }
    fun stop() { job?.cancel(); job = null; band = -1; arrivalCount = 0; vibrator?.cancel() }
    private fun pulse(amplitude: Int) { vibrator?.vibrate(VibrationEffect.createOneShot(55, amplitude)) }
    private fun vibrate(t: LongArray, a: IntArray) { vibrator?.vibrate(VibrationEffect.createWaveform(t, a, -1)) }
}
