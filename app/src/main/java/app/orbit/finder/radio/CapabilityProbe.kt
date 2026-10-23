package app.orbit.finder.radio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Vibrator
import android.ranging.RangingCapabilities
import android.ranging.RangingManager
import androidx.annotation.RequiresApi
import androidx.core.uwb.UwbManager
import app.orbit.finder.core.Capabilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CapabilityProbe(private val context: Context) {
    suspend fun read(): Capabilities {
        val pm = context.packageManager
        val vib = context.getSystemService(Vibrator::class.java)
        var available: Boolean? = null
        var distance: Boolean? = null
        var azimuth: Boolean? = null
        var elevation: Boolean? = null
        val notes = mutableListOf<String>()
        var bluetoothCs: Boolean? = null
        var bluetoothRssi: Boolean? = null
        val hasUwb = pm.hasSystemFeature("android.hardware.uwb")
        if (hasUwb) {
            runCatching {
                val manager = UwbManager.createInstance(context)
                available = manager.isAvailable()
                if (available == true) {
                    val caps = manager.controllerSessionScope().rangingCapabilities
                    distance = caps.isDistanceSupported
                    azimuth = caps.isAzimuthalAngleSupported
                    elevation = caps.isElevationAngleSupported
                }
            }.onFailure { notes += "UWB runtime probe: ${it.javaClass.simpleName}" }
        }
        val bt = context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
        if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                val ranging = context.getSystemService(RangingManager::class.java)
                val platformCaps = ranging?.readCapabilities()
                bluetoothCs = platformCaps?.technologyAvailability?.get(RangingManager.BLE_CS) == RangingCapabilities.ENABLED
                bluetoothRssi = platformCaps?.technologyAvailability?.get(RangingManager.BLE_RSSI) == RangingCapabilities.ENABLED
                if (platformCaps != null) notes += "Android Ranging: ${platformCaps.technologyAvailability}"
            }.onFailure { notes += "Android Ranging probe: ${it.javaClass.simpleName}" }
        }
        return Capabilities(
            ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            bleAdvertising = bt?.bluetoothLeAdvertiser != null,
            camera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
            vibrator = vib?.hasVibrator() == true,
            amplitudeControl = vib?.hasAmplitudeControl() == true,
            uwbHardware = hasUwb, uwbAvailable = available, uwbDistance = distance,
            uwbAzimuth = azimuth, uwbElevation = elevation,
            wifiAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            wifiRtt = pm.hasSystemFeature("android.hardware.wifi.rtt"),
            androidRangingApi = Build.VERSION.SDK_INT >= 36,
            bluetoothChannelSounding = bluetoothCs,
            bluetoothRssiRanging = bluetoothRssi,
            notes = notes
        )
    }

    @RequiresApi(36)
    private suspend fun RangingManager.readCapabilities(): RangingCapabilities = suspendCancellableCoroutine { continuation ->
        lateinit var callback: RangingManager.RangingCapabilitiesCallback
        callback = RangingManager.RangingCapabilitiesCallback { capabilities ->
            unregisterCapabilitiesCallback(callback)
            if (continuation.isActive) continuation.resume(capabilities)
        }
        continuation.invokeOnCancellation { runCatching { unregisterCapabilitiesCallback(callback) } }
        registerCapabilitiesCallback(context.mainExecutor, callback)
    }
}
