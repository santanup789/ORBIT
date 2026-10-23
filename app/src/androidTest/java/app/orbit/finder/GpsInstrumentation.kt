package app.orbit.finder

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import app.orbit.finder.core.IdentityStore
import app.orbit.finder.core.FriendStore
import app.orbit.finder.online.*
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.security.KeyStore
import java.util.concurrent.TimeUnit

/** Test-only runner: exports public signed QR data, never credentials or physical coordinates. */
object GpsInstrumentation {
    fun run(test: Instrumentation, args: Bundle) {
        val result = Bundle()
        try {
            val context = test.targetContext
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser ?: Tasks.await(auth.signInAnonymously(), 25, TimeUnit.SECONDS).user!!
            Tasks.await(user.getIdToken(true), 25, TimeUnit.SECONDS)
            val id = IdentityStore(context)
            val crypto = GpsCrypto()
            val key = crypto.publicKey()
            val uid = user.uid
            val db = FirebaseDatabase.getInstance(FirebaseConnection.DATABASE_URL)
            val mode = args.getString("gpsMode")
            when (mode) {
                "identity" -> {
                    val a = GpsCrypto("orbit_test_gps_a"); val b = GpsCrypto("orbit_test_gps_b")
                    try {
                        val ak = a.key(b.publicKey()); val bk = b.key(a.publicKey())
                        check(ak.contentEquals(bk))
                        val packet = GpsCrypto.encrypt(ak, "synthetic-test", "a>b")
                        check(GpsCrypto.decrypt(bk, packet, "a>b") == "synthetic-test")
                        check(runCatching { GpsCrypto.decrypt(bk, packet, "b>a") }.isFailure)
                        val bytes = GpsCrypto.unb64(packet); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
                        check(runCatching { GpsCrypto.decrypt(bk, GpsCrypto.b64(bytes), "a>b") }.isFailure)
                    } finally {
                        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("orbit_test_gps_a"); deleteEntry("orbit_test_gps_b") }
                    }
                    result.putString("qr", id.createQr(id.profileName(), id.profileIcon(), uid, key))
                    result.putString("uid", uid)
                    result.putString("stream", "PASS: live anonymous auth, non-exportable ECDH agreement, AES-GCM round-trip, AAD and tamper rejection. No coordinates collected.\n")
                }
                "link" -> {
                    test.runOnMainSync {
                        val vm = (context.applicationContext as OrbitApplication).model
                        vm.acceptQr(args.getString("qr")!!)
                        check(vm.state.value.pendingGpsLink != null) { "Signed QR did not match an existing trusted friend" }
                        vm.acceptGpsLink(true)
                    }
                    // Instrumentation exits immediately; wait for the app's asynchronous preference write.
                    check(context.getSharedPreferences("orbit_friends", 0).edit().commit())
                    check(FriendStore(context).load().any { it.firebaseUid.isNotBlank() })
                    result.putString("stream", "PASS: signed QR verified against existing paired identity; GPS link saved.\n")
                }
                else -> {
                    val friend = FriendStore(context).load().first { it.firebaseUid == args.getString("peerUid") }
                    val peer = friend.firebaseUid
                    val ownConsent = db.getReference("consents/$uid/$peer")
                    val ownLocation = db.getReference("locations/$uid/$peer")
                    val remote = db.getReference("locations/$peer/$uid")
                    when(mode) {
                        "consent" -> Tasks.await(ownConsent.setValue(ServerValue.TIMESTAMP), 20, TimeUnit.SECONDS)
                        "send" -> {
                            Tasks.await(ownConsent.setValue(ServerValue.TIMESTAMP), 20, TimeUnit.SECONDS)
                            val packet = GpsCrypto.encrypt(crypto.key(friend.gpsPublicKey), "ORBIT_INSTRUMENTATION_TEST", "$uid>$peer")
                            Tasks.await(ownLocation.setValue(mapOf("cipher" to packet, "updated" to ServerValue.TIMESTAMP)), 20, TimeUnit.SECONDS)
                        }
                        "receive" -> {
                            Tasks.await(ownConsent.setValue(ServerValue.TIMESTAMP), 20, TimeUnit.SECONDS)
                            val packet = Tasks.await(remote.get(), 20, TimeUnit.SECONDS).child("cipher").getValue(String::class.java)!!
                            check(GpsCrypto.decrypt(crypto.key(friend.gpsPublicKey), packet, "$peer>$uid") == "ORBIT_INSTRUMENTATION_TEST")
                        }
                        "cleanup" -> {
                            Tasks.await(ownLocation.removeValue(), 20, TimeUnit.SECONDS)
                            Tasks.await(ownConsent.removeValue(), 20, TimeUnit.SECONDS)
                        }
                        "denied" -> check(runCatching { Tasks.await(remote.get(), 20, TimeUnit.SECONDS) }.isFailure) { "Remote read unexpectedly allowed without both consents" }
                        else -> error("Unknown mode")
                    }
                    result.putString("stream", "PASS: live Firebase $mode. Synthetic non-location payload only.\n")
                }
            }
            test.finish(Activity.RESULT_OK, result)
        } catch (e: Exception) {
            result.putString("stream", "FAIL: ${e.javaClass.simpleName}: ${e.message}\n")
            test.finish(Activity.RESULT_CANCELED, result)
        }
    }
}
