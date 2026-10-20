package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.data.api.CompletionProfile
import com.example.data.api.DriverDepartureValidation
import com.example.data.api.DriverRosterPassenger
import com.example.data.api.ProfileDocument
import com.example.data.entity.RouteStopEntity
import com.example.ui.screens.driver.*
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.passenger.CommuterHeader
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import com.example.data.api.DriverGpsFix
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriverLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-10-19T04:00:00Z").toEpochMilli()
    // Endpoint coordinates remain trip data; the map waits for the driver's current GPS position.
    private val stops = listOf(
        RouteStopEntity("pickup", "test-route", "Test pickup", "", 1, Double.NaN, Double.NaN, "07:15", "17:30"),
        RouteStopEntity("destination", "test-route", "Test destination", "", 2, Double.NaN, Double.NaN, "08:05", "18:00")
    )
    private val roster = listOf(
        DriverRosterPassenger(id = "boarded", passengerName = "Boarded Test Passenger", passengerPhone = "0912000001", pickupStop = "Test pickup", status = "BOARDED"),
        DriverRosterPassenger(id = "assigned", passengerName = "Assigned Test Passenger", passengerPhone = "0912000002", seatNumber = 3, status = "ASSIGNED"),
        DriverRosterPassenger(id = "pending", passengerName = "Pending Test Passenger", status = "PENDING"),
        DriverRosterPassenger(id = "inactive", passengerName = "Inactive Test Passenger", status = "INACTIVE")
    )

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val directory = File("build/driver-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `location overlays contain only current driver marker and replace any old route geometry`() {
        val map = MapView(compose.activity).apply { setUseDataConnection(false) }
        try {
            map.overlays.add(Polyline())
            map.overlays.add(Marker(map).apply { title = "Old departure marker" })
            val fix = DriverGpsFix(9.03, 38.74, 12.0, now)
            replaceDriverLocationOverlay(map, fix, AppLanguage.ENGLISH, compose.activity)
            assertEquals(1, map.overlays.size)
            val marker = map.overlays.single() as Marker
            assertEquals(fix.latitude, marker.position.latitude, 0.000001)
            assertEquals(fix.longitude, marker.position.longitude, 0.000001)
            assertEquals("Your location", marker.title)
            assertEquals("Driver current location map", map.contentDescription)
            val newerFix = fix.copy(latitude = 9.04, longitude = 38.75, timestamp = now + 1000)
            replaceDriverLocationOverlay(map, newerFix, AppLanguage.ENGLISH, compose.activity)
            assertEquals(1, map.overlays.size)
            assertEquals(newerFix.latitude, (map.overlays.single() as Marker).position.latitude, 0.000001)
        } finally { map.onDetach() }
    }

    @Test fun `known departure and destination remain text while missing current GPS shows awaiting location`() {
        compose.setContent {
            MyApplicationTheme {
                DriverRouteOverview("Test pickup – Test destination", "TEST-4321", "MINIVAN_8", "07:15", "OUTBOUND", false,
                    stops.map { it.copy(latitude = 9.03, longitude = 38.74) }, null, null, null,
                    "Waiting for a precise GPS fix", null, now, AppLanguage.ENGLISH, {}, null, false, {}, false,
                    routeId = "test-route")
            }
        }
        compose.onNode(hasText("Test pickup") and hasAnyAncestor(hasTestTag("driver_route_overview"))).assertExists()
        compose.onNode(hasText("Test destination") and hasAnyAncestor(hasTestTag("driver_route_overview"))).assertExists()
        compose.onNodeWithTag("driver_location_unavailable").assertIsDisplayed()
        compose.onNodeWithText("Your current GPS location will appear here.").assertExists()
    }

    @Test fun `driver home uses real data and preserves boarding profile documents and completion actions`() {
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var boarding by mutableStateOf(false)
        var filter by mutableStateOf("ALL")
        var confirmations = 0
        var scans = 0
        var readiness = 0
        var completed = 0
        var profile = 0
        var documents = 0
        var section by mutableStateOf("Home")
        var documentsOpen by mutableStateOf(false)
        val sections = mutableListOf<String>()
        var documentNavigation = 0
        val uploadedProfile = CompletionProfile(userId = "test-driver", documents = listOf(ProfileDocument(kind = "LICENSE")))
        compose.setContent {
            MyApplicationTheme {
                Box(Modifier.fillMaxSize().background(CommuteCream)) {
                Column(Modifier.fillMaxSize().padding(bottom = 104.dp).verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CommuterHeader(language, { language = it })
                    DriverGreeting("test-driver", "Test Driver", language, now, { profile++ }, portrait = { Icon(Icons.Default.Person, null, Modifier.size(62.dp)) })
                    DriverBoardingBanner(boarding, false, true, false, null, "07:15", now, language,
                        onConfirm = { confirmations++; boarding = true }, onScan = { scans++ })
                    DriverRouteOverview("Test pickup – Test destination", "TEST-4321", "MINIVAN_8", "07:15", "OUTBOUND", false,
                        stops, null, null, null, "Waiting for a precise GPS fix", null, now, language, {}, { readiness++ }, boarding, { completed++ }, false)
                    DriverRosterSummary(roster, { filter = it }, filter, language = language)
                    roster.filter { filter == "ALL" || it.status == filter }.forEach {
                        DriverRosterRow(it, language = language, photoContent = { Icon(Icons.Default.Person, null, Modifier.size(44.dp)) })
                    }
                    DriverDocumentsOverview(uploadedProfile, false, false, language, { documents++ })
                }
                DriverBottomNavigation(section, documentsOpen, language,
                    onSection = { section = it; documentsOpen = false; sections += it },
                    onDocuments = { documentsOpen = true; documentNavigation++ },
                    modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.onNodeWithText("Test!").assertExists()
        compose.onNodeWithText("TEST-4321 · MINIVAN 8").assertExists()
        compose.onNode(hasText("Test pickup") and hasAnyAncestor(hasTestTag("driver_route_overview"))).assertExists()
        compose.onNode(hasText("Test destination") and hasAnyAncestor(hasTestTag("driver_route_overview"))).assertExists()
        compose.onNodeWithText("Assigned pickup").assertDoesNotExist()
        compose.onNodeWithText("See assigned route").assertDoesNotExist()
        compose.onNodeWithText("Fuel 78%").assertDoesNotExist()
        compose.onNodeWithText("All valid").assertDoesNotExist()
        capture("01-driver-home")
        compose.onNodeWithTag("driver_profile_photo").performClick()
        assertEquals(1, profile)
        compose.onNodeWithTag("driver_readiness_check").performScrollTo().performClick()
        assertEquals(1, readiness)
        compose.onNodeWithTag("driver_boarding_banner").performScrollTo().performClick()
        assertEquals(1, confirmations)
        compose.onNodeWithText("Scan passengers").assertExists()
        compose.onNodeWithTag("driver_boarding_banner").performClick()
        assertEquals(1, scans)
        compose.onNodeWithTag("driver_complete_route").performScrollTo().performClick()
        assertEquals(1, completed)
        compose.onNodeWithTag("driver_roster_row_boarded").performScrollTo().assertIsDisplayed()
        capture("02-driver-roster")
        compose.onNodeWithTag("driver_documents_overview").performScrollTo().performClick()
        assertEquals(1, documents)
        compose.onNodeWithText("Uploaded").assertExists()
        capture("03-driver-documents")
        compose.onNodeWithTag("commuter_language_om").performScrollTo().performClick()
        assertEquals(AppLanguage.OROMO, language)
        compose.onNodeWithText("Har'aaf qophii,").assertExists()
        compose.onNodeWithTag("driver_nav_passengers").performClick().assertIsSelected()
        compose.onNodeWithTag("driver_nav_more").performClick().assertIsSelected()
        compose.onNodeWithTag("driver_nav_home").performClick().assertIsSelected()
        assertEquals(listOf("Passengers", "More", "Home"), sections)
        compose.onNodeWithTag("driver_nav_documents").performClick().assertIsSelected()
        assertEquals(1, documentNavigation)
    }

    @Test fun `boarding banner never enables arrival without GPS or while busy or completed`() {
        var preciseGps by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var completed by mutableStateOf(false)
        var boarding by mutableStateOf(false)
        var confirms = 0
        var scans = 0
        compose.setContent {
            MyApplicationTheme {
                DriverBoardingBanner(boarding, completed, preciseGps, busy,
                    DriverDepartureValidation(allowed = false, message = "Move inside the departure radius"), "07:15", now, AppLanguage.ENGLISH,
                    onConfirm = { confirms++ }, onScan = { scans++ })
            }
        }
        compose.onNodeWithTag("driver_boarding_banner").assertIsNotEnabled()
        compose.onNodeWithText("Waiting for a fresh, precise GPS fix").assertExists()
        compose.runOnIdle { preciseGps = true; busy = true }
        compose.onNodeWithTag("driver_boarding_banner").assertIsNotEnabled()
        compose.runOnIdle { busy = false; completed = true }
        compose.onNodeWithTag("driver_boarding_banner").assertIsNotEnabled()
        compose.runOnIdle { completed = false }
        compose.onNodeWithText("Move inside the departure radius").assertExists()
        compose.onNodeWithTag("driver_boarding_banner").assertIsEnabled().performClick()
        assertEquals(1, confirms)
        // An already-open trip can continue scanning if GPS reception temporarily drops.
        compose.runOnIdle { boarding = true; preciseGps = false }
        compose.onNodeWithTag("driver_boarding_banner").assertIsEnabled().performClick()
        assertEquals(1, scans)
        assertEquals(1, confirms)
    }

    @Test fun `roster status filters emit exact API keys and show the matching real passenger`() {
        var filter by mutableStateOf("ALL")
        compose.setContent {
            MyApplicationTheme {
                Column(Modifier.fillMaxSize().background(CommuteCream).padding(14.dp)) {
                    DriverRosterSummary(roster, { filter = it }, filter)
                    roster.filter { filter == "ALL" || it.status == filter }.forEach { DriverRosterRow(it) }
                }
            }
        }
        listOf("BOARDED", "ASSIGNED", "PENDING", "INACTIVE").forEach { status ->
            compose.onNodeWithTag("driver_roster_filter_${status.lowercase()}").performScrollTo().performClick()
            assertEquals(status, filter)
            compose.onNodeWithTag("driver_roster_row_${status.lowercase()}").assertExists()
            roster.filter { it.status != status }.forEach { compose.onNodeWithTag("driver_roster_row_${it.id}").assertDoesNotExist() }
        }
        compose.onNodeWithTag("driver_roster_filter_all").performScrollTo().performClick()
        roster.forEach { compose.onNodeWithTag("driver_roster_row_${it.id}").assertExists() }
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun `small screen and larger text keep native actions reachable`() {
        var direction by mutableStateOf("OUTBOUND")
        var completed = 0
        var documents = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                MyApplicationTheme {
                    Column(Modifier.fillMaxSize().background(CommuteCream).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CommuterHeader(AppLanguage.ENGLISH, {})
                        DriverGreeting("", "Test Driver", AppLanguage.ENGLISH, now, {}, portrait = { Icon(Icons.Default.Person, null, Modifier.size(62.dp)) })
                        DriverBoardingBanner(true, false, false, false, null, "07:15", now, AppLanguage.ENGLISH, {}, {})
                        DriverRouteOverview("Test pickup – Test destination", "TEST-4321", "MINIVAN_8", "07:15", direction, true,
                            stops, null, null, null, "Waiting for a precise GPS fix", null, now, AppLanguage.ENGLISH,
                            { direction = it }, null, true, { completed++ }, false)
                        DriverRosterSummary(roster, {}, "ALL")
                        roster.forEach { DriverRosterRow(it) }
                        DriverDocumentsOverview(null, false, true, AppLanguage.ENGLISH, { documents++ })
                    }
                }
            }
        }
        capture("04-driver-small-large-text")
        compose.onNodeWithText("Return").performScrollTo().performClick()
        assertEquals("INBOUND", direction)
        compose.onNodeWithTag("driver_complete_route").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, completed)
        compose.onNodeWithTag("driver_roster_row_inactive").performScrollTo().assertIsDisplayed()
        capture("05-driver-small-roster")
        compose.onNodeWithTag("driver_documents_overview").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, documents)
    }
}
