package com.example

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.api.ApiClient
import com.example.ui.screens.taxi.TaxiLocationService
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TaxiLocationServiceStartupTest {
    @Test fun foregroundServiceStopsCleanlyWithoutSavedDriverSession() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracking = context.getSharedPreferences(
            ApiClient.environment().storageName("taxi_tracking"), Context.MODE_PRIVATE
        )
        assertFalse("Test requires no persisted driver tracking session", tracking.getBoolean("active", false))

        ContextCompat.startForegroundService(context, Intent(context, TaxiLocationService::class.java))

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val deadline = SystemClock.elapsedRealtime() + 4_000L
        var serviceRunning: Boolean
        do {
            serviceRunning = activityManager.getRunningServices(100).any {
                it.service.className == TaxiLocationService::class.java.name
            }
            if (!serviceRunning) break
            Thread.sleep(100L)
        } while (SystemClock.elapsedRealtime() < deadline)

        assertFalse("Service should promote immediately, validate, then stop without a session", serviceRunning)
    }
}
