package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.environment.*
import com.example.data.api.ApiClient
import com.example.data.database.TransportDatabase
import com.example.data.entity.UserEntity
import com.example.data.repository.AndroidEncryptedIntercityBookingStore
import com.example.ui.screens.intercity.trustedMobilityPassLink
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EnvironmentIsolationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val originalBase = ApiClient.getBaseUrl()

    @After fun restore() { ApiClient.setAuthToken(null); ApiClient.setBaseUrl(originalBase) }

    @Test fun `demo handshake must explicitly identify isolated data and disabled external services`() {
        val valid = mapOf("success" to true, "environment" to "REVIEW", "synthetic" to true,
            "paymentsEnabled" to false, "smsEnabled" to false)
        assertTrue(isIsolatedReviewDescriptor(valid))
        assertFalse(isIsolatedReviewDescriptor(null))
        for (key in valid.keys) assertFalse(key, isIsolatedReviewDescriptor(valid - key))
        assertFalse(isIsolatedReviewDescriptor(valid + ("environment" to "PRODUCTION")))
        assertFalse(isIsolatedReviewDescriptor(valid + ("synthetic" to false)))
        assertFalse(isIsolatedReviewDescriptor(valid + ("paymentsEnabled" to true)))
        assertFalse(isIsolatedReviewDescriptor(valid + ("smsEnabled" to true)))
        assertFalse(isIsolatedReviewDescriptor(valid + ("smsEnabled" to "false")))
    }

    @Test fun `review API private pass links and websocket preserve prefix without escaping into live`() {
        ApiClient.setBaseUrl(AppEnvironment.DEMO_BASE_URL)
        assertEquals("https://passo.live/review/api/routes", ApiClient.portalUrl("api/routes"))
        assertEquals("wss://passo.live/review/ws", ApiClient.webSocketUrl())
        val pass = "/review/api/mobility/boarding/example?token=synthetic"
        assertEquals("https://passo.live$pass", ApiClient.portalUrl(pass))
        assertTrue(trustedMobilityPassLink(pass))
        assertFalse(trustedMobilityPassLink("https://passo.live/api/mobility/boarding/example"))
        assertFalse(trustedMobilityPassLink("https://attacker.example/review/api/mobility/ticket"))
        val scope = ApiClient.sessionScope()
        for (path in listOf("/api/auth/login", "/review/../api/auth/login", "/review/%2e%2e/api/auth/login", "/reviewed/api/auth/login", "/review/%2Fapi/auth/login")) {
            assertFalse(path, scope.containsUrl("https://passo.live$path"))
        }
        ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
        assertFalse(ApiClient.sessionScope().containsUrl("https://passo.live/review/api/routes"))
    }

    @Test fun `environment transition destroys bearer scope even after returning to same origin`() = runBlocking {
        ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
        ApiClient.setAuthToken("synthetic-production-fixture")
        val production = ApiClient.sessionScope()
        val oldService = ApiClient.getService()
        ApiClient.setBaseUrl(AppEnvironment.DEMO_BASE_URL)
        assertNull(ApiClient.getAuthToken())
        ApiClient.setAuthToken("synthetic-demo-fixture")
        val review = ApiClient.sessionScope()
        ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
        assertNull(ApiClient.getAuthToken())
        assertFalse(ApiClient.isCurrent(production))
        assertFalse(ApiClient.isCurrent(review))
        try { ApiClient.setAuthToken("late-demo-token", review); fail("A previous environment cannot restore its bearer") }
        catch (_: IllegalStateException) { }
        assertNull(ApiClient.getAuthToken())
        // This stale Retrofit instance must fail in its interceptor, before DNS or HTTP.
        try { oldService.getRoutes(); fail("An old client must never make a request after a switch") }
        catch (error: java.io.IOException) { assertTrue(error.message.orEmpty().contains("environment changed")) }
    }

    @Test fun `live Room cache is retained and cannot be read from demo database`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val live = TransportDatabase.getDatabase(context, scope, AppEnvironment.PRODUCTION)
        val demo = TransportDatabase.getDatabase(context, scope, AppEnvironment.DEMO)
        try {
            assertNotSame(live, demo)
            live.transportDao().insertUser(UserEntity("same-fixture-id", "PASSENGER", "Live fixture", "0900000001", ""))
            assertNull(demo.transportDao().getUserById("same-fixture-id"))
            demo.transportDao().insertUser(UserEntity("same-fixture-id", "PASSENGER", "Demo fixture", "0900000002", ""))
            assertEquals("Live fixture", TransportDatabase.getDatabase(context, scope, AppEnvironment.PRODUCTION).transportDao().getUserById("same-fixture-id")!!.fullName)
            assertEquals("Demo fixture", demo.transportDao().getUserById("same-fixture-id")!!.fullName)
        } finally { scope.cancel() }
    }

    @Test fun `review selection preserves legacy encrypted guest files and uses a different crypto context`() {
        EnvironmentPreferences.save(context, AppEnvironment.DEMO)
        assertEquals(AppEnvironment.DEMO, EnvironmentPreferences.selected(context))
        for ((namespace, originalName) in listOf("intercity" to "intercity_booking_access", "mobility" to "mobility_private_access")) {
            val production = context.getSharedPreferences(originalName, Context.MODE_PRIVATE)
            production.edit().putString("capabilities_v1", "opaque-live-fixture-unchanged").commit()
            val demo = AndroidEncryptedIntercityBookingStore(context, namespace, AppEnvironment.DEMO)
            assertTrue(demo.load().isEmpty())
            assertEquals("opaque-live-fixture-unchanged", production.getString("capabilities_v1", null))
            assertFalse(capabilityContext(namespace, AppEnvironment.PRODUCTION).contentEquals(capabilityContext(namespace, AppEnvironment.DEMO)))
            assertEquals(originalName, AppEnvironment.PRODUCTION.storageName(originalName))
        }
        assertEquals("RoutePass.Intercity.BookingAccess.v1", String(capabilityContext("intercity", AppEnvironment.PRODUCTION), Charsets.UTF_8))
        EnvironmentPreferences.save(context, AppEnvironment.PRODUCTION)
        assertEquals(AppEnvironment.PRODUCTION, EnvironmentPreferences.selected(context))
    }
}
