package com.example.ui.screens.intercity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.*
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Response
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.OutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal enum class IntercityPhase { BROWSE, SEATS, DETAILS, TICKET, BOOKINGS, REQUEST }

internal data class IntercityUiState(
    val phase: IntercityPhase = IntercityPhase.BROWSE,
    val origin: String = "", val destination: String = "", val date: String = intercityToday(),
    val serviceTab: Int = 0, val busy: Boolean = false, val message: String? = null,
    val departures: List<IntercityDeparture> = emptyList(), val directory: List<IntercityDeparture> = emptyList(),
    val companies: List<IntercityCompany> = emptyList(), val selected: IntercityDeparture? = null,
    val inventory: IntercitySeatsResponse? = null, val seat: Int? = null,
    val booking: IntercityBooking? = null, val recent: List<IntercityBooking> = emptyList(),
    val checkoutUrl: String? = null, val searchCompleted: Boolean = false,
    val showingUpcoming: Boolean = true,
    val testVerificationPending: Boolean = false,
    val testVerificationPhone: String? = null,
    val testOtpResendAt: Long = 0L
)

internal fun intercityToday(now: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Africa/Addis_Ababa") }.format(Date(now))

internal fun trustedIntercityCheckout(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.userInfo == null && uri.host in setOf("superapp.ethiomobilemoney.et", "developerportal.ethiotelebirr.et") && uri.port in setOf(-1, 443, 38443)
}.getOrDefault(false)

internal fun canShowIntercityQr(booking: IntercityBooking?): Boolean = booking != null &&
    booking.paymentStatus == "PAID" && booking.status == "CONFIRMED" && !booking.qrToken.isNullOrBlank()

/** Client UI hint only. Eligibility and proof are enforced independently by the server. */
internal fun isIntercityTestPhone(value: String): Boolean {
    val digits = value.filter(Char::isDigit)
    return digits in setOf("0912469923", "912469923", "251912469923", "00251912469923")
}

/** Guest booking capabilities remain private; every payment and boarding credential comes from the server. */
internal class IntercityPassengerViewModel(
    private val api: IntercityPassengerApi,
    private val store: IntercityBookingAccessStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutable = MutableStateFlow(IntercityUiState())
    val state = mutable.asStateFlow()
    private val capabilities = linkedMapOf<String, String>()
    private var navigationEpoch = 0L
    private data class DepartureSearch(val origin: String?, val destination: String?, val date: String)
    // Draft fields are not applied until Search is pressed. Null means upcoming discovery.
    private var activeSearch: DepartureSearch? = null
    private data class TestDraft(val bookingId: String, val name: String, val phone: String, val epoch: Long)
    private var testDraft: TestDraft? = null
    private val errorAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java))

    init { action {
        try { withContext(ioDispatcher) { store.load() }.forEach { capabilities[it.id] = it.secret } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "Saved tickets could not be opened on this device.") } }
        try { loadDepartures() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.update { it.copy(message = error.message ?: "Routes could not be loaded.") } }
        loadRecent()
    } }

    private fun <T> body(response: Response<T>): T {
        if (!response.isSuccessful) {
            val message = runCatching { errorAdapter.fromJson(response.errorBody()?.string().orEmpty())?.get("error") as? String }.getOrNull()
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Could not connect. Please try again.")
        }
        return response.body() ?: throw IllegalStateException("The server returned an empty response.")
    }

    private fun action(block: suspend (Long) -> Unit) {
        if (mutable.value.busy) return
        val expectedEpoch = navigationEpoch
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block(expectedEpoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (expectedEpoch == navigationEpoch) mutable.update { it.copy(message = error.message ?: "Could not complete this request.") }
            }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun loadDepartures() {
        val directory = body(api.departures())
        check(directory.success) { directory.error ?: "Routes could not be loaded." }
        mutable.update { it.copy(directory = directory.departures) }
        val appliedSearch = activeSearch
        if (appliedSearch == null) {
            // The public endpoint already returns scheduled future departures, ordered by time.
            // Applying the form's default date here hides every departure after today.
            mutable.update { it.copy(departures = directory.departures, searchCompleted = true, showingUpcoming = true) }
        } else {
            applySearch(appliedSearch)
        }
    }

    private suspend fun searchDepartures() {
        val input = mutable.value
        require(input.origin.isBlank() || !input.origin.trim().equals(input.destination.trim(), true)) { "Choose different departure and destination cities." }
        val query = DepartureSearch(input.origin.trim().ifBlank { null }, input.destination.trim().ifBlank { null }, input.date)
        applySearch(query)
        activeSearch = query
    }

    private suspend fun applySearch(query: DepartureSearch) {
        val result = body(api.departures(query.origin, query.destination, query.date))
        check(result.success) { result.error ?: "Routes could not be loaded." }
        mutable.update { it.copy(departures = result.departures, searchCompleted = true, showingUpcoming = false) }
    }

    fun setOrigin(value: String) { mutable.update { it.copy(origin = value) } }
    fun setDestination(value: String) { mutable.update { it.copy(destination = value) } }
    fun setDate(value: String) { mutable.update { it.copy(date = value) } }
    fun setTab(value: Int) { mutable.update { it.copy(serviceTab = value) } }
    fun swapCities() { mutable.update { it.copy(origin = it.destination, destination = it.origin) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun search() = action { searchDepartures() }
    fun refresh() = action { loadDepartures(); loadRecent() }
    fun home() {
        navigationEpoch++
        clearTestDraft()
        mutable.update { it.copy(phase = IntercityPhase.BROWSE, checkoutUrl = null, message = null) }
    }

    fun selectDeparture(departure: IntercityDeparture) = action { expectedEpoch ->
        if (expectedEpoch != navigationEpoch) return@action
        clearTestDraft()
        mutable.update { it.copy(phase = IntercityPhase.SEATS, selected = departure, inventory = null, seat = null, booking = null) }
        val result = body(api.seats(departure.id))
        check(result.success) { result.error ?: "Seats could not be loaded." }
        if (expectedEpoch == navigationEpoch) mutable.update { it.copy(inventory = result) }
    }

    fun selectSeat(number: Int) {
        val inventory = mutable.value.inventory ?: return
        if (number in 1..inventory.seats && number !in inventory.reserved) mutable.update { it.copy(seat = number) }
    }

    fun holdSeat() = action { expectedEpoch ->
        if (expectedEpoch != navigationEpoch) return@action
        val selected = mutable.value.selected ?: error("Choose a departure.")
        val seat = mutable.value.seat ?: error("Choose a seat.")
        val result = body(api.hold(selected.id, IntercitySeatHoldRequest(seat)))
        check(result.success) { result.error ?: "This seat could not be reserved." }
        val booking = result.booking ?: error("The reservation was incomplete. Refresh the seats.")
        val secret = result.bookingSecret?.takeIf { it.isNotBlank() } ?: error("The reservation did not include private access.")
        capabilities[booking.id] = secret
        if (expectedEpoch == navigationEpoch) mutable.update { it.copy(booking = booking, phase = IntercityPhase.DETAILS) }
        rememberBooking(booking)
        try { withContext(ioDispatcher) { store.save(IntercityBookingAccess(booking.id, secret)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(message = "Booking access could not be saved. Keep this app open to finish your booking.") } }
    }

    private fun rememberBooking(booking: IntercityBooking) {
        mutable.update { it.copy(recent = (listOf(booking) + it.recent.filterNot { old -> old.id == booking.id }).take(8)) }
    }

    private suspend fun fetchBooking(id: String): IntercityBooking {
        val secret = capabilities[id] ?: error("This booking is not saved on this phone.")
        val result = body(api.booking(id, secret))
        check(result.success) { result.error ?: "Your booking could not be loaded." }
        return result.booking ?: error("Your booking could not be loaded.")
    }

    private suspend fun refreshBooking(id: String, open: Boolean, expectedEpoch: Long) {
        val booking = fetchBooking(id)
        rememberBooking(booking)
        if (expectedEpoch == navigationEpoch) mutable.update { it.copy(booking = if (open) booking else it.booking,
            phase = if (open) { if (booking.paymentStatus == "PAID") IntercityPhase.TICKET else IntercityPhase.DETAILS } else it.phase) }
    }

    private suspend fun loadRecent() {
        val recent = coroutineScope {
            capabilities.keys.toList().map { id -> async {
                try { fetchBooking(id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null /* Keep the private capability for a later retry. */ }
            } }.awaitAll().filterNotNull()
        }
        mutable.update { it.copy(recent = recent) }
    }

    fun openBooking(id: String) = action { expectedEpoch -> clearTestDraft(); refreshBooking(id, true, expectedEpoch) }
    fun showBookings() = action { expectedEpoch ->
        if (expectedEpoch != navigationEpoch) return@action
        clearTestDraft()
        mutable.update { it.copy(phase = IntercityPhase.BOOKINGS) }
        loadRecent()
    }
    fun checkPayment() = action { expectedEpoch -> mutable.value.booking?.let { refreshBooking(it.id, true, expectedEpoch) } }
    fun refreshOnResume() {
        if (!mutable.value.busy && !mutable.value.testVerificationPending && mutable.value.phase in setOf(IntercityPhase.DETAILS, IntercityPhase.TICKET)) checkPayment()
    }

    fun checkout(name: String, phone: String) = action { expectedEpoch ->
        if (expectedEpoch != navigationEpoch) return@action
        require(name.trim().length >= 2 && Regex("^\\+?[0-9 ()-]{7,24}$").matches(phone.trim()) && phone.count(Char::isDigit) in 7..15) { "Enter the passenger name and a valid phone number." }
        val booking = mutable.value.booking ?: error("Choose a seat first.")
        val secret = capabilities[booking.id] ?: error("Private booking access is unavailable.")
        if (isIntercityTestPhone(phone)) {
            clearTestDraft()
            val draft = TestDraft(booking.id, name.trim(), phone.trim(), expectedEpoch)
            val result = body(api.requestTestPaymentOtp(booking.id, secret, IntercityTestOtpRequest(draft.phone)))
            check(result.success && result.delivery == "SENT") { result.error ?: "The verification code could not be sent." }
            if (expectedEpoch == navigationEpoch && mutable.value.booking?.id == booking.id) {
                testDraft = draft
                mutable.update { it.copy(testVerificationPending = true, testVerificationPhone = draft.phone,
                    testOtpResendAt = System.currentTimeMillis() + (result.retryAfterSeconds ?: 60).coerceAtLeast(1) * 1000L) }
            }
        } else {
            clearTestDraft()
            completeCheckout(booking.id, secret, IntercityCheckoutRequest(name.trim(), phone.trim()), expectedEpoch)
        }
    }

    private suspend fun completeCheckout(id: String, secret: String, request: IntercityCheckoutRequest, expectedEpoch: Long) {
        try {
            val result = body(api.checkout(id, secret, request))
            check(result.success) { result.error ?: "Payment could not be prepared." }
            if (result.paid) refreshBooking(id, true, expectedEpoch)
            else {
                val url = result.checkoutUrl?.takeIf(::trustedIntercityCheckout) ?: error("The payment provider returned an unsupported checkout link.")
                if (expectedEpoch == navigationEpoch) mutable.update { it.copy(checkoutUrl = url, message = "Complete the secure Telebirr payment, then return here to check your ticket.") }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            // A lost response can follow a successful payment. Read once; never replay a
            // provider checkout or a one-use OTP proof to discover its outcome.
            val reconciled = try { fetchBooking(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (reconciled?.paymentStatus == "PAID") {
                rememberBooking(reconciled)
                if (expectedEpoch == navigationEpoch) mutable.update { it.copy(booking = reconciled, phase = IntercityPhase.TICKET) }
            } else throw error
        }
    }

    private fun clearTestDraft() {
        testDraft = null
        mutable.update { it.copy(testVerificationPending = false, testVerificationPhone = null, testOtpResendAt = 0L) }
    }

    // Cancelling an in-flight verify/resend discards its context. A late proof must never pay.
    fun cancelTestPaymentVerification() { clearTestDraft() }

    fun resendTestPaymentOtp() = action { expectedEpoch ->
        val draft = testDraft ?: return@action
        if (draft.epoch != expectedEpoch || mutable.value.booking?.id != draft.bookingId) return@action
        require(System.currentTimeMillis() >= mutable.value.testOtpResendAt) { "Please wait before requesting another code." }
        val secret = capabilities[draft.bookingId] ?: error("Private booking access is unavailable.")
        val result = body(api.requestTestPaymentOtp(draft.bookingId, secret, IntercityTestOtpRequest(draft.phone)))
        check(result.success && result.delivery == "SENT") { result.error ?: "The verification code could not be sent." }
        if (testDraft == draft && expectedEpoch == navigationEpoch) mutable.update {
            it.copy(testOtpResendAt = System.currentTimeMillis() + (result.retryAfterSeconds ?: 60).coerceAtLeast(1) * 1000L)
        }
    }

    fun verifyTestPayment(code: String) = action { expectedEpoch ->
        require(Regex("^[0-9]{6}$").matches(code.trim())) { "Enter the six-digit verification code." }
        val draft = testDraft ?: error("Request a new verification code.")
        if (draft.epoch != expectedEpoch || mutable.value.booking?.id != draft.bookingId) return@action
        val secret = capabilities[draft.bookingId] ?: error("Private booking access is unavailable.")
        val result = body(api.verifyTestPaymentOtp(draft.bookingId, secret, IntercityTestOtpRequest(draft.phone, code.trim())))
        check(result.success) { result.error ?: "The code could not be verified." }
        val proof = result.testPaymentVerificationToken?.takeIf(String::isNotBlank) ?: error("Request a new verification code.")
        if (testDraft != draft || expectedEpoch != navigationEpoch || mutable.value.booking?.id != draft.bookingId) return@action
        // One-use proof stays only in this request; never persist it or silently replay a checkout.
        clearTestDraft()
        completeCheckout(draft.bookingId, secret, IntercityCheckoutRequest(draft.name, draft.phone, proof), expectedEpoch)
    }

    fun checkoutOpened() { mutable.update { it.copy(checkoutUrl = null) } }
    fun checkoutLaunchFailed() { mutable.update { it.copy(checkoutUrl = null, message = "No app could open Telebirr checkout. Please try again.") } }

    fun requestRoute() = action { expectedEpoch ->
        if (expectedEpoch != navigationEpoch) return@action
        mutable.update { it.copy(phase = IntercityPhase.REQUEST) }
        val result = body(api.companies())
        check(result.success) { result.error ?: "Companies could not be loaded." }
        mutable.update { it.copy(companies = result.companies) }
    }

    fun sendRequest(companyId: String, name: String, phone: String) = action {
        val input = mutable.value
        require(input.origin.isNotBlank() && input.destination.isNotBlank() && companyId.isNotBlank()) { "Enter your cities and choose a transport company." }
        val result = body(api.requestRoute(IntercityRouteRequest(input.origin.trim(), input.destination.trim(), input.date, companyId, name.trim(), phone.trim())))
        check(result.success) { result.error ?: "Your request could not be sent." }
        mutable.update { it.copy(phase = IntercityPhase.BROWSE, message = result.message ?: "Your route request was sent.") }
    }

    fun savePdf(openOutput: () -> OutputStream?) = action {
        val booking = mutable.value.booking?.takeIf { it.paymentStatus == "PAID" } ?: error("Payment must be confirmed before saving a ticket.")
        val secret = capabilities[booking.id] ?: error("Private booking access is unavailable.")
        withContext(ioDispatcher) {
            val response = api.ticketPdf(booking.id, secret)
            val pdf = body(response)
            pdf.use { body ->
                val output = openOutput() ?: error("Could not open the selected file.")
                output.use { body.byteStream().use { input -> input.copyTo(it) } }
            }
        }
        mutable.update { it.copy(message = "Ticket PDF saved.") }
    }
}
