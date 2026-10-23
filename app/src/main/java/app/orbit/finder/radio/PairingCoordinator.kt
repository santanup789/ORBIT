package app.orbit.finder.radio

import android.bluetooth.BluetoothDevice
import android.os.Handler
import android.os.Looper
import app.orbit.finder.core.*
import org.json.JSONObject
import java.util.UUID

data class PairingRequest(val connection: String, val peer: PairingPayload)

/** Authenticated consent only. Public pairing metadata is not encrypted application data. */
class PairingCoordinator(private val identity: IdentityStore,
    private val send: (String, String) -> Unit,
    private val connect: (BluetoothDevice, String) -> Unit,
    private val finish: () -> Unit,
    private val onRequest: (PairingRequest) -> Unit,
    private val onDismiss: (String) -> Unit,
    private val onTrusted: (PairingPayload) -> Unit,
    private val status: (String) -> Unit,
    private val onPending: (String?) -> Unit = {},
    private val localPayload: () -> String = { identity.createQr(identity.profileName(), identity.profileIcon()) },
    private val isTrusted: (PairingPayload) -> Boolean = { false },
    private val onLinked: (PairingPayload) -> Unit = {}) {
    private data class Session(val challenge: String, val deadline: Long, var peer: PairingPayload? = null, var phase: String = "HELLO", var requestId: String = "", val expectedShortId: String? = null, val upgrade: Boolean = false)
    private val sessions = mutableMapOf<String, Session>()
    private var target: PairingPayload? = null
    private var outgoingKey: String? = null
    private var upgrading = false
    fun upgrade(peer: PairingPayload): Boolean {
        if (outgoingKey != null || target != null || !isTrusted(peer)) return false
        request(peer)
        upgrading = true
        return true
    }
    private val main = Handler(Looper.getMainLooper())
    private var expiry: Runnable? = null
    fun requestNearby(peer: NearbyPeer, device: BluetoothDevice?) {
        if (outgoingKey != null || target != null) { status("A request is already pending. Cancel it before connecting again."); return }
        if (device == null || System.currentTimeMillis() - peer.seenMs > 15_000) { status("Device is no longer nearby. Refresh and try again."); return }
        val key = "out:${peer.shortId}"
        outgoingKey = key
        sessions[key] = Session("", System.currentTimeMillis() + 120_000, expectedShortId = peer.shortId)
        onPending(peer.shortId)
        expiry?.let(main::removeCallbacks)
        expiry = Runnable { cancel(); status("Pairing timed out. Keep both phones open and tap Connect again.") }.also { main.postDelayed(it, 120_000) }
        status("Connecting to nearby ORBIT device ${peer.shortId.take(8)}…")
        connect(device, key)
    }
    fun request(peer: PairingPayload) {
        if (target?.id == peer.id) { status("Pairing already in progress. Waiting for the other phone."); return }
        finish()
        target = peer
        upgrading = false
        onPending(peer.id.take(16))
        outgoingKey = null
        expiry?.let(main::removeCallbacks)
        expiry = Runnable { cancel(); status("Pairing timed out. Keep both phones nearby and tap Retry pairing.") }.also { main.postDelayed(it, 120_000) }
        status("Looking for ${peer.name} to send the pairing request…")
    }
    fun discovered(peer: NearbyPeer, device: BluetoothDevice?) {
        val wanted = target ?: return
        if (outgoingKey == null && wanted.id.startsWith(peer.shortId) && device != null) {
            val key = "out:${wanted.id}"
            outgoingKey = key
            sessions[key] = Session("", System.currentTimeMillis() + 120_000, wanted, upgrade = upgrading)
            status("Connecting to ${wanted.name}…")
            connect(device, key)
        }
    }
    fun opened(key: String) {
        val session = Session(UUID.randomUUID().toString(), System.currentTimeMillis() + 120_000)
        sessions[key] = session
        send(key, JSONObject().put("type", "HELLO").put("challenge", session.challenge)
            .put("qr", localPayload()).toString())
    }
    fun closed(key: String) {
        val session = sessions.remove(key)
        onDismiss(key)
        if (outgoingKey == key) {
            outgoingKey = null; target = null; expiry?.let(main::removeCallbacks)
            onPending(null)
            if (session != null && session.phase != "DONE") status("Pairing connection ended. Tap Retry pairing.")
        }
    }
    private fun signed(type: String, session: Session, peer: PairingPayload, qr: Boolean = false): String = identity.signMessage(JSONObject().apply {
        put("type", type); put("challenge", session.challenge); put("request", session.requestId)
        put("to", peer.id); put("from", identity.identity().id)
        if (qr) put("qr", localPayload())
    }.toString())
    fun message(key: String, packet: String) {
        runCatching {
            require(packet.toByteArray(Charsets.UTF_8).size <= PairingFrames.MAX_BYTES) { "Pairing message too large" }
            val session = requireNotNull(sessions[key])
            require(System.currentTimeMillis() < session.deadline) { "Pairing request expired" }
            val outer = JSONObject(packet)
            if (key.startsWith("out:") && session.phase == "HELLO") {
                require(outer.getString("type") == "HELLO")
                val peer = identity.parseQr(outer.getString("qr")).getOrThrow()
                if (session.peer != null) {
                    require(peer.id == session.peer!!.id && peer.publicKey == session.peer!!.publicKey) { "Nearby identity does not match scanned QR" }
                } else {
                    require(session.expectedShortId != null && peer.id.startsWith(session.expectedShortId)) { "Nearby identity does not match discovered device" }
                }
                val challenge = outer.getString("challenge")
                require(challenge.length in 16..64)
                val ready = Session(challenge, session.deadline, peer, "REQUEST", UUID.randomUUID().toString(), upgrade = session.upgrade)
                sessions[key] = ready
                send(key, signed(if (ready.upgrade) "LINK" else "REQUEST", ready, peer, true))
                status(if (ready.upgrade) "Setting up GPS with ${peer.name}…" else "Request sent to ${peer.name} — waiting for Accept on their phone. Your identity: ${identity.identity().id.chunked(4).joinToString(" ")}")
                return
            }
            val unsigned = JSONObject(outer.getString("body"))
            var peer = session.peer ?: identity.parseQr(unsigned.getString("qr")).getOrThrow()
            val body = identity.verifyMessage(packet, peer.publicKey)
            require(body.getString("to") == identity.identity().id && body.getString("from") == peer.id)
            require(body.getString("challenge") == session.challenge) { "Pairing challenge mismatch" }
            val type = body.getString("type")
            if (body.has("qr")) {
                val refreshed = identity.parseQr(body.getString("qr")).getOrThrow()
                require(refreshed.id == peer.id && refreshed.publicKey == peer.publicKey)
                peer = refreshed
                session.peer = peer
            }
            if ((type == "REQUEST" || type == "LINK") && key.startsWith("in:") && session.phase == "HELLO") {
                val requestId = body.getString("request")
                require(requestId.length in 16..64)
                session.requestId = requestId; session.peer = peer; session.phase = "REQUEST"
                if (type == "LINK") {
                    require(isTrusted(peer)) { "GPS setup requires an existing trusted friend" }
                    require(peer.firebaseUid.isNotBlank() && peer.gpsPublicKey.isNotBlank()) { "Other phone is still preparing online mode" }
                    val ready = session.copy(upgrade = true, phase = "ACCEPT")
                    sessions[key] = ready
                    send(key, signed("ACCEPT", ready, peer, true))
                } else onRequest(PairingRequest(key, peer))
            } else {
                require(body.getString("request") == session.requestId)
                when {
                    type == "ACCEPT" && key.startsWith("out:") && session.phase == "REQUEST" -> {
                        session.phase = "CONFIRM"; send(key, signed("CONFIRM", session, peer, true)); status(if (session.upgrade) "Confirming GPS setup…" else "${peer.name} accepted — confirming pairing…")
                    }
                    type == "REJECT" && key.startsWith("out:") && session.phase == "REQUEST" -> {
                        session.phase = "DONE"; finish(); status("${peer.name} declined the pairing request")
                    }
                    type == "CONFIRM" && key.startsWith("in:") && session.phase == "ACCEPT" -> {
                        session.phase = "DONE"; onDismiss(key); if (session.upgrade) onLinked(peer) else onTrusted(peer); send(key, signed("DONE", session, peer))
                    }
                    type == "DONE" && key.startsWith("out:") && session.phase == "CONFIRM" -> {
                        session.phase = "DONE"; if (session.upgrade) onLinked(peer) else onTrusted(peer); finish()
                    }
                    else -> error("Unexpected pairing message")
                }
            }
        }.onFailure {
            sessions.remove(key); onDismiss(key)
            if (outgoingKey == key) cancel()
            status("Pairing failed: ${it.message ?: "invalid message"}")
        }
    }
    fun decide(key: String, accept: Boolean) {
        val session = sessions[key] ?: return
        if (session.phase != "REQUEST" || System.currentTimeMillis() >= session.deadline) { onDismiss(key); status("Request expired. Ask the other phone to retry."); return }
        val peer = session.peer ?: return
        session.phase = if (accept) "ACCEPT" else "REJECT"
        send(key, signed(session.phase, session, peer, accept))
        onDismiss(key)
        status(if (accept) "Acceptance sent to ${peer.name}; waiting for confirmation…" else "Pairing declined")
    }
    fun cancel() { finish(); target = null; outgoingKey = null; expiry?.let(main::removeCallbacks); onPending(null) }
}
