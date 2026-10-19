package com.example.data.api

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming

data class ProfileDocument(
    val kind: String = "",
    val contentType: String = "",
    val size: Long = 0,
    val uploadedAt: String = "",
    val url: String = ""
)

data class CompletionProfile(
    val userId: String = "",
    val role: String = "",
    val required: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val complete: Boolean = false,
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val vehicleColor: String = "",
    val photoUrl: String? = null,
    val documents: List<ProfileDocument> = emptyList()
)

data class ProfileResponse(
    val success: Boolean = false,
    val profile: CompletionProfile? = null,
    val error: String? = null
)

data class ProfileUpdateInput(
    val emergencyContactName: String,
    val emergencyContactPhone: String,
    val vehicleColor: String
)

interface ProfileApi {
    @GET("api/profiles/me")
    suspend fun getMyProfile(): Response<ProfileResponse>

    @PATCH("api/profiles/me")
    suspend fun updateMyProfile(@Body input: ProfileUpdateInput): Response<ProfileResponse>

    @PUT("api/profiles/me/documents/{kind}")
    suspend fun uploadDocument(@Path("kind") kind: String, @Body content: RequestBody): Response<ProfileResponse>

    @Streaming
    @GET("api/profiles/{userId}/photo")
    suspend fun getPhoto(@Path("userId") userId: String): Response<ResponseBody>
}
