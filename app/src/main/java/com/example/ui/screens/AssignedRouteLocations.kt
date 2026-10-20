package com.example.ui.screens

import com.example.core.localization.AppLanguage
import com.example.data.entity.RouteStopEntity

/** Presentation values from the assigned route only; never substitute a different route's stops. */
internal data class AssignedRouteLocations(val departure: String?, val destination: String?)

internal fun assignedRouteLocations(
    routeId: String?,
    routeName: String?,
    stops: List<RouteStopEntity>,
    language: AppLanguage,
    pickupStopId: String? = null,
    destinationStopId: String? = null,
    origin: String? = null,
    destination: String? = null,
    inbound: Boolean = false
): AssignedRouteLocations {
    val ordered = stops.filter { !routeId.isNullOrBlank() && it.routeId == routeId }.sortedBy { it.stopOrder }
    fun RouteStopEntity.locationName(): String? =
        (if (language == AppLanguage.AMHARIC) stopNameAm.takeIf { it.isNotBlank() } else null)
            ?: stopName.takeIf { it.isNotBlank() }

    // Older routes sometimes store their two endpoints in the route name and have no stop rows.
    // Split only recognizable route separators, retaining hyphens within a place name.
    val namedEndpoints = routeName.orEmpty().split(Regex("\\s*(?:→|->|–|—)\\s*|\\s+-\\s+"))
        .map { it.trim() }.takeIf { it.size >= 2 && it.all(String::isNotBlank) }
    val first = origin?.trim()?.takeIf(String::isNotBlank)
        ?: ordered.firstOrNull()?.locationName() ?: namedEndpoints?.first()
    val last = destination?.trim()?.takeIf(String::isNotBlank)
        ?: ordered.lastOrNull()?.takeIf { ordered.size > 1 }?.locationName() ?: namedEndpoints?.last()

    // A nonempty personal stop ID must resolve to that exact stop. An unrelated endpoint would
    // conceal an assignment or refresh problem and could direct a passenger to the wrong place.
    val pickup = if (pickupStopId.isNullOrBlank()) first else ordered.find { it.id == pickupStopId }?.locationName()
    val dropoff = if (destinationStopId.isNullOrBlank()) last else ordered.find { it.id == destinationStopId }?.locationName()
    return if (inbound) AssignedRouteLocations(dropoff, pickup) else AssignedRouteLocations(pickup, dropoff)
}
