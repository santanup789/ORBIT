package app.orbit.finder.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import app.orbit.finder.core.Friend
import app.orbit.finder.core.TrustState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import org.json.JSONObject

data class GpsUi(val active: Boolean = false, val connected: Boolean = false, val mutual: Boolean = false,
    val retryable: Boolean = false,
    val status: String = "GPS sharing is off", val own: GeoFix? = null, val peer: GeoFix? = null,
    val heading: Double? = null, val headingTime: Long = 0, val now: Long = System.currentTimeMillis())

/** Foreground-only, one trusted friend at a time; no disk persistence of locations. */
class GpsRelay(private val context: Context, private val update: (GpsUi) -> Unit, private val accepted: () -> Unit,
    private val networkProbe: (() -> Boolean)? = null) {
    private val main = Handler(Looper.getMainLooper())
    private val crypto = GpsCrypto()
    private val connectionCheck = FirebaseConnection(context)
    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseDatabase.getInstance(FirebaseConnection.DATABASE_URL)
    private var state = GpsUi()
    private var generation = 0
    private var uid = ""
    private var peerUid = ""
    private var key: ByteArray? = null
    private var peerConsent = 0L
    private var ownConsent = false
    private var online = false
    private var offset = 0L
    private var endAt = 0L
    private var lastSend = 0L
    private var lastRenew = 0L
    private var notified = false
    private var failure: String? = null
    private var lastHeadingUpdate = 0L
    private val observers = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var remoteObserver: Pair<DatabaseReference, ValueEventListener>? = null
    private val sensors = GpsSensors(context, { sample -> emit(state.copy(own = sample)); publish(sample) },
        { value, time -> if (value == null || time - lastHeadingUpdate >= 200) { lastHeadingUpdate = time; emit(state.copy(heading = value, headingTime = time)) } },
        { message -> emit(state.copy(status = message)) })
    private fun emit(value: GpsUi) { state = value; update(value) }
    private fun consent(owner: String, reader: String) = db.getReference("consents/$owner/$reader")
    private fun location(owner: String, reader: String) = db.getReference("locations/$owner/$reader")
    private fun networkAvailable(): Boolean {
        networkProbe?.let { return it() }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }
    fun prepare(done: (String?, String?, String?) -> Unit) {
        try {
            val publicKey = crypto.publicKey()
            val user = auth.currentUser
            if (user != null) user.getIdToken(true).addOnCompleteListener { task ->
                if (task.isSuccessful) done(user.uid, publicKey, null) else done(null, null, "Cannot sign in. Check mobile data or Wi-Fi.")
            } else auth.signInAnonymously().addOnCompleteListener { task ->
                if (task.isSuccessful) done(auth.currentUser!!.uid, publicKey, null)
                else done(null, null, "Anonymous sign-in failed. Check your internet connection.")
            }
        } catch (e: Exception) { done(null, null, e.message ?: "Firebase setup unavailable") }
    }
    fun start(friend: Friend) {
        stop()
        if (friend.trust != TrustState.TRUSTED || friend.firebaseUid.isBlank() || friend.gpsPublicKey.isBlank()) {
            emit(state.copy(status = "Keep both updated phones nearby and online to finish automatic GPS setup.")); return
        }
        if (!networkAvailable()) { fail("No internet connection. Turn on Wi-Fi or mobile data, then retry."); return }
        val attempt = generation
        emit(GpsUi(active = true, status = "Checking online connection…"))
        connectionCheck.check(report = { _, _ -> }, completed = { connected ->
            if (generation != attempt || !state.active) return@check
            if (!connected) {
                fail(if (!networkAvailable()) "No internet connection. Turn on Wi-Fi or mobile data, then retry."
                    else "Unable to connect to the location service. Please retry.")
            } else startConnected(friend, attempt)
        })
    }
    private fun startConnected(friend: Friend, attempt: Int) {
        emit(state.copy(status = "Starting secure sharing…"))
        endAt = System.currentTimeMillis() + 15 * 60_000
        main.post(tick)
        prepare { me, _, error ->
            if (generation != attempt || !state.active) return@prepare
            if (error != null || me == null) { fail("Unable to connect to the location service. Please retry."); return@prepare }
            if (friend.firebaseUid == me) { fail("This GPS link belongs to your own phone. Link your friend’s GPS QR again."); return@prepare }
            try { key = crypto.key(friend.gpsPublicKey) } catch (_: Exception) { fail("The GPS link is invalid. Scan your friend’s GPS QR again."); return@prepare }
            uid = me; peerUid = friend.firebaseUid
            if (!sensors.start()) { fail("Turn on Location and allow precise location, then retry."); return@prepare }
            observe(db.getReference(".info/serverTimeOffset"), attempt) { offset = (it.value as? Number)?.toLong() ?: 0L }
            observe(consent(peerUid, uid), attempt) { peerConsent = (it.value as? Number)?.toLong() ?: 0; reconcile() }
            observe(db.getReference(".info/connected"), attempt) { snapshot ->
                online = snapshot.getValue(Boolean::class.java) == true
                if (online) {
                    ownConsent = false
                    // Register removal at the server BEFORE granting consent.
                    consent(uid, peerUid).onDisconnect().removeValue { error1, _ ->
                        if (generation != attempt || !online) return@removeValue
                        if (error1 != null) { failure = "Unable to start secure sharing. Please retry."; reconcile(); return@removeValue }
                        location(uid, peerUid).onDisconnect().removeValue { error2, _ ->
                            if (generation != attempt || !online) return@removeValue
                            if (error2 != null) { failure = "Unable to start secure sharing. Please retry."; reconcile(); return@removeValue }
                            renew(attempt)
                        }
                    }
                } else { ownConsent = false; emit(state.copy(peer = null)) }
                reconcile()
            }
        }
    }
    private fun observe(ref: DatabaseReference, attempt: Int, changed: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { if (attempt == generation) changed(snapshot) }
            override fun onCancelled(error: DatabaseError) { if (attempt == generation) { failure = "Unable to connect to the location service. Please retry."; reconcile() } }
        }
        observers.add(ref to listener); ref.addValueEventListener(listener)
    }
    private fun renew(attempt: Int) {
        if (!online || !networkAvailable() || uid.isBlank()) return
        lastRenew = System.currentTimeMillis()
        consent(uid, peerUid).setValue(ServerValue.TIMESTAMP) { error, _ ->
            if (generation != attempt) return@setValue
            ownConsent = error == null
            failure = if (error != null) "Unable to start secure sharing. Please retry." else null
            reconcile()
        }
    }
    private fun reconcile() {
        if (!state.active || uid.isBlank()) return
        val connected = online && networkAvailable()
        val mutual = failure == null && connected && ownConsent && System.currentTimeMillis() + offset - peerConsent in 0..30_000
        emit(state.copy(connected = connected, mutual = mutual, retryable = failure != null || !connected,
            status = when {
                failure != null -> failure!!
                !connected -> if (!networkAvailable()) "No internet connection. Turn on Wi-Fi or mobile data, then retry." else "Connection interrupted. Reconnecting… or tap Retry."
                !ownConsent -> "Starting secure sharing…"
                !mutual -> "Waiting for your friend to tap START GPS on their phone"
                state.own == null -> "Both accepted — waiting for an outdoor location fix"
                else -> "Both accepted — encrypted GPS sharing active"
            }, peer = if (mutual) state.peer else null))
        if (mutual && !notified) { notified = true; accepted() }
        if (!mutual) {
            remoteObserver?.let { it.first.removeEventListener(it.second) }; remoteObserver = null
        } else if (remoteObserver == null) {
            val attempt = generation
            val ref = location(peerUid, uid)
            val observer = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (attempt != generation || !state.mutual) return
                    if (!snapshot.exists()) { emit(state.copy(peer = null)); return }
                    runCatching {
                        val packet = snapshot.child("cipher").getValue(String::class.java) ?: error("Missing cipher")
                        val body = JSONObject(GpsCrypto.decrypt(key!!, packet, "$peerUid>$uid"))
                        require(body.getString("from") == peerUid && body.getString("to") == uid)
                        val sample = GeoFix(body.getDouble("lat"), body.getDouble("lon"), body.getDouble("accuracy").toFloat(), body.getLong("time") - offset, body.optString("provider", "Location"))
                        require(sample.valid())
                        if (sample.time >= (state.peer?.time ?: 0)) emit(state.copy(peer = sample))
                    }.onFailure { emit(state.copy(peer = null, status = "Rejected invalid GPS data. Scan each other's GPS QR again.")) }
                }
                override fun onCancelled(error: DatabaseError) {
                    if (attempt == generation) { remoteObserver = null; failure = "Unable to receive your friend’s location. Please retry."; reconcile() }
                }
            }
            remoteObserver = ref to observer; ref.addValueEventListener(observer)
        }
    }
    private fun publish(sample: GeoFix) {
        val now = System.currentTimeMillis()
        if (!state.active || !state.mutual || !networkAvailable() || !sample.fresh(now) || now - lastSend < 3000) return
        lastSend = now
        val attempt = generation
        val body = JSONObject().put("from", uid).put("to", peerUid).put("lat", sample.lat).put("lon", sample.lon)
            .put("accuracy", sample.accuracy).put("time", sample.time + offset).put("provider", sample.provider).toString()
        val cipher = GpsCrypto.encrypt(key!!, body, "$uid>$peerUid")
        location(uid, peerUid).setValue(mapOf("cipher" to cipher, "updated" to ServerValue.TIMESTAMP)) { error, _ ->
            if (generation == attempt && error != null) { failure = "Unable to send your location. Please retry."; reconcile() }
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!state.active) return
            if (uid.isBlank() && System.currentTimeMillis() > endAt - 15 * 60_000 + 25_000) { fail("Connection timed out. Please retry."); return }
            if (System.currentTimeMillis() >= endAt) { stop("15-minute sharing session ended. Tap START GPS to share again."); return }
            if (uid.isNotBlank()) {
                if (ownConsent && System.currentTimeMillis() - lastRenew >= 10_000) renew(generation)
                reconcile()
                state.own?.let { publish(it) }
            }
            emit(state.copy(now = System.currentTimeMillis()))
            main.postDelayed(this, 1000)
        }
    }
    fun stop(message: String = "GPS sharing stopped") {
        connectionCheck.close()
        generation++; main.removeCallbacks(tick); sensors.stop()
        observers.forEach { it.first.removeEventListener(it.second) }; observers.clear()
        remoteObserver?.let { it.first.removeEventListener(it.second) }; remoteObserver = null
        if (uid.isNotBlank() && peerUid.isNotBlank()) {
            // Prevent queued coordinates being uploaded after the user stops offline.
            db.purgeOutstandingWrites()
            location(uid, peerUid).removeValue()
            consent(uid, peerUid).removeValue()
        }
        uid = ""; peerUid = ""; key?.fill(0); key = null
        ownConsent = false; peerConsent = 0; online = false; notified = false; lastSend = 0; lastRenew = 0
        failure = null; lastHeadingUpdate = 0
        emit(GpsUi(status = message))
    }
    private fun fail(message: String) { stop(message); emit(state.copy(retryable = true)) }
}
