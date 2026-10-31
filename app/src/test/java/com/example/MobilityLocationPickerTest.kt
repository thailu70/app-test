package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.data.api.MobilityApi
import com.example.data.api.MobilityPlace
import com.example.data.api.MobilityPlacesResponse
import com.example.ui.screens.intercity.MobilityLocationPicker
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.osmdroid.views.MapView
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobilityLocationPickerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val bole = MobilityPlace("Bole Medhanialem, Addis Ababa", 8.9952, 38.7860)
    private val api: MobilityApi = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Location search API fixture"
            "locations" -> Response.success(MobilityPlacesResponse(true, listOf(bole)))
            else -> error("Unexpected API call ${method.name}")
        }
    } as MobilityApi

    private fun verifyMapIsBounded() {
        compose.waitForIdle()
        val search = compose.onNodeWithTag("mobility_location_search").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val viewport = compose.onNodeWithTag("mobility_location_picker").fetchSemanticsNode().boundsInWindow
        val mapBounds = compose.onNodeWithTag("mobility_location_map").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        val confirm = compose.onNodeWithTag("mobility_location_confirm").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
        assertTrue("Search must stay inside the picker", search.top >= viewport.top && search.bottom <= viewport.bottom)
        assertTrue("Map must have a finite visible area", mapBounds.height > 80f && mapBounds.width > 80f)
        assertTrue("Confirm must remain inside the screen", confirm.top >= viewport.top && confirm.bottom <= viewport.bottom + 1.5f)
        compose.runOnIdle {
            val decor = ShadowDialog.getLatestDialog().window!!.decorView
            val frame = decor.findViewWithTag<FrameLayout>("mobility_native_map_frame")
            val nativeMap = decor.findViewWithTag<MapView>("mobility_native_map")
            assertNotNull(frame); assertNotNull(nativeMap)
            assertTrue("Native map may not measure beyond its Android frame", nativeMap.width <= frame.width && nativeMap.height <= frame.height)
            assertEquals("Android frame must follow Compose slot height", mapBounds.height, frame.height.toFloat(), 2f)
            assertEquals("Android frame must follow Compose slot width", mapBounds.width, frame.width.toFloat(), 2f)
        }
    }

    private fun capture(name: String) {
        compose.runOnIdle {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/mobility-previews").apply { mkdirs() }
            File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `place selection preserves actual name and coordinates with the map bounded`() {
        var chosen: Pair<Double, Double>? = null
        var name = ""
        compose.setContent { MyApplicationTheme {
            MobilityLocationPicker("Choose pickup location", null, {}, { chosen = it }, onPlaceName = { name = it }, providedApi = api, tileDownloadsEnabled = false)
        } }
        compose.onNodeWithTag("mobility_location_confirm").assertIsNotEnabled()
        verifyMapIsBounded()
        compose.onNodeWithTag("mobility_location_search").performTextInput("Bole")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mobility_location_suggestion_0").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mobility_location_suggestion_0").performClick()
        compose.onNodeWithTag("mobility_location_selected_name").assertTextEquals(bole.name)
        verifyMapIsBounded()
        capture("05-location-picker-portrait.png")
        compose.onNodeWithTag("mobility_location_confirm").assertIsEnabled().performClick()
        assertEquals(bole.name, name)
        assertEquals(bole.latitude to bole.longitude, chosen)
    }

    @Test
    @Config(qualifiers = "w852dp-h393dp-xhdpi")
    fun `landscape keeps search map and confirmation usable without native overflow`() {
        compose.setContent { MyApplicationTheme {
            MobilityLocationPicker("Choose destination location", bole.latitude to bole.longitude, {}, {}, initialLabel = bole.name, providedApi = api, tileDownloadsEnabled = false)
        } }
        verifyMapIsBounded()
        compose.onNodeWithText("Choose destination location").assertIsDisplayed()
        compose.onNodeWithTag("mobility_location_confirm").assertIsEnabled()
        capture("06-location-picker-landscape.png")
    }
}
