package app.orbit.finder.core

enum class DeviceIcon(val symbol: String) { PHONE("▯"), ORB("◉"), CRYSTAL("◇"), SATELLITE("✦") }
enum class TrustState { PENDING, TRUSTED }
enum class Presence { OFFLINE, NEARBY, SEARCHING, CONNECTED }
enum class MeasurementSource { UWB, WIFI_RTT, BLE_RSSI }

data class Friend(
    val id: String,
    val publicKey: String,
    val name: String,
    val icon: DeviceIcon,
    val trust: TrustState = TrustState.PENDING,
    val lastSeenMs: Long = 0,
    val presence: Presence = Presence.OFFLINE,
    val rssi: Int? = null,
    val firebaseUid: String = "",
    val gpsPublicKey: String = ""
)

data class Measurement(
    val source: MeasurementSource,
    val timestampMs: Long,
    val distanceMetres: Float? = null,
    val azimuthDegrees: Float? = null,
    val distanceAccuracyMetres: Float? = null,
    val signalDbm: Int? = null,
    val valid: Boolean,
    val detail: String
) {
    fun isFresh(now: Long = System.currentTimeMillis()) = valid && now - timestampMs < 3_000
}

data class Capabilities(
    val ble: Boolean = false,
    val bleAdvertising: Boolean = false,
    val camera: Boolean = false,
    val vibrator: Boolean = false,
    val amplitudeControl: Boolean = false,
    val uwbHardware: Boolean = false,
    val uwbAvailable: Boolean? = null,
    val uwbDistance: Boolean? = null,
    val uwbAzimuth: Boolean? = null,
    val uwbElevation: Boolean? = null,
    val wifiAware: Boolean = false,
    val wifiRtt: Boolean = false,
    val androidRangingApi: Boolean = false,
    val bluetoothChannelSounding: Boolean? = null,
    val bluetoothRssiRanging: Boolean? = null,
    val notes: List<String> = emptyList()
)
