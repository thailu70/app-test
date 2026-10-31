package com.example

import android.content.Context
import android.location.LocationManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.core.location.AppLocationPrompt
import com.example.core.location.AppLocationPromptSession
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
class AppLocationPromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun location(enabled: Boolean) {
        val manager = compose.activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(manager).setLocationEnabled(enabled)
    }
    @Test fun `location enabled does not show the app prompt`() {
        location(true)
        compose.setContent { MyApplicationTheme { AppLocationPrompt(true) } }
        compose.onNodeWithTag("app_location_prompt").assertDoesNotExist()
    }
    @Test fun `location off prompts once and Later survives recomposition`() {
        location(false)
        val unrelated = mutableStateOf(false)
        compose.setContent { MyApplicationTheme { unrelated.value; AppLocationPrompt(true) } }
        compose.onNodeWithText("Turn on location").assertIsDisplayed()
        compose.onNodeWithTag("app_location_later").performClick()
        compose.runOnIdle { unrelated.value = true }
        compose.onNodeWithTag("app_location_prompt").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
    @Test fun `Enable opens device settings only on explicit tap`() {
        location(false)
        compose.setContent { MyApplicationTheme { AppLocationPrompt(true) } }
        assertNull(shadowOf(compose.activity).nextStartedActivity)
        compose.onNodeWithTag("app_location_enable").performClick()
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertNotNull(intent)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, intent.action)
        compose.onNodeWithTag("app_location_prompt").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
    @Test fun `demo disabled gate suppresses an off-location prompt`() {
        location(false)
        compose.setContent { MyApplicationTheme { AppLocationPrompt(false) } }
        compose.onNodeWithTag("app_location_prompt").assertDoesNotExist()
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
    @Test fun `returning from own settings cannot create a relaunch loop`() {
        val session = AppLocationPromptSession()
        assertTrue(session.start(false))
        session.openSettings()
        session.stop()
        assertFalse(session.start(false))
        assertFalse(session.check(false))
        session.stop()
        assertTrue(session.start(false))
    }
    @Test fun `new real foreground session checks switch again without requesting permissions`() {
        val session = AppLocationPromptSession()
        assertFalse(session.start(true))
        assertTrue(session.check(false))
        assertFalse(session.check(false))
        session.stop()
        assertFalse(session.start(true))
        assertTrue(session.check(false))
    }
}
