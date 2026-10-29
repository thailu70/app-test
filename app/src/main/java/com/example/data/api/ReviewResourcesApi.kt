package com.example.data.api

import retrofit2.Response
import retrofit2.http.GET

data class ReviewBookingAccess(val id: String, val bookingSecret: String)
data class ReviewRequestAccess(val id: String, val requestSecret: String, val kind: String)
data class ReviewResourcesResponse(val success: Boolean = false, val environment: String? = null,
    val groupBooking: ReviewBookingAccess? = null, val intercityBooking: ReviewBookingAccess? = null,
    val requests: List<ReviewRequestAccess> = emptyList())

interface ReviewResourcesApi {
    @GET("api/review/resources") suspend fun resources(): Response<ReviewResourcesResponse>
}
