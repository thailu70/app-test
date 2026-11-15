package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.data.api.*
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.taxi.*
import com.example.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

/** Synthetic fixtures exercise the real native screens. No production accounts or map APIs are used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiStatesLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var previewSuffix = ""
    private val vehicle = TaxiVehicle("vehicle-fixture", "Fixture sedan", "QA-TEST", 4, "ECONOMY")
    private val ride = TaxiRide("ride-fixture", "passenger-fixture", "driver-fixture", vehicle.id,
        origin = "Bole Medhanialem", destination = "Meskel Square", originLatitude = 9.0, originLongitude = 38.78,
        destinationLatitude = 9.015, destinationLongitude = 38.76, estimatedFareEtb = 275.0, commissionEtb = 20.0,
        driverName = "Fixture Driver", vehicleModel = vehicle.model, vehiclePlate = vehicle.plateNumber,
        passengerName = "Fixture Passenger", boardingPin = "1234", distanceKm = 5.2, durationMinutes = 18.0)
    private val trip = SharedRideTrip("shared-fixture", driverId = "driver-fixture", vehicleId = vehicle.id,
        origin = ride.origin, destination = ride.destination, originLatitude = ride.originLatitude,
        originLongitude = ride.originLongitude, destinationLatitude = ride.destinationLatitude,
        destinationLongitude = ride.destinationLongitude, departureAt = "2026-11-15T15:00:00.000Z",
        seats = 4, availableSeats = 2, farePerSeatEtb = 80.0, driverName = "Fixture Driver", vehicleModel = "Fixture minivan")
    private val cars = listOf(TaxiNearbyCar(9.004, 38.772), TaxiNearbyCar(9.006, 38.782))
    private fun api(driverRide: TaxiRide? = null, calls: List<TaxiRide> = emptyList()): TaxiApi = Proxy.newProxyInstance(TaxiApi::class.java.classLoader, arrayOf(TaxiApi::class.java)) { _, method, _ -> when (method.name) {
        "catalog" -> Response.success(TaxiResponse(true, settings = TaxiSettings(enabled = true, sharedEnabled = true)))
        "shared", "me" -> Response.success(TaxiResponse(true))
        "dashboard" -> Response.success(TaxiResponse(true, eligible = true, online = true, vehicles = listOf(vehicle), calls = calls, ride = driverRide, wallet = MobilityWallet(750.0, 20.0, 730.0)))
        else -> error("Unexpected test API ${method.name}")
    } } as TaxiApi
    private fun roads(): MobilityApi = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { proxy, method, args ->
        if (method.name == "equals") return@newProxyInstance proxy === args?.firstOrNull()
        if (method.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
        if (method.name == "toString") return@newProxyInstance "Synthetic road fixture"
        if (method.name != "road") error("Unexpected map API ${method.name}")
        val from = args!![0] as Double to args[1] as Double; val to = args[2] as Double to args[3] as Double
        Response.success(MobilityRoadResponse(true, 5.2, 18.0, geometry = MobilityRoadGeometry("LineString",
            listOf(listOf(from.second, from.first), listOf(from.second - .005, from.first + .003), listOf(to.second + .003, to.first - .004), listOf(to.second, to.first)))))
    } as MobilityApi
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle { val view = compose.activity.window.decorView; val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            val directory = File("build/taxi-previews").apply { mkdirs() }
            val width = (view.width / view.resources.displayMetrics.density).toInt()
            File(directory, "$name-$width$previewSuffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    private fun passenger(status: String) {
        compose.setContent { MyApplicationTheme { val vm = remember { TaxiViewModel(api(), false, false) }; val road = remember { roads() }
            Column(Modifier.fillMaxSize().background(CommuteCream)) { TaxiHeader(onDailyCommute = {})
                Box(Modifier.weight(1f)) { PassengerCurrentRide(ride.copy(status = status, driverId = if (status == "REQUESTED") null else "driver-fixture"), false, vm, mapTiles = false, mapApi = road, nearbyCars = cars) }
            }
        } }
        compose.onNodeWithText(if (status == "REQUESTED") "Connecting to nearby drivers…" else "Your driver is on the way").assertIsDisplayed()
        capture("passenger-${status.lowercase()}")
        if (status == "REQUESTED") compose.onNodeWithText("Nearby cars · approximate").assertExists()
        else { compose.onNodeWithText("1234").performScrollTo().assertIsDisplayed(); capture("passenger-accepted-boarding") }
    }
    private fun driver(meter: Boolean, fontScale: Float = 1f) {
        if (fontScale > 1f) previewSuffix = "-large-text"
        compose.setContent { val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) { MyApplicationTheme { val vm = remember { TaxiViewModel(api(if (meter) ride.copy(status = "IN_PROGRESS", boardingPin = null,
            fareEtb = 187.5, meterDistanceKm = 3.2, tripDurationSeconds = 522) else null, if (meter) emptyList() else listOf(ride)), true, true) }
            TaxiDriverContent("driver-fixture", vm, {}, trackingEnabled = false, mapTiles = false, mapApi = remember { roads() })
        } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(if (meter) "Trip in progress" else "Nearby taxi calls").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(if (meter) "Trip in progress" else "Nearby taxi calls").assertIsDisplayed()
        if (meter) {
            compose.onNodeWithTag("taxi_meter_fare").assertIsDisplayed()
            compose.onNodeWithTag("taxi_meter_distance").assertIsDisplayed()
            compose.onNodeWithTag("taxi_meter_waiting").assertIsDisplayed()
            compose.onNodeWithTag("taxi_passenger_stop").assertIsDisplayed()
            compose.onNodeWithTag("taxi_end_trip").assertIsDisplayed().assertIsEnabled()
        }
        capture(if (meter) "driver-meter" else "driver-calls")
        if (meter) {
            compose.onNodeWithTag("taxi_end_trip").performClick()
            compose.onNodeWithText("Complete this ride?").assertIsDisplayed()
            compose.onNodeWithText("Keep driving").performClick()
        }
        else compose.onNodeWithTag("taxi_accept_ride-fixture").performScrollTo().assertIsEnabled()
        capture(if (meter) "driver-meter-action" else "driver-calls-action")
    }
    private fun shared() {
        compose.setContent { MyApplicationTheme { val vm = remember { TaxiViewModel(api(), false, false) }
            Column(Modifier.fillMaxSize().background(CommuteCream)) { TaxiHeader("Group Ride")
                SharedTripDetail(vm, TaxiUiState(selectedTrip = trip, occupiedSeats = listOf(1, 3)), true, {}, mapTiles = false, mapApi = remember { roads() }) }
        } }
        capture("shared-trip-overview")
        compose.onNodeWithTag("shared_seat_1").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("shared_seat_2").performScrollTo().performClick()
        compose.onNodeWithTag("shared_book_seats").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Total: ETB 80 for 1 seat(s)").assertIsDisplayed()
        capture("shared-seat-booking")
    }
    @Test fun requesting393() = passenger("REQUESTED")
    @Test fun accepted393() = passenger("ACCEPTED")
    @Test fun driverCalls393() = driver(false)
    @Test fun driverMeter393() = driver(true)
    @Test fun sharedSeats393() = shared()
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun requesting320() = passenger("REQUESTED")
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun accepted320() = passenger("ACCEPTED")
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun driverCalls320() = driver(false)
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun driverMeter320() = driver(true)
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun driverMeter320LargeText() = driver(true, 1.2f)
    @Test @Config(qualifiers = "w320dp-h740dp-xhdpi") fun sharedSeats320() = shared()
}
