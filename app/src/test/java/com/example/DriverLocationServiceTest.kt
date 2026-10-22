package com.example

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.core.location.*
import com.example.data.api.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Proxy

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriverLocationServiceTest {
    private val application: Application get() = ApplicationProvider.getApplicationContext()
    private val originalFactory = DriverLocationService.createApis
    private val target = DriverLocationTarget("fixture-driver", "fixture-trip", false, "Actual pickup")
    private val scheduler = TestCoroutineScheduler()
    private var tripActive = true
    private var uploads = 0
    private var lastUpload: LocationUpdateRequest? = null

    @Before fun isolateService() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        val manager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        ApiClient.setAuthToken("fixture-only-token")
        DriverLocationService.createApis = { token ->
            assertEquals("fixture-only-token", token)
            val operations = Proxy.newProxyInstance(DriverOperationsApi::class.java.classLoader, arrayOf(DriverOperationsApi::class.java)) { _, method, _ ->
                check(method.name == "activeTrip")
                Response.success(ActiveTripResponse(true, tripActive, if (tripActive) TripDto(target.tripId, target.driverId, "fixture-vehicle", routeId = "fixture-route") else null))
            } as DriverOperationsApi
            val api = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader, arrayOf(RoutePassApiService::class.java)) { _, method, args ->
                when (method.name) {
                    "getGpsSharing" -> Response.success(GpsSharingResponse(true, 5))
                    "updateMyLocation" -> { uploads++; lastUpload = args!![0] as LocationUpdateRequest; Response.success(GenericResponse(true)) }
                    else -> error("Unexpected external request: ${method.name}")
                }
            } as RoutePassApiService
            operations to api
        }
    }

    @After fun cleanService() {
        DriverLocationSharing.stop()
        ApiClient.setAuthToken(null)
        DriverLocationService.createApis = originalFactory
        Dispatchers.resetMain()
    }

    @Test fun `active trip uses visible service fresh fixes and ends when server trip completes`() {
        assertTrue(DriverLocationSharing.start(application, target))
        val intent = shadowOf(application).nextStartedService
        assertNotNull(intent)
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
        val controller = Robolectric.buildService(DriverLocationService::class.java).create()
        try {
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(application, DriverLocationService::class.java), 0, 1))
            scheduler.runCurrent()
            assertTrue(DriverLocationSharing.running.value)
            val notification = shadowOf(service).lastForegroundNotification!!
            assertTrue(notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Stop sharing", notification.actions.single().title)
            val manager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            fun fix(time: Long) = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 9.0; longitude = 38.0; accuracy = 8f; this.time = time
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos().coerceAtLeast(1)
            }
            shadowOf(manager).simulateLocation(fix(System.currentTimeMillis() - 120_001))
            shadowOf(Looper.getMainLooper()).idle(); scheduler.runCurrent()
            assertEquals(0, uploads)
            assertNull(DriverLocationSharing.fix.value)
            // The platform's 1s provider interval is separate from the 5s server upload gate.
            // A stale delivered fix still advances the provider's last-delivery timestamp.
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100))
            shadowOf(manager).simulateLocation(fix(System.currentTimeMillis()))
            shadowOf(Looper.getMainLooper()).idle(); scheduler.runCurrent()
            assertEquals(1, uploads)
            assertEquals("Actual pickup", lastUpload!!.currentStop)
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(1100))
            shadowOf(manager).simulateLocation(fix(System.currentTimeMillis()).apply { longitude = 38.1 })
            shadowOf(Looper.getMainLooper()).idle(); scheduler.runCurrent()
            assertEquals(38.1, DriverLocationSharing.fix.value!!.longitude, 0.0)
            assertEquals(1, uploads)
            tripActive = false
            scheduler.advanceTimeBy(20_000); scheduler.runCurrent()
            assertFalse(DriverLocationSharing.running.value)
        } finally { controller.destroy() }
    }

    @Test fun `missing permission or a restarted process cannot silently resume tracking`() {
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertFalse(DriverLocationSharing.start(application, target))
        DriverLocationSharing.stop()
        val controller = Robolectric.buildService(DriverLocationService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, controller.get().onStartCommand(null, 0, 1))
            scheduler.runCurrent()
            assertFalse(DriverLocationSharing.running.value)
            assertEquals(0, uploads)
        } finally { controller.destroy() }
    }

    @Test fun `signout and account switch invalidate the old trip session before another upload`() {
        assertTrue(DriverLocationSharing.start(application, target))
        val session = DriverLocationSharing.session!!
        assertTrue(DriverLocationSharing.isCurrent(session))
        ApiClient.setAuthToken("different-account-token")
        assertFalse(DriverLocationSharing.isCurrent(session))
        DriverLocationSharing.stop()
        assertNull(DriverLocationSharing.session)
        assertFalse(DriverLocationSharing.running.value)
    }

    @Test fun `fake future stale and invalid coordinates never become usable GPS fixes`() {
        val now = 1000000L
        assertTrue(isUsableDriverFix(9.0, 38.0, now, now, false))
        assertFalse(isUsableDriverFix(9.0, 38.0, now, now, true))
        assertFalse(isUsableDriverFix(9.0, 38.0, now + 1, now, false))
        assertFalse(isUsableDriverFix(9.0, 38.0, now - 120001, now, false))
        assertFalse(isUsableDriverFix(Double.NaN, 38.0, now, now, false))
        assertFalse(isUsableDriverFix(9.0, Double.POSITIVE_INFINITY, now, now, false))
        assertFalse(isUsableDriverFix(91.0, 38.0, now, now, false))
    }
}
