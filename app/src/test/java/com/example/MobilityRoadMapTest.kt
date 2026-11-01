package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.data.api.*
import com.example.ui.components.*
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import retrofit2.Response
import java.lang.reflect.Proxy
import java.time.Instant
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobilityRoadMapTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val pickup = 9.03 to 38.74
    private val destination = 9.12 to 38.85
    private val curve = listOf(pickup, 9.05 to 38.745, 9.052 to 38.79, destination)
    private fun geometry(points: List<Pair<Double, Double>>) = MobilityRoadGeometry("LineString", points.map { listOf(it.second, it.first) })

    @Test fun `GeoJSON converts curved longitude latitude geometry without losing road vertices`() {
        assertEquals(curve, roadPoints(geometry(curve)))
        assertTrue(roadPoints(MobilityRoadGeometry("Polygon", geometry(curve).coordinates)).isEmpty())
        assertTrue(roadPoints(MobilityRoadGeometry("LineString", listOf(listOf(38.0, 100.0), listOf(39.0, 9.0)))).isEmpty())
        assertTrue(roadPoints(geometry(listOf(pickup, pickup))).isEmpty())
        assertTrue(roadPoints(null).isEmpty())
    }
    @Test fun `routing absence leaves labeled pins without a fabricated straight line`() {
        val map = MapView(compose.activity)
        replaceRoadOverlays(map, emptyList(), emptyList(), pickup, destination, null, false, false, "Bole loading", "Sendafa delivery")
        assertEquals(0, map.overlays.filterIsInstance<Polyline>().size)
        assertEquals(listOf("Bole loading", "Sendafa delivery"), map.overlays.filterIsInstance<Marker>().map { it.title })
        map.onDetach()
    }
    @Test fun `two legs retain real curves and distinct dashed pickup style`() {
        val map = MapView(compose.activity)
        val driver = MobilityLocation(9.0, 38.72, Instant.now().toString())
        val approach = listOf(9.0 to 38.72, 9.01 to 38.735, pickup)
        replaceRoadOverlays(map, curve, approach, pickup, destination, driver, true, false, "Loading", "Delivery")
        val lines = map.overlays.filterIsInstance<Polyline>()
        assertEquals(2, lines.size)
        assertEquals(4, lines[0].actualPoints.size)
        assertNotNull(lines[1].outlinePaint.pathEffect)
        assertNotEquals(lines[0].outlinePaint.color, lines[1].outlinePaint.color)
        assertEquals(3, map.overlays.filterIsInstance<Marker>().size)
        replaceRoadOverlays(map, curve, curve, pickup, destination, driver, true, true, "Loading", "Delivery")
        assertNull(map.overlays.filterIsInstance<Polyline>()[1].outlinePaint.pathEffect)
        map.onDetach()
    }
    @Test fun `stale invalid and future fixes never qualify for a live route estimate`() {
        val now = 1_800_000_000_000L
        val fix = MobilityLocation(9.0, 38.7, Instant.ofEpochMilli(now).toString())
        assertTrue(roadLocationFresh(fix, now))
        assertFalse(roadLocationFresh(fix.copy(stale = true), now))
        assertFalse(roadLocationFresh(fix.copy(timestamp = Instant.ofEpochMilli(now - 121_000).toString()), now))
        assertFalse(roadLocationFresh(fix.copy(timestamp = Instant.ofEpochMilli(now + 6_000).toString()), now))
        assertFalse(roadLocationFresh(fix.copy(latitude = Double.NaN), now))
    }
    @Test fun `rerouting requires elapsed time and meaningful movement`() {
        assertTrue(shouldRerouteRoad(null, pickup, 0, 1_000))
        assertFalse(shouldRerouteRoad(pickup, destination, 1_000, 30_999))
        assertFalse(shouldRerouteRoad(pickup, pickup, 1_000, 90_000))
        assertTrue(shouldRerouteRoad(pickup, destination, 1_000, 31_000))
    }
    @Test fun `loading switches to destination and terminal states stop live routing`() {
        assertFalse(roadToDestination("ASSIGNED"))
        assertFalse(roadToDestination("AT_PICKUP"))
        assertTrue(roadToDestination("LOADED"))
        assertTrue(roadToDestination("IN_TRANSIT"))
        assertTrue(roadToDestination("IN_PROGRESS"))
        for (stage in listOf("AWAITING_CONFIRMATION", "COMPLETED", "CANCELLED", "DISPUTED")) assertFalse(roadTrackingStage(stage))
    }
    @Test fun `native journey shows road route and pins before GPS is shared`() {
        val api = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { _, method, _ ->
            when (method.name) {
                "road" -> Response.success(MobilityRoadResponse(true, 26.0, 42.0, geometry = geometry(curve)))
                "toString" -> "Road map fixture"
                "hashCode" -> 7
                "equals" -> false
                else -> error("Unexpected ${method.name}")
            }
        } as MobilityApi
        compose.setContent { MyApplicationTheme { Column { MobilityRoadMap(pickup, destination, pickupLabel = "Bole loading", destinationLabel = "Sendafa delivery", providedApi = api, tileDownloadsEnabled = false) } } }
        compose.waitUntil(10_000) { compose.activity.window.decorView.findViewWithTag<MapView>("mobility_road_native_map")?.overlays?.filterIsInstance<Polyline>()?.size == 1 }
        compose.onNodeWithText("Journey route").assertIsDisplayed()
        compose.onNodeWithTag("road_map_estimate").assertDoesNotExist()
        compose.runOnIdle {
            val map = compose.activity.window.decorView.findViewWithTag<MapView>("mobility_road_native_map")
            assertEquals(2, map.overlays.filterIsInstance<Marker>().size)
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("build/mobility-previews").mkdirs()
            File("build/mobility-previews/08-road-journey.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
