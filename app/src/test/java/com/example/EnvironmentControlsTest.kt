package com.example

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import com.example.ui.components.DemoEnvironmentBanner
import com.example.ui.components.EnvironmentChooser
import com.example.ui.screens.home.HostedPortalScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EnvironmentControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val originalBase = ApiClient.getBaseUrl()
    @After fun restore() { ApiClient.setAuthToken(null); ApiClient.setBaseUrl(originalBase) }

    @Test fun `demo requires an explicit selection and explains isolated synthetic data`() {
        ApiClient.setBaseUrl(ApiClient.DEFAULT_HTTPS_URL)
        val originalScope = ApiClient.sessionScope()
        var selected: AppEnvironment? = null
        compose.setContent { MyApplicationTheme { EnvironmentChooser({}, { selected = it }) } }
        compose.onNodeWithTag("environment_choose_PRODUCTION").assertIsNotEnabled()
        compose.onNodeWithText("Demo uses an isolated service", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("environment_choose_DEMO").performClick()
        assertEquals(AppEnvironment.DEMO, selected)
        assertTrue(ApiClient.isCurrent(originalScope))
    }

    @Test fun `demo indicator stays visible and company portal disables browser storage`() {
        ApiClient.setBaseUrl(AppEnvironment.DEMO_BASE_URL)
        var settings = 0
        compose.setContent { MyApplicationTheme { Column {
            DemoEnvironmentBanner { settings++ }
            HostedPortalScreen("/company/", "Company", {})
        } } }
        compose.onNodeWithTag("demo_environment_banner").assertIsDisplayed()
        compose.onNodeWithText("Demo — synthetic data; no real payments or SMS").assertIsDisplayed()
        compose.runOnIdle {
            fun findWeb(view: android.view.View): android.webkit.WebView? {
                if (view is android.webkit.WebView) return view
                if (view is android.view.ViewGroup) for (index in 0 until view.childCount) {
                    findWeb(view.getChildAt(index))?.let { return it }
                }
                return null
            }
            val web = findWeb(compose.activity.window.decorView)!!
            assertEquals("https://passo.live/review/company/", web.url)
            assertFalse(web.settings.domStorageEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(android.webkit.CookieManager.getInstance().acceptCookie())
        }
        compose.onNodeWithText("Exit demo").performClick()
        assertEquals(1, settings)
        assertEquals(AppEnvironment.DEMO, ApiClient.environment())
    }
}
