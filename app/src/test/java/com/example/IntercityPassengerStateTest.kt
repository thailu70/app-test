package com.example

import com.example.data.api.*
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import com.example.ui.screens.intercity.IntercityPassengerViewModel
import com.example.ui.screens.intercity.IntercityPhase
import com.example.ui.screens.intercity.canShowIntercityQr
import com.example.ui.screens.intercity.intercityToday
import com.example.ui.screens.intercity.trustedIntercityCheckout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.TestScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class IntercityPassengerStateTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setMainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    @Test fun `opening intercity immediately shows all upcoming departures without applying the default date`() = runTest(dispatcher) {
        val todayTrip = departure.copy(id = "today-trip", departureAt = "2026-10-20T12:00:00Z")
        val nextDayTrip = departure.copy(id = "next-day-trip", departureAt = "2026-10-20T22:07:00Z")
        val laterTrip = departure.copy(id = "later-trip", departureAt = "2026-10-21T12:36:00Z")
        val api = FakeApi().apply {
            directoryDepartures = listOf(todayTrip, nextDayTrip, laterTrip)
            filteredDepartures = emptyList()
        }
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
        advanceUntilIdle()

        assertEquals(listOf(Triple<String?, String?, String?>(null, null, null)), api.departureQueries)
        assertEquals(listOf("today-trip", "next-day-trip", "later-trip"), model.state.value.departures.map { it.id })
        assertEquals(api.directoryDepartures, model.state.value.directory)
        assertTrue(model.state.value.searchCompleted)
        assertTrue(model.state.value.showingUpcoming)
        assertFalse(model.state.value.busy)
        assertNull(model.state.value.message)
    }

    @Test fun `refresh upcoming discovery does not silently apply draft cities or date`() = runTest(dispatcher) {
        val api = FakeApi()
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.setOrigin("Addis Ababa")
        model.setDestination("Hawassa")
        model.setDate("2026-10-25")
        val newTrip = departure.copy(id = "new-upcoming-trip", departureAt = "2026-10-22T04:00:00Z")
        api.directoryDepartures = listOf(newTrip)
        model.refresh()
        advanceUntilIdle()

        assertEquals(2, api.departureQueries.size)
        assertTrue(api.departureQueries.all { it == Triple<String?, String?, String?>(null, null, null) })
        assertEquals(listOf(newTrip), model.state.value.departures)
        assertTrue(model.state.value.showingUpcoming)
        assertEquals("2026-10-25", model.state.value.date)
    }

    @Test fun `explicit search keeps exact Ethiopia date and refresh reuses the applied query`() = runTest(dispatcher) {
        val api = FakeApi()
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        val found = departure.copy(id = "searched-trip", departureAt = "2026-10-25T04:00:00Z")
        api.filteredDepartures = listOf(found)
        model.setOrigin("  Addis Ababa  ")
        model.setDestination(" Hawassa ")
        model.setDate("2026-10-25")
        model.search()
        advanceUntilIdle()
        val appliedQuery = Triple<String?, String?, String?>("Addis Ababa", "Hawassa", "2026-10-25")
        assertEquals(appliedQuery, api.departureQueries.last())
        assertEquals(listOf(found), model.state.value.departures)
        assertFalse(model.state.value.showingUpcoming)

        model.setOrigin("Bahir Dar")
        model.setDate("2026-10-26")
        model.refresh()
        advanceUntilIdle()
        assertEquals(listOf(Triple<String?, String?, String?>(null, null, null), appliedQuery), api.departureQueries.takeLast(2))
        assertEquals(listOf(found), model.state.value.departures)
        assertFalse(model.state.value.showingUpcoming)
    }

    @Test fun `failed explicit search preserves upcoming results and refresh mode`() = runTest(dispatcher) {
        val api = FakeApi()
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        api.departureFailure = "Routes temporarily unavailable."
        model.setDate("2026-10-25")
        model.search()
        advanceUntilIdle()
        assertEquals("Routes temporarily unavailable.", model.state.value.message)
        assertEquals(listOf(departure), model.state.value.departures)
        assertTrue(model.state.value.showingUpcoming)

        api.departureFailure = null
        model.refresh()
        advanceUntilIdle()
        assertEquals(Triple<String?, String?, String?>(null, null, null), api.departureQueries.last())
        assertTrue(model.state.value.showingUpcoming)
        assertNull(model.state.value.message)
    }

    @Test fun `default travel date crosses midnight in Ethiopia rather than device timezone`() {
        assertEquals("2026-10-19", intercityToday(Instant.parse("2026-10-19T20:59:59Z").toEpochMilli()))
        assertEquals("2026-10-20", intercityToday(Instant.parse("2026-10-19T21:00:00Z").toEpochMilli()))
    }

    @Test fun `reserved and out of range seats cannot be selected or held`() = runTest(dispatcher) {
        val api = FakeApi()
        val store = MemoryStore()
        val model = IntercityPassengerViewModel(api, store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.selectDeparture(departure)
        advanceUntilIdle()

        for (seat in listOf(1, 0, 31)) {
            model.selectSeat(seat)
            assertNull(model.state.value.seat)
        }
        model.holdSeat()
        advanceUntilIdle()
        assertEquals(0, api.holdCalls)
        assertTrue(model.state.value.message.orEmpty().contains("Choose a seat"))

        model.selectSeat(2)
        model.holdSeat()
        advanceUntilIdle()
        assertEquals(1, api.holdCalls)
        assertEquals(2, api.lastHold?.seatNumber)
        assertEquals(IntercityPhase.DETAILS, model.state.value.phase)
        assertEquals(listOf(IntercityBookingAccess("booking-1", "private-capability")), store.load())
        assertFalse(canShowIntercityQr(model.state.value.booking))
    }

    @Test fun `checkout paid flag cannot reveal ticket before refreshed server booking is paid`() = runTest(dispatcher) {
        val api = FakeApi()
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.selectDeparture(departure)
        advanceUntilIdle()
        model.selectSeat(2)
        model.holdSeat()
        advanceUntilIdle()

        // Even a complete ticket in the checkout response must not replace the authoritative GET.
        api.checkoutResult = IntercityCheckoutResponse(true, paid = true, booking = paidBooking)
        model.checkout("Test Passenger", "0912000000")
        advanceUntilIdle()
        assertEquals(listOf("booking-1" to "private-capability"), api.bookingAccesses)
        assertEquals(IntercityPhase.DETAILS, model.state.value.phase)
        assertEquals("PENDING", model.state.value.booking?.paymentStatus)
        assertFalse(canShowIntercityQr(model.state.value.booking))

        api.currentBooking = paidBooking
        model.checkPayment()
        advanceUntilIdle()
        assertEquals(IntercityPhase.TICKET, model.state.value.phase)
        assertTrue(canShowIntercityQr(model.state.value.booking))
        assertEquals("RP-AB12CD34", model.state.value.booking?.ticketNumber)
    }

    @Test fun `Telebirr redirect leaves reservation unpaid until server reconciliation`() = runTest(dispatcher) {
        val api = FakeApi()
        val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.selectDeparture(departure)
        advanceUntilIdle()
        model.selectSeat(2)
        model.holdSeat()
        advanceUntilIdle()

        val checkout = "https://superapp.ethiomobilemoney.et:38443/payment/web/paygate?order=test"
        api.checkoutResult = IntercityCheckoutResponse(true, checkoutUrl = checkout)
        model.checkout("Test Passenger", "0912000000")
        advanceUntilIdle()
        assertEquals(checkout, model.state.value.checkoutUrl)
        assertEquals(IntercityPhase.DETAILS, model.state.value.phase)
        assertFalse(canShowIntercityQr(model.state.value.booking))
        assertTrue(api.bookingAccesses.isEmpty())
        assertEquals("private-capability", api.lastCheckoutSecret)
        model.checkoutOpened()
        assertNull(model.state.value.checkoutUrl)
    }

    @Test fun `restored private capability recovers recent ticket with exact server header`() = runTest(dispatcher) {
        val api = FakeApi().apply { currentBooking = paidBooking }
        val store = MemoryStore(listOf(IntercityBookingAccess("booking-1", "restored-private-capability")))
        val model = IntercityPassengerViewModel(api, store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        assertEquals(listOf("booking-1"), model.state.value.recent.map { it.id })
        assertEquals(IntercityPhase.BROWSE, model.state.value.phase)
        assertNull(model.state.value.booking)
        assertEquals(listOf("booking-1" to "restored-private-capability"), api.bookingAccesses)

        model.openBooking("booking-1")
        advanceUntilIdle()
        assertEquals(IntercityPhase.TICKET, model.state.value.phase)
        assertTrue(canShowIntercityQr(model.state.value.booking))
        assertEquals("restored-private-capability", api.bookingAccesses.last().second)
    }

    @Test fun `checkout allowlist rejects non HTTPS lookalike credentials and unexpected ports`() {
        listOf(
            "https://superapp.ethiomobilemoney.et:38443/payment/web/paygate?order=test",
            "https://developerportal.ethiotelebirr.et:38443/payment/web/paygate?order=test",
            "https://superapp.ethiomobilemoney.et/payment/web/paygate",
            "https://superapp.ethiomobilemoney.et:443/payment/web/paygate"
        ).forEach { assertTrue(it, trustedIntercityCheckout(it)) }
        listOf(
            "http://superapp.ethiomobilemoney.et/payment/web/paygate",
            "javascript:alert(1)",
            "https://superapp.ethiomobilemoney.et.evil.example/payment/web/paygate",
            "https://superapp.ethiomobilemoney.et@evil.example/payment/web/paygate",
            "https://attacker@superapp.ethiomobilemoney.et/payment/web/paygate",
            "https://superapp.ethiomobilemoney.et:9999/payment/web/paygate",
            "https://evil.example/superapp.ethiomobilemoney.et",
            "not a URL"
        ).forEach { assertFalse(it, trustedIntercityCheckout(it)) }
    }

    @Test fun `private ticket PDF streams natively only after paid server booking`() = runTest(dispatcher) {
        val api = FakeApi().apply { currentBooking = paidBooking }
        val store = MemoryStore(listOf(IntercityBookingAccess("booking-1", "private-capability")))
        val model = IntercityPassengerViewModel(api, store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.openBooking("booking-1")
        advanceUntilIdle()
        val output = ByteArrayOutputStream()
        model.savePdf { output }
        advanceUntilIdle()
        assertEquals("%PDF-test", output.toString("UTF-8"))
        assertEquals("booking-1" to "private-capability", api.pdfAccess)

        api.currentBooking = pendingBooking
        model.openBooking("booking-1")
        advanceUntilIdle()
        var outputOpened = false
        model.savePdf { outputOpened = true; ByteArrayOutputStream() }
        advanceUntilIdle()
        assertFalse(outputOpened)
        assertTrue(model.state.value.message.orEmpty().contains("Payment must be confirmed"))
    }

    @Test fun `back during delayed hold preserves private access without reopening checkout`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { holdGate = gate }
        val store = MemoryStore()
        val model = IntercityPassengerViewModel(api, store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.selectDeparture(departure)
        advanceUntilIdle()
        model.selectSeat(2)
        model.holdSeat()
        runCurrent()
        assertTrue(model.state.value.busy)
        assertEquals(1, api.holdCalls)
        model.home()
        assertEquals(IntercityPhase.BROWSE, model.state.value.phase)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(IntercityPhase.BROWSE, model.state.value.phase)
        assertFalse(model.state.value.busy)
        assertEquals(listOf("booking-1"), model.state.value.recent.map { it.id })
        assertEquals(listOf(IntercityBookingAccess("booking-1", "private-capability")), store.load())
    }

    @Test fun `back during checkout prevents both late redirect and paid ticket navigation`() = runTest(dispatcher) {
        for (paid in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            val api = FakeApi().apply {
                checkoutGate = gate
                currentBooking = paidBooking
                checkoutResult = IntercityCheckoutResponse(true, paid = paid,
                    checkoutUrl = if (paid) null else "https://superapp.ethiomobilemoney.et:38443/payment/web/paygate?order=test")
            }
            val model = IntercityPassengerViewModel(api, MemoryStore(), ioDispatcher = dispatcher)
            advanceUntilIdle()
            model.selectDeparture(departure)
            advanceUntilIdle()
            model.selectSeat(2)
            model.holdSeat()
            advanceUntilIdle()
            model.checkout("Test Passenger", "0912000000")
            runCurrent()
            assertTrue(model.state.value.busy)
            model.home()
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(IntercityPhase.BROWSE, model.state.value.phase)
            assertNull(model.state.value.checkoutUrl)
            if (paid) assertEquals("PAID", model.state.value.recent.first().paymentStatus)
        }
    }

    @Test fun `saved bookings refresh concurrently rather than waiting on each previous request`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val api = FakeApi().apply { bookingGate = gate }
        val store = MemoryStore(listOf(IntercityBookingAccess("booking-1", "private-one"), IntercityBookingAccess("booking-2", "private-two")))
        val model = IntercityPassengerViewModel(api, store, ioDispatcher = dispatcher)
        runCurrent()
        assertEquals(listOf("booking-1" to "private-one", "booking-2" to "private-two"), api.bookingAccesses)
        assertTrue(model.state.value.busy)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(model.state.value.busy)
        assertEquals(listOf("booking-1", "booking-2"), model.state.value.recent.map { it.id })
    }

    private fun TestScope.readyForCheckout(api: FakeApi, store: MemoryStore = MemoryStore()): IntercityPassengerViewModel {
        val model = IntercityPassengerViewModel(api, store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        model.selectDeparture(departure)
        advanceUntilIdle()
        model.selectSeat(2)
        model.holdSeat()
        advanceUntilIdle()
        return model
    }

    @Test fun `only the designated test phone requests SENT OTP before a proof-bound checkout`() = runTest(dispatcher) {
        for (phone in listOf("0912469923", "+251912469923", "00251912469923")) {
            val api = FakeApi().apply { currentBooking = paidBooking }
            val store = MemoryStore()
            val model = readyForCheckout(api, store)
            model.checkout(" Test Passenger ", phone)
            advanceUntilIdle()
            assertEquals(listOf(IntercityTestOtpRequest(phone)), api.otpRequests)
            assertTrue(model.state.value.testVerificationPending)
            assertTrue(api.checkoutRequests.isEmpty())
            model.verifyTestPayment("123456")
            advanceUntilIdle()
            assertEquals(IntercityTestOtpRequest(phone, "123456"), api.otpVerifications.single())
            assertEquals(IntercityCheckoutRequest("Test Passenger", phone, "one-use-fixture-proof"), api.checkoutRequests.single())
            assertFalse(model.state.value.testVerificationPending)
            assertEquals(IntercityPhase.TICKET, model.state.value.phase)
            assertFalse(model.state.value.toString().contains("one-use-fixture-proof"))
            assertEquals(listOf(IntercityBookingAccess("booking-1", "private-capability")), store.load())
        }
        for (phone in listOf("0912469924", "0912000000", "+251912469924")) {
            val api = FakeApi()
            val model = readyForCheckout(api)
            model.checkout("Normal Passenger", phone)
            advanceUntilIdle()
            assertTrue(api.otpRequests.isEmpty())
            assertTrue(api.otpVerifications.isEmpty())
            assertNull(api.checkoutRequests.single().testPaymentVerificationToken)
        }
    }

    @Test fun `resend during cooldown does not request another SMS or discard verification context`() = runTest(dispatcher) {
        val api = FakeApi()
        val model = readyForCheckout(api)
        model.checkout("Test Passenger", "0912469923")
        advanceUntilIdle()
        val resendAt = model.state.value.testOtpResendAt
        assertEquals(1, api.otpRequests.size)
        assertTrue(resendAt > System.currentTimeMillis())

        repeat(3) { model.resendTestPaymentOtp(); advanceUntilIdle() }

        assertEquals(1, api.otpRequests.size)
        assertEquals(resendAt, model.state.value.testOtpResendAt)
        assertTrue(model.state.value.testVerificationPending)
        assertTrue(model.state.value.message.orEmpty().contains("Please wait"))
        assertTrue(api.checkoutRequests.isEmpty())
        model.verifyTestPayment("123456")
        advanceUntilIdle()
        assertEquals(1, api.otpVerifications.size)
        assertEquals("one-use-fixture-proof", api.checkoutRequests.single().testPaymentVerificationToken)
    }

    @Test fun `missing SENT delivery rejected codes and missing proof never trigger payment`() = runTest(dispatcher) {
        for (delivery in listOf(null, "SIMULATED", "FAILED")) {
            val api = FakeApi().apply { otpRequestResult = Response.success(IntercityTestOtpResponse(true, delivery = delivery)) }
            val model = readyForCheckout(api)
            model.checkout("Test Passenger", "0912469923")
            advanceUntilIdle()
            assertFalse(model.state.value.testVerificationPending)
            model.verifyTestPayment("123456")
            advanceUntilIdle()
            assertTrue(api.otpVerifications.isEmpty())
            assertTrue(api.checkoutRequests.isEmpty())
        }
        for (result in listOf(IntercityTestOtpResponse(false, error = "Invalid code."), IntercityTestOtpResponse(true))) {
            val api = FakeApi().apply { otpVerifyResult = Response.success(result) }
            val model = readyForCheckout(api)
            model.checkout("Test Passenger", "0912469923")
            advanceUntilIdle()
            model.verifyTestPayment("123")
            advanceUntilIdle()
            assertTrue(api.otpVerifications.isEmpty())
            model.verifyTestPayment("123456")
            advanceUntilIdle()
            assertEquals(1, api.otpVerifications.size)
            assertTrue(api.checkoutRequests.isEmpty())
            assertTrue(model.state.value.testVerificationPending)
        }
    }

    @Test fun `cancelled or changed booking context rejects late OTP verification before checkout`() = runTest(dispatcher) {
        for (navigateHome in listOf(true, false)) {
            val gate = CompletableDeferred<Unit>()
            val api = FakeApi().apply { otpVerifyGate = gate }
            val model = readyForCheckout(api)
            model.checkout("Test Passenger", "0912469923")
            advanceUntilIdle()
            model.verifyTestPayment("123456")
            runCurrent()
            assertTrue(model.state.value.busy)
            if (navigateHome) model.home() else model.cancelTestPaymentVerification()
            assertFalse(model.state.value.testVerificationPending)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(if (navigateHome) IntercityPhase.BROWSE else IntercityPhase.DETAILS, model.state.value.phase)
            assertFalse(model.state.value.testVerificationPending)
            assertTrue(api.checkoutRequests.isEmpty())
        }

        val cancelled = readyForCheckout(FakeApi())
        cancelled.checkout("Test Passenger", "0912469923")
        advanceUntilIdle()
        cancelled.cancelTestPaymentVerification()
        assertFalse(cancelled.state.value.testVerificationPending)
        cancelled.verifyTestPayment("123456")
        advanceUntilIdle()
        assertTrue(cancelled.state.value.message.orEmpty().contains("Request a new"))
    }

    @Test fun `one-use proof is not replayed or restored when checkout outcome remains unpaid`() = runTest(dispatcher) {
        val api = FakeApi().apply { checkoutFailure = java.io.IOException("Checkout response lost.") }
        val store = MemoryStore()
        val model = readyForCheckout(api, store)
        model.checkout("Test Passenger", "0912469923")
        advanceUntilIdle()
        model.verifyTestPayment("123456")
        advanceUntilIdle()
        assertEquals(1, api.checkoutRequests.size)
        assertEquals(1, api.bookingAccesses.size)
        assertFalse(model.state.value.testVerificationPending)
        model.verifyTestPayment("123456")
        advanceUntilIdle()
        assertEquals(1, api.otpVerifications.size)
        assertEquals(1, api.checkoutRequests.size)
        val restored = IntercityPassengerViewModel(FakeApi(), store, ioDispatcher = dispatcher)
        advanceUntilIdle()
        assertFalse(restored.state.value.testVerificationPending)
        assertNull(restored.state.value.testVerificationPhone)
        assertFalse(store.load().toString().contains("one-use-fixture-proof"))
    }

    @Test fun `uncertain checkout reconciles a paid booking without resubmission for both phone paths`() = runTest(dispatcher) {
        for (phone in listOf("0912469923", "0912000000")) {
            val api = FakeApi().apply { checkoutFailure = java.io.IOException("Checkout response lost."); currentBooking = paidBooking }
            val model = readyForCheckout(api)
            model.checkout("Test Passenger", phone)
            advanceUntilIdle()
            if (phone == "0912469923") { model.verifyTestPayment("123456"); advanceUntilIdle() }
            assertEquals(1, api.checkoutRequests.size)
            assertEquals(1, api.bookingAccesses.size)
            assertEquals(IntercityPhase.TICKET, model.state.value.phase)
            assertEquals("PAID", model.state.value.booking!!.paymentStatus)
            assertNull(model.state.value.message)
        }
    }

    @Test fun `checkout pending initialization and safe retry errors retain exact server message without replay`() = runTest(dispatcher) {
        for ((status, code) in listOf(409 to "CHECKOUT_INITIALIZING", 502 to "CHECKOUT_RETRY_SAFE", 503 to "PAYMENT_CONFIRMATION_PENDING")) {
            val api = FakeApi().apply {
                checkoutError = Response.error(status, "{\"error\":\"$code: Please check your booking.\"}".toResponseBody("application/json".toMediaType()))
            }
            val model = readyForCheckout(api)
            model.checkout("Normal Passenger", "0912000000")
            advanceUntilIdle()
            assertEquals("$code: Please check your booking.", model.state.value.message)
            assertEquals(1, api.checkoutRequests.size)
            assertEquals(1, api.bookingAccesses.size)
            assertFalse(canShowIntercityQr(model.state.value.booking))
        }
    }

    private class MemoryStore(initial: List<IntercityBookingAccess> = emptyList()) : IntercityBookingAccessStore {
        private var entries = initial
        override fun load(): List<IntercityBookingAccess> = entries
        override fun save(access: IntercityBookingAccess) { entries = (listOf(access) + entries.filterNot { it.id == access.id }).take(8) }
        override fun forget(id: String) { entries = entries.filterNot { it.id == id } }
    }

    private class FakeApi : IntercityPassengerApi {
        var currentBooking = pendingBooking
        var checkoutResult = IntercityCheckoutResponse(true, paid = true)
        var holdCalls = 0
        var lastHold: IntercitySeatHoldRequest? = null
        var lastCheckoutSecret: String? = null
        var pdfAccess: Pair<String, String>? = null
        var holdGate: CompletableDeferred<Unit>? = null
        var checkoutGate: CompletableDeferred<Unit>? = null
        var otpRequestGate: CompletableDeferred<Unit>? = null
        var otpVerifyGate: CompletableDeferred<Unit>? = null
        var otpRequestResult: Response<IntercityTestOtpResponse> = Response.success(IntercityTestOtpResponse(true, delivery = "SENT", retryAfterSeconds = 60))
        var otpVerifyResult: Response<IntercityTestOtpResponse> = Response.success(IntercityTestOtpResponse(true, testPaymentVerificationToken = "one-use-fixture-proof", expiresInSeconds = 120))
        var checkoutFailure: Exception? = null
        var checkoutError: Response<IntercityCheckoutResponse>? = null
        val otpRequests = mutableListOf<IntercityTestOtpRequest>()
        val otpVerifications = mutableListOf<IntercityTestOtpRequest>()
        val checkoutRequests = mutableListOf<IntercityCheckoutRequest>()
        var bookingGate: CompletableDeferred<Unit>? = null
        val bookingAccesses = mutableListOf<Pair<String, String>>()
        val departureQueries = mutableListOf<Triple<String?, String?, String?>>()
        var directoryDepartures = listOf(departure)
        var filteredDepartures = listOf(departure)
        var departureFailure: String? = null

        override suspend fun departures(origin: String?, destination: String?, date: String?): Response<IntercityDeparturesResponse> {
            departureQueries += Triple(origin, destination, date)
            return Response.success(IntercityDeparturesResponse(departureFailure == null,
                if (date == null) directoryDepartures else filteredDepartures, departureFailure))
        }
        override suspend fun seats(id: String) = Response.success(IntercitySeatsResponse(true, 30, listOf(1), 650.0))
        override suspend fun hold(id: String, body: IntercitySeatHoldRequest): Response<IntercityBookingResponse> {
            holdCalls++
            lastHold = body
            holdGate?.await()
            return Response.success(IntercityBookingResponse(true, pendingBooking.copy(seatNumber = body.seatNumber), "private-capability"))
        }
        override suspend fun booking(id: String, bookingSecret: String): Response<IntercityBookingResponse> {
            bookingAccesses += id to bookingSecret
            bookingGate?.await()
            return Response.success(IntercityBookingResponse(true, currentBooking.copy(id = id)))
        }
        override suspend fun checkout(id: String, bookingSecret: String, body: IntercityCheckoutRequest): Response<IntercityCheckoutResponse> {
            lastCheckoutSecret = bookingSecret
            checkoutRequests += body
            checkoutGate?.await()
            checkoutFailure?.let { throw it }
            checkoutError?.let { return it }
            return Response.success(checkoutResult)
        }
        override suspend fun requestTestPaymentOtp(id: String, bookingSecret: String, body: IntercityTestOtpRequest): Response<IntercityTestOtpResponse> {
            assertEquals("private-capability", bookingSecret)
            otpRequests += body
            otpRequestGate?.await()
            return otpRequestResult
        }
        override suspend fun verifyTestPaymentOtp(id: String, bookingSecret: String, body: IntercityTestOtpRequest): Response<IntercityTestOtpResponse> {
            assertEquals("private-capability", bookingSecret)
            otpVerifications += body
            otpVerifyGate?.await()
            return otpVerifyResult
        }
        override suspend fun ticketPdf(id: String, secret: String): Response<ResponseBody> {
            pdfAccess = id to secret
            return Response.success("%PDF-test".toResponseBody("application/pdf".toMediaType()))
        }
        override suspend fun companies() = Response.success(IntercityCompaniesResponse(true, listOf(IntercityCompany("company-1", "Example Transport"))))
        override suspend fun requestRoute(body: IntercityRouteRequest) = Response.success(IntercityRouteRequestResponse(true, "request-1", "Route requested."))
    }

    private companion object {
        val departure = IntercityDeparture("departure-1", "Addis Ababa", "Hawassa", "2026-10-20T04:00:00Z",
            "Example Transport", "company-1", seats = 30, availableSeats = 29, fareEtb = 650.0)
        val detail = IntercityDepartureDetail("departure-1", "Addis Ababa", "Hawassa", "2026-10-20T04:00:00Z",
            "company-1", 650.0, 30, "SCHEDULED")
        val pendingBooking = IntercityBooking("booking-1", "departure-1", 2, 650.0, "HOLD", "PENDING",
            expiresAt = "2026-10-19T11:10:00Z", departure = detail)
        val paidBooking = pendingBooking.copy(status = "CONFIRMED", paymentStatus = "PAID", paymentMethod = "TELEBIRR",
            passengerName = "Test Passenger", passengerPhone = "0912000000", ticketNumber = "RP-AB12CD34", qrToken = "server-signed-qr")
    }
}
