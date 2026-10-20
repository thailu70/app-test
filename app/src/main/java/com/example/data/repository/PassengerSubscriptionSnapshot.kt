package com.example.data.repository

import com.example.data.entity.SubscriptionEntity

/** Live, session-scoped assignment metadata. It is deliberately not an offline Room record. */
data class PassengerStopAssignment(
    val passengerId: String,
    val subscriptionId: String,
    val routeId: String,
    val pickupStopId: String,
    val destinationStopId: String,
    // Null means an older server omitted the field; empty means the server could not resolve it.
    val pickupStopName: String? = null,
    val pickupStopNameAm: String? = null,
    val destinationStopName: String? = null,
    val destinationStopNameAm: String? = null
) {
    fun matches(subscription: SubscriptionEntity?): Boolean = subscription != null &&
        passengerId == subscription.passengerId && subscriptionId == subscription.id &&
        routeId == subscription.routeId && pickupStopId == subscription.pickupStopId &&
        destinationStopId == subscription.destinationStopId
}

/** The pass and its display names originate from the same successful status response. */
data class PassengerSubscriptionSnapshot(
    val subscription: SubscriptionEntity? = null,
    val stopAssignment: PassengerStopAssignment? = null
)
