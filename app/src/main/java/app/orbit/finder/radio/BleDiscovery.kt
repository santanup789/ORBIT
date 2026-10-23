package app.orbit.finder.radio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat
import app.orbit.finder.core.LocalIdentity
import java.util.UUID

data class NearbyPeer(val shortId: String, val rssi: Int, val seenMs: Long)

@SuppressLint("MissingPermission")
class BleDiscovery(private val context: Context, private val onPeer: (NearbyPeer) -> Unit,
    private val onStatus: (String) -> Unit, private val startServer: ((Boolean) -> Unit) -> Unit,
    private val stopServer: () -> Unit) {
    companion object { val SERVICE_UUID: UUID = UUID.fromString("780f89aa-5e92-4e48-a1ae-70b2c6a5f731") }
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanning = false
    private var advertising = false
    private var starting = false
    private var scanCallback: ScanCallback? = null
    private val recovery = ScanRecovery()
    private var scanError: String? = null
    private val retryScan = Runnable { identity?.let { start(it) } }
    private var identity: LocalIdentity? = null
    private val devices = mutableMapOf<String, BluetoothDevice>()
    fun device(id: String): BluetoothDevice? = devices[id.take(16)]
    private fun status(text: String) { Log.i("OrbitBluetooth", text); onStatus(text) }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val saved = identity ?: return
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> start(saved)
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> { stopRadio(); status("Bluetooth is off. Enable it, then return to ORBIT.") }
                    BluetoothAdapter.STATE_TURNING_ON -> status("Bluetooth is turning on…")
                }
            } else start(saved)
        }
    }
    init {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply { addAction(LocationManager.MODE_CHANGED_ACTION) }, ContextCompat.RECEIVER_EXPORTED)
    }
    private fun found(result: ScanResult, callback: ScanCallback) {
        val bytes = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
        if (bytes.size != 8) return
        val id = bytes.joinToString("") { "%02x".format(it) }
        main.post {
            if (!scanning || scanCallback !== callback || identity?.id?.startsWith(id) == true) return@post
            recovery.reset()
            if (devices.size >= 64 && !devices.containsKey(id)) devices.remove(devices.keys.first())
            devices[id] = result.device
            onPeer(NearbyPeer(id, result.rssi, System.currentTimeMillis()))
        }
    }
    private fun newScanCallback() = object : ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) = found(result, this)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { found(it, this) } }
        override fun onScanFailed(errorCode: Int) { main.post {
            if (scanCallback !== this) return@post
            // Release the exact callback even after a failed registration. Do not leave
            // cleanup conditional on 'scanning', or accept callbacks from old sessions.
            releaseScan()
            val retry = recovery.failed(SystemClock.elapsedRealtime()) && errorCode != SCAN_FAILED_FEATURE_UNSUPPORTED
            scanError = if (retry) "Bluetooth ON · Scan interrupted ($errorCode). Retrying automatically in 30 seconds."
                else "Bluetooth ON · Android could not start scanning ($errorCode). Restart Bluetooth in system settings, then tap Nearby."
            status(scanError!!)
            main.removeCallbacks(retryScan)
            if (retry && identity != null) main.postDelayed(retryScan, 30_000L)
        } }
    }
    private fun releaseScan() {
        val old = scanCallback
        scanCallback = null
        scanning = false
        if (old != null) runCatching { scanner?.stopScan(old) }
        scanner = null
    }
    private fun radioStatus() = scanError ?: if (scanning) "Bluetooth ON · Visible · Scanning for ORBIT phones" else "Bluetooth ON · Visible · Scanner stopped"
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { main.post {
            starting = false
            advertising = errorCode == ADVERTISE_FAILED_ALREADY_STARTED
            status(if (advertising) radioStatus() else "Bluetooth ON · Advertising failed ($errorCode); this phone is not visible")
        } }
        override fun onStartSuccess(settings: AdvertiseSettings?) { main.post {
            starting = false; advertising = true; status(radioStatus())
        } }
    }
    fun start(localIdentity: LocalIdentity) {
        identity = localIdentity
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { status("Permission needed: ${missing.joinToString { it.substringAfterLast('.') }}. Tap Nearby to allow."); return }
        val radio = adapter ?: run { status("No Bluetooth adapter available on this device"); return }
        runCatching {
            when (radio.state) {
                BluetoothAdapter.STATE_TURNING_ON -> { status("Bluetooth is turning on. ORBIT will resume automatically."); return }
                BluetoothAdapter.STATE_ON -> Unit
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> { status("Bluetooth is off. Enable it, then return to ORBIT."); return }
                else -> { status("Bluetooth state unavailable. Return to ORBIT after checking system settings."); return }
            }
            val location = context.getSystemService(LocationManager::class.java)
            val enabled = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!enabled) { status("Bluetooth ON · Turn on Location for proximity scanning (no GPS coordinates collected)"); return }
            if (!scanning) {
                if (recovery.remaining(SystemClock.elapsedRealtime()) > 0) {
                    status(scanError ?: "Bluetooth scan cooling down; retry shortly."); return
                }
                main.removeCallbacks(retryScan)
                releaseScan()
                scanner = radio.bluetoothLeScanner ?: run { status("Bluetooth ON · Scanner not ready; tap Nearby again"); return }
                val filter = ScanFilter.Builder().setServiceData(ParcelUuid(SERVICE_UUID), byteArrayOf()).build()
                val callback = newScanCallback()
                scanCallback = callback
                scanning = true
                scanError = null
                scanner!!.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            }
            if (advertising || starting) { status(if (advertising) radioStatus() else "Bluetooth ON · Starting visibility…"); return }
            starting = true
            startServer { ready ->
                if (!ready) { starting = false; status("Bluetooth ON · Pairing service failed to start. Tap Nearby to retry."); return@startServer }
                if (identity == null || !starting) return@startServer
                runCatching {
                    advertiser = radio.bluetoothLeAdvertiser ?: error("This phone cannot advertise BLE")
                    val bytes = localIdentity.id.chunked(2).take(8).map { it.toInt(16).toByte() }.toByteArray()
                    val data = AdvertiseData.Builder().addServiceData(ParcelUuid(SERVICE_UUID), bytes).build()
                    advertiser!!.startAdvertising(AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY).setConnectable(true).setTimeout(0).build(), data, advertiseCallback)
                }.onFailure { starting = false; status("Bluetooth ON · ${it.message}") }
            }
        }.onFailure { releaseScan(); starting = false; status("Bluetooth operation failed: ${it.javaClass.simpleName}. Check Nearby permission and retry."); Log.e("OrbitBluetooth", "start failed", it) }
    }
    private fun stopRadio() {
        main.removeCallbacks(retryScan)
        releaseScan()
        recovery.reset(); scanError = null
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        runCatching { stopServer() }
        scanning = false; advertising = false; starting = false; devices.clear()
    }
    fun stop() { identity = null; stopRadio() }
    fun close() { stop(); context.unregisterReceiver(receiver) }
    fun requiredPermissions(): Array<String> = (if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT) else emptyList()).plus(listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)).toTypedArray()
}
