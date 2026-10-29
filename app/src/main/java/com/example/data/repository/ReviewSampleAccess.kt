package com.example.data.repository

import android.content.Context
import com.example.core.environment.AppEnvironment
import com.example.data.api.ApiClient
import com.example.data.api.ReviewResourcesResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicitly requested sample access is never imported into the live capability namespace. */
internal suspend fun importReviewSamples(context: Context, result: ReviewResourcesResponse) {
    check(ApiClient.environment() == AppEnvironment.DEMO && result.success && result.environment == "REVIEW") {
        "Sample access is available only in the demo environment."
    }
    val selected = ApiClient.sessionScope()
    val mobility = AndroidEncryptedIntercityBookingStore(context, "mobility", AppEnvironment.DEMO)
    val intercity = AndroidEncryptedIntercityBookingStore(context, "intercity", AppEnvironment.DEMO)
    withContext(Dispatchers.IO) {
        check(ApiClient.isCurrent(selected)) { "The environment changed. Try again in demo." }
        result.groupBooking?.let { mobility.save(IntercityBookingAccess("booking:${it.id}", it.bookingSecret)) }
        result.intercityBooking?.let { intercity.save(IntercityBookingAccess(it.id, it.bookingSecret)) }
        result.requests.forEach { mobility.save(IntercityBookingAccess("request:${it.id}", it.requestSecret)) }
    }
}
