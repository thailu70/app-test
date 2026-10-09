package com.example.core.payment

import com.example.data.api.ApiClient
import com.example.data.api.TelebirrPayRequest
import com.example.data.entity.PaymentTransactionEntity
import com.example.data.entity.SubscriptionEntity
import com.example.data.repository.TransportRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed class TelebirrPaymentResult {
    data class Success(val transaction: PaymentTransactionEntity, val subscription: SubscriptionEntity) : TelebirrPaymentResult()
    data class Failed(val errorCode: String, val message: String) : TelebirrPaymentResult()
    data object Cancelled : TelebirrPaymentResult()
    data object Timeout : TelebirrPaymentResult()
    data object Duplicate : TelebirrPaymentResult()
}

/**
 * Server-side Telebirr Payment Gateway Client.
 * SECURITY: Never collects or stores Telebirr PIN on Android!
 * Supports Idempotency and server-side subscription activation.
 */
object TelebirrGateway {

    suspend fun processSubscriptionPayment(
        passengerId: String,
        routeId: String,
        pickupStopId: String,
        destinationStopId: String,
        morningSchedule: String,
        eveningSchedule: String,
        amountEtb: Double,
        phoneNumber: String,
        vehicleId: String,
        idempotencyKey: String = "TB-IDEM-${UUID.randomUUID().toString().take(12)}",
        repository: TransportRepository
    ): TelebirrPaymentResult {
        // Validation of Telebirr Phone Number
        val cleanPhone = phoneNumber.trim()
        if (cleanPhone.length < 9) {
            return TelebirrPaymentResult.Failed("INVALID_PHONE", "Please enter a valid Ethiopian Telebirr phone number (+251 / 09...).")
        }

        return try {
            val apiService = ApiClient.getService()
            val request = TelebirrPayRequest(
                routeId = routeId,
                phone = cleanPhone,
                idempotencyKey = idempotencyKey
            )

            val response = apiService.payTelebirr(request, idempotencyKey = idempotencyKey)

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val txnDto = body.transaction
                val subDto = body.subscription

                val txnEntity = PaymentTransactionEntity(
                    id = txnDto?.id ?: "tx_${UUID.randomUUID().toString().take(8)}",
                    transactionRef = txnDto?.referenceNumber ?: "TB-ET-${System.currentTimeMillis()}",
                    passengerId = passengerId,
                    subscriptionId = subDto?.id ?: "sub_$passengerId",
                    amountEtb = txnDto?.amountEtb ?: amountEtb,
                    provider = "Telebirr",
                    status = "SUCCESS",
                    timestamp = txnDto?.date ?: SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()),
                    paymentPhone = cleanPhone,
                    receiptNumber = "REC-ET-${(10000..99999).random()}"
                )

                val subEntity = SubscriptionEntity(
                    id = subDto?.id ?: "sub_${passengerId}_active",
                    passengerId = passengerId,
                    routeId = routeId,
                    pickupStopId = pickupStopId.ifBlank { "stop_atlas" },
                    destinationStopId = destinationStopId.ifBlank { "stop_merkato" },
                    morningSchedule = morningSchedule.ifBlank { "06:30" },
                    eveningSchedule = eveningSchedule.ifBlank { "17:30" },
                    startDate = subDto?.startDate ?: SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()),
                    endDate = subDto?.endDate ?: SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(System.currentTimeMillis() + 30L * 86400000)),
                    priceEtb = amountEtb,
                    paymentStatus = "PAID",
                    subscriptionStatus = "ACTIVE",
                    vehicleId = vehicleId.ifBlank { "3-AA-34921" },
                    qrToken = subDto?.qrToken ?: "",
                    daysRemaining = subDto?.daysRemaining ?: 30
                )

                // Sync transaction and subscription to local Room cache
                repository.cachePaymentAndSubscription(txnEntity, subEntity)

                TelebirrPaymentResult.Success(txnEntity, subEntity)
            } else {
                val errorMsg = response.body()?.error ?: response.message()
                TelebirrPaymentResult.Failed("PAYMENT_REJECTED", errorMsg)
            }
        } catch (e: Exception) {
            // A network failure is not evidence of payment. Never mint a local
            // active subscription or QR pass when the server cannot verify payment.
            TelebirrPaymentResult.Failed(
                "PAYMENT_STATUS_UNKNOWN",
                "Payment could not be verified. Check your connection and subscription status before trying again."
            )
        }
    }
}
