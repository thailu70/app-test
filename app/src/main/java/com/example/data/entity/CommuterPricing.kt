package com.example.data.entity

import java.util.Locale

fun commuterServiceClass(value: String?): String = when (val normalized = value?.trim()?.uppercase(Locale.ROOT)) {
    null, "" -> "ECONOMY"
    "DELUXE", "DELUX" -> "DELUXE"
    else -> normalized
}

private fun Double?.positiveFare(): Double? = this?.takeIf { it.isFinite() && it > 0.0 }

/** An explicit server fare map replaces the legacy route fare, including unavailable classes. */
fun RouteEntity.monthlyFareForClass(serviceClass: String): Double? {
    val tier = commuterServiceClass(serviceClass)
    if (tier !in listOf("ECONOMY", "DELUXE", "VIP")) return null
    if (!serviceFaresConfigured) return basePriceEtb.takeIf { tier == "ECONOMY" }?.positiveFare()
    return when (tier) {
        "DELUXE" -> deluxeMonthlyFareEtb
        "VIP" -> vipMonthlyFareEtb
        else -> economyMonthlyFareEtb
    }.positiveFare()
}

fun RouteEntity.lowestMonthlyFare(): Double? =
    listOfNotNull(monthlyFareForClass("ECONOMY"), monthlyFareForClass("DELUXE"), monthlyFareForClass("VIP")).minOrNull()

/** Display the server's passenger offer, rather than a different driver's route minimum. */
fun commuterCheckoutFare(subscription: SubscriptionEntity?, route: RouteEntity?): Double? {
    if (subscription != null && route != null && subscription.routeId != route.id) return null
    if (subscription?.renewalPriceEtb != null) return subscription.renewalPriceEtb.positiveFare()
    return subscription?.priceEtb.positiveFare()
        ?: route?.monthlyFareForClass(subscription?.serviceClass ?: "ECONOMY")
}
