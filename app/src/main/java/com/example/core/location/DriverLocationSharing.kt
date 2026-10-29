package com.example.core.location

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.data.api.ApiClient
import com.example.data.api.DriverGpsFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DriverLocationTarget(val driverId: String, val tripId: String, val intercity: Boolean, val currentStop: String = "")
internal data class DriverLocationSession(val target: DriverLocationTarget, val token: String,
    val environmentScope: com.example.core.environment.ApiSessionScope = ApiClient.sessionScope())

/** Process-only session: never restore a location service or bearer token from disk. */
object DriverLocationSharing {
    private var application: Context? = null
    @Volatile internal var session: DriverLocationSession? = null
        private set
    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()
    private val _status = MutableStateFlow("Trip location sharing is off.")
    val status = _status.asStateFlow()
    private val _fix = MutableStateFlow<DriverGpsFix?>(null)
    val fix = _fix.asStateFlow()

    fun start(context: Context, target: DriverLocationTarget): Boolean {
        if (target.driverId.isBlank() || target.tripId.isBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            report("Allow location permission before sharing your trip.")
            return false
        }
        if (!notificationsVisible(context)) {
            report("Enable notifications to show the ongoing location-sharing notice.")
            return false
        }
        val token = ApiClient.getAuthToken()?.takeIf(String::isNotBlank) ?: return false
        val next = DriverLocationSession(target, token)
        if (session?.target?.tripId != target.tripId || session?.token != token) stop()
        application = context.applicationContext
        session = next
        return try {
            ContextCompat.startForegroundService(context, Intent(context, DriverLocationService::class.java))
            true
        } catch (_: RuntimeException) {
            stop("Open the driver portal and allow location and notifications to start sharing.")
            false
        }
    }

    fun syncTarget(target: DriverLocationTarget?) {
        val current = session ?: return
        if (target == null || current.target.driverId != target.driverId || current.target.tripId != target.tripId || current.target.intercity != target.intercity) {
            stop("Trip ended. Location sharing stopped.")
        } else session = current.copy(target = target)
    }

    fun stop(message: String = "Trip location sharing is off.") {
        session = null
        _running.value = false
        _fix.value = null
        report(message)
        application?.let { it.stopService(Intent(it, DriverLocationService::class.java)) }
    }

    internal fun isCurrent(value: DriverLocationSession): Boolean = session?.let {
        it.target.driverId == value.target.driverId && it.target.tripId == value.target.tripId &&
            it.target.intercity == value.target.intercity && it.token == value.token && ApiClient.getAuthToken() == value.token &&
            it.environmentScope == value.environmentScope && ApiClient.isCurrent(value.environmentScope)
    } == true

    internal fun started() { _running.value = true; report("Trip location sharing is on, including while the screen is locked.") }
    internal fun notificationsVisible(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel("active_trip_location")
            if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }
    internal fun report(message: String) { _status.value = message }
    internal fun acceptFix(value: DriverGpsFix) { _fix.value = value }
    internal fun serviceStopped(value: DriverLocationSession?) {
        val current = session
        if (current != null && (value == null || current.token != value.token || current.target.driverId != value.target.driverId ||
                current.target.tripId != value.target.tripId || current.target.intercity != value.target.intercity)) return
        session = null
        _running.value = false
        _fix.value = null
    }
}

/** Strict fix checks shared by UI departure validation and the foreground service. */
internal fun isUsableDriverFix(latitude: Double, longitude: Double, timestamp: Long, now: Long, mock: Boolean): Boolean =
    !mock && latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0 && now - timestamp in 0L..120_000L
