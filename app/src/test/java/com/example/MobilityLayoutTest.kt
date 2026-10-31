package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import com.example.ui.screens.intercity.*
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MobilityLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val trip = MobilityTrip("native-group", "Addis Ababa", "Hawassa", "2026-11-03T05:00:00Z", 650.0, 12, 10,
        vehicleType = "Minibus", vehiclePlate = "FIXTURE-1", status = "APPROVED")
    private val store = object : IntercityBookingAccessStore {
        override fun load() = emptyList<IntercityBookingAccess>()
        override fun save(access: IntercityBookingAccess) {}
        override fun forget(id: String) {}
    }
    private val api: MobilityApi get() = Proxy.newProxyInstance(MobilityApi::class.java.classLoader, arrayOf(MobilityApi::class.java)) { _, method, _ ->
        when (method.name) {
            "trips" -> Response.success(MobilityTripsResponse(true, listOf(trip)))
            "catalog" -> Response.success(MobilityCatalogResponse(true, listOf(MobilityVehicleType("MINIBUS_14", "Minibus", 14))))
            "seats" -> Response.success(MobilitySeatsResponse(true, trip, listOf(1, 4)))
            else -> error("Unexpected network call ${method.name}")
        }
    } as MobilityApi
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/mobility-previews").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun `group travel opens upcoming trips and native multiple-seat controls`() {
        val model = MobilityViewModel(api, store, Dispatchers.Unconfined)
        compose.setContent { MyApplicationTheme { MobilityPassengerScreen("GROUP", AppLanguage.ENGLISH, {}, providedModel = model) } }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.trips.isNotEmpty() }
        compose.onNodeWithTag("mobility_search").assertExists()
        capture("01-group-discovery")
        compose.onNodeWithTag("group_trip_native-group").performScrollTo().performClick()
        compose.waitUntil(5_000) { !model.state.value.busy && model.state.value.phase == MobilityPhase.SEATS }
        compose.onNodeWithTag("group_seat_1").assertIsNotEnabled()
        compose.onNodeWithTag("group_seat_2").performClick()
        compose.onNodeWithTag("group_seat_3").performClick()
        compose.runOnIdle { assertEquals(listOf(2, 3), model.state.value.seats) }
        compose.onNodeWithTag("group_buyer_name").assertExists()
        compose.onNodeWithTag("group_traveler_name_2").assertExists()
        compose.onNodeWithTag("group_traveler_name_3").assertExists()
        compose.onNodeWithTag("group_reserve").performScrollTo().assertIsNotEnabled()
        capture("02-group-travelers")
    }
    @Test fun `cargo opens a real request form with photo upload and vehicle choice`() {
        val model = MobilityViewModel(api, store, Dispatchers.Unconfined)
        compose.setContent { MyApplicationTheme { MobilityPassengerScreen("CARGO", AppLanguage.ENGLISH, {}, providedModel = model) } }
        compose.waitUntil(10_000) { !model.state.value.busy }
        compose.onNodeWithTag("mobility_new_request").performScrollTo().performClick()
        compose.waitUntil { model.state.value.phase == MobilityPhase.REQUEST }
        compose.onNodeWithTag("cargo_section_1").assertExists()
        capture("03-cargo-request")
        compose.onNodeWithTag("cargo_section_2").performScrollTo().performClick()
        compose.onNodeWithTag("cargo_details").performScrollTo().assertExists()
        compose.onNodeWithTag("cargo_photo").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("mobility_send_request").assertDoesNotExist()
        capture("04-cargo-photo")
    }
}
