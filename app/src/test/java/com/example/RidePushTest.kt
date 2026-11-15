package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.environment.AppEnvironment
import com.example.core.environment.EnvironmentPreferences
import com.example.core.notifications.*
import com.example.data.api.ApiClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RidePushTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val payload = mapOf("requestId" to "ride-123", "eventType" to "STATUS_ACCEPTED", "scope" to "PASSENGER", "kind" to "TAXI")
    @After fun clean() { ApiClient.setAuthToken(null); ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL); EnvironmentPreferences.save(context, AppEnvironment.PRODUCTION) }
    @Test fun `ride references accept only account scopes and never contain location PIN or links`() {
        assertNotNull(MobilityPushPayload.parse(payload))
        assertNotNull(MobilityPushPayload.parse(payload + ("kind" to "SHARED") + ("eventType" to "BOOKING_UPDATED")))
        assertNull(MobilityPushPayload.parse(payload + ("scope" to "REQUEST")))
        assertNull(MobilityPushPayload.parse(payload + ("kind" to "CARGO")))
        for (key in listOf("boardingPin", "latitude", "phone", "url", "secret")) assertNull(MobilityPushPayload.parse(payload + (key to "private")))
        assertFalse(MobilityPushPayload.parse(payload)!!.notificationCopy().toString().contains("ride-123"))
    }
    @Test fun `in flight registration cannot bind after account token permission or environment changes`() {
        fun confirmed(owner: String? = "driver-a", role: String? = "DRIVER", token: String? = "session-a", session: Boolean = true, allowed: Boolean = true) =
            canConfirmRidePush("driver-a", "DRIVER", owner, role, "session-a", token, session, allowed)
        assertTrue(confirmed())
        assertFalse(confirmed(owner = "driver-b")); assertFalse(confirmed(owner = null))
        assertFalse(confirmed(role = "PASSENGER")); assertFalse(confirmed(token = "rotated-session"))
        assertFalse(confirmed(session = false)); assertFalse(confirmed(allowed = false))
    }
    @Test fun `logout revokes closed app delivery durably without depending on a server connection`() {
        EnvironmentPreferences.save(context, AppEnvironment.PRODUCTION)
        RidePush.setSession(context, "driver-fixture", "DRIVER")
        RidePush.beforeLogout(context)
        assertTrue(MobilityPushStore(context).ridesRevoked())
        assertFalse(RidePush.deliveryAllowed(context, "DRIVER"))
        assertFalse(RidePush.deliveryAllowed(context, "PASSENGER"))
    }
    @Test fun `switching role and review environment cannot retain another account delivery`() {
        RidePush.setSession(context, "passenger-fixture", "PASSENGER")
        assertFalse(RidePush.deliveryAllowed(context, "DRIVER"))
        ApiClient.setBaseUrl(AppEnvironment.DEMO_BASE_URL); EnvironmentPreferences.save(context, AppEnvironment.DEMO)
        RidePush.setSession(context, "passenger-fixture", "PASSENGER")
        assertFalse(RidePush.deliveryAllowed(context, "PASSENGER"))
    }
}
