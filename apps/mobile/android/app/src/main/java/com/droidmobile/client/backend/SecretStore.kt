package com.droidmobile.client.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Factory key encrypted with a nonexportable Android Keystore AES-GCM key.
 * Never write plaintext keys to preferences, logcat, links or saved state.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("droid_native_secret", Context.MODE_PRIVATE)
    private val alias = "droidmobile.factory.apiKey.v1"
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
    fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = cipher.iv + encrypted
        prefs.edit().putString("factory_key", Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
    }
    fun read(): String? {
        val encrypted = prefs.getString("factory_key", null) ?: return null
        return try {
            val all = Base64.decode(encrypted, Base64.NO_WRAP)
            if (all.size < 29) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all.copyOfRange(0, 12)))
            String(cipher.doFinal(all.copyOfRange(12, all.size)), Charsets.UTF_8)
        } catch (_: Exception) { null }
    }
    fun clear() { prefs.edit().remove("factory_key").apply() }
}
