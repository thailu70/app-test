package com.example.core.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.core.environment.AppEnvironment
import com.example.core.environment.EnvironmentPreferences
import com.example.data.api.*
import com.example.data.repository.AndroidEncryptedIntercityBookingStore
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** Guest registration stays capability-protected and optional; it can never block submitting a request. */
interface MobilityGuestPushRegistrar { fun registerRequest(id: String, secret: String) }
class AndroidMobilityGuestPushRegistrar(context: Context) : MobilityGuestPushRegistrar {
    private val app = context.applicationContext
    private val session = ApiClient.sessionScope()
    override fun registerRequest(id: String, secret: String) {
        if (ApiClient.isCurrent(session) && AppEnvironment.forBaseUrl(session.baseUrl) == AppEnvironment.PRODUCTION) MobilityPush.registerRequest(app, id, secret)
    }
}

object MobilityPush {
    const val CHANNEL_ID = "cargo_updates"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guestSynced = ConcurrentHashMap<String, Pair<String, Long>>()
    @Volatile private var activeDriverId: String? = null
    @Volatile private var driverSessionKnown = false
    @Volatile private var tokenInMemory: String? = null
    @Volatile private var applicationContext: Context? = null
    private val driverSessionLock = Any()

    /** Supply the process context before account restoration; this performs no registration or GPS work. */
    fun initialize(context: Context) { applicationContext = context.applicationContext }

    fun notificationsGranted(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun configured(context: Context): Boolean = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    fun production(context: Context): Boolean = ApiClient.environment() == AppEnvironment.PRODUCTION && EnvironmentPreferences.selected(context) == AppEnvironment.PRODUCTION
    fun allowed(context: Context): Boolean = mobilityPushAllowed(if (production(context)) AppEnvironment.PRODUCTION else AppEnvironment.DEMO, notificationsGranted(context), configured(context))

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transport requests & updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nearby transport requests, driver quotes and booking progress."
                lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
            })
    }

    private suspend fun currentToken(context: Context): String? {
        if (!allowed(context)) return null
        val messaging = FirebaseMessaging.getInstance()
        messaging.isAutoInitEnabled = true
        val token = withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<String?> { continuation ->
                messaging.token.addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resume(null) }
            }
        } ?: return null
        if (!allowed(context) || token.isBlank()) return null
        tokenInMemory = token
        val store = MobilityPushStore(context)
        val previous = store.read()
        if (previous.token != token) { guestSynced.clear(); store.update { it.copy(token = token) } }
        return token
    }

    internal fun receivedToken(context: Context, token: String) {
        if (!production(context) || !notificationsGranted(context) || token.isBlank()) return
        tokenInMemory = token; guestSynced.clear()
        runCatching { MobilityPushStore(context).update { it.copy(token = token) } }
    }

    fun setDriverSession(context: Context, userId: String?, role: String?) {
        initialize(context)
        val next = userId?.takeIf { role.equals("DRIVER", true) && production(context) }
        synchronized(driverSessionLock) {
            if (driverSessionKnown && next == activeDriverId) return
            driverSessionKnown = true; activeDriverId = next
            val store = MobilityPushStore(context)
            runCatching { if (store.read().driverOwner != next) store.revokeDriver() }
                .onFailure { runCatching { store.revokeDriver() } }
        }
    }

    suspend fun syncDriver(context: Context, userId: String) {
        if (activeDriverId != userId || !allowed(context)) return
        val bearer = ApiClient.getAuthToken()?.takeIf(String::isNotBlank) ?: return
        val session = ApiClient.sessionScope()
        val api = ApiClient.createSessionService(MobilityPushApi::class.java, bearer)
        val token = currentToken(context) ?: return
        val point = recentLocation(context)
        val result = api.registerDriver(MobilityPushInput(token, point?.first, point?.second))
        if (result.isSuccessful && result.body()?.success == true && activeDriverId == userId && ApiClient.isCurrent(session) && ApiClient.getAuthToken() == bearer && allowed(context)) {
            synchronized(driverSessionLock) {
                MobilityPushStore(context).confirmDriver(userId) { activeDriverId == userId && ApiClient.isCurrent(session) && ApiClient.getAuthToken() == bearer && allowed(context) }
            }
        }
    }

    /** Invoke before clearing the bearer. Local delivery is revoked immediately, even when offline. */
    fun beforeLogout(): Job? = applicationContext?.let { beforeLogout(it) }

    fun beforeLogout(context: Context): Job? {
        initialize(context)
        val app = context.applicationContext
        val value = synchronized(driverSessionLock) {
            activeDriverId = null; driverSessionKnown = true
            val store = MobilityPushStore(app)
            val saved = runCatching { store.read() }.getOrElse { MobilityPushDevice(tokenInMemory.orEmpty()) }
            runCatching { store.revokeDriver() }
            saved
        }
        pauseAutomaticRegistration(app)
        val bearer = ApiClient.getAuthToken()
        val api = bearer?.takeIf { production(app) && it.isNotBlank() }?.let { ApiClient.createSessionService(MobilityPushApi::class.java, it) }
        return scope.launch {
            if (api != null && value.token.isNotBlank()) runCatching { api.removeDriver(MobilityPushInput(value.token)) }
            if (!production(app)) runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
        }
    }

    fun pauseAutomaticRegistration(context: Context) {
        if (configured(context)) runCatching { FirebaseMessaging.getInstance().isAutoInitEnabled = false }
    }

    internal fun driverDeliveryAllowed(context: Context): Boolean =
        (!driverSessionKnown || activeDriverId != null) && runCatching { val store = MobilityPushStore(context); !store.driverRevoked() && store.read().driverOwner != null }.getOrDefault(false)

    fun onPermissionGranted(context: Context) {
        ensureChannel(context); syncGuestRequests(context)
        activeDriverId?.let { owner -> scope.launch { runCatching { syncDriver(context.applicationContext, owner) } } }
    }

    fun registerRequest(context: Context, id: String, secret: String): Job? {
        if (!allowed(context)) return null
        val session = ApiClient.sessionScope()
        return scope.launch {
            try {
                val token = currentToken(context) ?: return@launch
                val now = SystemClock.elapsedRealtime()
                val previous = guestSynced[id]
                if (previous?.first == token && now - previous.second < 5 * 60_000L) return@launch
                if (!ApiClient.isCurrent(session) || !allowed(context)) return@launch
                val api = ApiClient.createFeatureService(MobilityPushApi::class.java)
                val result = api.registerRequest(id, secret, MobilityPushInput(token))
                if (result.isSuccessful && result.body()?.success == true && ApiClient.isCurrent(session)) guestSynced[id] = token to now
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { /* A later refresh retries without affecting guest request access. */ }
        }
    }

    fun syncGuestRequests(context: Context): Job? {
        if (!allowed(context)) return null
        return scope.launch { runCatching {
            AndroidEncryptedIntercityBookingStore(context, "mobility", AppEnvironment.PRODUCTION).load()
                .filter { it.id.startsWith("request:") }.forEach { registerRequest(context, it.id.removePrefix("request:"), it.secret) }
        } }
    }

    internal fun guestDeliveryAllowed(context: Context, id: String): Boolean = runCatching {
        AndroidEncryptedIntercityBookingStore(context, "mobility", AppEnvironment.PRODUCTION).load().any { it.id == "request:$id" }
    }.getOrDefault(false)

    @Suppress("MissingPermission", "DEPRECATION")
    private fun recentLocation(context: Context): Pair<Double, Double>? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return runCatching {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
                .filter { location -> (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L in 0..5 * 60_000L && location.hasAccuracy() && location.accuracy in 0f..1000f }
                .maxByOrNull { it.elapsedRealtimeNanos }?.let { it.latitude to it.longitude }
        }.getOrNull()
    }
}
