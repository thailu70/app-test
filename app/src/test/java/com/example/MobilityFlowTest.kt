package com.example

import com.example.data.api.*
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import com.example.ui.screens.intercity.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import retrofit2.http.Header
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
class MobilityFlowTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val trip = MobilityTrip("trip-1", "Addis Ababa", "Hawassa", "2026-11-03T05:00:00Z", 650.0, 12, 11,
        vehicleId = "vehicle-1", vehiclePlate = "FIXTURE-1", status = "APPROVED")
    private val traveler = MobilityTraveler("traveler-1", "Fixture Traveler", "0912000222", 2)
    private val booking get() = MobilityBooking("booking-1", trip.id, "Fixture Buyer", "0912000111", "HOLD", "PENDING", 650.0,
        "2026-11-03T04:10:00Z", listOf(traveler), trip)
    private class Store : IntercityBookingAccessStore {
        private val entries = linkedMapOf<String, IntercityBookingAccess>()
        override fun load() = entries.values.toList()
        override fun save(access: IntercityBookingAccess) { entries[access.id] = access }
        override fun forget(id: String) { entries.remove(id) }
    }
    private fun fake(call: (String, Array<out Any?>) -> Any): MobilityApi = Proxy.newProxyInstance(MobilityApi::class.java.classLoader,
        arrayOf(MobilityApi::class.java)) { _, method, args -> call(method.name, args.orEmpty()) } as MobilityApi
    private fun initial(method: String): Any = when (method) {
        "trips" -> Response.success(MobilityTripsResponse(true, listOf(trip)))
        "catalog" -> Response.success(MobilityCatalogResponse(true, listOf(MobilityVehicleType("MINIBUS_14", "Minibus", 14))))
        "seats" -> Response.success(MobilitySeatsResponse(true, trip, listOf(1)))
        else -> error("Unexpected API call: $method")
    }

    @Test fun `opening group portal discovers upcoming trips without a date filter`() = runTest(dispatcher) {
        var query: List<Any?>? = null
        val model = MobilityViewModel(fake { method, args -> if (method == "trips") query = args.take(3); initial(method) }, Store(), dispatcher)
        advanceUntilIdle()
        assertEquals(listOf(null, null, null), query)
        assertEquals(listOf(trip), model.state.value.trips)
        assertEquals("MINIBUS_14", model.state.value.types.single().id)
        assertFalse(model.state.value.adultConfirmed)
    }
    @Test fun `multiple seat reservation requires complete named travelers and explicit adult confirmation`() = runTest(dispatcher) {
        var bookCalls = 0; var captured: MobilityBookingInput? = null
        val store = Store()
        val model = MobilityViewModel(fake { method, args ->
            if (method == "book") { bookCalls++; captured = args[1] as MobilityBookingInput; Response.success(MobilityBookingResponse(true, booking, "private-booking")) }
            else initial(method)
        }, store, dispatcher)
        advanceUntilIdle(); model.choose(trip); advanceUntilIdle()
        model.toggleSeat(1); model.toggleSeat(0); model.toggleSeat(13)
        assertTrue(model.state.value.seats.isEmpty())
        model.toggleSeat(2); model.toggleSeat(3)
        val people = listOf(traveler, traveler.copy(name = "Second Traveler", phone = "0912000333", seatNumber = 3))
        model.book("Buyer", "0912000111", people); advanceUntilIdle()
        assertEquals(0, bookCalls)
        model.setAdult(true)
        model.book("Buyer", "0912000111", listOf(traveler)); advanceUntilIdle()
        assertEquals(0, bookCalls)
        model.book("Buyer", "0912000111", people); advanceUntilIdle()
        assertEquals(1, bookCalls); assertEquals(listOf(2, 3), captured!!.travelers.map { it.seatNumber })
        assertTrue(captured!!.adultConfirmed)
        assertEquals(listOf(IntercityBookingAccess("booking:booking-1", "private-booking")), store.load())
        assertEquals(MobilityPhase.BOOKING, model.state.value.phase)
        assertFalse(canShowGroupTicket(booking, traveler.copy(ticketNumber = "SHOULD-NOT-SHOW")))
    }
    @Test fun `payment redirect leaves booking unpaid and rejects an untrusted payment host`() = runTest(dispatcher) {
        val store = Store().apply { save(IntercityBookingAccess("booking:booking-1", "private-booking")) }
        var trusted = false
        val model = MobilityViewModel(fake { method, args -> when (method) {
            "booking" -> { assertEquals("private-booking", args[1]); Response.success(MobilityBookingResponse(true, booking)) }
            "checkout" -> Response.success(MobilityBookingResponse(true, booking, checkoutUrl = if (trusted) "https://superapp.ethiomobilemoney.et/pay" else "https://untrusted.example/pay", paid = true))
            else -> initial(method)
        } }, store, dispatcher)
        advanceUntilIdle(); model.openBooking("booking-1"); advanceUntilIdle(); model.setAdult(true)
        model.checkout(); advanceUntilIdle(); assertNull(model.state.value.checkoutUrl)
        assertEquals("PENDING", model.state.value.booking!!.paymentStatus)
        trusted = true; model.checkout(); advanceUntilIdle()
        assertEquals("https://superapp.ethiomobilemoney.et/pay", model.state.value.checkoutUrl)
        assertFalse(canShowGroupTicket(model.state.value.booking!!, traveler))
    }
    @Test fun `cargo photo failure preserves private draft for upload retry`() = runTest(dispatcher) {
        val request = MobilityRequest("request-1", "CARGO", "Addis Ababa", "Adama", "DRAFT", "2026-11-03", vehicleType = "TRUCK")
        var uploads = 0; var submits = 0
        val store = Store()
        val model = MobilityViewModel(fake { method, _ -> when (method) {
            "request" -> Response.success(MobilityRequestResponse(true, request, "private-request"))
            "photo" -> { uploads++; if (uploads == 1) throw IllegalStateException("Network interrupted") else Response.success(MobilityRequestResponse(true, request)) }
            "submitRequest" -> { submits++; Response.success(MobilityRequestResponse(true, request.copy(status = "REQUESTED"))) }
            else -> initial(method)
        } }, store, dispatcher)
        advanceUntilIdle()
        model.sendRequest(MobilityRequestInput("CARGO", "Addis Ababa", "Adama", "2026-11-03", vehicleType = "TRUCK",
            cargoDetails = "Fixture cargo", customerName = "Customer", customerPhone = "0912000111", adultConfirmed = true), byteArrayOf(1,2,3), "image/jpeg")
        advanceUntilIdle()
        assertEquals(MobilityPhase.REQUEST_STATUS, model.state.value.phase)
        assertEquals("DRAFT", model.state.value.request!!.status)
        assertEquals("private-request", store.load().single().secret)
        assertEquals(0, submits)
        model.retryCargo(byteArrayOf(1,2,3), "image/jpeg"); advanceUntilIdle()
        assertEquals(2, uploads); assertEquals(1, submits)
        assertEquals("REQUESTED", model.state.value.request!!.status)
    }
    @Test fun `capability headers never move into public query parameters`() {
        for (name in listOf("booking", "checkout", "reconcile")) {
            val method = MobilityApi::class.java.declaredMethods.single { it.name == name }
            assertEquals(listOf("X-Booking-Secret"), method.parameterAnnotations.flatMap { it.toList() }.filterIsInstance<Header>().map { it.value })
        }
        for (name in listOf("requestStatus", "photo", "submitRequest", "requestPhoto")) {
            val method = MobilityApi::class.java.declaredMethods.single { it.name == name }
            assertEquals(listOf("X-Request-Secret"), method.parameterAnnotations.flatMap { it.toList() }.filterIsInstance<Header>().map { it.value })
        }
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val json = moshi.adapter(MobilityBookingInput::class.java).toJson(MobilityBookingInput("Buyer", "0912000111", true, listOf(traveler)))
        assertFalse(json.contains("paid")); assertFalse(json.contains("paymentStatus")); assertTrue(json.contains("seatNumber"))
    }
    @Test fun `boarding credentials require confirmed server payment and Ethiopia departure time is converted correctly`() {
        val ticket = traveler.copy(ticketNumber = "PG-123", qrToken = "server-signed")
        assertFalse(canShowGroupTicket(booking.copy(status = "CONFIRMED"), ticket))
        assertFalse(canShowGroupTicket(booking.copy(paymentStatus = "PAID"), ticket))
        assertTrue(canShowGroupTicket(booking.copy(status = "CONFIRMED", paymentStatus = "PAID"), ticket))
        assertEquals("2026-11-03T05:30:00.000Z", mobilityDepartureIso("2026-11-03", "08:30"))
        assertNull(mobilityDepartureIso("2026-02-30", "08:30")); assertNull(mobilityDepartureIso("2026-11-03", "25:00"))
        assertNull(validMobilityPoint("NaN", "40")); assertNull(validMobilityPoint("9", "181"))
        assertEquals(9.0 to 38.0, validMobilityPoint("9", "38"))
    }
}
