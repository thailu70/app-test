package com.example.ui.screens.taxi

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.core.environment.ApiSessionScope
import com.example.data.api.*
import com.example.ui.screens.intercity.mobilityBody
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class TaxiTrackingTarget(val driverId: String, val vehicleId: String,
    val tripId: String? = null, val shared: Boolean = false, val sequence: Long = 0,
    val uploadSeconds: Int = 10)
internal data class TaxiTrackingSession(val target: TaxiTrackingTarget, val token: String, val scope: ApiSessionScope)

/** Visible, user-started tracking. Credentials remain process-only and are never restored at boot. */
internal object TaxiTracking {
    @Volatile var session: TaxiTrackingSession? = null
        private set
    private var app: Context? = null
    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        ApiClient.environment().storageName("taxi_tracking"), Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow("Location sharing is off.")
    internal var finisher: Pair<TaxiTrackingSession, suspend (Map<String, Any>) -> Long>? = null
    val status = mutable.asStateFlow()
    fun report(value: String) { mutable.value = value }
    fun current(value: TaxiTrackingSession) = session?.let {
        it.target.driverId == value.target.driverId && it.target.vehicleId == value.target.vehicleId &&
            it.target.tripId == value.target.tripId && it.target.shared == value.target.shared &&
            it.token == value.token && ApiClient.getAuthToken() == value.token && ApiClient.isCurrent(value.scope)
    } == true
    fun start(context: Context, target: TaxiTrackingTarget): Boolean {
        val token = ApiClient.getAuthToken()?.takeIf(String::isNotBlank) ?: return false
        if (!taxiLocationPermission(context) || !taxiNotificationsVisible(context)) {
            report("Allow location and notifications before going online or starting a trip."); return false
        }
        val next = TaxiTrackingSession(target, token, ApiClient.sessionScope())
        val old = session
        app = context.applicationContext
        saveTarget(context, target)
        session = next
        if (old != null && old.target.tripId == target.tripId && old.target.vehicleId == target.vehicleId &&
            old.target.driverId == target.driverId && old.target.shared == target.shared && old.token == token) return true
        return try {
            ContextCompat.startForegroundService(context, Intent(context, TaxiLocationService::class.java)); true
        } catch (_: RuntimeException) { stop("Open the driver portal to resume location sharing."); false }
    }
    /** Restore an active user-started tracking session if Android recreates its service. */
    fun restore(context: Context): TaxiTrackingSession? {
        session?.let { return it }
        val appContext = context.applicationContext
        val prefs = preferences(appContext)
        if (!prefs.getBoolean("active", false)) return null
        val saved = com.example.core.session.AndroidSessionStore(appContext).load() ?: return null
        val driverId = prefs.getString("driver_id", null) ?: return null
        if (saved.user.role != "DRIVER" || saved.user.id != driverId) return null
        val vehicleId = prefs.getString("vehicle_id", null)?.takeIf(String::isNotBlank) ?: return null
        val tripId = prefs.getString("trip_id", null)?.takeIf(String::isNotBlank)
        val target = TaxiTrackingTarget(driverId, vehicleId, tripId, prefs.getBoolean("shared", false),
            prefs.getLong("sequence", 0), prefs.getInt("upload_seconds", 10))
        return runCatching {
            val sessionScope = ApiClient.sessionScope()
            ApiClient.setAuthToken(saved.token, sessionScope)
            TaxiTrackingSession(target, saved.token, sessionScope).also { session = it; app = appContext }
        }.getOrNull()
    }
    private fun saveTarget(context: Context, target: TaxiTrackingTarget) {
        preferences(context).edit()
            .putBoolean("active", true).putString("driver_id", target.driverId).putString("vehicle_id", target.vehicleId)
            .putString("trip_id", target.tripId).putBoolean("shared", target.shared)
            .putLong("sequence", target.sequence).putInt("upload_seconds", target.uploadSeconds).apply()
    }
    fun stop(message: String = "Location sharing is off.") {
        app?.let(::preferences)?.edit()?.clear()?.apply()
        session = null; finisher = null; report(message); app?.stopService(Intent(app, TaxiLocationService::class.java))
    }
    suspend fun finishMeter(tripId: String, point: Map<String, Any>): Long {
        val value = finisher ?: error("Resume trip location sharing and reconnect before completing the meter.")
        check(current(value.first) && value.first.target.tripId == tripId && !value.first.target.shared) { "Reopen this trip before completing it." }
        return value.second(point)
    }
}

internal fun taxiLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
internal fun taxiNotificationsVisible(context: Context): Boolean {
    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
    if (Build.VERSION.SDK_INT >= 26) {
        val channel = (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel("taxi_location")
        if (channel?.importance == NotificationManager.IMPORTANCE_NONE) return false
    }
    return true
}

internal fun Location.taxiBody(): Map<String, Any> = mapOf("latitude" to latitude, "longitude" to longitude,
    "accuracyMeters" to accuracy.toDouble(), "timestamp" to taxiTimestamp(time))
internal fun taxiTimestamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(time))
internal fun usableTaxiFix(location: Location, nowElapsed: Long = SystemClock.elapsedRealtimeNanos()): Boolean =
    !location.isFromMockProvider && location.hasAccuracy() && location.accuracy in 0.1f..100f &&
        location.latitude.isFinite() && location.longitude.isFinite() && location.latitude in -90.0..90.0 &&
        location.longitude in -180.0..180.0 && (nowElapsed - location.elapsedRealtimeNanos) / 1_000_000L in 0..60_000

/** Encrypted, bounded retry queue. No auth token, passenger identity or phone number is stored. */
internal class TaxiGpsQueue(context: Context, session: TaxiTrackingSession) {
    private val identity = "${session.scope.baseUrl}|${session.target.driverId}|${session.target.tripId}"
    private val hash = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    private val file = File(context.noBackupFilesDir, "taxi-gps-$hash.enc")
    private val aad = identity.toByteArray()
    private val alias = "passo_taxi_meter_v1"
    private var writtenVersion = -1L
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        check(create) { "The saved trip location key is unavailable." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    @Synchronized fun read(): List<TaxiGpsPoint> {
        if (!file.exists()) return emptyList()
        val raw = file.readBytes(); check(raw.size in 29..2_000_000) { "The saved trip location queue is invalid." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, raw.copyOfRange(0, 12))); cipher.updateAAD(aad)
        val plain = cipher.doFinal(raw.copyOfRange(12, raw.size))
        return try { val rows = JSONArray(String(plain, Charsets.UTF_8)); check(rows.length() <= 7200)
            (0 until rows.length()).map { index -> val row = rows.getJSONObject(index)
                TaxiGpsPoint(row.getLong("sequence"), row.getDouble("latitude"), row.getDouble("longitude"), row.getDouble("accuracyMeters"), row.getString("timestamp")) }
        } finally { plain.fill(0) }
    }
    @Synchronized fun write(points: List<TaxiGpsPoint>, version: Long = 0) {
        if (version < writtenVersion) return
        writtenVersion = version
        if (points.isEmpty()) { if (file.exists()) check(file.delete()); return }
        val json = JSONArray().apply { points.takeLast(7200).forEach { point -> put(JSONObject().put("sequence", point.sequence)
            .put("latitude", point.latitude).put("longitude", point.longitude).put("accuracyMeters", point.accuracyMeters).put("timestamp", point.timestamp)) } }
        val plain = json.toString().toByteArray(); val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(aad)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val temp = File(file.parentFile, file.name + ".new"); temp.writeBytes(cipher.iv + encrypted)
        check(temp.renameTo(file)) { "Trip locations could not be saved for retry." }
    }
}

class TaxiLocationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listener: LocationListener? = null
    private var manager: LocationManager? = null
    private var fusedCallback: LocationCallback? = null
    private var worker: Job? = null
    private var last: Location? = null
    private var active: TaxiTrackingSession? = null
    private var acceptedSequence = 0L
    private var sequence = 0L
    private var storeVersion = 0L
    private var queue: TaxiGpsQueue? = null
    private val pending = mutableListOf<TaxiGpsPoint>()
    private val uploads = Mutex()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { TaxiTracking.stop("Location sharing paused. Reopen the driver portal before continuing."); return START_NOT_STICKY }
        // Promote immediately: Android gives startForegroundService() only a short deadline.
        // Session restoration and validation can touch Keystore and preferences, so do not
        // perform either operation before the service has its visible foreground notification.
        try {
            ServiceCompat.startForeground(this, 7103, notification(),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
        } catch (_: RuntimeException) {
            TaxiTracking.stop("Open the driver portal and allow location and notifications.")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val session = TaxiTracking.session ?: TaxiTracking.restore(this)
        if (session == null || !TaxiTracking.current(session) || !taxiLocationPermission(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (active?.target == session.target && worker?.isActive == true) return START_STICKY
        clean(); active = session; sequence = session.target.sequence
        worker = scope.launch {
            val api = ApiClient.createSessionService(TaxiApi::class.java, session.token)
            try {
                if (session.target.tripId != null && !session.target.shared) {
                    queue = TaxiGpsQueue(this@TaxiLocationService, session)
                    pending.addAll(withContext(Dispatchers.IO) { queue!!.read() })
                    sequence = maxOf(sequence, pending.maxOfOrNull { it.sequence } ?: 0)
                    val finish: suspend (Map<String, Any>) -> Long = { point -> uploads.withLock {
                        check(TaxiTracking.current(session)) { "This trip session ended." }
                        val final = TaxiGpsPoint(++sequence, point["latitude"] as Double, point["longitude"] as Double,
                            point["accuracyMeters"] as Double, point["timestamp"] as String)
                        pending.add(final); persistQueue()
                        while (pending.any { it.sequence <= final.sequence }) uploadBatch(api, session.target.tripId, final.sequence)
                        final.sequence
                    } }
                    TaxiTracking.finisher = session to finish
                }
                begin(session)
                var checkedAt = 0L
                while (isActive && TaxiTracking.current(session)) {
                    if (!taxiLocationPermission(this@TaxiLocationService)) error("Location permission ended.")
                    check(taxiNotificationsVisible(this@TaxiLocationService)) { "Enable taxi location notifications before resuming this trip." }
                    val now = SystemClock.elapsedRealtime()
                    val point = last?.takeIf { usableTaxiFix(it) }
                    if (session.target.tripId == null) {
                        try {
                            val heartbeat = (if (point != null) point.taxiBody() + mapOf("online" to true, "vehicleId" to session.target.vehicleId)
                                else mapOf("online" to true, "vehicleId" to session.target.vehicleId, "heartbeatOnly" to true)) + mapOf("backgroundSync" to true)
                            check(mobilityBody(api.availability(heartbeat)).success)
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { TaxiTracking.report("Unable to connect. Please connect to the internet.") }
                    }
                    if (now - checkedAt >= 20_000) {
                        val response = try { api.dashboard() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        if (response?.code() in setOf(401, 403)) error("Your driver session ended.")
                        if (response?.isSuccessful == true && response.body()?.success == true) {
                            val dashboard = response.body()!!
                            checkedAt = now
                            if (session.target.tripId != null && session.target.shared)
                                check(dashboard.sharedTrips.any { it.id == session.target.tripId && it.status in setOf("OPEN", "BOARDING", "IN_PROGRESS") }) { "The shared trip ended." }
                            else if (session.target.tripId != null)
                                check(dashboard.ride?.id == session.target.tripId && dashboard.ride.status in setOf("ACCEPTED", "AT_PICKUP", "IN_PROGRESS")) { "The ride ended." }
                        } else {
                            checkedAt = now
                            TaxiTracking.report("Unable to connect. Please connect to the internet.")
                        }
                    }
                    try {
                        when {
                            session.target.shared && point != null -> check(mobilityBody(api.sharedLocation(session.target.tripId!!, point.taxiBody())).success)
                            session.target.tripId != null && pending.isNotEmpty() -> {
                                uploads.withLock { uploadBatch(api, session.target.tripId) }
                            }
                        }
                        TaxiTracking.report(when {
                            point == null -> "Location sharing is on. Waiting for precise GPS."
                            session.target.tripId != null && !session.target.shared && point.accuracy > 50f ->
                                "Waiting for precise GPS. The meter is paused until location is accurate."
                            else -> "Location sharing is on, including while the screen is locked."
                        })
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { TaxiTracking.report("Connection interrupted. Trip locations are queued for retry; the server meter remains authoritative.") }
                    delay(session.target.uploadSeconds.coerceIn(1, 60) * 1000L)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { TaxiTracking.report(e.message ?: "Open the driver portal to resume tracking.") }
            finally {
                if (TaxiTracking.current(session)) TaxiTracking.stop("Location sharing stopped. Open the driver portal to resume.")
                // Android ignores an obsolete startId when a newer trip has replaced this one.
                stopSelf(startId)
            }
        }
        return START_STICKY
    }
    private suspend fun persistQueue() {
        val snapshot = pending.toList(); val store = queue; val version = ++storeVersion
        withContext(Dispatchers.IO) { store?.write(snapshot, version) }
    }
    private suspend fun uploadBatch(api: TaxiApi, tripId: String, through: Long = Long.MAX_VALUE) {
        val batch = pending.filter { it.sequence <= through }.take(120)
        if (batch.isEmpty()) return
        val result = mobilityBody(api.locations(tripId, TaxiGpsBatch(batch)))
        check(result.success && (result.ride?.lastSequence ?: -1) >= batch.last().sequence) { result.error ?: "Trip locations have not been acknowledged. Reconnect before completing." }
        acceptedSequence = batch.last().sequence
        pending.removeAll { it.sequence <= acceptedSequence }; persistQueue()
    }
    @Suppress("MissingPermission", "DEPRECATION")
    private fun begin(session: TaxiTrackingSession) {
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        check(listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { manager!!.isProviderEnabled(it) }) {
            "Turn on device location before going online."
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (!TaxiTracking.current(session) || !usableTaxiFix(location)) return
                if (last?.time?.let { location.time <= it } == true) return
                last = location
                if (session.target.tripId != null && !session.target.shared) {
                    sequence++
                    pending.add(TaxiGpsPoint(sequence, location.latitude, location.longitude, location.accuracy.toDouble(), taxiTimestamp(location.time)))
                    if (pending.size > 7200) { TaxiTracking.report("The offline trip queue is full. Reconnect before continuing."); pending.removeAt(0) }
                    val snapshot = pending.toList(); val store = queue; val version = ++storeVersion
                    scope.launch(Dispatchers.IO) {
                        runCatching { store?.write(snapshot, version) }.onFailure { TaxiTracking.report("Trip locations could not be saved for retry. Reconnect before continuing.") }
                    }
                }
            }
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) { TaxiTracking.report("Turn on device location to continue accurate tracking.") }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        val intervalMs = session.target.uploadSeconds.coerceIn(1, 60) * 1_000L
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location -> listener?.onLocationChanged(location) }
            }
        }
        fusedCallback = callback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs.toLong())
            .setMinUpdateIntervalMillis((intervalMs / 2).coerceAtLeast(1_000L))
            .setMaxUpdateDelayMillis(intervalMs.toLong())
            .setWaitForAccurateLocation(true)
            .build()
        LocationServices.getFusedLocationProviderClient(this)
            .requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener {
                if (!TaxiTracking.current(session)) return@addOnFailureListener
                runCatching { startPlatformLocationFallback(session, intervalMs) }
                    .onFailure { TaxiTracking.report("Turn on device location and wait for a precise GPS fix.") }
            }
    }
    @Suppress("MissingPermission", "DEPRECATION")
    private fun startPlatformLocationFallback(session: TaxiTrackingSession, intervalMs: Long) {
        manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = (if (session.target.tripId != null && !session.target.shared)
            listOf(LocationManager.GPS_PROVIDER)
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
            .filter { manager!!.isProviderEnabled(it) }
        check(providers.isNotEmpty()) { "Turn on device location before going online." }
        providers.forEach { manager!!.requestLocationUpdates(it, intervalMs, 0f, listener!!, Looper.getMainLooper()) }
    }
    private fun notification(): Notification {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(NotificationChannel("taxi_location", "Taxi availability and trip location", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 4, Intent(this, TaxiLocationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "taxi_location").setSmallIcon(R.drawable.ic_trip_location)
            .setContentTitle("PASSO · Taxi location sharing").setContentText("Location supports nearby calls and the trip meter. Stop sharing to pause.")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).addAction(0, "Stop sharing", stop).build()
    }
    private fun clean() {
        worker?.cancel(); worker = null
        fusedCallback?.let { callback -> runCatching { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(callback) } }
        fusedCallback = null
        listener?.let { runCatching { manager?.removeUpdates(it) } }
        listener = null; manager = null; last = null; pending.clear(); queue = null; TaxiTracking.finisher = null
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        TaxiTracking.stop("Driver availability stopped because Passo was closed.")
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() { clean(); scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    companion object { private const val STOP = "com.example.STOP_TAXI_LOCATION" }
}
