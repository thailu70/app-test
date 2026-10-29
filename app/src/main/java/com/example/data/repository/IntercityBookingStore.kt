package com.example.data.repository

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.example.core.environment.AppEnvironment
import com.example.core.environment.capabilityContext
import com.example.data.api.ApiClient

/** A private booking capability, never a passenger profile or a cached boarding QR. */
data class IntercityBookingAccess(val id: String, val secret: String)

interface IntercityBookingAccessStore {
    fun load(): List<IntercityBookingAccess>
    fun save(access: IntercityBookingAccess)
    fun forget(id: String)
}

/**
 * Keeps at most eight recent booking capabilities encrypted with a device Keystore key.
 * Crypto/storage errors deliberately propagate so the UI can report that ticket recovery is
 * unavailable. They must not silently become an empty history or a plaintext fallback.
 */
class AndroidEncryptedIntercityBookingStore(context: Context, namespace: String = "intercity", environment: AppEnvironment = ApiClient.environment()) : IntercityBookingAccessStore {
    init { require(namespace in setOf("intercity", "mobility")) }
    private val preferences = context.applicationContext.getSharedPreferences(environment.storageName(if (namespace == "intercity") PREFERENCES_NAME else "mobility_private_access"), Context.MODE_PRIVATE)
    private val authenticatedContext = capabilityContext(namespace, environment)
    private val keyAlias = environment.storageName(KEY_ALIAS)

    override fun load(): List<IntercityBookingAccess> = synchronized(storageLock) { readAccess() }

    override fun save(access: IntercityBookingAccess): Unit = synchronized(storageLock) {
        validate(access)
        val recent = (listOf(access) + readAccess().filterNot { it.id == access.id }).take(MAX_RECENT)
        writeAccess(recent)
    }

    override fun forget(id: String): Unit = synchronized(storageLock) {
        val existing = readAccess()
        val remaining = existing.filterNot { it.id == id }
        if (remaining.size != existing.size) writeAccess(remaining)
    }

    private fun readAccess(): List<IntercityBookingAccess> {
        val envelope = preferences.getString(ENTRY_NAME, null) ?: return emptyList()
        val parts = envelope.split(':')
        if (parts.size != 3 || parts[0] != "1") throw IOException("Saved ticket access has an unsupported format.")
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        if (iv.size != GCM_IV_BYTES) throw IOException("Saved ticket access is damaged.")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // An entry without its original device key is an error, not a newly generated key.
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(authenticatedContext)
        val plaintext = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
        try {
            val entries = JSONArray(String(plaintext, Charsets.UTF_8))
            if (entries.length() > MAX_RECENT) throw IOException("Saved ticket access contains too many entries.")
            val result = (0 until entries.length()).map { index ->
                val value = entries.getJSONObject(index)
                IntercityBookingAccess(value.getString("id"), value.getString("secret")).also(::validate)
            }
            if (result.map { it.id }.distinct().size != result.size) throw IOException("Saved ticket access is damaged.")
            return result
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeAccess(access: List<IntercityBookingAccess>) {
        if (access.isEmpty()) {
            if (!preferences.edit().remove(ENTRY_NAME).commit()) throw IOException("Unable to remove saved ticket access.")
            return
        }
        val entries = JSONArray().apply {
            access.forEach { item -> put(JSONObject().put("id", item.id).put("secret", item.secret)) }
        }
        val plaintext = entries.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val ciphertext = try {
            cipher.init(Cipher.ENCRYPT_MODE, keyForEncryption())
            cipher.updateAAD(authenticatedContext)
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val envelope = "1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        if (!preferences.edit().putString(ENTRY_NAME, envelope).commit()) throw IOException("Unable to save private ticket access.")
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey =
        (keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw IOException("Saved ticket access cannot be opened on this device.")

    private fun keyForEncryption(): SecretKey {
        val store = keyStore()
        if (store.containsAlias(keyAlias)) {
            return (store.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: throw IOException("Saved ticket key is unavailable.")
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun validate(access: IntercityBookingAccess) {
        require(access.id.isNotBlank() && access.id.length <= 150) { "Invalid private booking identifier." }
        require(access.secret.isNotBlank() && access.secret.length <= 200) { "Invalid private booking capability." }
    }

    private companion object {
        const val PREFERENCES_NAME = "intercity_booking_access"
        const val ENTRY_NAME = "capabilities_v1"
        const val KEY_ALIAS = "routepass_intercity_booking_access_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val MAX_RECENT = 8
        val storageLock = Any()
    }
}
