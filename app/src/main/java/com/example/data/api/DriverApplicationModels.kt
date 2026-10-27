package com.example.data.api

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DriverApplicationInput(
    val fullName: String, val phone: String, val password: String,
    val licenseNumber: String, val serviceType: String = "DAILY_COMMUTE",
    val role: String = "DRIVER", val companyId: String? = null,
    val assignedVehiclePlate: String? = null, val vehicleModel: String? = null,
    val vehicleType: String? = null, val routeChoice: String? = null,
    val proposedDeparture: String? = null, val proposedDestination: String? = null,
    val proposedMonthlyFeeEtb: Double? = null, val proposedMonthlyFeeBasis: String? = null
)

@JsonClass(generateAdapter = true)
data class DriverApplicationAccount(
    val id: String, val role: String, val fullName: String, val phone: String,
    val status: String, val licenseNumber: String = "", val companyName: String = "",
    val companyId: String = "", val assignedVehiclePlate: String = "",
    val vehicleModel: String = "", val vehicleType: String = "", val serviceType: String = "DAILY_COMMUTE"
)

@JsonClass(generateAdapter = true)
data class DriverApplicationProfile(
    val emergencyContactName: String = "", val emergencyContactPhone: String = "", val vehicleColor: String = ""
)

@JsonClass(generateAdapter = true)
data class DriverApplicationDocument(val kind: String, val uploadedAt: String? = null)

@JsonClass(generateAdapter = true)
data class DriverRoutePreference(
    val mode: String = "", val routeId: String = "", val routeName: String = "",
    val departure: String = "", val destination: String = "",
    val proposedMonthlyFeeEtb: Double? = null, val feeLocked: Boolean = false,
    val vehicleType: String = ""
)

@JsonClass(generateAdapter = true)
data class DriverApplicationProgress(
    val acceptanceRequired: Boolean = true, val acceptanceStatus: String = "PENDING",
    val canVerifyAndUpload: Boolean = false, val reviewStatus: String? = null,
    val reviewNote: String = "", val submittedAt: String? = null,
    val reviewReady: Boolean = false, val reviewMissing: List<String> = emptyList(),
    val initialMissing: List<String> = emptyList(), val canInitiallySubmit: Boolean = false,
    val requiredDocuments: List<String> = emptyList(), val documents: List<DriverApplicationDocument> = emptyList(),
    val profile: DriverApplicationProfile = DriverApplicationProfile(),
    val routePreference: DriverRoutePreference = DriverRoutePreference()
)

@JsonClass(generateAdapter = true)
data class DriverApplicationResponse(
    val success: Boolean, val account: DriverApplicationAccount? = null,
    val onboarding: DriverApplicationProgress? = null, val token: String? = null,
    val error: String? = null, val message: String? = null
) {
    val approved: Boolean get() = account?.status == "ACTIVE" && onboarding?.reviewStatus == "APPROVED"
    val editable: Boolean get() = onboarding?.canVerifyAndUpload == true &&
        onboarding?.reviewStatus !in setOf("SUBMITTED", "APPROVED")
}

@JsonClass(generateAdapter = true)
data class CommutePlace(val label: String, val aliases: List<String> = emptyList())
@JsonClass(generateAdapter = true)
data class CommuteSuggestionsResponse(val success: Boolean, val places: List<CommutePlace> = emptyList())
@JsonClass(generateAdapter = true)
data class DriverQuotePrice(
    val mode: String, val amountEtb: Double? = null,
    val passengerCapacity: Int, val passengerMonthlyFareEtb: Double? = null,
    val basis: String = "PER_VEHICLE"
)
@JsonClass(generateAdapter = true)
data class DriverQuoteMatch(val status: String, val routeId: String? = null, val routeName: String? = null)
@JsonClass(generateAdapter = true)
data class DriverQuoteResponse(val success: Boolean, val routeMatch: DriverQuoteMatch, val price: DriverQuotePrice)

@JsonClass(generateAdapter = true)
data class AccountRecoveryRequest(val role: String, val phone: String)
@JsonClass(generateAdapter = true)
data class AccountRecoveryReset(val role: String, val phone: String, val code: String, val newPassword: String)
@JsonClass(generateAdapter = true)
data class AccountRecoveryResponse(val success: Boolean, val message: String? = null,
    val retryAfterSeconds: Int = 60, val error: String? = null)
