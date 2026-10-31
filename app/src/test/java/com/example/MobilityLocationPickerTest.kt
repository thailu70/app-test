package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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
import org.osmdroid.util.GeoPoint
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

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
    @Config(qualifiers = "w852dp-h393dp-land-xhdpi")
    fun `landscape keeps search map and confirmation usable without native overflow`() {
        compose.setContent { MyApplicationTheme {
            MobilityLocationPicker("Choose destination location", bole.latitude to bole.longitude, {}, {}, initialLabel = bole.name, providedApi = api, tileDownloadsEnabled = false)
        } }
        verifyMapIsBounded()
        compose.onNodeWithText("Choose destination location").assertIsDisplayed()
        compose.onNodeWithTag("mobility_location_confirm").assertIsEnabled()
        capture("06-location-picker-landscape.png")
    }

    @Test fun `pickup search retains focus text and suggestions across keyboard height changes`() {
        var keyboardBottom by mutableIntStateOf(0)
        var chosen: Pair<Double, Double>? = null
        var name = ""
        val queries = mutableListOf<String>()
        val searchApi = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Keyboard resize search fixture"
                "locations" -> { queries += args!![0] as String; Response.success(MobilityPlacesResponse(true, listOf(bole))) }
                else -> error("Unexpected API call ${method.name}")
            }
        } as MobilityApi
        compose.setContent { MyApplicationTheme {
            MobilityLocationPicker("Choose pickup location", null, {}, { chosen = it }, onPlaceName = { name = it },
                providedApi = searchApi, tileDownloadsEnabled = false, contentInsets = WindowInsets(bottom = keyboardBottom))
        } }
        val search = compose.onNodeWithTag("mobility_location_search")
        search.performClick().performTextInput("Bo")
        search.assertIsFocused()
        val fieldId = search.fetchSemanticsNode().id
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mobility_location_suggestion_0").fetchSemanticsNodes().isNotEmpty() }

        // These IME insets cross both former branch thresholds: 560dp and width > height.
        // The same focused text field must survive, rather than being recreated in another subtree.
        compose.runOnIdle { keyboardBottom = 600 }
        search.assertIsFocused().assertTextContains("Bo")
        assertEquals(fieldId, search.fetchSemanticsNode().id)
        search.performTextInput("le")
        compose.waitUntil(10_000) { queries.lastOrNull() == "Bole" }
        compose.onNodeWithTag("mobility_location_suggestion_0").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { keyboardBottom = 1000 }
        search.assertIsFocused().assertTextContains("Bole")
        assertEquals(fieldId, search.fetchSemanticsNode().id)
        compose.onNodeWithTag("mobility_location_suggestion_0").assertIsDisplayed()
        capture("07-location-search-keyboard.png")
        compose.onNodeWithTag("mobility_location_suggestion_0").performClick()
        compose.runOnIdle { keyboardBottom = 0 }
        compose.onNodeWithTag("mobility_location_selected_name").assertTextEquals(bole.name)
        compose.onNodeWithTag("mobility_location_suggestions").assertDoesNotExist()
        verifyMapIsBounded()
        compose.onNodeWithTag("mobility_location_confirm").assertIsEnabled().performClick()
        assertEquals(bole.name, name)
        assertEquals(bole.latitude to bole.longitude, chosen)
        assertEquals(listOf("Bo", "Bole"), queries)
    }

    @Test fun `destination search stays focused after an ended map gesture and programmatic map resize`() {
        var chosen: Pair<Double, Double>? = null
        var name = ""
        compose.setContent { MyApplicationTheme {
            MobilityLocationPicker("Choose destination location", null, {}, { chosen = it }, onPlaceName = { name = it },
                providedApi = api, tileDownloadsEnabled = false)
        } }
        lateinit var nativeMap: MapView
        compose.runOnIdle {
            nativeMap = ShadowDialog.getLatestDialog().window!!.decorView.findViewWithTag("mobility_native_map")
            val now = SystemClock.uptimeMillis()
            // A cancelled map gesture must not leave a permanent "user is moving map" flag.
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 60f, 60f, 0).also { nativeMap.dispatchTouchEvent(it); it.recycle() }
            MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_CANCEL, 60f, 60f, 0).also { nativeMap.dispatchTouchEvent(it); it.recycle() }
        }
        val search = compose.onNodeWithTag("mobility_location_search")
        search.performClick().performTextInput("Bole")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mobility_location_suggestion_0").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { nativeMap.controller.setCenter(GeoPoint(9.10, 38.80)); nativeMap.scrollBy(35, 20) }
        search.assertIsFocused().assertTextContains("Bole")
        compose.onNodeWithTag("mobility_location_suggestion_0").assertIsDisplayed().performClick()
        compose.onNodeWithTag("mobility_location_selected_name").assertTextEquals(bole.name)
        compose.onNodeWithTag("mobility_location_confirm").assertIsEnabled().performClick()
        assertEquals(bole.name, name)
        assertEquals(bole.latitude to bole.longitude, chosen)
    }

    @Test fun `late cancelled search cannot enable stale rows while the current query is pending`() {
        val pending = ConcurrentHashMap<String, Continuation<Response<MobilityPlacesResponse>>>()
        val sendafa = MobilityPlace("Sendafa, Oromia", 9.1486, 39.0225)
        val old = MobilityPlace("Late old result", 8.90, 38.60)
        @Suppress("UNCHECKED_CAST")
        val delayedApi = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "Out of order search fixture"
                "locations" -> {
                    val input = args!![0] as String
                    if (input == "Bo") Response.success(MobilityPlacesResponse(true, listOf(bole)))
                    else {
                        // Deliberately ignore cancellation, like a late network callback, until resumed below.
                        pending[input] = args.last() as Continuation<Response<MobilityPlacesResponse>>
                        COROUTINE_SUSPENDED
                    }
                }
                else -> error("Unexpected API call ${method.name}")
            }
        } as MobilityApi
        var chosen: Pair<Double, Double>? = null
        compose.setContent { MyApplicationTheme {
            MobilityLocationPicker("Choose destination location", null, {}, { chosen = it }, providedApi = delayedApi, tileDownloadsEnabled = false)
        } }
        val search = compose.onNodeWithTag("mobility_location_search")
        search.performClick().performTextInput("Bo")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("mobility_location_suggestion_0").fetchSemanticsNodes().isNotEmpty() }
        search.performTextReplacement("Bole")
        compose.waitUntil(10_000) { pending.containsKey("Bole") }
        compose.onNodeWithTag("mobility_location_suggestion_0").assertIsDisplayed().assertIsNotEnabled()
        search.performTextReplacement("Sendafa")
        compose.waitUntil(10_000) { pending.containsKey("Sendafa") }
        compose.runOnIdle { pending.remove("Bole")!!.resume(Response.success(MobilityPlacesResponse(true, listOf(old)))) }
        search.assertIsFocused().assertTextContains("Sendafa")
        compose.onNodeWithText(old.name).assertDoesNotExist()
        compose.onNodeWithTag("mobility_location_suggestion_0").assertIsDisplayed().assertIsNotEnabled()
        compose.runOnIdle { pending.remove("Sendafa")!!.resume(Response.success(MobilityPlacesResponse(true, listOf(sendafa)))) }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(sendafa.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("mobility_location_suggestion_0").assertIsEnabled().performClick()
        compose.onNodeWithTag("mobility_location_selected_name").assertTextEquals(sendafa.name)
        compose.onNodeWithTag("mobility_location_confirm").performClick()
        assertEquals(sendafa.latitude to sendafa.longitude, chosen)
    }
}
