package app.orbit.finder

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import app.orbit.finder.core.*
import app.orbit.finder.online.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Checks read-only connectivity and cancellation without starting location sensors. */
object ConnectionInstrumentation {
    fun run(test: Instrumentation) {
        val report = Bundle()
        var checker: FirebaseConnection? = null
        var relay: GpsRelay? = null
        try {
            val friend = Friend("test", "", "Test", DeviceIcon.PHONE, TrustState.TRUSTED,
                firebaseUid = "test-peer", gpsPublicKey = "not-used-before-cancellation")
            var latest = GpsUi()
            test.runOnMainSync {
                relay = GpsRelay(test.targetContext, { latest = it }, {}, { false })
                relay!!.start(friend)
                check(!latest.active && latest.retryable && latest.status.startsWith("No internet connection"))
                relay!!.stop()
                check(!latest.active && !latest.retryable)
            }
            val done = CountDownLatch(1)
            var connected = false
            test.runOnMainSync {
                checker = FirebaseConnection(test.targetContext)
                checker!!.check(report = { _, _ -> }, completed = { connected = it; done.countDown() })
            }
            check(done.await(30, TimeUnit.SECONDS) && connected) { "Online service preflight did not succeed" }
            test.runOnMainSync {
                relay = GpsRelay(test.targetContext, { latest = it }, {}, { true })
                relay!!.start(friend)
                check(latest.active && latest.status == "Checking online connection…")
                relay!!.stop()
            }
            Thread.sleep(2500)
            test.runOnMainSync { check(!latest.active && latest.own == null && latest.peer == null && !latest.retryable) }
            report.putString("stream", "PASS: offline preflight gives retryable plain-language error; live auth/database connectivity succeeds; stop cancels pending GPS startup without late sensor activation. No coordinates collected or shared.\n")
            test.finish(Activity.RESULT_OK, report)
        } catch (e: Throwable) {
            report.putString("stream", "FAIL: ${e.message}\n")
            test.finish(Activity.RESULT_CANCELED, report)
        } finally {
            test.runOnMainSync { checker?.close(); relay?.stop() }
        }
    }
}
