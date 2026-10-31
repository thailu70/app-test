package com.example.core.notifications

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class MobilityPushDevice(val token: String = "", val driverOwner: String? = null)

/** Device routing token only, encrypted under a Keystore key and excluded from cloud/device backup. */
internal class MobilityPushStore(context: Context) {
    private val file = java.io.File(context.applicationContext.noBackupFilesDir, "mobility_push_device_v1")
    private val revoked = java.io.File(context.applicationContext.noBackupFilesDir, "mobility_push_driver_revoked_v1")
    fun driverRevoked(): Boolean = revoked.exists()
    fun update(transform: (MobilityPushDevice) -> MobilityPushDevice) = synchronized(lock) { write(transform(read())) }
    fun revokeDriver() = synchronized(lock) {
        revoked.writeText("revoked")
        runCatching { write(read().copy(driverOwner = null)) }
        Unit
    }
    fun confirmDriver(owner: String, permitted: () -> Boolean) = synchronized(lock) {
        if (permitted()) { write(read().copy(driverOwner = owner)); revoked.delete() }
    }
    fun read(): MobilityPushDevice = synchronized(lock) {
        if (!file.exists()) return@synchronized MobilityPushDevice()
        val parts = file.readText().split(':')
        if (parts.size != 3 || parts[0] != "1") throw IOException("Saved notification setup cannot be opened.")
        val key = (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: throw IOException("Saved notification key is unavailable.")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(parts[1], Base64.NO_WRAP)))
        cipher.updateAAD(AAD)
        val raw = cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP))
        try {
            val value = JSONObject(String(raw, Charsets.UTF_8))
            MobilityPushDevice(value.optString("token"), value.optString("driverOwner").takeIf { it.isNotBlank() })
        } finally { raw.fill(0) }
    }
    fun write(value: MobilityPushDevice) = synchronized(lock) {
        require(value.token.length <= 4096 && (value.driverOwner?.length ?: 0) <= 150)
        val store = keyStore()
        val key = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
        val raw = JSONObject().put("token", value.token).put("driverOwner", value.driverOwner.orEmpty()).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key); cipher.updateAAD(AAD)
        val encrypted = try { cipher.doFinal(raw) } finally { raw.fill(0) }
        val target = java.io.File(file.parentFile, file.name + ".tmp")
        target.writeText("1:" + Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
        if (!target.renameTo(file)) { target.delete(); throw IOException("Notification setup could not be saved.") }
    }
    fun clear() = synchronized(lock) { if (file.exists() && !file.delete()) throw IOException("Notification setup could not be cleared.") }
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    companion object {
        private val lock = Any()
        private const val ALIAS = "passo_mobility_push_device_v1"
        private val AAD = "Passo.Mobility.Push.Production.v1".toByteArray()
    }
}
