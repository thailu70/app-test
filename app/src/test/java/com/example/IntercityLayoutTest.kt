package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import com.example.ui.screens.intercity.*
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.theme.MyApplicationTheme
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntercityLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val folder = File("build/intercity-previews").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    private fun mount(api: FakeApi): IntercityPassengerViewModel {
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = Dispatchers.Unconfined)
        compose.setContent {
            MyApplicationTheme { IntercityPassengerScreen(AppLanguage.ENGLISH, {}, {}, providedViewModel = model) }
        }
        compose.waitUntil(10_000) { model.state.value.searchCompleted && !model.state.value.busy }
        return model
    }

    @Test fun `native journey books an available seat and renders only the server confirmed boarding credentials`() {
        val api = FakeApi()
        val model = mount(api)
        compose.onNodeWithTag("intercity_hero").assertExists()
        compose.onNodeWithTag("intercity_from").performClick().performTextInput("Add")
        compose.onNodeWithTag("intercity_from").assertIsFocused().performTextInput("is")
        compose.onNodeWithTag("intercity_from").assertTextEquals("Addis").performTextReplacement("Addis Ababa")
        compose.onNodeWithTag("intercity_to").performClick().performTextReplacement("Hawassa")
        compose.onNodeWithTag("intercity_search").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !model.state.value.busy }
        capture("01-home")

        compose.onNodeWithTag("intercity_content").performScrollToNode(hasTestTag("intercity_select_departure-native"))
        capture("01b-route-cards")
        compose.onNodeWithTag("intercity_select_departure-native").performClick()
        compose.waitUntil(5_000) { model.state.value.phase == IntercityPhase.SEATS && !model.state.value.busy }
        compose.onNodeWithTag("intercity_seat_1").assertIsNotEnabled()
        compose.onNodeWithTag("intercity_hold").assertIsNotEnabled()
        compose.onNodeWithTag("intercity_seat_2").performClick().assertIsSelected()
        compose.onNodeWithTag("intercity_hold").assertIsEnabled()
        capture("02-seats")
        compose.onNodeWithTag("intercity_hold").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.phase == IntercityPhase.DETAILS && !model.state.value.busy }
        assertEquals(2, api.lastHold?.seatNumber)
        compose.onNodeWithTag("intercity_ticket_qr").assertDoesNotExist()
        compose.onNodeWithTag("intercity_ticket_number").assertDoesNotExist()

        compose.onNodeWithTag("intercity_passenger_name").performScrollTo().performTextInput("Native Test Passenger")
        compose.onNodeWithTag("intercity_passenger_phone").performScrollTo().performTextInput("0912469923")
        compose.onNodeWithTag("intercity_pay").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.testVerificationPending && !model.state.value.busy }
        assertNull(api.lastCheckout)
        compose.onNodeWithTag("intercity_verify_test").assertIsNotEnabled()
        compose.onNodeWithTag("intercity_test_otp").performTextInput("123")
        compose.onNodeWithTag("intercity_resend_test").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { !model.state.value.busy }
        assertTrue(model.state.value.message.orEmpty().contains("Please wait"))
        compose.onNodeWithTag("intercity_test_otp")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("123")))
        compose.onNodeWithTag("intercity_verify_test").assertIsNotEnabled()
        assertNull(api.lastCheckout)
        compose.onNodeWithTag("intercity_test_otp").performTextInput("456")
        compose.onNodeWithTag("intercity_verify_test").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { model.state.value.phase == IntercityPhase.TICKET && !model.state.value.busy }
        assertEquals("Native Test Passenger", api.lastCheckout?.passengerName)
        assertEquals("0912469923", api.lastCheckout?.passengerPhone)
        assertEquals("native-one-use-proof", api.lastCheckout?.testPaymentVerificationToken)
        assertEquals("booking-native" to "private-native-capability", api.lastBookingAccess)
        compose.onNodeWithTag("intercity_ticket_number").assertTextEquals("RP-NATIVE24")
        compose.onNodeWithTag("intercity_content").performScrollToNode(hasTestTag("intercity_ticket_qr"))
        compose.onNodeWithTag("intercity_ticket_qr").assertIsDisplayed()
        val qrBounds = compose.onNodeWithTag("intercity_ticket_qr", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        val screen = capture("03-ticket")
        val qr = Bitmap.createBitmap(screen, qrBounds.left.toInt(), qrBounds.top.toInt(), qrBounds.width.toInt(), qrBounds.height.toInt())
        val pixels = IntArray(qr.width * qr.height)
        qr.getPixels(pixels, 0, qr.width, 0, 0, qr.width, qr.height)
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, pixels)))).text
        assertEquals("server-native-boarding-payload", decoded)
        compose.onNodeWithTag("intercity_save_ticket").assertExists()
        compose.onNodeWithTag("intercity_flow_back").performClick()
        compose.waitUntil(5_000) { model.state.value.phase == IntercityPhase.BROWSE }
        compose.onNodeWithTag("intercity_hero").assertExists()
        compose.onNodeWithTag("intercity_content").performScrollToNode(hasTestTag("intercity_community"))
        capture("01c-recent-ticket-community")
        compose.onNodeWithTag("intercity_my_tickets").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.phase == IntercityPhase.BOOKINGS && !model.state.value.busy }
        compose.onNodeWithTag("intercity_view_ticket").performScrollTo().performClick()
        compose.waitUntil(5_000) { model.state.value.phase == IntercityPhase.TICKET && !model.state.value.busy }
        compose.onNodeWithTag("intercity_ticket_number").assertTextEquals("RP-NATIVE24")
    }

    @Test fun `opening intercity immediately displays upcoming route cards without Search`() {
        val model = mount(FakeApi())
        assertTrue(model.state.value.showingUpcoming)
        compose.onNodeWithTag("intercity_content").performScrollToNode(hasTestTag("intercity_select_departure-native"))
        compose.onNodeWithTag("intercity_select_departure-native").assertIsDisplayed()
        compose.onNodeWithText("Departures from today onward").assertExists()
        capture("05-upcoming-on-open")
    }

    @Test fun `empty native discovery shows no invented routes prices or tickets`() {
        val model = mount(FakeApi(empty = true))
        compose.onNodeWithTag("intercity_content").performScrollToNode(hasText("No available routes"))
        compose.onNodeWithText("No available routes").assertIsDisplayed()
        compose.onNodeWithText("ETB 650").assertDoesNotExist()
        compose.onNodeWithText("ETB 1,200").assertDoesNotExist()
        compose.onNodeWithTag("intercity_ticket_qr").assertDoesNotExist()
        capture("04-no-routes")
        compose.onNodeWithTag("intercity_content").performScrollToNode(hasTestTag("intercity_service_tab_1"))
        compose.onNodeWithTag("intercity_service_tab_1").performClick()
        compose.onNodeWithText("Not available yet").assertExists()
        compose.onNodeWithTag("intercity_search").assertDoesNotExist()
        compose.onNodeWithTag("intercity_service_tab_0").performClick()
        compose.onNodeWithTag("intercity_search").assertExists()
        assertEquals(0, model.state.value.serviceTab)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h800dp-xhdpi")
    fun `narrow screen with larger text keeps native city date swap and language controls usable`() {
        var origin by mutableStateOf("Addis Ababa")
        var destination by mutableStateOf("Hawassa")
        var language by mutableStateOf(AppLanguage.ENGLISH)
        var dateClicks = 0
        var searchClicks = 0
        var tab by mutableIntStateOf(0)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                MyApplicationTheme {
                    Column(Modifier.fillMaxSize().background(CommuteCream).verticalScroll(rememberScrollState())) {
                        IntercityHero(language, { language = it }, {})
                        IntercitySearchPanel(origin, destination, "Tue, 20 Oct 2026", emptyList(), tab, language,
                            { origin = it }, { destination = it }, { val previous = origin; origin = destination; destination = previous },
                            { dateClicks++ }, { searchClicks++ }, { tab = it }, modifier = Modifier.padding(12.dp))
                        IntercityCommunityBanner(language, Modifier.padding(12.dp))
                        Box(Modifier.padding(12.dp)) {
                            IntercityTicketCard(pending.copy(status = "CONFIRMED", paymentStatus = "PAID", ticketNumber = "RP-NATIVE24", qrToken = "server-native-boarding-payload"),
                                language, {}, compact = true)
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("intercity_swap").performScrollTo().performClick()
        assertEquals("Hawassa", origin)
        assertEquals("Addis Ababa", destination)
        compose.onNodeWithTag("intercity_from").performTextReplacement("Bahir Dar")
        compose.onNodeWithTag("intercity_to").performTextReplacement("Mekelle")
        compose.onNodeWithTag("intercity_date").performScrollTo().performClick()
        compose.onNodeWithTag("intercity_search").performScrollTo().performClick()
        assertEquals("Bahir Dar", origin)
        assertEquals("Mekelle", destination)
        assertEquals(1, dateClicks)
        assertEquals(1, searchClicks)
        capture("05-narrow-large-text")
        compose.onNodeWithTag("commuter_language_om").performScrollTo().performClick()
        assertEquals(AppLanguage.OROMO, language)
        compose.onNodeWithTag("intercity_search").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("intercity_view_ticket").performScrollTo().assertIsDisplayed()
        capture("06-narrow-recent-ticket")
    }

    private class MemoryStore : IntercityBookingAccessStore {
        private var saved = emptyList<IntercityBookingAccess>()
        override fun load() = saved
        override fun save(access: IntercityBookingAccess) { saved = listOf(access) + saved.filterNot { it.id == access.id } }
        override fun forget(id: String) { saved = saved.filterNot { it.id == id } }
    }

    private class FakeApi(private val empty: Boolean = false) : IntercityPassengerApi {
        var current = pending
        var lastHold: IntercitySeatHoldRequest? = null
        var lastCheckout: IntercityCheckoutRequest? = null
        var lastBookingAccess: Pair<String, String>? = null
        override suspend fun departures(origin: String?, destination: String?, date: String?) = Response.success(IntercityDeparturesResponse(true, if (empty) emptyList() else listOf(departure)))
        override suspend fun seats(id: String) = Response.success(IntercitySeatsResponse(true, 8, listOf(1), 725.0))
        override suspend fun hold(id: String, body: IntercitySeatHoldRequest): Response<IntercityBookingResponse> {
            lastHold = body
            current = pending.copy(seatNumber = body.seatNumber)
            return Response.success(IntercityBookingResponse(true, current, "private-native-capability"))
        }
        override suspend fun booking(id: String, bookingSecret: String): Response<IntercityBookingResponse> {
            lastBookingAccess = id to bookingSecret
            return Response.success(IntercityBookingResponse(true, current))
        }
        override suspend fun checkout(id: String, bookingSecret: String, body: IntercityCheckoutRequest): Response<IntercityCheckoutResponse> {
            lastCheckout = body
            current = current.copy(status = "CONFIRMED", paymentStatus = "PAID", paymentMethod = "TELEBIRR", passengerName = body.passengerName,
                passengerPhone = body.passengerPhone, ticketNumber = "RP-NATIVE24", qrToken = "server-native-boarding-payload")
            return Response.success(IntercityCheckoutResponse(true, paid = true))
        }
        override suspend fun requestTestPaymentOtp(id: String, bookingSecret: String, body: IntercityTestOtpRequest): Response<IntercityTestOtpResponse> {
            assertEquals("0912469923", body.passengerPhone)
            return Response.success(IntercityTestOtpResponse(true, delivery = "SENT", retryAfterSeconds = 60))
        }
        override suspend fun verifyTestPaymentOtp(id: String, bookingSecret: String, body: IntercityTestOtpRequest): Response<IntercityTestOtpResponse> {
            assertEquals("123456", body.code)
            return Response.success(IntercityTestOtpResponse(true, testPaymentVerificationToken = "native-one-use-proof", expiresInSeconds = 120))
        }
        override suspend fun ticketPdf(id: String, secret: String): Response<ResponseBody> = Response.success("%PDF-test".toResponseBody("application/pdf".toMediaType()))
        override suspend fun companies() = Response.success(IntercityCompaniesResponse(true, listOf(IntercityCompany("company-native", "Native Test Transport"))))
        override suspend fun requestRoute(body: IntercityRouteRequest) = Response.success(IntercityRouteRequestResponse(true, "request-native", "Route requested."))
    }

    private companion object {
        val departure = IntercityDeparture("departure-native", "Addis Ababa", "Hawassa", "2026-10-20T04:00:00Z", "Native Test Transport", "company-native",
            seats = 8, availableSeats = 7, fareEtb = 725.0)
        val detail = IntercityDepartureDetail("departure-native", "Addis Ababa", "Hawassa", "2026-10-20T04:00:00Z", "company-native", 725.0, 8, "SCHEDULED")
        val pending = IntercityBooking("booking-native", "departure-native", 2, 725.0, "HOLD", "PENDING", expiresAt = "2026-10-20T03:40:00Z", departure = detail)
    }
}
