package com.example.core.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.api.*
import kotlinx.coroutines.*

/** User-started, visible trip tracking; no boot receiver, sticky restart or disk credentials. */
class DriverLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uploads = DriverGpsUploadGate { SystemClock.elapsedRealtime() }
    private var active: DriverLocationSession? = null
    private var locationManager: LocationManager? = null
    private var listener: LocationListener? = null
    private var tripConfirmedAt = 0L
    private var sharingJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DriverLocationSharing.stop("You stopped trip location sharing.")
            stopSelf()
            return START_NOT_STICKY
        }
        val session = DriverLocationSharing.session
        if (session == null || !DriverLocationSharing.isCurrent(session) || !hasLocationPermission()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (active == session && sharingJob?.isActive == true) return START_NOT_STICKY
        stopTracking()
        active = session
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (_: RuntimeException) {
            DriverLocationSharing.stop("Location sharing could not start. Open the driver portal and check permissions.")
            stopSelf()
            return START_NOT_STICKY
        }
        val (operations, api) = createApis(session.token)
        sharingJob = scope.launch {
            while (isActive && DriverLocationSharing.isCurrent(session)) {
                if (!hasLocationPermission() || !DriverLocationSharing.notificationsVisible(this@DriverLocationService)) break
                try {
                    val response = if (session.target.intercity) operations.departures() else null
                    val tripResponse = if (!session.target.intercity) operations.activeTrip() else null
                    val status = response?.code() ?: tripResponse!!.code()
                    if (status == 401 || status == 403) break
                    val found = if (response != null) {
                        check(response.isSuccessful && response.body()?.success == true)
                        response.body()!!.departures.any { it.id == session.target.tripId && it.driver_id == session.target.driverId && it.status == "BOARDING" }
                    } else {
                        check(tripResponse!!.isSuccessful && tripResponse.body()?.success == true)
                        tripResponse.body()!!.trip?.let { it.id == session.target.tripId && it.driverId == session.target.driverId && it.status == "IN_PROGRESS" } == true
                    }
                    if (!found) break
                    tripConfirmedAt = SystemClock.elapsedRealtime()
                    if (listener == null) beginLocations(session, api)
                    val config = withTimeoutOrNull(8_000L) { api.getGpsSharing() }
                    if (config?.isSuccessful == true && config.body()?.success == true) uploads.updateInterval(config.body()?.intervalSeconds)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    DriverLocationSharing.report("Location sharing is waiting for the server connection.")
                    if (tripConfirmedAt == 0L || SystemClock.elapsedRealtime() - tripConfirmedAt > TRIP_LEASE_MILLIS) break
                }
                delay(20_000L)
            }
            if (DriverLocationSharing.isCurrent(session)) DriverLocationSharing.stop("Trip or connection ended. Location sharing stopped; reopen the driver portal to resume.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginLocations(session: DriverLocationSession, api: RoutePassApiService) {
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = manager
        val callback = object : LocationListener {
            override fun onProviderEnabled(provider: String) { DriverLocationSharing.report("Trip sharing is on. Waiting for a fresh GPS fix.") }
            override fun onProviderDisabled(provider: String) { DriverLocationSharing.report("Trip sharing is on. Turn on Location to send positions.") }
            @Deprecated("Required for Android 7-9 LocationListener compatibility")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
            override fun onLocationChanged(location: Location) {
                if (!DriverLocationSharing.isCurrent(session) || !hasLocationPermission() || !DriverLocationSharing.notificationsVisible(this@DriverLocationService)) {
                    DriverLocationSharing.stop("Location sharing stopped. Check your signed-in account and permissions.")
                    return
                }
                if (SystemClock.elapsedRealtime() - tripConfirmedAt > TRIP_LEASE_MILLIS ||
                    !isUsableDriverFix(location.latitude, location.longitude, location.time, System.currentTimeMillis(), location.isFromMockProvider)) return
                if (location.hasAccuracy()) DriverLocationSharing.acceptFix(DriverGpsFix(location.latitude, location.longitude, location.accuracy.toDouble(), location.time))
                val claim = uploads.tryBeginUpload() ?: return
                scope.launch {
                    try {
                        if (!DriverLocationSharing.isCurrent(session)) return@launch
                        val response = api.updateMyLocation(LocationUpdateRequest(location.latitude, location.longitude,
                            (location.speed.toDouble() * 3.6).coerceAtLeast(0.0), DriverLocationSharing.session?.target?.currentStop.orEmpty()))
                        if (!DriverLocationSharing.isCurrent(session)) return@launch
                        if (response.code() == 401 || response.code() == 403) {
                            DriverLocationSharing.stop("Your session ended. Location sharing stopped.")
                        } else DriverLocationSharing.report(if (response.isSuccessful && response.body()?.success == true)
                            "Trip location sharing is on · latest position sent." else "Location sharing is on; the latest upload failed. Retrying with fresh GPS.")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { DriverLocationSharing.report("Location sharing is on; waiting for a network connection.") }
                    finally { uploads.finishUpload(claim) }
                }
            }
        }
        listener = callback
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { it in manager.allProviders }
        providers.forEach {
            try { manager.requestLocationUpdates(it, 1000L, 0f, callback, Looper.getMainLooper()) }
            catch (_: SecurityException) { DriverLocationSharing.stop("Location permission was revoked. Sharing stopped.") }
            catch (_: IllegalArgumentException) { /* Provider disappeared; the other provider may still work. */ }
        }
        if (DriverLocationSharing.isCurrent(session)) {
            DriverLocationSharing.started()
            if (providers.none { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) })
                DriverLocationSharing.report("Trip sharing is on. Turn on your phone's Location to send positions.")
        }
    }

    private fun hasLocationPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun notification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Active trip location", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriverLocationService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_trip_location)
            .setContentTitle("PASSO · Trip location sharing")
            .setContentText("Sharing during your active trip, even with the screen locked. Tap Stop to pause.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop sharing", stop).build()
    }

    private fun stopTracking() {
        sharingJob?.cancel()
        sharingJob = null
        listener?.let { callback -> runCatching { locationManager?.removeUpdates(callback) } }
        listener = null
        uploads.reset()
    }

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        DriverLocationSharing.serviceStopped(active)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal var createApis: (String) -> Pair<DriverOperationsApi, RoutePassApiService> = { token ->
            ApiClient.createSessionService(DriverOperationsApi::class.java, token) to ApiClient.createSessionService(RoutePassApiService::class.java, token)
        }
        internal const val TRIP_LEASE_MILLIS = 90_000L
        private const val NOTIFICATION_ID = 7102
        private const val CHANNEL_ID = "active_trip_location"
        private const val ACTION_STOP = "com.example.STOP_TRIP_LOCATION"
    }
}
