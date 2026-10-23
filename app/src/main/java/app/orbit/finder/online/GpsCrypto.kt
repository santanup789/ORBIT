package app.orbit.finder.online

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Separate non-exportable ECDH key, signed into the existing identity's QR. Android 12+. */
class GpsCrypto(private val alias: String = "orbit_gps_ecdh_v1") {
    fun publicKey(): String {
        check(Build.VERSION.SDK_INT >= 31) { "Encrypted GPS mode requires Android 12 or newer" }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).build())
            }.generateKeyPair()
        }
        return b64(ks.getCertificate(alias).publicKey.encoded)
    }
    fun key(peer: String): ByteArray {
        val mine = publicKey()
        val remote = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(peer)))
        require((remote as java.security.interfaces.ECPublicKey).params.curve.field.fieldSize == 256)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val secret = KeyAgreement.getInstance("ECDH").apply {
            init(ks.getKey(alias, null)); doPhase(remote, true)
        }.generateSecret()
        // HKDF-SHA256, fixed protocol domain + both public keys binds this key to the pair.
        val salt = MessageDigest.getInstance("SHA-256").digest(listOf(mine, peer).sorted().joinToString("|").toByteArray())
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(secret)
        secret.fill(0)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
            .doFinal("ORBIT-GPS-v1\u0001".toByteArray()).also { extract.fill(0) }
    }
    companion object {
        fun b64(data: ByteArray): String = Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun unb64(data: String): ByteArray = Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        fun encrypt(key: ByteArray, message: String, aad: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            cipher.updateAAD(aad.toByteArray())
            return b64(cipher.iv + cipher.doFinal(message.toByteArray()))
        }
        fun decrypt(key: ByteArray, packet: String, aad: String): String {
            require(packet.length <= 4096)
            val bytes = unb64(packet); require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(aad.toByteArray())
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }
    }
}
