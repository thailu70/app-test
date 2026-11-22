package com.example.ui.screens.taxi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.*
import com.example.ui.screens.intercity.mobilityBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import retrofit2.Response

internal enum class NearbyCarsStatus { IDLE, LOADING, AVAILABLE, EMPTY, UNAVAILABLE }

internal data class TaxiUiState(val busy: Boolean = false, val message: String? = null,
    val settings: TaxiSettings = TaxiSettings(), val tiers: List<TaxiTier> = emptyList(),
    val estimate: TaxiEstimate? = null, val ride: TaxiRide? = null, val rides: List<TaxiRide> = emptyList(),
    val dashboard: TaxiResponse? = null, val trips: List<SharedRideTrip> = emptyList(),
    val myTrips: List<SharedRideTrip> = emptyList(), val bookings: List<SharedRideBooking> = emptyList(),
    val selectedTrip: SharedRideTrip? = null, val selectedBooking: SharedRideBooking? = null,
    val occupiedSeats: List<Int> = emptyList(), val nearbyCars: List<TaxiNearbyCar> = emptyList(),
    val nearbyCarsStatus: NearbyCarsStatus = NearbyCarsStatus.IDLE)

/** Server fare and seat counts are authoritative. A failed refresh never empties a displayed roster. */
internal class TaxiViewModel(private val api: TaxiApi, private val authenticated: Boolean, private val driver: Boolean,
    private val flushMeter: suspend (String, Map<String, Any>) -> Long = TaxiTracking::finishMeter) : ViewModel() {
    private val mutable = MutableStateFlow(TaxiUiState())
    val state = mutable.asStateFlow()
    private var refreshRunning = false
    private val operations = Mutex()
    private val idempotency = mutableMapOf<String, String>()
    private var nearbyQuery: Triple<Double, Double, String>? = null
    private var nearbyJob: Job? = null
    private var estimateEpoch = 0L
    init { refresh() }
    private fun key(value: String) = idempotency.getOrPut(value) { UUID.randomUUID().toString() }
    private fun check(result: TaxiResponse): TaxiResponse {
        check(result.success) { result.error ?: "This action could not be completed." }
        return result
    }
    private fun quotedBody(response: Response<TaxiResponse>): TaxiResponse {
        if (!response.isSuccessful) {
            val value = runCatching { com.squareup.moshi.Moshi.Builder().build().adapter(Map::class.java).fromJson(response.errorBody()?.string().orEmpty()) }.getOrNull()
            val code = (value?.get("code") as? String).orEmpty()
            if (code in setOf("ESTIMATE_CHANGED", "COMMISSION_CHANGED", "TARIFF_CHANGED")) mutable.update { it.copy(estimate = null) }
            error((value?.get("error") as? String)?.takeIf(String::isNotBlank) ?: "The request could not be completed. Refresh the estimate and try again.")
        }
        return mobilityBody(response)
    }
    private fun act(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { operations.withLock { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "Check your connection and try again.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun clearEstimate() { estimateEpoch++; mutable.update { it.copy(estimate = null) } }
    /** Reprices the already-routed journey locally using the server catalog multipliers.
     * The server recomputes and validates the fare again when the passenger requests a ride. */
    fun selectTier(tierId: String) {
        val current = mutable.value
        val tier = current.tiers.firstOrNull { it.id == tierId } ?: return
        val quote = current.estimate
        val fare = quote?.let {
            val raw = (current.settings.baseFareEtb + it.distanceKm * current.settings.perKmEtb) * tier.multiplier
            max(current.settings.minimumFareEtb, raw).let { value -> (value * 100.0).roundToInt() / 100.0 }
        }
        mutable.update { state -> state.copy(estimate = state.estimate?.copy(tier = tierId, estimatedFareEtb = fare ?: state.estimate.estimatedFareEtb)) }
    }
    fun nearby(point: Pair<Double, Double>?, tier: String, force: Boolean = false) {
        val next = point?.let { Triple(it.first, it.second, tier) }
        val changed = nearbyQuery != next
        if (!changed && (!force || nearbyJob?.isActive == true)) return
        nearbyQuery = next
        if (next == null) mutable.update { it.copy(nearbyCars = emptyList(), nearbyCarsStatus = NearbyCarsStatus.IDLE) }
        else if (changed) mutable.update { it.copy(nearbyCars = emptyList(), nearbyCarsStatus = NearbyCarsStatus.LOADING) }
        nearbyJob?.cancel()
        nearbyJob = next?.let { query -> viewModelScope.launch {
            try { loadNearby(query) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (nearbyQuery == query) mutable.update { current -> current.copy(
                    nearbyCarsStatus = if (current.nearbyCars.isNotEmpty()) NearbyCarsStatus.AVAILABLE else NearbyCarsStatus.UNAVAILABLE) }
            }
        } }
    }
    private suspend fun loadNearby(query: Triple<Double, Double, String>) {
        val result = mobilityBody(api.nearby(query.first, query.second, query.third))
        if (nearbyQuery == query) {
            val cars = result.vehicles.filter { car -> car.latitude.isFinite() && car.longitude.isFinite() && car.latitude in -90.0..90.0 && car.longitude in -180.0..180.0 }.take(40)
            mutable.update { current -> current.copy(nearbyCars = if (result.success && result.approximate) cars else current.nearbyCars,
                nearbyCarsStatus = when { !result.success || !result.approximate -> if (current.nearbyCars.isNotEmpty()) NearbyCarsStatus.AVAILABLE else NearbyCarsStatus.UNAVAILABLE
                    cars.isEmpty() -> NearbyCarsStatus.EMPTY
                    else -> NearbyCarsStatus.AVAILABLE }) }
        }
    }
    fun closeTrip() { mutable.update { it.copy(selectedTrip = null, selectedBooking = null, occupiedSeats = emptyList()) } }
    fun dismissRide() { mutable.update { it.copy(ride = null) } }
    fun refresh(quiet: Boolean = false) {
        if (refreshRunning || mutable.value.busy) return
        refreshRunning = true
        viewModelScope.launch {
            try { operations.withLock { load() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (!quiet) mutable.update { it.copy(message = e.message ?: "Could not refresh rides.") } }
            finally { refreshRunning = false }
        }
    }
    private suspend fun load() {
        val catalog = check(mobilityBody(api.catalog()))
        mutable.update { it.copy(settings = catalog.settings ?: it.settings, tiers = catalog.tiers) }
        val shared = check(mobilityBody(api.shared()))
        mutable.update { it.copy(trips = shared.trips) }
        if (authenticated) {
            if (driver) {
                val result = check(mobilityBody(api.dashboard()))
                mutable.update { it.copy(dashboard = result, ride = result.ride ?: it.ride?.takeIf { r -> r.status in setOf("COMPLETED", "CANCELLED") },
                    myTrips = result.sharedTrips) }
            } else {
                val result = check(mobilityBody(api.me()))
                mutable.update { it.copy(ride = result.ride ?: it.ride?.takeIf { r -> r.status in setOf("COMPLETED", "CANCELLED") }, rides = result.rides,
                    myTrips = result.sharedTrips, bookings = result.bookings) }
            }
            mutable.value.selectedTrip?.let { loadTrip(it.id) }
        }
        if (!driver && (mutable.value.ride == null || mutable.value.ride?.status == "REQUESTED")) nearbyQuery?.let { runCatching { loadNearby(it) } }
    }
    fun estimate(route: TaxiRouteInput) = act {
        val epoch = estimateEpoch
        val result = check(mobilityBody(api.estimate(route)))
        if (epoch == estimateEpoch) mutable.update { it.copy(estimate = result.estimate ?: error("No road estimate was returned.")) }
    }
    fun request(route: TaxiRouteInput) = act {
        val intent = "request:$route"
        val result = check(quotedBody(api.request(route.copy(idempotencyKey = key(intent), expectedFareEtb = mutable.value.estimate?.estimatedFareEtb))))
        mutable.update { it.copy(ride = result.ride ?: error("The request was not returned."), estimate = null) }
        idempotency.remove(intent)
    }
    fun openRide(id: String) = act { val result = check(mobilityBody(api.ride(id))); mutable.update { it.copy(ride = result.ride) } }
    fun openNotifiedTrip(id: String, shared: Boolean) { viewModelScope.launch { state.first { !it.busy }; if (shared) chooseTrip(id) else openRide(id) } }
    fun cancelRide(reason: String = "Cancelled in app", location: Map<String, Any> = emptyMap()) = act {
        val ride = mutable.value.ride ?: return@act
        val body = buildMap { put("reason", reason); location.forEach { (key, value) -> put(key, value.toString()) } }
        val result = check(mobilityBody(if (driver) api.driverCancel(ride.id, body) else api.cancel(ride.id, body)))
        mutable.update { it.copy(ride = result.ride) }; load()
        if (driver && result.ride?.status == "CANCELLED") mutable.update { it.copy(ride = null, message = "Trip cancelled. You are back online.") }
    }
    fun online(online: Boolean, vehicle: String, location: Map<String, Any>) = act {
        val result = check(mobilityBody(api.availability(location + mapOf("online" to online, "vehicleId" to vehicle))))
        mutable.update { it.copy(dashboard = result, ride = result.ride) }
    }
    fun accept(id: String, vehicle: String) = act {
        val result = check(mobilityBody(api.accept(id, mapOf("vehicleId" to vehicle))))
        mutable.update { it.copy(ride = result.ride) }; load()
    }
    fun arrive(location: Map<String, Any>) = act {
        val ride = mutable.value.ride ?: return@act
        val result = check(mobilityBody(api.arrive(ride.id, location)))
        mutable.update { it.copy(ride = result.ride) }
    }
    fun start(qrToken: String, location: Map<String, Any>) = act {
        val ride = mutable.value.ride ?: return@act
        flushMeter(ride.id, location)
        val result = check(mobilityBody(api.start(ride.id, mapOf("qrToken" to qrToken))))
        mutable.update { it.copy(ride = result.ride) }
    }
    fun street(route: TaxiRouteInput, location: Map<String, Any>) = act {
        val intent = "street:$route"
        val result = check(quotedBody(api.street(route.copy(idempotencyKey = key(intent), passengerConsent = true,
            latitude = location["latitude"] as Double, longitude = location["longitude"] as Double,
            accuracyMeters = location["accuracyMeters"] as Double, timestamp = location["timestamp"] as String,
            expectedFareEtb = mutable.value.estimate?.estimatedFareEtb, expectedCommissionEtb = mutable.value.settings.fixedCommissionEtb))))
        mutable.update { it.copy(ride = result.ride, estimate = null) }
        idempotency.remove(intent)
        load()
    }
    fun wait(action: String, location: Map<String, Any>? = null) = act {
        val ride = mutable.value.ride ?: return@act
        if (action in setOf("REQUEST_STOP", "END_STOP")) {
            check(location != null) { "Confirm a fresh GPS location before changing the passenger stop." }
            flushMeter(ride.id, location)
        }
        val result = check(mobilityBody(api.wait(ride.id, mapOf("action" to action))))
        mutable.update { it.copy(ride = result.ride) }
    }
    fun approveWait(approved: Boolean) = act {
        val ride = mutable.value.ride ?: return@act
        val result = check(mobilityBody(api.approveWait(ride.id, mapOf("approved" to approved))))
        mutable.update { it.copy(ride = result.ride) }
    }
    fun complete(location: Map<String, Any>) = act {
        val ride = mutable.value.ride ?: return@act
        val finalSequence = flushMeter(ride.id, location)
        val result = check(mobilityBody(api.complete(ride.id, location + mapOf("finalSequence" to finalSequence))))
        if (driver && result.ride?.status == "COMPLETED") {
            mutable.update { it.copy(ride = null, message = "Trip completed. You are back online.") }
            val vehicle = result.ride.vehicleId
            val availability = if (!vehicle.isNullOrBlank()) runCatching {
                check(mobilityBody(api.availability(location + mapOf("online" to true, "vehicleId" to vehicle))))
            }.getOrNull() else null
            if (availability != null) mutable.update { it.copy(dashboard = availability, ride = null) }
            else {
                runCatching { load() }
                if (mutable.value.dashboard?.online != true) mutable.update { it.copy(message = "Trip completed. Reconnecting to your driver availability…") }
            }
        } else {
            mutable.update { it.copy(ride = result.ride) }
            load()
        }
    }
    fun chooseTrip(id: String) = act { loadTrip(id) }
    private suspend fun loadTrip(id: String) {
        val result = check(mobilityBody(api.sharedTrip(id)))
        mutable.update { it.copy(selectedTrip = result.trip, selectedBooking = result.booking,
            occupiedSeats = result.occupiedSeats) }
    }
    fun sharedRequest(route: TaxiRouteInput) = act {
        val intent = "shared-request:$route"
        val result = check(mobilityBody(api.sharedRequest(route.copy(idempotencyKey = key(intent)))))
        mutable.update { it.copy(selectedTrip = result.trip, message = "Your route is available to eligible drivers. Confirm the fare before booking when a driver accepts.") }
        idempotency.remove(intent)
        load()
    }
    fun offer(route: TaxiRouteInput) = act {
        val intent = "offer:$route"
        val result = check(mobilityBody(api.offer(route.copy(idempotencyKey = key(intent)))))
        mutable.update { it.copy(selectedTrip = result.trip) }
        idempotency.remove(intent)
        load()
    }
    fun acceptShared(id: String, vehicle: String, seats: Int, fare: Double) = act {
        val result = check(mobilityBody(api.acceptShared(id, mapOf("vehicleId" to vehicle, "seats" to seats, "farePerSeatEtb" to fare))))
        mutable.update { it.copy(selectedTrip = result.trip) }; load()
    }
    fun book(numbers: List<Int>) = act {
        val trip = mutable.value.selectedTrip ?: return@act
        val intent = "book:${trip.id}:$numbers"
        val result = check(mobilityBody(api.book(trip.id, mapOf("seatNumbers" to numbers, "idempotencyKey" to key(intent)))))
        mutable.update { it.copy(selectedTrip = result.trip ?: trip, selectedBooking = result.booking) }
        idempotency.remove(intent)
        loadTrip(trip.id); load()
    }
    fun cancelBooking(id: String) = act { check(mobilityBody(api.cancelBooking(id))); mutable.value.selectedTrip?.let { loadTrip(it.id) }; load() }
    fun cancelSharedRequest() = act { val trip = mutable.value.selectedTrip ?: return@act
        val result = check(mobilityBody(api.cancelSharedRequest(trip.id))); mutable.update { it.copy(selectedTrip = result.trip ?: trip.copy(status = "CANCELLED")) }; load()
    }
    fun sharedAction(action: String, location: Map<String, Any> = emptyMap(), bookingId: String? = null, qrToken: String? = null) = act {
        val trip = mutable.value.selectedTrip ?: return@act
        val body = location.toMutableMap().apply { put("action", action); bookingId?.let { put("bookingId", it) }; qrToken?.let { put("qrToken", it) } }
        val result = check(mobilityBody(api.sharedAction(trip.id, body)))
        mutable.update { it.copy(selectedTrip = result.trip ?: trip) }; load()
        if (action in setOf("COMPLETE", "CANCEL") && result.trip?.status in setOf("COMPLETED", "CANCELLED"))
            mutable.update { it.copy(selectedTrip = null, message = if (action == "COMPLETE") "Group trip completed. You are back online." else "Group trip cancelled. You are back online.") }
    }
}
