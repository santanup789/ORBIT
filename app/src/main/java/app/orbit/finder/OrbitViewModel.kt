package app.orbit.finder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.orbit.finder.core.*
import app.orbit.finder.haptics.HapticGuide
import app.orbit.finder.radio.*
import app.orbit.finder.online.FirebaseConnection
import app.orbit.finder.online.GpsRelay
import app.orbit.finder.online.GpsUi
import app.orbit.finder.online.GpsCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

enum class Screen { ONBOARDING, HOME, MY_QR, SCAN_QR, FINDING, GPS, SETTINGS, DIAGNOSTICS }
data class OrbitState(
    val screen: Screen = Screen.ONBOARDING,
    val profileName: String = "",
    val defaultDeviceName: String = "Android phone",
    val usesDefaultName: Boolean = true,
    val profileIcon: DeviceIcon = DeviceIcon.ORB,
    val friends: List<Friend> = emptyList(),
    val nearby: List<NearbyPeer> = emptyList(),
    val capabilities: Capabilities = Capabilities(),
    val status: String = "Ready",
    val pairingStatus: String = "",
    val pairingPending: String? = null,
    val requests: List<PairingRequest> = emptyList(),
    val selectedId: String? = null,
    val haptics: Boolean = true,
    val reducedMotion: Boolean = false,
    val metric: Boolean = true,
    val firebaseStatus: String = "Not checked. No location is being shared.",
    val firebaseChecking: Boolean = false,
    val onlineUid: String = "",
    val gpsPublicKey: String = "",
    val onlinePreparing: Boolean = false,
    val onlineMessage: String = "GPS setup is automatic with nearby paired friends. No extra QR scan.",
    val pendingGpsLink: PairingPayload? = null,
    val gps: GpsUi = GpsUi(),
    val mapEnabled: Boolean = false
)

class OrbitViewModel(app: Application) : AndroidViewModel(app) {
    private val identityStore = IdentityStore(app)
    private val friendStore = FriendStore(app)
    private val prefs = app.getSharedPreferences("orbit_settings", 0)
    private val haptics = HapticGuide(app)
    private val _state = MutableStateFlow(OrbitState())
    val state = _state.asStateFlow()
    val identity = identityStore.identity()
    private val notices = PairingNotifications(app)
    private val firebaseConnection = FirebaseConnection(app)
    private var lastOnlinePrepare = 0L
    private var lastLinkAttempt = 0L
    private var onlinePreparedAt = 0L
    private var prepareGeneration = 0
    private val gpsRelay = GpsRelay(app, { value -> _state.value = _state.value.copy(gps = value) }, {
        notices.show("gps_session", "GPS sharing accepted", "Both phones accepted. Encrypted location sharing is active while ORBIT stays open.")
    })

    fun prepareGpsQr() {
        if (_state.value.onlinePreparing) return
        lastOnlinePrepare = System.currentTimeMillis()
        val attempt = ++prepareGeneration
        _state.value = _state.value.copy(onlinePreparing = true, onlineMessage = "Preparing online mode…")
        viewModelScope.launch {
            delay(25_000)
            if (attempt == prepareGeneration && _state.value.onlinePreparing) {
                prepareGeneration++
                _state.value = _state.value.copy(onlinePreparing = false, onlineMessage = "Online setup timed out. Check internet and tap Retry setup.")
            }
        }
        gpsRelay.prepare { uid, key, error ->
            if (attempt != prepareGeneration) return@prepare
            _state.value = _state.value.copy(onlinePreparing = false, onlineUid = uid ?: "", gpsPublicKey = key ?: "",
                onlineMessage = if (error != null) "Online setup unavailable. Check internet and tap Retry setup." else "Online mode ready. GPS setup completes automatically with nearby friends.")
            if (error == null) { onlinePreparedAt = System.currentTimeMillis(); syncGpsLinks() }
        }
    }
    private fun saveGpsLink(peer: PairingPayload) {
        val friend = _state.value.friends.firstOrNull { it.id == peer.id && it.publicKey == peer.publicKey && it.trust == TrustState.TRUSTED } ?: return
        if (peer.firebaseUid.isBlank() || peer.gpsPublicKey.isBlank() || peer.firebaseUid == _state.value.onlineUid) return
        if (runCatching { GpsCrypto().key(peer.gpsPublicKey).fill(0) }.isFailure) return
        if (friend.firebaseUid == peer.firebaseUid && friend.gpsPublicKey == peer.gpsPublicKey) return
        if (_state.value.gps.active) stopGps()
        persist(_state.value.friends.map { if (it.id == peer.id) it.copy(firebaseUid = peer.firebaseUid, gpsPublicKey = peer.gpsPublicKey) else it })
        setPairingStatus("GPS ready with ${peer.name}. Start GPS when you both want to share.")
    }
    fun retryGpsSetup() { lastLinkAttempt = 0; if (_state.value.onlineUid.isBlank()) prepareGpsQr() else syncGpsLinks(force = true) }
    private fun syncGpsLinks(force: Boolean = false) {
        val s = _state.value
        if (s.onlineUid.isBlank() || s.pairingPending != null || s.requests.isNotEmpty()) return
        val now = System.currentTimeMillis()
        if (!force && now - lastLinkAttempt < 30_000) return
        val friend = s.friends.firstOrNull { f ->
            f.trust == TrustState.TRUSTED && (f.firebaseUid.isBlank() || f.gpsPublicKey.isBlank()) &&
                s.nearby.any { f.id.startsWith(it.shortId) } &&
                (force || identity.id < f.id || now - onlinePreparedAt > 15_000)
        } ?: return
        lastLinkAttempt = now
        if (pairing.upgrade(PairingPayload(friend.id, friend.name, friend.icon, friend.publicKey, Long.MAX_VALUE))) {
            s.nearby.forEach { pairing.discovered(it, ble.device(it.shortId)) }
        }
    }
    fun acceptGpsLink(accept: Boolean) {
        val peer = _state.value.pendingGpsLink ?: return
        if (accept) {
            gpsRelay.stop("GPS identity updated; start a new sharing session.")
            persist(_state.value.friends.map { if (it.id == peer.id) it.copy(firebaseUid = peer.firebaseUid, gpsPublicKey = peer.gpsPublicKey) else it })
        }
        _state.value = _state.value.copy(pendingGpsLink = null, status = if (accept) "GPS link saved for ${peer.name}" else "GPS link cancelled")
    }
    fun openGps() { _state.value = _state.value.copy(screen = Screen.GPS); retryGpsSetup() }
    fun startGps(map: Boolean) {
        val friend = _state.value.friends.firstOrNull { it.id == _state.value.selectedId } ?: return
        _state.value = _state.value.copy(mapEnabled = map)
        gpsRelay.start(friend)
    }
    fun stopGps() { gpsRelay.stop(); notices.cancel("gps_session") }
    fun foregroundEnded() { if (_state.value.gps.active) { gpsRelay.stop("Sharing stopped because ORBIT left the foreground. Start again to share."); notices.cancel("gps_session") } }

    fun checkFirebase() {
        if (_state.value.firebaseChecking) return
        firebaseConnection.check(report = { message, checking ->
            _state.value = _state.value.copy(firebaseStatus = message, firebaseChecking = checking)
        })
    }
    private val transport: PairingTransport by lazy { PairingTransport(app, { pairing.opened(it) }, { key, packet -> pairing.message(key, packet) }, { pairing.closed(it) }, { setPairingStatus(it) }) }
    private val pairing: PairingCoordinator by lazy { PairingCoordinator(identityStore,
        { key, message -> transport.send(key, message) }, { device, key -> transport.connect(device, key) }, { transport.closeClient(null) },
        { request ->
            _state.value = _state.value.copy(requests = _state.value.requests.filterNot { it.connection == request.connection } + request)
            notices.show(request.connection, "Pairing request", "${request.peer.name} wants to pair. Tap to Accept or Decline.")
        }, { key ->
            _state.value = _state.value.copy(requests = _state.value.requests.filterNot { it.connection == key }); notices.cancel(key)
        }, { peer ->
            val existing = _state.value.friends.firstOrNull { it.id == peer.id && it.publicKey == peer.publicKey }
            val nearby = _state.value.nearby.firstOrNull { peer.id.startsWith(it.shortId) }
            persist(_state.value.friends.filterNot { it.id == peer.id || (it.publicKey.isBlank() && peer.id.startsWith(it.id)) } +
                (existing?.copy(trust = TrustState.TRUSTED) ?: Friend(peer.id, peer.publicKey, peer.name, peer.icon, TrustState.TRUSTED)).let {
                    if (nearby != null) it.copy(presence = Presence.NEARBY, rssi = nearby.rssi, lastSeenMs = nearby.seenMs) else it
                })
            setPairingStatus("Paired with ${peer.name}")
            saveGpsLink(peer)
            notices.show(peer.id, "Pairing accepted", "${peer.name} is now a trusted device.")
        }, ::setPairingStatus, { _state.value = _state.value.copy(pairingPending = it) },
        { qrPayload() },
        { peer -> _state.value.friends.any { it.id == peer.id && it.publicKey == peer.publicKey && it.trust == TrustState.TRUSTED } },
        ::saveGpsLink) }
    private val ble by lazy { BleDiscovery(app, ::onPeer, ::setStatus, { ready -> transport.start(ready) }, { transport.stop() }) }

    init {
        val name = identityStore.profileName()
        _state.value = _state.value.copy(
            screen = if (!identityStore.hasProfile()) Screen.ONBOARDING else Screen.HOME,
            defaultDeviceName = identityStore.defaultDeviceName(), usesDefaultName = identityStore.usesDefaultName(),
            profileName = name, profileIcon = identityStore.profileIcon(), friends = friendStore.load(),
            haptics = prefs.getBoolean("haptics", true), reducedMotion = prefs.getBoolean("motion", false), metric = prefs.getBoolean("metric", true)
        )
        viewModelScope.launch { _state.value = _state.value.copy(capabilities = CapabilityProbe(app).read()) }
        viewModelScope.launch {
            while (true) {
                delay(3_000)
                val cutoff = System.currentTimeMillis() - 15_000
                _state.value = _state.value.copy(nearby = _state.value.nearby.filter { it.seenMs >= cutoff }, friends = _state.value.friends.map { if (it.lastSeenMs < cutoff) it.copy(presence = Presence.OFFLINE, rssi = null) else it })
                syncGpsLinks()
            }
        }
    }

    fun completeOnboarding(name: String, icon: DeviceIcon) {
        if (name.trim().isBlank()) { setStatus("Enter a display name"); return }
        identityStore.saveProfile(name, icon)
        if (name.trim() == identityStore.defaultDeviceName()) identityStore.useDefaultName()
        _state.value = _state.value.copy(profileName = identityStore.profileName(), usesDefaultName = identityStore.usesDefaultName(), profileIcon = icon, screen = Screen.HOME)
        startNearby()
    }
    fun updateDeviceName(customName: String?) {
        if (customName == null) identityStore.useDefaultName()
        else {
            val name = customName.trim()
            if (name.isBlank() || name.length > 40 || name.any { it.isISOControl() }) return
            identityStore.saveProfile(name, _state.value.profileIcon)
        }
        _state.value = _state.value.copy(profileName = identityStore.profileName(),
            defaultDeviceName = identityStore.defaultDeviceName(), usesDefaultName = identityStore.usesDefaultName())
    }
    fun navigate(screen: Screen) { _state.value = _state.value.copy(screen = screen, status = "Ready") }
    fun requiredPermissions() = ble.requiredPermissions()
    fun startNearby() {
        ble.start(identity)
        if (_state.value.screen != Screen.ONBOARDING && _state.value.onlineUid.isBlank() && System.currentTimeMillis() - lastOnlinePrepare > 30_000) prepareGpsQr()
        if (requiredPermissions().all { androidx.core.content.ContextCompat.checkSelfPermission(getApplication(), it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(getApplication(), android.content.Intent(getApplication(), NearbyService::class.java)) }
                .onFailure { setStatus("Nearby works while ORBIT is open; background service unavailable") }
        }
    }
    fun refreshNearby() {
        _state.value = _state.value.copy(profileName = identityStore.profileName(), defaultDeviceName = identityStore.defaultDeviceName(), usesDefaultName = identityStore.usesDefaultName())
        startNearby()
    }
    fun stopNearby() { pairing.cancel(); ble.stop(); setStatus("Nearby stopped"); getApplication<Application>().stopService(android.content.Intent(getApplication(), NearbyService::class.java)) }
    fun decidePairing(key: String, accept: Boolean) = pairing.decide(key, accept)
    fun setPairingStatus(value: String) { _state.value = _state.value.copy(pairingStatus = value) }
    fun qrPayload() = identityStore.createQr(_state.value.profileName, _state.value.profileIcon, _state.value.onlineUid, _state.value.gpsPublicKey)
    fun acceptQr(raw: String) {
        identityStore.parseQr(raw).onSuccess { p ->
            val items = _state.value.friends.toMutableList()
            if (items.any { it.id == p.id && it.trust == TrustState.TRUSTED }) {
                val saved = items.first { it.id == p.id }
                if (saved.publicKey != p.publicKey) { setStatus("Saved identity does not match"); return }
                _state.value = _state.value.copy(screen = Screen.HOME, pendingGpsLink = if (p.firebaseUid.isNotBlank()) p else null,
                    status = if (p.firebaseUid.isNotBlank()) "Confirm this friend's GPS link" else "Already paired. GPS setup completes automatically when both updated phones are nearby and online.")
                return
            }
            items.removeAll { it.id == p.id || (it.publicKey.isBlank() && p.id.startsWith(it.id)) }
            items += Friend(p.id, p.publicKey, p.name, p.icon, TrustState.PENDING)
            persist(items); _state.value = _state.value.copy(screen = Screen.HOME)
            startNearby()
            pairing.request(p)
            _state.value.nearby.forEach { pairing.discovered(it, ble.device(it.shortId)) }
        }.onFailure { setStatus(it.message ?: "Invalid pairing code") }
    }
    fun addNearby(peer: NearbyPeer) {
        _state.value.friends.firstOrNull { it.id.startsWith(peer.shortId) && it.trust == TrustState.TRUSTED }?.let {
            beginFinding(it.id)
            return
        }
        pairing.requestNearby(peer, ble.device(peer.shortId))
    }
    fun cancelPairing() { pairing.cancel(); setPairingStatus("Pairing request cancelled") }
    fun retryPairing(id: String) {
        val friend = _state.value.friends.firstOrNull { it.id == id } ?: return
        if (friend.publicKey.isBlank()) { setPairingStatus("This old pending entry has no identity. Scan the other phone's QR to pair."); return }
        startNearby(); pairing.request(PairingPayload(friend.id, friend.name, friend.icon, friend.publicKey, Long.MAX_VALUE))
        _state.value.nearby.forEach { pairing.discovered(it, ble.device(it.shortId)) }
    }
    fun beginFinding(id: String) {
        if (_state.value.friends.firstOrNull { it.id == id }?.trust != TrustState.TRUSTED) { retryPairing(id); return }
        _state.value = _state.value.copy(selectedId = id, screen = Screen.FINDING); startNearby()
    }
    fun stopFinding() { stopGps(); haptics.stop(); _state.value = _state.value.copy(selectedId = null, screen = Screen.HOME) }
    fun forget(id: String) { stopGps(); pairing.cancel(); persist(_state.value.friends.filterNot { it.id == id }); if (_state.value.selectedId == id) stopFinding() }
    fun rename(id: String, name: String) { persist(_state.value.friends.map { if (it.id == id) it.copy(name = name.take(40)) else it }) }
    fun setHaptics(v: Boolean) { prefs.edit().putBoolean("haptics", v).apply(); _state.value = _state.value.copy(haptics = v); if (!v) haptics.stop() }
    fun previewHaptic() = haptics.preview()
    fun setReducedMotion(v: Boolean) { prefs.edit().putBoolean("motion", v).apply(); _state.value = _state.value.copy(reducedMotion = v) }
    fun setMetric(v: Boolean) { prefs.edit().putBoolean("metric", v).apply(); _state.value = _state.value.copy(metric = v) }
    fun setStatus(s: String) { _state.value = _state.value.copy(status = s) }

    private fun persist(items: List<Friend>) { friendStore.save(items); _state.value = _state.value.copy(friends = items) }
    private fun onPeer(peer: NearbyPeer) {
        val n = (_state.value.nearby.filterNot { it.shortId == peer.shortId } + peer).sortedByDescending { it.seenMs }.take(20)
        val updated = _state.value.friends.map { f ->
            if (f.id.startsWith(peer.shortId)) f.copy(lastSeenMs = peer.seenMs, presence = Presence.NEARBY, rssi = peer.rssi) else f
        }
        _state.value = _state.value.copy(nearby = n, friends = updated)
        pairing.discovered(peer, ble.device(peer.shortId))
        // RSSI is deliberately not passed to distance-based haptics.
        if (_state.value.selectedId?.startsWith(peer.shortId) == true) haptics.update(viewModelScope, null, false, _state.value.haptics)
    }
    override fun onCleared() { gpsRelay.stop(); firebaseConnection.close(); pairing.cancel(); ble.close(); haptics.stop(); super.onCleared() }
}
