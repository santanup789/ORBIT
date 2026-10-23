package app.orbit.finder.radio

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.ArrayDeque

/** One request at a time per connection; all state and callbacks serialized on main. */
@SuppressLint("MissingPermission")
class PairingTransport(private val context: Context,
    private val onOpen: (String) -> Unit,
    private val onMessage: (String, String) -> Unit,
    private val onClose: (String) -> Unit,
    private val onStatus: (String) -> Unit) {
    companion object {
        val WRITE: UUID = UUID.fromString("780f89ab-5e92-4e48-a1ae-70b2c6a5f731")
        val READ: UUID = UUID.fromString("780f89ac-5e92-4e48-a1ae-70b2c6a5f731")
    }
    private val main = Handler(Looper.getMainLooper())
    private var server: BluetoothGattServer? = null
    private data class Remote(val device: BluetoothDevice, val decoder: PairingFrames.Decoder = PairingFrames.Decoder(), val output: ArrayDeque<ByteArray> = ArrayDeque())
    private val remotes = mutableMapOf<String, Remote>()
    private var client: BluetoothGatt? = null
    private var clientKey: String? = null
    private var read: BluetoothGattCharacteristic? = null
    private var write: BluetoothGattCharacteristic? = null
    private val outgoing = ArrayDeque<ByteArray>()
    private val incoming = PairingFrames.Decoder()
    private var busy = false
    private val poll = Runnable { pump() }
    private val timeout = Runnable { closeClient("Pairing timed out. Keep both apps open, then retry.") }
    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) { main.post {
            if (status != BluetoothGatt.GATT_SUCCESS) { server?.close(); server = null }
            ready?.invoke(status == BluetoothGatt.GATT_SUCCESS)
        } }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) { main.post {
            val key = "in:${device.address}"
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (remotes.size >= 4) { server?.cancelConnection(device); return@post }
                val remote = Remote(device)
                remotes[key] = remote; onOpen(key)
                main.postDelayed({ if (remotes[key] === remote) { server?.cancelConnection(device); remotes.remove(key); onClose(key) } }, 125_000)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) { remotes.remove(key); onClose(key) }
        } }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) { main.post {
            val remote = remotes["in:${device.address}"]
            val valid = characteristic.uuid == READ && offset == 0 && remote != null
            server?.sendResponse(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INVALID_OFFSET, offset,
                if (valid) remote!!.output.pollFirst() ?: byteArrayOf() else null)
        } }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) { main.post {
            val key = "in:${device.address}"
            var message: String? = null
            val ok = runCatching {
                require(!preparedWrite && offset == 0 && characteristic.uuid == WRITE)
                message = requireNotNull(remotes[key]).decoder.accept(value)
            }.isSuccess
            if (responseNeeded) server?.sendResponse(device, requestId, if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE, 0, null)
            if (ok) message?.let { onMessage(key, it) } else { server?.cancelConnection(device) }
        } }
    }
    private var ready: ((Boolean) -> Unit)? = null
    fun start(onReady: (Boolean) -> Unit) {
        if (server != null) { onReady(true); return }
        ready = onReady
        val manager = context.getSystemService(BluetoothManager::class.java)
        server = manager.openGattServer(context, callback)
        if (server == null) { onReady(false); return }
        val service = BluetoothGattService(BleDiscovery.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        service.addCharacteristic(BluetoothGattCharacteristic(READ, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        if (server?.addService(service) != true) { server?.close(); server = null; onReady(false) }
    }
    fun connect(device: BluetoothDevice, key: String) {
        closeClient(null)
        clientKey = key
        client = device.connectGatt(context, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed(timeout, 120_000)
    }
    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { main.post {
            if (gatt != client) return@post
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.discoverServices()) closeClient("Could not discover pairing service")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) closeClient("Pairing disconnected ($status). Retry when both phones are nearby.")
        } }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { main.post {
            if (gatt != client) return@post
            val service = gatt.getService(BleDiscovery.SERVICE_UUID)
            read = service?.getCharacteristic(READ); write = service?.getCharacteristic(WRITE)
            if (status != BluetoothGatt.GATT_SUCCESS || read == null || write == null) closeClient("Other phone needs ORBIT 0.2.0 or newer") else pump()
        } }
        @Deprecated("Legacy callback used on Android 12 and earlier")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (android.os.Build.VERSION.SDK_INT < 33) received(gatt, characteristic.value ?: byteArrayOf(), status)
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) { received(gatt, value, status) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) { main.post {
            if (gatt != client) return@post
            busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) closeClient("Pairing write failed ($status)") else pump()
        } }
    }
    private fun received(gatt: BluetoothGatt, value: ByteArray, status: Int) { main.post {
        if (gatt != client) return@post
        busy = false
        if (status != BluetoothGatt.GATT_SUCCESS) { closeClient("Pairing read failed ($status)"); return@post }
        if (value.isNotEmpty()) {
            runCatching { incoming.accept(value)?.let { clientKey?.let { key -> onMessage(key, it) } } }
                .onFailure { closeClient("Invalid pairing response") }
        }
        main.removeCallbacks(poll)
        main.postDelayed(poll, if (value.isEmpty()) 500 else 10)
    } }
    fun send(key: String, message: String) {
        val frames = PairingFrames.encode(message)
        if (key == clientKey) { outgoing.addAll(frames); pump() }
        else remotes[key]?.output?.let { if (it.size + frames.size <= 512) it.addAll(frames) }
    }
    private fun pump() {
        val gatt = client ?: return
        if (busy || read == null || write == null) return
        main.removeCallbacks(poll)
        busy = true
        val ok = if (outgoing.isNotEmpty()) {
            val frame = outgoing.removeFirst()
            if (android.os.Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(write!!, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { write!!.value = frame; write!!.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; gatt.writeCharacteristic(write!!) }
        } else gatt.readCharacteristic(read)
        if (!ok) closeClient("Bluetooth operation could not start. Retry pairing.")
    }
    fun closeClient(reason: String?) {
        main.removeCallbacks(poll); main.removeCallbacks(timeout)
        val old = client; val key = clientKey
        client = null; clientKey = null; read = null; write = null; busy = false
        outgoing.clear(); incoming.reset()
        old?.disconnect(); old?.close()
        key?.let(onClose); reason?.let(onStatus)
    }
    fun stop() {
        closeClient(null)
        remotes.keys.toList().forEach(onClose); remotes.clear()
        server?.close(); server = null; ready = null
    }
}
