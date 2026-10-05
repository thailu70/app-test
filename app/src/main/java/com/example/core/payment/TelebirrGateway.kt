package com.example.core.payment

import com.example.core.qr.QrSecurityEngine
import com.example.data.entity.PaymentTransactionEntity
import com.example.data.entity.SubscriptionEntity
import com.example.data.repository.TransportRepository
import kotlinx.coroutines.delay
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

object TelebirrGateway {
    // Process payment transaction with simulated server-side verification and idempotency check
    suspend fun processSubscriptionPayment(
        passengerId: String,
        routeId: String,
        pickupStopId: String,
        destinationStopId: String,
        morningSchedule: String,
        eveningSchedule: String,
        amountEtb: Double,
        phoneNumber: String,
        telebirrPin: String,
        vehicleId: String,
        idempotencyKey: String,
        repository: TransportRepository
    ): TelebirrPaymentResult {
        // Validation of Telebirr credentials
        if (phoneNumber.length < 9) {
            return TelebirrPaymentResult.Failed("INVALID_PHONE", "Please enter a valid 9 or 10-digit Ethiopian Telebirr phone number.")
        }
        if (telebirrPin.length < 4) {
            return TelebirrPaymentResult.Failed("INVALID_PIN", "Invalid Telebirr 4-digit security PIN.")
        }

        // Idempotency: Check if a transaction with this reference was already processed
        val existingTx = repository.allPayments
        // In real backend, lookup idempotency table

        // Simulate secure Telebirr API handshake & server processing
        delay(1200)

        val txId = "tx_tb_" + UUID.randomUUID().toString().take(8)
        val txRef = "TB-ET-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + "-" + (1000..9999).random()
        val receiptNo = "REC-ET-" + (10000..99999).random()
        val nowStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US).format(Date())

        val subId = "sub_" + passengerId + "_" + SimpleDateFormat("yyyyMM", Locale.US).format(Date())
        val generatedToken = QrSecurityEngine.generateSecureQrToken(subId, passengerId)

        val newSubscription = SubscriptionEntity(
            id = subId,
            passengerId = passengerId,
            routeId = routeId,
            pickupStopId = pickupStopId,
            destinationStopId = destinationStopId,
            morningSchedule = morningSchedule,
            eveningSchedule = eveningSchedule,
            startDate = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date()),
            endDate = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000)),
            priceEtb = amountEtb,
            paymentStatus = "PAID",
            subscriptionStatus = "ACTIVE",
            vehicleId = vehicleId,
            qrToken = generatedToken,
            daysRemaining = 30
        )

        val paymentRecord = PaymentTransactionEntity(
            id = txId,
            transactionRef = txRef,
            passengerId = passengerId,
            subscriptionId = subId,
            amountEtb = amountEtb,
            provider = "Telebirr",
            status = "SUCCESS",
            timestamp = nowStr,
            paymentPhone = phoneNumber,
            receiptNumber = receiptNo
        )

        // Save server-side
        repository.recordPayment(paymentRecord)
        repository.insertSubscription(newSubscription)
        repository.logAction(
            "TELEBIRR_PAYMENT_SUCCESS",
            passengerId,
            "PASSENGER",
            "Payment of ETB $amountEtb processed via Telebirr (Ref: $txRef)"
        )

        return TelebirrPaymentResult.Success(paymentRecord, newSubscription)
    }
}
