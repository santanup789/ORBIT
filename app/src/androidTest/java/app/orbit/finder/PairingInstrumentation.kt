package app.orbit.finder

import android.app.Instrumentation
import android.app.Activity
import android.os.Bundle
import android.bluetooth.BluetoothManager
import app.orbit.finder.core.*
import app.orbit.finder.radio.*
import org.json.JSONObject
import java.security.KeyStore
import java.util.ArrayDeque

/** On-device crypto/protocol tests, using simulated transport, not a claim of two-radio coverage. */
class PairingInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        runOnMainSync { callApplicationOnCreate(targetContext.applicationContext as OrbitApplication) }
        if (arguments.containsKey("appCheckChecks")) { AppCheckInstrumentation.run(this); return }
        if (arguments.containsKey("connectionChecks")) { ConnectionInstrumentation.run(this); return }
        if (arguments.containsKey("scanRecovery")) { ScanRecoveryInstrumentation.run(this); return }
        if (arguments.containsKey("gpsMode")) { GpsInstrumentation.run(this, arguments); return }
        val report = Bundle()
        var failure: Throwable? = null
        runOnMainSync {
            try {
                val a = IdentityStore(targetContext, "orbit_test_a")
                val b = IdentityStore(targetContext, "orbit_test_b")
                check(!a.hasProfile())
                check(a.profileName() == a.defaultDeviceName())
                val originalId = a.identity().id
                a.saveProfile("Custom device", DeviceIcon.ORB)
                check(a.hasProfile() && !a.usesDefaultName() && a.profileName() == "Custom device")
                check(IdentityStore(targetContext, "orbit_test_a").profileName() == "Custom device")
                a.useDefaultName()
                check(a.usesDefaultName() && a.profileName() == a.defaultDeviceName())
                check(a.identity().id == originalId)
                a.saveProfile("Test A", DeviceIcon.ORB); b.saveProfile("Test B", DeviceIcon.PHONE)
                val ap = b.parseQr(a.createQr("Test A", DeviceIcon.ORB)).getOrThrow()
                val bp = a.parseQr(b.createQr("Test B", DeviceIcon.PHONE)).getOrThrow()
                check(a.parseQr(a.createQr("Self", DeviceIcon.ORB)).isFailure)
                check(a.parseQr(b.createQr("Bad\nname", DeviceIcon.PHONE)).isFailure)
                check(a.parseQr(b.createQr(" ", DeviceIcon.PHONE)).isFailure)
                check(a.verifyMessage(b.signMessage("{\"test\":1}"), bp.publicKey).getInt("test") == 1)
                check(runCatching { a.verifyMessage(b.signMessage("{\"test\":1}").replace("test", "evil"), bp.publicKey) }.isFailure)
                var ac = 0; var bc = 0; var incoming: PairingRequest? = null
                val packets = ArrayDeque<Pair<Boolean, String>>()
                val statuses = mutableListOf<String>()
                lateinit var ca: PairingCoordinator
                lateinit var cb: PairingCoordinator
                var outKey = "out:${bp.id}"
                var allowUpgrade = true
                var linkedA = 0; var linkedB = 0
                val gpsA = app.orbit.finder.online.GpsCrypto("orbit_test_link_a").publicKey()
                val gpsB = app.orbit.finder.online.GpsCrypto("orbit_test_link_b").publicKey()
                val secretA = app.orbit.finder.online.GpsCrypto("orbit_test_link_a").key(gpsB)
                val secretB = app.orbit.finder.online.GpsCrypto("orbit_test_link_b").key(gpsA)
                try {
                    check(secretA.contentEquals(secretB))
                    val crypto = app.orbit.finder.online.GpsCrypto
                    val encrypted = crypto.encrypt(secretA, "synthetic-security-test", "a>b")
                    check(crypto.decrypt(secretB, encrypted, "a>b") == "synthetic-security-test")
                    check(runCatching { crypto.decrypt(secretB, encrypted, "b>a") }.isFailure)
                    val tampered = crypto.unb64(encrypted)
                    tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
                    check(runCatching { crypto.decrypt(secretB, crypto.b64(tampered), "a>b") }.isFailure)
                    report.putString("crypto", "PASS: ECDH agreement, AES-GCM round trip, wrong-recipient AAD and tamper rejection; malformed names rejected.")
                } finally { secretA.fill(0); secretB.fill(0) }
                cb = PairingCoordinator(b, { _, p -> packets.add(false to p) }, { _, _ -> }, {}, { incoming = it }, {}, { check(it.firebaseUid == "test-a"); bc++ }, statuses::add,
                    localPayload = { b.createQr("Test B", DeviceIcon.PHONE, "test-b", gpsB) },
                    isTrusted = { allowUpgrade && it.id == ap.id && it.publicKey == ap.publicKey }, onLinked = { check(it.firebaseUid == "test-a"); linkedB++ })
                ca = PairingCoordinator(a, { _, p -> packets.add(true to p) }, { _, _ -> cb.opened("in:test") }, { ca.closed(outKey) }, {}, {}, { check(it.firebaseUid == "test-b"); ac++ }, statuses::add,
                    localPayload = { a.createQr("Test A", DeviceIcon.ORB, "test-a", gpsA) },
                    isTrusted = { it.id == bp.id && it.publicKey == bp.publicKey }, onLinked = { check(it.firebaseUid == "test-b"); linkedA++ })
                val device = targetContext.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice("02:00:00:00:00:01")
                fun drain() {
                    var count = 0
                    while (packets.isNotEmpty()) {
                        check(count++ < 20)
                        val (toB, packet) = packets.removeFirst()
                        val decoder = PairingFrames.Decoder()
                        val reassembled = PairingFrames.encode(packet).mapNotNull { decoder.accept(it) }.single()
                        if (toB) cb.message("in:test", reassembled) else ca.message(outKey, reassembled)
                    }
                }
                ca.request(bp); ca.discovered(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device)
                drain()
                check(incoming?.peer?.id == ap.id) { "No incoming request: $statuses" }
                check(ac == 0 && bc == 0) { "Trusted before acceptance" }
                cb.decide("in:test", true); drain()
                check(ac == 1 && bc == 1) { "Acceptance failed: $statuses" }
                cb.closed("in:test"); incoming = null
                ca.request(bp); ca.discovered(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device); drain()
                check(incoming != null)
                cb.decide("in:test", false); drain()
                check(ac == 1 && bc == 1) { "Rejected request changed trust" }
                ca.cancel(); cb.closed("in:test")
                // Direct nearby requests learn a signed identity over GATT, still requiring consent.
                outKey = "out:${bp.id.take(16)}"
                incoming = null
                ca.requestNearby(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device); drain()
                check(incoming?.peer?.id == ap.id) { "Direct nearby request did not arrive: $statuses" }
                check(ac == 1 && bc == 1) { "Direct request bypassed consent" }
                cb.decide("in:test", true); drain()
                check(ac == 2 && bc == 2) { "Direct acceptance failed: $statuses" }
                cb.closed("in:test"); incoming = null
                ca.requestNearby(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device); drain()
                cb.decide("in:test", false); drain()
                check(ac == 2 && bc == 2) { "Direct decline changed trust" }
                cb.closed("in:test"); incoming = null
                ca.requestNearby(NearbyPeer("0000000000000000", -50, System.currentTimeMillis()), device)
                outKey = "out:0000000000000000"; drain()
                check(incoming == null && statuses.any { it.contains("does not match discovered device") })
                ca.cancel(); cb.closed("in:test")
                // Pinning must reject another identity, before showing an approval prompt.
                outKey = "out:${bp.id}"
                val wrong = bp.copy(publicKey = ap.publicKey)
                ca.request(wrong); ca.discovered(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device); drain()
                check(statuses.any { it.contains("does not match scanned QR") })
                ca.cancel(); cb.closed("in:test")
                incoming = null
                check(ca.upgrade(bp)); ca.discovered(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device); drain()
                check(linkedA == 1 && linkedB == 1 && incoming == null) { "Trusted GPS upgrade failed: $statuses" }
                ca.cancel(); cb.closed("in:test")
                allowUpgrade = false
                check(ca.upgrade(bp)); ca.discovered(NearbyPeer(bp.id.take(16), -50, System.currentTimeMillis()), device); drain()
                check(linkedA == 1 && linkedB == 1 && incoming == null) { "Untrusted GPS upgrade was accepted" }
                ca.cancel(); cb.closed("in:test")
                report.putString("gpsLink", "PASS: new pairing carries signed GPS identities; existing trusted friends upgrade without another prompt; untrusted upgrade rejected.")
                val notices = PairingNotifications(targetContext)
                notices.show("orbit_protocol_test", "ORBIT test", "Test pairing notification")
                report.putString("stream", "PASS: signed GPS identity exchange on new pairing, trusted GPS upgrade without another prompt, untrusted upgrade rejection; Keystore signatures, self-QR rejection, tamper rejection, MTU-23 framing, signed request, explicit accept, both-side confirmation, decline, QR pin mismatch, direct nearby accept/decline and advertisement identity mismatch. Transport was simulated.\n")
            } catch (t: Throwable) { failure = t; report.putString("stream", "FAIL: ${t.stackTraceToString()}\n") }
            finally {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                listOf("orbit_test_a", "orbit_test_b").forEach { ks.deleteEntry(it); targetContext.deleteSharedPreferences(it) }
                listOf("orbit_test_link_a", "orbit_test_link_b").forEach { ks.deleteEntry(it) }
            }
        }
        if (failure == null) {
            // NotificationManager queues posts asynchronously. Never block the UI thread waiting.
            val nm = targetContext.getSystemService(android.app.NotificationManager::class.java)
            var found = false
            for (attempt in 0 until 20) {
                found = nm.activeNotifications.any { it.tag == "orbit_protocol_test" && it.notification.contentIntent != null }
                if (found) break
                Thread.sleep(100)
            }
            if (found) report.putString("stream", report.getString("stream") + "PASS: local notification delivered with tap-to-open intent.\n")
            else {
                failure = IllegalStateException("Notification absent; enabled=${nm.areNotificationsEnabled()}, permission=${targetContext.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)}")
                report.putString("stream", report.getString("stream") + "FAIL: $failure\n")
            }
        }
        PairingNotifications(targetContext).cancel("orbit_protocol_test")
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, report)
    }
}
