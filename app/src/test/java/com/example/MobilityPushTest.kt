package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
class MobilityPushTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val payload = mapOf("requestId" to "request-123", "eventType" to "QUOTES", "scope" to "REQUEST", "kind" to "CARGO")

    @After fun resetEnvironment() { ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL); EnvironmentPreferences.save(context, AppEnvironment.PRODUCTION) }

    @Test fun `demo environment cannot create notification registrations even with device permission`() {
        assertFalse(mobilityPushAllowed(AppEnvironment.DEMO, notificationsGranted = true, firebaseConfigured = true))
        assertFalse(mobilityPushAllowed(AppEnvironment.PRODUCTION, notificationsGranted = false, firebaseConfigured = true))
        assertTrue(mobilityPushAllowed(AppEnvironment.PRODUCTION, notificationsGranted = true, firebaseConfigured = true))
        ApiClient.setBaseUrl(AppEnvironment.DEMO_BASE_URL); EnvironmentPreferences.save(context, AppEnvironment.DEMO)
        assertNull(MobilityPush.registerRequest(context, "request-123", "private-request-capability"))
        assertNull(MobilityPush.syncGuestRequests(context))
    }

    @Test fun `push accepts only known reference payload and rejects secret or external link fields`() {
        assertEquals("request-123", MobilityPushPayload.parse(payload)!!.requestId)
        assertNull(MobilityPushPayload.parse(payload + ("secret" to "private-capability")))
        assertNull(MobilityPushPayload.parse(payload + ("url" to "https://attacker.example/")))
        assertNull(MobilityPushPayload.parse(payload + ("requestId" to "https://attacker.example/")))
        assertNull(MobilityPushPayload.parse(payload + ("eventType" to "OPEN_URL")))
        assertNull(MobilityPushPayload.parse(payload + ("scope" to "ADMIN")))
    }

    @Test fun `notification intent contains no capability and custom deep links are refused`() {
        val intent = Intent().putExtra(MobilityPushPayload.EXTRA_ID, "request-123")
            .putExtra(MobilityPushPayload.EXTRA_EVENT, "QUOTES").putExtra(MobilityPushPayload.EXTRA_SCOPE, "REQUEST")
            .putExtra(MobilityPushPayload.EXTRA_KIND, "CARGO")
        assertNotNull(MobilityPushPayload.fromIntent(intent))
        intent.data = Uri.parse("https://attacker.example/redirect")
        assertNull(MobilityPushPayload.fromIntent(intent))
        val copy = MobilityPushPayload.parse(payload)!!.notificationCopy()
        assertFalse(copy.toString().contains("request-123"))
        assertFalse(copy.toString().contains("private-capability"))
    }

    @Test fun `messaging service is private and token auto initialisation stays disabled`() {
        val service = context.packageManager.getServiceInfo(ComponentName(context, PassoMessagingService::class.java), 0)
        assertFalse(service.exported)
        val app = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertFalse(app.metaData.getBoolean("firebase_messaging_auto_init_enabled", true))
        assertFalse(app.metaData.getBoolean("firebase_analytics_collection_enabled", true))
        assertEquals("cargo_updates", app.metaData.getString("com.google.firebase.messaging.default_notification_channel_id"))
    }

    @Test fun `logout writes durable revocation even when encrypted notification storage is unavailable`() {
        val store = MobilityPushStore(context)
        store.revokeDriver()
        assertTrue(store.driverRevoked())
        assertFalse(MobilityPush.driverDeliveryAllowed(context))
        assertTrue(java.io.File(context.noBackupFilesDir, "mobility_push_driver_revoked_v1").exists())
    }
    @Test fun `cold start initialisation makes no argument logout revoke before returning`() {
        MobilityPush.initialize(context)
        assertNotNull(MobilityPush.beforeLogout())
        assertTrue(MobilityPushStore(context).driverRevoked())
        assertFalse(MobilityPush.driverDeliveryAllowed(context))
    }
    @Test fun `changing driver sessions revokes the previous registration synchronously`() {
        ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
        EnvironmentPreferences.save(context, AppEnvironment.PRODUCTION)
        MobilityPush.beforeLogout(context)
        val marker = java.io.File(context.noBackupFilesDir, "mobility_push_driver_revoked_v1")
        marker.delete()
        MobilityPush.setDriverSession(context, "fixture-driver-change", "DRIVER")
        assertTrue(marker.exists())
        assertFalse(MobilityPush.driverDeliveryAllowed(context))
    }
}
