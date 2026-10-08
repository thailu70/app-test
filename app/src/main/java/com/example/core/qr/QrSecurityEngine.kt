package com.example.core.qr

import com.example.data.api.ApiClient
import com.example.data.api.ScanCheckinRequest
import com.example.data.entity.SubscriptionEntity
import com.example.data.entity.UserEntity
import com.example.data.repository.TransportRepository

sealed class QrValidationResult {
    data class Valid(
        val passenger: UserEntity,
        val subscription: SubscriptionEntity,
        val stopName: String,
        val destinationName: String,
        val vehiclePlate: String,
        val currentOccupancy: Int = 1,
        val capacityLimit: Int = 24
    ) : QrValidationResult()

    data class Invalid(
        val reason: String,
        val details: String = ""
    ) : QrValidationResult()
}

/**
 * QR Verification Engine.
 * Client-side signing secret REMOVED.
 * All QR signing and verification authorities reside securely on the VPS backend!
 */
object QrSecurityEngine {

    /**
     * Driver scans QR token and sends it directly to VPS backend for verification.
     * The backend validates:
     * - Server signature & expiration
     * - Passenger identity & role
     * - Subscription status (ACTIVE) and payment (PAID)
     * - Route match
     * - Atomic vehicle capacity limit check
     * - Duplicate check-in prevention on current trip
     */
    suspend fun validateToken(
        qrToken: String,
        currentTripId: String,
        currentRouteId: String,
        currentVehicleId: String,
        currentStopName: String,
        driverId: String,
        repository: TransportRepository,
        vehicleCapacity: Int = 24,
        currentPassengerCount: Int = 0
    ): QrValidationResult {
        val trimmedToken = qrToken.trim()

        if (trimmedToken.isBlank()) {
            return QrValidationResult.Invalid("reason_qr_corrupted", "Empty QR token.")
        }

        return try {
            // Call VPS Backend API
            val apiService = ApiClient.getService()
            val response = apiService.scanCheckin(
                ScanCheckinRequest(
                    qrToken = trimmedToken,
                    tripId = currentTripId,
                    vehicleId = currentVehicleId,
                    currentStop = currentStopName
                )
            )

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val passengerInfo = body.passenger
                val occupancy = body.occupancy

                val passengerEntity = UserEntity(
                    id = passengerInfo?.id ?: "usr_commuter",
                    role = "PASSENGER",
                    fullName = passengerInfo?.name ?: "Verified Commuter",
                    phone = passengerInfo?.phone ?: "",
                    email = "",
                    status = "ACTIVE"
                )

                val subEntity = SubscriptionEntity(
                    id = passengerInfo?.subscriptionId ?: "sub_remote",
                    passengerId = passengerEntity.id,
                    routeId = currentRouteId,
                    pickupStopId = currentStopName,
                    destinationStopId = "Route Terminal",
                    morningSchedule = "06:30",
                    eveningSchedule = "17:30",
                    startDate = "",
                    endDate = "",
                    priceEtb = 2500.0,
                    paymentStatus = "PAID",
                    subscriptionStatus = "ACTIVE",
                    vehicleId = currentVehicleId,
                    qrToken = trimmedToken,
                    daysRemaining = passengerInfo?.daysRemaining ?: 30
                )

                // Sync check-in to local repository cache
                repository.recordRemoteCheckIn(
                    tripId = currentTripId,
                    passengerId = passengerEntity.id,
                    passengerName = passengerEntity.fullName,
                    routeId = currentRouteId,
                    stopName = currentStopName,
                    vehicleId = currentVehicleId,
                    driverId = driverId
                )

                QrValidationResult.Valid(
                    passenger = passengerEntity,
                    subscription = subEntity,
                    stopName = currentStopName,
                    destinationName = "Scheduled Terminal",
                    vehiclePlate = "Current Vehicle",
                    currentOccupancy = occupancy?.current ?: (currentPassengerCount + 1),
                    capacityLimit = occupancy?.maxCapacity ?: vehicleCapacity
                )
            } else {
                val errorBody = response.body()
                val statusCode = response.code()
                val statusString = errorBody?.status ?: ""
                val errorMsg = errorBody?.error ?: response.message()

                when {
                  statusString == "DENIED_CAPACITY_FULL" || statusCode == 409 && errorMsg.contains("CAPACITY", ignoreCase = true) -> {
                      QrValidationResult.Invalid("reason_capacity_full", errorMsg)
                  }
                  statusString == "ALREADY_CHECKED_IN" -> {
                      QrValidationResult.Invalid("reason_already_boarded", "Passenger already checked in on this trip.")
                  }
                  statusString == "NOT_SUBSCRIBED" -> {
                      QrValidationResult.Invalid("reason_not_subscribed", "Passenger is not subscribed to this route.")
                  }
                  statusString == "SUBSCRIPTION_EXPIRED" -> {
                      QrValidationResult.Invalid("reason_subscription_expired", "Monthly commuter pass has expired.")
                  }
                  else -> {
                      QrValidationResult.Invalid("reason_qr_corrupted", errorMsg)
                  }
                }
            }
        } catch (e: Exception) {
            // If offline, validate against local cache
            repository.validateLocalQr(
                qrToken = trimmedToken,
                currentTripId = currentTripId,
                currentVehicleId = currentVehicleId,
                currentStopName = currentStopName,
                driverId = driverId,
                vehicleCapacity = vehicleCapacity,
                currentPassengerCount = currentPassengerCount
            )
        }
    }
}
