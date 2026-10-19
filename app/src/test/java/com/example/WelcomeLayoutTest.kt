package com.example

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import com.example.core.localization.AppLanguage
import com.example.ui.screens.home.PassoWelcomeScreen
import com.example.ui.screens.home.WelcomeService
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun captureWindow(): Bitmap {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        return bitmap
    }

    @Test fun `service choice reaches Continue and language updates real controls`() {
        var destination: WelcomeService? = null
        var language by mutableStateOf(AppLanguage.ENGLISH)
        compose.setContent {
            MyApplicationTheme {
                PassoWelcomeScreen(onContinue = { destination = it }, language = language, onLanguage = { language = it })
            }
        }
        val dir = File("build/welcome-previews").apply { mkdirs() }
        captureWindow().let { bitmap ->
            File(dir, "welcome-top.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        WelcomeService.entries.forEach { service ->
            compose.onNodeWithTag("welcome_service_${service.name}").performScrollTo().performClick()
            compose.onNodeWithTag("welcome_continue").performScrollTo().performClick()
            assertEquals(service, destination)
        }
        captureWindow().let { bitmap ->
            File(dir, "welcome-controls.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("welcome_language_om").performScrollTo().performClick()
        assertEquals(AppLanguage.OROMO, language)
        compose.onNodeWithText("Itti fufi").assertExists()
        compose.onNodeWithTag("welcome_language_am").performScrollTo().performClick()
        assertEquals(AppLanguage.AMHARIC, language)
        compose.onNodeWithText("ቀጥል").assertExists()
    }
}
