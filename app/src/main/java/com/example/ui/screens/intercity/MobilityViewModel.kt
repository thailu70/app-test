package com.example.ui.screens.intercity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.*
import com.example.data.repository.IntercityBookingAccess
import com.example.data.repository.IntercityBookingAccessStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

internal enum class MobilityPhase { BROWSE, SEATS, BOOKING, REQUEST, REQUEST_STATUS, HISTORY }
internal data class MobilityUiState(val phase: MobilityPhase = MobilityPhase.BROWSE, val busy: Boolean = false,
    val message: String? = null, val trips: List<MobilityTrip> = emptyList(),
    val types: List<MobilityVehicleType> = emptyList(), val selected: MobilityTrip? = null,
    val occupied: List<Int> = emptyList(), val seats: List<Int> = emptyList(),
    val booking: MobilityBooking? = null, val bookings: List<MobilityBooking> = emptyList(),
    val requests: List<MobilityRequest> = emptyList(), val request: MobilityRequest? = null,
    val checkoutUrl: String? = null, val adultConfirmed: Boolean = false)

internal fun <T> mobilityBody(response: Response<T>): T {
    if (!response.isSuccessful) {
        val serverMessage = runCatching { org.json.JSONObject(response.errorBody()?.string().orEmpty()).optString("error") }.getOrNull()
        error(serverMessage?.takeIf { it.isNotBlank() } ?: "Could not complete the request. Please try again.")
    }
    return response.body() ?: error("The server returned an empty response.")
}

/** Persist only encrypted capabilities; never cache passenger manifests, cargo photos or boarding credentials. */
internal class MobilityViewModel(private val api: MobilityApi, private val store: IntercityBookingAccessStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : ViewModel() {
    private val mutable = MutableStateFlow(MobilityUiState())
    val state = mutable.asStateFlow()
    private val capabilities = linkedMapOf<String, String>()
    private var epoch = 0L
    init { action {
        try { withContext(ioDispatcher) { store.load() }.forEach { capabilities[it.id] = it.secret } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(message = "Saved travel access could not be opened on this device.") } }
        load()
        loadHistory()
    } }
    private fun action(block: suspend (Long) -> Unit) {
        if (mutable.value.busy) return
        val expected = epoch
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { block(expected) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (expected == epoch) mutable.update { it.copy(message = e.message ?: "Please try again.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    private suspend fun load(origin: String? = null, destination: String? = null, date: String? = null) {
        val trips = mobilityBody(api.trips(origin, destination, date)); check(trips.success) { trips.error ?: "Trips unavailable." }
        mutable.update { it.copy(trips = trips.trips) }
        val catalog = mobilityBody(api.catalog()); check(catalog.success) { catalog.error ?: "Vehicle types unavailable." }
        mutable.update { it.copy(types = catalog.vehicleTypes) }
    }
    fun search(origin: String, destination: String, date: String?) = action { load(origin.trim().ifBlank { null }, destination.trim().ifBlank { null }, date) }
    fun refresh() = action { load(); loadHistory() }
    fun home() { epoch++; mutable.update { it.copy(phase = MobilityPhase.BROWSE, checkoutUrl = null, message = null) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun setAdult(value: Boolean) { if (!mutable.value.busy) mutable.update { it.copy(adultConfirmed = value) } }
    fun request() { epoch++; mutable.update { it.copy(phase = MobilityPhase.REQUEST, request = null, message = null) } }
    fun choose(trip: MobilityTrip) = action { expected ->
        val result = mobilityBody(api.seats(trip.id)); check(result.success) { result.error ?: "Could not load seats." }
        if (expected == epoch) mutable.update { it.copy(phase = MobilityPhase.SEATS, selected = result.trip ?: trip, occupied = result.occupiedSeats, seats = emptyList(), booking = null) }
    }
    fun toggleSeat(number: Int) {
        val current = mutable.value; val trip = current.selected ?: return
        if (current.busy || number !in 1..trip.seats || number in current.occupied) return
        mutable.update { it.copy(seats = if (number in it.seats) it.seats - number else if (it.seats.size < 20) (it.seats + number).sorted() else it.seats) }
    }
    fun book(name: String, phone: String, travelers: List<MobilityTraveler>) = action { expected ->
        val input = mutable.value; val trip = input.selected ?: error("Choose a trip.")
        require(input.adultConfirmed) { "Confirm you are 18 or older." }
        require(input.seats.isNotEmpty() && input.seats.size == travelers.size && travelers.map { it.seatNumber }.toSet() == input.seats.toSet()) { "Complete each selected traveler's details." }
        require(name.isNotBlank() && phone.isNotBlank() && travelers.all { it.name.isNotBlank() && it.phone.isNotBlank() }) { "Enter the buyer and each traveler's name and phone number." }
        val result = mobilityBody(api.book(trip.id, MobilityBookingInput(name.trim(), phone.trim(), true, travelers)))
        check(result.success) { result.error ?: "Could not reserve these seats." }
        val booking = result.booking ?: error("No reservation was returned.")
        val secret = result.bookingSecret?.takeIf { it.isNotBlank() } ?: error("Private booking access was not returned.")
        save("booking:${booking.id}", secret)
        if (expected == epoch) mutable.update { it.copy(booking = booking, phase = MobilityPhase.BOOKING) }
    }
    private suspend fun save(id: String, secret: String) {
        capabilities[id] = secret
        try { withContext(ioDispatcher) { store.save(IntercityBookingAccess(id, secret)) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.update { it.copy(message = "Private access could not be saved. Keep the app open to finish.") } }
    }
    fun openBooking(id: String) = action { expected ->
        val result = mobilityBody(api.booking(id, capabilities["booking:$id"] ?: error("Private ticket access is unavailable.")))
        check(result.success) { result.error ?: "Could not open the booking." }
        if (expected == epoch) mutable.update { it.copy(booking = result.booking, phase = MobilityPhase.BOOKING) }
    }
    fun checkout() = action { expected ->
        val booking = mutable.value.booking ?: return@action
        require(mutable.value.adultConfirmed) { "Confirm you are 18 or older before paying." }
        val result = mobilityBody(api.checkout(booking.id, capabilities["booking:${booking.id}"] ?: error("Private booking access is unavailable.")))
        check(result.success) { result.error ?: "Checkout is not available yet. Your reservation has not been paid." }
        val url = result.checkoutUrl
        require(url == null || trustedIntercityCheckout(url)) { "The payment provider returned an unsupported checkout link." }
        if (expected == epoch) mutable.update { it.copy(booking = result.booking ?: booking, checkoutUrl = url) }
    }
    fun checkoutOpened() { mutable.update { it.copy(checkoutUrl = null) } }
    fun checkPayment() = action { expected ->
        val booking = mutable.value.booking ?: return@action
        val result = mobilityBody(api.reconcile(booking.id, capabilities["booking:${booking.id}"] ?: error("Private booking access is unavailable.")))
        check(result.success) { result.error ?: "Could not check payment." }
        if (expected == epoch) mutable.update { it.copy(booking = result.booking ?: booking) }
    }
    fun sendRequest(input: MobilityRequestInput, photo: ByteArray?, photoType: String?) = action { expected ->
        require(input.adultConfirmed) { "Confirm you are 18 or older." }
        require(input.kind != "CARGO" || photo != null) { "Choose a cargo photo." }
        var result = mobilityBody(api.request(input)); check(result.success) { result.error ?: "Could not create the request." }
        var request = result.request ?: error("No request was returned.")
        val secret = result.requestSecret?.takeIf { it.isNotBlank() } ?: error("Private request access was not returned.")
        save("request:${request.id}", secret)
        // Keep a recoverable draft if uploading or submitting fails.
        if (expected == epoch) mutable.update { it.copy(request = request, phase = MobilityPhase.REQUEST_STATUS) }
        if (input.kind == "CARGO") {
            require(photo != null && photo.size <= 5 * 1024 * 1024 && photoType in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP photo up to 5 MB." }
            result = mobilityBody(api.photo(request.id, secret, photo.toRequestBody(photoType!!.toMediaType())))
            check(result.success) { result.error ?: "Cargo photo could not be uploaded. Your draft is saved." }
            result = mobilityBody(api.submitRequest(request.id, secret)); check(result.success) { result.error ?: "The draft could not be submitted." }
            request = result.request ?: request
        }
        if (expected == epoch) mutable.update { it.copy(request = request, message = "Your request has been sent for admin review.") }
        loadHistory()
    }
    fun retryCargo(photo: ByteArray, photoType: String) = action { expected ->
        val request = mutable.value.request ?: return@action
        val secret = capabilities["request:${request.id}"] ?: error("Private request access is unavailable.")
        require(photo.size <= 5 * 1024 * 1024 && photoType in setOf("image/jpeg", "image/png", "image/webp")) { "Choose a JPEG, PNG or WebP photo up to 5 MB." }
        val uploaded = mobilityBody(api.photo(request.id, secret, photo.toRequestBody(photoType.toMediaType())))
        check(uploaded.success) { uploaded.error ?: "Could not upload the photo." }
        val result = mobilityBody(api.submitRequest(request.id, secret)); check(result.success) { result.error ?: "Could not submit the request." }
        if (expected == epoch) mutable.update { it.copy(request = result.request ?: request) }
    }
    fun openRequest(id: String) = action { expected ->
        val result = mobilityBody(api.requestStatus(id, capabilities["request:$id"] ?: error("Private request access is unavailable.")))
        check(result.success) { result.error ?: "Could not load request status." }
        if (expected == epoch) mutable.update { it.copy(request = result.request, phase = MobilityPhase.REQUEST_STATUS) }
    }
    private suspend fun loadHistory() {
        val bookings = mutableListOf<MobilityBooking>(); val requests = mutableListOf<MobilityRequest>()
        for ((key, secret) in capabilities.toMap()) {
            try {
                if (key.startsWith("booking:")) {
                    val result = mobilityBody(api.booking(key.removePrefix("booking:"), secret)); if (result.success) result.booking?.let(bookings::add)
                } else if (key.startsWith("request:")) {
                    val result = mobilityBody(api.requestStatus(key.removePrefix("request:"), secret)); if (result.success) result.request?.let(requests::add)
                }
            } catch (e: CancellationException) { throw e } catch (_: Exception) { /* One unavailable item must not discard the remaining history. */ }
        }
        mutable.update { it.copy(bookings = bookings, requests = requests) }
    }
    fun history() = action { loadHistory(); mutable.update { it.copy(phase = MobilityPhase.HISTORY) } }
}
