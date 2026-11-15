package com.example.core.notifications

import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.example.data.api.ApiClient
import com.example.data.api.TaxiApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object RideNotificationNavigation {
    private val pending = MutableStateFlow<MobilityPushPayload?>(null)
    val destination = pending.asStateFlow()
    private val focused = MutableStateFlow<MobilityPushPayload?>(null)
    val focus = focused.asStateFlow()
    fun accept(payload: MobilityPushPayload) { if (payload.kind in setOf("TAXI", "SHARED")) pending.value = payload }
    fun dismiss() { pending.value = null }
    fun open(payload: MobilityPushPayload) { focused.value = payload; pending.value = null }
    fun consumeFocus() { focused.value = null }
}

/** A forged or stale notification cannot open another account's private trip. */
@Composable internal fun RideNotificationDestination(userId: String?, role: String?, onOpen: (Boolean) -> Unit) {
    val payload by RideNotificationNavigation.destination.collectAsState()
    val selected = payload ?: return
    var problem by remember(selected) { mutableStateOf<String?>(null) }
    LaunchedEffect(selected, userId, role) {
        if (userId == null || role != selected.scope) return@LaunchedEffect
        val bearer = ApiClient.getAuthToken() ?: return@LaunchedEffect
        val session = ApiClient.sessionScope()
        try {
            val api = ApiClient.createSessionService(TaxiApi::class.java, bearer)
            val authorised = if (selected.scope == "DRIVER" && selected.eventType == "NEW_REQUEST") {
                val response = api.dashboard(); val data = response.body()
                response.isSuccessful && data?.success == true &&
                    (if (selected.kind == "TAXI") data.calls.any { it.id == selected.requestId } else data.sharedRequests.any { it.id == selected.requestId })
            } else if (selected.kind == "TAXI") {
                val response = api.ride(selected.requestId); val ride = response.body()?.ride
                response.isSuccessful && response.body()?.success == true && ride != null &&
                    (if (role == "DRIVER") ride.driverId == userId else ride.passengerId == userId)
            } else {
                val response = api.sharedTrip(selected.requestId); val data = response.body(); val trip = data?.trip
                response.isSuccessful && data?.success == true && trip != null &&
                    (if (role == "DRIVER") trip.driverId == userId else trip.passengerId == userId || data.booking?.passengerId == userId)
            }
            if (!ApiClient.isCurrent(session) || ApiClient.getAuthToken() != bearer) return@LaunchedEffect
            if (authorised) { RideNotificationNavigation.open(selected); onOpen(role == "DRIVER") }
            else problem = "This request has changed or is no longer available to your account. Refresh your trips or calls."
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { problem = "Could not open the ride update. Check your connection and refresh your trips." }
    }
    if (userId == null || role != selected.scope || problem != null) AlertDialog(onDismissRequest = RideNotificationNavigation::dismiss,
        title = { Text("Passo ride update") }, text = { Text(problem ?: "Sign in to the ${if (selected.scope == "DRIVER") "driver" else "passenger"} account that received this update to view the trip.") },
        confirmButton = { TextButton(onClick = RideNotificationNavigation::dismiss) { Text("OK") } })
}
