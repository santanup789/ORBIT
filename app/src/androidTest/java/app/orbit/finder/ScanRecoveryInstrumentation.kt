package app.orbit.finder

import android.app.Activity
import android.app.Instrumentation
import android.bluetooth.le.ScanCallback
import android.os.Bundle
import app.orbit.finder.core.IdentityStore
import app.orbit.finder.radio.BleDiscovery
import java.util.concurrent.atomic.AtomicInteger

/** Injects a failure callback, but releases/restarts a REAL Android scan. */
object ScanRecoveryInstrumentation {
    fun run(test: Instrumentation) {
        val result = Bundle()
        lateinit var ble: BleDiscovery
        var initialized = false
        val peers = AtomicInteger()
        val field = BleDiscovery::class.java.getDeclaredField("scanCallback").apply { isAccessible = true }
        val identity = IdentityStore(test.targetContext).identity()
        var ok = false
        try {
            val activity = test.startActivitySync(android.content.Intent(test.targetContext, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            test.runOnMainSync {
                (test.targetContext.applicationContext as OrbitApplication).model.apply {
                    navigate(Screen.SETTINGS)
                    stopNearby()
                }
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                ble = BleDiscovery(test.targetContext, { peers.incrementAndGet() }, {}, { it(false) }, {})
                initialized = true
                ble.start(identity)
                val first = field.get(ble)
                repeat(10) { ble.start(identity) }
                check(first != null && field.get(ble) === first) { "Repeated start allocated another scan callback" }
            }
            Thread.sleep(8_000)
            check(peers.get() > 0) { "No real peer advertisements before recovery" }
            var old: ScanCallback? = null
            test.runOnMainSync {
                old = field.get(ble) as ScanCallback
                old!!.onScanFailed(ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED)
            }
            test.runOnMainSync {
                check(field.get(ble) == null) { "Failed callback not released" }
                repeat(10) { ble.start(identity) }
                check(field.get(ble) == null) { "Manual retry bypassed backoff" }
            }
            peers.set(0)
            test.sendStatus(1, Bundle().apply { putString("stream", "Scan released; waiting for bounded automatic recovery.\n") })
            Thread.sleep(38_000)
            test.runOnMainSync {
                val fresh = field.get(ble)
                check(fresh != null && fresh !== old) { "Automatic retry did not create a new scan" }
                old!!.onScanFailed(ScanCallback.SCAN_FAILED_INTERNAL_ERROR)
            }
            test.runOnMainSync { check(field.get(ble) != null) { "Old callback disrupted new scan" } }
            check(peers.get() > 0) { "No real advertisements after automatic recovery" }
            test.runOnMainSync { ble.stop(); check(field.get(ble) == null) }
            result.putString("stream", "PASS: repeated starts reuse one scan; injected error releases real scan; backoff blocks repeated taps; automatic recovery receives real peer advertisements; stale callback ignored; stop releases scan. Error injection does not prove recovery from an exhausted OS stack.\n")
            ok = true
        } catch (e: Throwable) {
            result.putString("stream", "FAIL: ${e.message}\n")
        } finally {
            test.runOnMainSync { if (initialized) ble.close() }
        }
        test.finish(if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
