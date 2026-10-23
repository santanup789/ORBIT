package app.orbit.finder.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec

data class LocalIdentity(val id: String, val publicKey: String)
data class PairingPayload(val id: String, val name: String, val icon: DeviceIcon, val publicKey: String, val expiresAt: Long, val firebaseUid: String = "", val gpsPublicKey: String = "")

class IdentityStore(private val context: Context, private val alias: String = "orbit_identity_v1") {
    private val prefs = context.getSharedPreferences(if (alias == "orbit_identity_v1") "orbit_identity" else alias, Context.MODE_PRIVATE)

    fun identity(): LocalIdentity {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
                generateKeyPair()
            }
        }
        val pub = ks.getCertificate(alias).publicKey.encoded
        val encoded = Base64.encodeToString(pub, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val id = MessageDigest.getInstance("SHA-256").digest(pub).take(16).joinToString("") { "%02x".format(it) }
        return LocalIdentity(id, encoded)
    }

    fun createQr(name: String, icon: DeviceIcon, firebaseUid: String = "", gpsPublicKey: String = ""): String {
        val i = identity()
        val body = JSONObject().apply {
            put("v", 1); put("id", i.id); put("name", name.take(40)); put("icon", icon.name)
            put("pk", i.publicKey); put("exp", System.currentTimeMillis() + 10 * 60_000)
            if (firebaseUid.isNotBlank() && gpsPublicKey.isNotBlank()) { put("uid", firebaseUid); put("gpsKey", gpsPublicKey) }
        }.toString()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(ks.getKey(alias, null) as java.security.PrivateKey)
            update(body.toByteArray(StandardCharsets.UTF_8))
        }.sign()
        return "ORBIT1." + b64(body.toByteArray()) + "." + b64(sig)
    }

    fun parseQr(value: String): Result<PairingPayload> = runCatching {
        require(value.length <= 4096) { "QR payload is too large" }
        val parts = value.split('.')
        require(parts.size == 3 && parts[0] == "ORBIT1") { "Not an ORBIT pairing code" }
        val bodyBytes = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val body = JSONObject(String(bodyBytes, StandardCharsets.UTF_8))
        require(body.getInt("v") == 1) { "Unsupported pairing version" }
        val pk = body.getString("pk")
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(Base64.decode(pk, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)))
        val ok = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(key); update(bodyBytes)
        }.verify(Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        require(ok) { "Pairing signature is invalid" }
        val id = body.getString("id")
        require(id == MessageDigest.getInstance("SHA-256").digest(key.encoded).take(16).joinToString("") { "%02x".format(it) }) { "Identity does not match its public key" }
        require(id != identity().id) { "This is your own QR code" }
        val exp = body.getLong("exp")
        require(exp >= System.currentTimeMillis()) { "This pairing code has expired" }
        require(exp <= System.currentTimeMillis() + 11 * 60_000) { "Invalid pairing expiry" }
        val uid = body.optString("uid")
        require(uid.isEmpty() || uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid Firebase identity" }
        val gpsKey = body.optString("gpsKey")
        require(gpsKey.length <= 200 && (uid.isEmpty() == gpsKey.isEmpty())) { "Invalid GPS identity" }
        val name = body.getString("name")
        require(name.isNotBlank() && name.length <= 40 && name.none { it.isISOControl() }) { "Invalid device name" }
        PairingPayload(id, name, DeviceIcon.valueOf(body.getString("icon")), pk, exp, uid, gpsKey)
    }

    fun defaultDeviceName(): String = runCatching {
        android.provider.Settings.Global.getString(context.contentResolver, "device_name")
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.take(40)
        ?: android.os.Build.MODEL.trim().take(40).ifBlank { "Android phone" }
    fun hasProfile(): Boolean = prefs.getString("name", "")?.isNotBlank() == true || prefs.getBoolean("default_name", false)
    fun usesDefaultName(): Boolean = prefs.getBoolean("default_name", !hasProfile())
    fun profileName(): String = if (usesDefaultName()) defaultDeviceName() else prefs.getString("name", "")!!.ifBlank { defaultDeviceName() }
    fun signMessage(body: String): String {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(ks.getKey(alias, null) as java.security.PrivateKey)
            update(body.toByteArray(StandardCharsets.UTF_8))
        }.sign()
        return JSONObject().put("body", body).put("sig", b64(signature)).toString()
    }

    fun verifyMessage(packet: String, publicKey: String): JSONObject {
        require(packet.length <= 4096)
        val wrapper = JSONObject(packet)
        val body = wrapper.getString("body")
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(publicKey, Base64.URL_SAFE)))
        require(Signature.getInstance("SHA256withECDSA").apply { initVerify(key); update(body.toByteArray(StandardCharsets.UTF_8)) }
            .verify(Base64.decode(wrapper.getString("sig"), Base64.URL_SAFE))) { "Invalid pairing signature" }
        return JSONObject(body)
    }
    fun profileIcon(): DeviceIcon = runCatching { DeviceIcon.valueOf(prefs.getString("icon", DeviceIcon.ORB.name)!!) }.getOrDefault(DeviceIcon.ORB)
    fun saveProfile(name: String, icon: DeviceIcon) { prefs.edit().putString("name", name.trim().take(40)).putBoolean("default_name", false).putString("icon", icon.name).apply() }
    fun useDefaultName() { prefs.edit().putBoolean("default_name", true).apply() }
    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
