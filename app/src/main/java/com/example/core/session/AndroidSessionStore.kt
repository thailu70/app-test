package com.example.core.session

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.data.api.ApiClient
import com.example.data.entity.UserEntity
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredSession(val token: String, val user: UserEntity)

/** Keeps only the bearer token, encrypted by an Android Keystore key and separated per environment. */
class AndroidSessionStore(context: Context) {
    private val app = context.applicationContext
    private val prefs get() = app.getSharedPreferences(ApiClient.environment().storageName("session"), Context.MODE_PRIVATE)
    private val alias get() = "passo_session_${ApiClient.environment().name.lowercase()}"

    @Synchronized fun save(token: String, user: UserEntity) {
        require(token.isNotBlank())
        val payload = JSONObject().apply {
            put("token", token); put("id", user.id); put("role", user.role); put("fullName", user.fullName)
            put("phone", user.phone); put("email", user.email); put("status", user.status)
            put("licenseNumber", user.licenseNumber); put("avatarInitials", user.avatarInitials)
            put("companyName", user.companyName); put("assignedVehiclePlate", user.assignedVehiclePlate)
            put("appliedRouteId", user.appliedRouteId); put("appliedRouteName", user.appliedRouteName)
            put("driverServiceType", user.driverServiceType)
        }.toString()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("token_v1", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
    }

    @Synchronized fun load(): StoredSession? = runCatching {
        val encoded = prefs.getString("token_v1", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        val token = json.getString("token").takeIf(String::isNotBlank) ?: return null
        val user = UserEntity(json.getString("id"), json.getString("role"), json.getString("fullName"), json.getString("phone"),
            json.optString("email"), json.optString("status", "ACTIVE"), json.optString("licenseNumber"),
            json.optString("avatarInitials"), json.optString("companyName"), json.optString("assignedVehiclePlate"),
            json.optString("appliedRouteId"), json.optString("appliedRouteName"), json.optString("driverServiceType"))
        StoredSession(token, user)
    }.getOrNull()

    @Synchronized fun clear() { prefs.edit().remove("token_v1").commit() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }
}
