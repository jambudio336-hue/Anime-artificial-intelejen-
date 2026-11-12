package id.maskiplay.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted-at-rest WireGuard config using an Android Keystore AES-GCM key. */
class VpnStore(context: Context) {
    private val prefs = context.getSharedPreferences("wireguard_secure_store", Context.MODE_PRIVATE)

    fun save(config: String) {
        require(config.isNotBlank()) { "Konfigurasi WireGuard kosong" }
        require(config.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES) {
            "Konfigurasi WireGuard terlalu besar (maksimum 64 KiB)"
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(config.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString(CONFIG_KEY, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }

    fun load(): String? {
        val packed = prefs.getString(CONFIG_KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            require(bytes.size > IV_SIZE)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            // Remove unusable ciphertext so a stale Keystore key does not cause
            // the same decryption failure on every subsequent app launch.
            prefs.edit().remove(CONFIG_KEY).apply()
            null
        }
    }

    fun clear() { prefs.edit().remove(CONFIG_KEY).apply() }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "maskiplay_wireguard_config"
        private const val CONFIG_KEY = "encrypted_config"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val MAX_CONFIG_BYTES = 64 * 1024
    }
}
