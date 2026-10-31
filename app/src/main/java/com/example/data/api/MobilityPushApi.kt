package com.example.data.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class MobilityPushInput(val token: String, val latitude: Double? = null, val longitude: Double? = null)
@JsonClass(generateAdapter = true)
data class MobilityPushResponse(val success: Boolean, val registered: Boolean = false, val enabled: Boolean = false, val error: String? = null)

interface MobilityPushApi {
    @POST("api/mobility/driver/push-token") suspend fun registerDriver(@Body input: MobilityPushInput): Response<MobilityPushResponse>
    @HTTP(method = "DELETE", path = "api/mobility/driver/push-token", hasBody = true)
    suspend fun removeDriver(@Body input: MobilityPushInput): Response<MobilityPushResponse>
    @POST("api/mobility/requests/{id}/push-token") suspend fun registerRequest(@Path("id") id: String,
        @Header("X-Request-Secret") secret: String, @Body input: MobilityPushInput): Response<MobilityPushResponse>
    @HTTP(method = "DELETE", path = "api/mobility/requests/{id}/push-token", hasBody = true)
    suspend fun removeRequest(@Path("id") id: String, @Header("X-Request-Secret") secret: String,
        @Body input: MobilityPushInput): Response<MobilityPushResponse>
}
