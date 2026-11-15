package com.example.ui.screens.taxi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.api.*
import com.example.ui.screens.intercity.mobilityBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal data class TaxiUiState(val busy: Boolean = false, val message: String? = null,
    val settings: TaxiSettings = TaxiSettings(), val tiers: List<TaxiTier> = emptyList(),
    val estimate: TaxiEstimate? = null, val ride: TaxiRide? = null, val rides: List<TaxiRide> = emptyList(),
    val dashboard: TaxiResponse? = null, val trips: List<SharedRideTrip> = emptyList(),
    val myTrips: List<SharedRideTrip> = emptyList(), val bookings: List<SharedRideBooking> = emptyList(),
    val selectedTrip: SharedRideTrip? = null, val selectedBooking: SharedRideBooking? = null,
    val occupiedSeats: List<Int> = emptyList())

/** Server fare and seat counts are authoritative. A failed refresh never empties a displayed roster. */
internal class TaxiViewModel(private val api: TaxiApi, private val authenticated: Boolean, private val driver: Boolean) : ViewModel() {
    private val mutable = MutableStateFlow(TaxiUiState())
    val state = mutable.asStateFlow()
    private var refreshRunning = false
    private val operations = Mutex()
    private val idempotency = mutableMapOf<String, String>()
    init { refresh() }
    private fun key(value: String) = idempotency.getOrPut(value) { UUID.randomUUID().toString() }
    private fun check(result: TaxiResponse): TaxiResponse {
        check(result.success) { result.error ?: "This action could not be completed." }
        return result
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
    fun clearEstimate() { mutable.update { it.copy(estimate = null) } }
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
    }
    fun estimate(route: TaxiRouteInput) = act {
        val result = check(mobilityBody(api.estimate(route)))
        mutable.update { it.copy(estimate = result.estimate ?: error("No road estimate was returned.")) }
    }
    fun request(route: TaxiRouteInput) = act {
        val result = check(mobilityBody(api.request(route.copy(idempotencyKey = key("request:$route")))))
        mutable.update { it.copy(ride = result.ride ?: error("The request was not returned."), estimate = null) }
    }
    fun openRide(id: String) = act { val result = check(mobilityBody(api.ride(id))); mutable.update { it.copy(ride = result.ride) } }
    fun cancelRide(reason: String = "Cancelled in app") = act {
        val ride = mutable.value.ride ?: return@act
        val result = check(mobilityBody(if (driver) api.driverCancel(ride.id, mapOf("reason" to reason)) else api.cancel(ride.id, mapOf("reason" to reason))))
        mutable.update { it.copy(ride = result.ride) }; load()
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
    fun start(pin: String) = act {
        val ride = mutable.value.ride ?: return@act
        val result = check(mobilityBody(api.start(ride.id, mapOf("pin" to pin))))
        mutable.update { it.copy(ride = result.ride) }
    }
    fun street(route: TaxiRouteInput) = act {
        val result = check(mobilityBody(api.street(route.copy(idempotencyKey = key("street:$route"), passengerConsent = true))))
        mutable.update { it.copy(ride = result.ride, estimate = null) }; load()
    }
    fun wait(action: String) = act {
        val ride = mutable.value.ride ?: return@act
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
        val result = check(mobilityBody(api.complete(ride.id, location)))
        mutable.update { it.copy(ride = result.ride) }
    }
    fun chooseTrip(id: String) = act { loadTrip(id) }
    private suspend fun loadTrip(id: String) {
        val result = check(mobilityBody(api.sharedTrip(id)))
        mutable.update { it.copy(selectedTrip = result.trip, selectedBooking = result.booking,
            occupiedSeats = result.occupiedSeats) }
    }
    fun sharedRequest(route: TaxiRouteInput) = act {
        val result = check(mobilityBody(api.sharedRequest(route.copy(idempotencyKey = key("shared-request:$route")))))
        mutable.update { it.copy(selectedTrip = result.trip, message = "Your route is available to eligible drivers. Confirm the fare before booking when a driver accepts.") }; load()
    }
    fun offer(route: TaxiRouteInput) = act {
        val result = check(mobilityBody(api.offer(route.copy(idempotencyKey = key("offer:$route")))))
        mutable.update { it.copy(selectedTrip = result.trip) }; load()
    }
    fun acceptShared(id: String, vehicle: String, seats: Int, fare: Double) = act {
        val result = check(mobilityBody(api.acceptShared(id, mapOf("vehicleId" to vehicle, "seats" to seats, "farePerSeatEtb" to fare))))
        mutable.update { it.copy(selectedTrip = result.trip) }; load()
    }
    fun book(numbers: List<Int>) = act {
        val trip = mutable.value.selectedTrip ?: return@act
        val result = check(mobilityBody(api.book(trip.id, mapOf("seatNumbers" to numbers, "idempotencyKey" to key("book:${trip.id}:$numbers")))))
        mutable.update { it.copy(selectedTrip = result.trip ?: trip, selectedBooking = result.booking) }; loadTrip(trip.id); load()
    }
    fun cancelBooking(id: String) = act { check(mobilityBody(api.cancelBooking(id))); mutable.value.selectedTrip?.let { loadTrip(it.id) }; load() }
    fun sharedAction(action: String, location: Map<String, Any> = emptyMap(), bookingId: String? = null, pin: String? = null) = act {
        val trip = mutable.value.selectedTrip ?: return@act
        val body = location.toMutableMap().apply { put("action", action); bookingId?.let { put("bookingId", it) }; pin?.let { put("pin", it) } }
        val result = check(mobilityBody(api.sharedAction(trip.id, body)))
        mutable.update { it.copy(selectedTrip = result.trip ?: trip) }; load()
    }
}
