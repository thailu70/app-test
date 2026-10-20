package com.example.ui.screens.passenger

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.data.entity.SubscriptionEntity
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlinx.coroutines.delay

private const val DAY_MILLIS = 86_400_000.0

/** Display only. The server still decides subscription and boarding eligibility. */
internal fun subscriptionDaysLeft(subscription: SubscriptionEntity?, now: Long): Int? {
    if (subscription == null) return null
    if (subscription.paymentStatus != "PAID" || subscription.subscriptionStatus !in setOf("ACTIVE", "EXPIRING")) return 0
    val expiry = displayedQrExpiry(subscription) ?: parseSubscriptionExpiry(subscription.endDate) ?: return null
    return ceil((expiry.toDouble() - now.toDouble()) / DAY_MILLIS).toInt().coerceAtLeast(0)
}

private fun displayedQrExpiry(subscription: SubscriptionEntity): Long? {
    val parts = subscription.qrToken.split(':')
    if (parts.size != 6 || parts[0] != "RP1" || parts[1] != subscription.id ||
        parts[2] != subscription.passengerId || parts[3] != subscription.routeId ||
        !Regex("(?:[a-fA-F0-9]{16}|[a-fA-F0-9]{64})").matches(parts[5])) return null
    // This reads an already-issued server token for display, without granting it any authority.
    // Signature verification and every boarding acceptance remain on the backend.
    return parts[4].toLongOrNull()?.takeIf { it > 0 }
}

internal fun parseSubscriptionExpiry(raw: String): Long? {
    val value = raw.trim()
    fun strict(pattern: String, input: String): Long? {
        val parser = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
            isLenient = false
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val position = ParsePosition(0)
        return parser.parse(input, position)?.takeIf { position.index == input.length }?.time
    }
    // Date-only expiry is UTC midnight, matching existing server QR and activation semantics.
    if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return strict("yyyy-MM-dd", value)
    if (Regex("\\d{1,2} [A-Za-z]{3} \\d{4}").matches(value)) return strict("dd MMM yyyy", value)
    val iso = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?(Z|[+-]\\d{2}:?\\d{2})$")
        .matchEntire(value) ?: return null
    val zone = iso.groupValues[3].let { if (it == "Z") "+0000" else it.replace(":", "") }
    val milliseconds = iso.groupValues[2].padEnd(3, '0').take(3)
    return strict("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "${iso.groupValues[1]}.$milliseconds$zone")
}

@Composable
internal fun rememberSubscriptionDaysLeft(subscription: SubscriptionEntity?): Int? {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(subscription?.id, subscription?.endDate, subscription?.qrToken) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000)
        }
    }
    return subscriptionDaysLeft(subscription, now)
}
