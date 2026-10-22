package com.example

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import com.example.core.localization.AppLanguage
import com.example.ui.screens.home.PassoWelcomeScreen
import com.example.ui.screens.home.WelcomeService
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun `privacy remains reachable without scrolling or hiding Continue on a compact phone`() {
        var opened = false
        var language by mutableStateOf(AppLanguage.ENGLISH)
        compose.setContent { MyApplicationTheme {
            PassoWelcomeScreen({}, language, { language = it }, onPrivacy = { opened = true })
        } }
        for (item in AppLanguage.entries) {
            compose.runOnIdle { language = item }
            compose.onNodeWithTag("welcome_continue").assertIsDisplayed()
            compose.onNodeWithTag("welcome_privacy").assertIsDisplayed()
            val viewport = compose.onNodeWithTag("welcome_viewport").fetchSemanticsNode().boundsInWindow
            val policy = compose.onNodeWithTag("welcome_privacy").fetchSemanticsNode().boundsInWindow
            assertTrue(policy.bottom <= viewport.bottom + 1.5f)
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        }
        compose.onNodeWithTag("welcome_privacy").performClick()
        assertTrue(opened)
    }

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

    private fun assertPortraitUsesSafeViewport(language: AppLanguage) {
        compose.waitForIdle()
        val viewport = compose.onNodeWithTag("welcome_viewport").fetchSemanticsNode().boundsInWindow
        val hero = compose.onNodeWithTag("welcome_hero").fetchSemanticsNode().boundsInWindow
        val controls = compose.onNodeWithTag("welcome_controls").fetchSemanticsNode().boundsInWindow
        val button = compose.onNodeWithTag("welcome_continue").fetchSemanticsNode().boundsInWindow
        val density = compose.activity.resources.displayMetrics.density
        val gutter = 16f * density
        val tolerance = 1.5f

        // A uniform page scale can leave every control visible while shrinking the
        // entire page into a narrow column. Check its actual rendered edges too.
        assertEquals("${language.code}: hero must fill the safe width", viewport.left, hero.left, tolerance)
        assertEquals("${language.code}: hero must fill the safe width", viewport.right, hero.right, tolerance)
        assertEquals("${language.code}: controls must use the safe width", viewport.left, controls.left, tolerance)
        assertEquals("${language.code}: controls must use the safe width", viewport.right, controls.right, tolerance)
        assertEquals("${language.code}: Continue keeps only the intended left gutter", viewport.left + gutter, button.left, tolerance)
        assertEquals("${language.code}: Continue keeps only the intended right gutter", viewport.right - gutter, button.right, tolerance)
        assertTrue("${language.code}: Continue must remain above the system navigation area", button.bottom <= viewport.bottom + tolerance)
        assertTrue("${language.code}: no unused space beneath Continue", viewport.bottom - button.bottom <= gutter + tolerance)
        assertTrue("${language.code}: hero stays within the safe height", hero.top >= viewport.top - tolerance && hero.bottom <= controls.top + tolerance)

        val cards = WelcomeService.entries.map { service ->
            compose.onNodeWithTag("welcome_service_${service.name}").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        }
        cards.forEach { card ->
            assertEquals("${language.code}: translated service cards share one top edge", cards.first().top, card.top, tolerance)
            assertEquals("${language.code}: translated service cards share one bottom edge", cards.first().bottom, card.bottom, tolerance)
            assertTrue("${language.code}: service card is fully on screen", card.top >= viewport.top - tolerance && card.bottom <= button.top + tolerance)
        }
        AppLanguage.entries.forEach { item ->
            val option = compose.onNodeWithTag("welcome_language_${item.code}").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("${language.code}: language touch target must not be scaled down", option.height >= 48f * density - tolerance)
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
    }

    private fun capturePreview(name: String) {
        val dir = File("build/welcome-previews").apply { mkdirs() }
        captureWindow().let { bitmap ->
            File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun verifyPortraitFitInEveryLanguage(previewPrefix: String) {
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var destination: WelcomeService? = null
        compose.setContent {
            MyApplicationTheme {
                PassoWelcomeScreen(onContinue = { destination = it }, language = language, onLanguage = { language = it })
            }
        }
        AppLanguage.entries.forEach { item ->
            compose.onNodeWithTag("welcome_language_${item.code}").assertIsDisplayed().performClick()
            assertEquals(item, language)
            assertPortraitUsesSafeViewport(item)
            WelcomeService.entries.forEach { service ->
                compose.onNodeWithTag("welcome_service_${service.name}").performClick()
                compose.onNodeWithTag("welcome_continue").assertIsDisplayed().performClick()
                assertEquals(service, destination)
            }
            capturePreview("$previewPrefix-${item.code}.png")
        }
    }

    @Test
    fun `portrait uses screen width and bottom space in every language`() {
        verifyPortraitFitInEveryLanguage("welcome-393x852")
    }

    @Test
    @Config(qualifiers = "w412dp-h915dp-xhdpi")
    fun `tall phone fills available screen without shrinking controls`() {
        verifyPortraitFitInEveryLanguage("welcome-412x915")
    }

    @Test
    @Config(qualifiers = "w800dp-h360dp-land-xhdpi")
    fun `landscape keeps service and continue reachable`() {
        var destination: WelcomeService? = null
        var language by mutableStateOf(AppLanguage.ENGLISH)
        compose.setContent {
            MyApplicationTheme {
                PassoWelcomeScreen(onContinue = { destination = it }, language = language, onLanguage = { language = it })
            }
        }
        AppLanguage.entries.forEach { item ->
            compose.onNodeWithTag("welcome_language_${item.code}").assertIsDisplayed().performClick()
            WelcomeService.entries.forEach { service ->
                compose.onNodeWithTag("welcome_service_${service.name}").assertIsDisplayed().performClick()
                compose.onNodeWithTag("welcome_continue").assertIsDisplayed().performClick()
                assertEquals(service, destination)
                val button = compose.onNodeWithTag("welcome_continue").fetchSemanticsNode().boundsInWindow
                val viewport = compose.onNodeWithTag("welcome_viewport").fetchSemanticsNode().boundsInWindow
                assertTrue("Landscape Continue remains fully within the safe screen", button.left >= viewport.left - 1f && button.right <= viewport.right + 1f && button.top >= viewport.top - 1f && button.bottom <= viewport.bottom + 1f)
            }
            capturePreview("welcome-landscape-${item.code}.png")
        }
        val dir = File("build/welcome-previews").apply { mkdirs() }
        captureWindow().let { bitmap ->
            File(dir, "welcome-landscape.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `short phone shows every welcome control without scrolling in every language`() {
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var destination: WelcomeService? = null
        compose.setContent {
            MyApplicationTheme {
                PassoWelcomeScreen(onContinue = { destination = it }, language = language, onLanguage = { language = it })
            }
        }
        AppLanguage.entries.forEach { item ->
            compose.onNodeWithTag("welcome_language_${item.code}").assertIsDisplayed().performClick()
            assertPortraitUsesSafeViewport(item)
            WelcomeService.entries.forEach { service ->
                compose.onNodeWithTag("welcome_service_${service.name}").assertIsDisplayed().performClick()
                compose.onNodeWithTag("welcome_continue").assertIsDisplayed().performClick()
                assertEquals(service, destination)
                val button = compose.onNodeWithTag("welcome_continue").fetchSemanticsNode().boundsInWindow
                val viewport = compose.onRoot().fetchSemanticsNode().boundsInWindow
                assertTrue("Continue must fit completely inside the screen", button.top >= viewport.top && button.bottom <= viewport.bottom + 1f)
            }
            capturePreview("welcome-360x640-${item.code}.png")
        }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        val dir = File("build/welcome-previews").apply { mkdirs() }
        captureWindow().let { bitmap ->
            File(dir, "welcome-short-phone.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
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
            compose.onNodeWithTag("welcome_service_${service.name}").assertIsDisplayed().performClick()
            compose.onNodeWithTag("welcome_continue").assertIsDisplayed().performClick()
            assertEquals(service, destination)
        }
        captureWindow().let { bitmap ->
            File(dir, "welcome-controls.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        compose.onNodeWithTag("welcome_language_om").assertIsDisplayed().performClick()
        assertEquals(AppLanguage.OROMO, language)
        compose.onNodeWithText("Itti fufi").assertExists()
        compose.onNodeWithTag("welcome_language_am").assertIsDisplayed().performClick()
        assertEquals(AppLanguage.AMHARIC, language)
        compose.onNodeWithText("ቀጥል").assertExists()
        assertPortraitUsesSafeViewport(AppLanguage.AMHARIC)
    }

    @Test fun `selection motion keeps Continue stationary and uses the latest role during animation`() {
        var destination: WelcomeService? = null
        var language by mutableStateOf(AppLanguage.ENGLISH)
        compose.setContent {
            MyApplicationTheme {
                PassoWelcomeScreen(onContinue = { destination = it }, language = language, onLanguage = { language = it })
            }
        }
        compose.waitForIdle()
        val button = compose.onNodeWithTag("welcome_continue").fetchSemanticsNode().boundsInWindow
        val cards = WelcomeService.entries.associateWith { service ->
            compose.onNodeWithTag("welcome_service_${service.name}").fetchSemanticsNode().boundsInWindow
        }
        compose.mainClock.autoAdvance = false
        try {
            WelcomeService.entries.forEach { service ->
                compose.onNodeWithTag("welcome_service_${service.name}").performClick()
                compose.mainClock.advanceTimeBy(80)
                assertEquals("Role motion must not move its touch target", cards[service], compose.onNodeWithTag("welcome_service_${service.name}").fetchSemanticsNode().boundsInWindow)
                assertEquals("Continue must not jump during selection", button, compose.onNodeWithTag("welcome_continue").assertIsDisplayed().fetchSemanticsNode().boundsInWindow)
                compose.onNodeWithTag("welcome_continue").performClick()
                assertEquals(service, destination)
            }
            compose.onNodeWithTag("welcome_language_om").performClick()
            compose.mainClock.advanceTimeBy(80)
            compose.onNodeWithText("Itti fufi").assertExists()
            assertEquals("Language motion must keep Continue on screen", button, compose.onNodeWithTag("welcome_continue").assertIsDisplayed().fetchSemanticsNode().boundsInWindow)
            assertPortraitUsesSafeViewport(AppLanguage.OROMO)
            compose.mainClock.advanceTimeBy(300)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }
}
