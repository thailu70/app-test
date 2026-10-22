package com.example.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

data class AccountDeletionBlocker(val code: String = "", val message: String = "")
data class AccountDeletionEligibility(
    val eligible: Boolean = false,
    val blockers: List<AccountDeletionBlocker> = emptyList(),
    val consequences: List<String> = emptyList(),
    val retainedCategories: List<String> = emptyList(),
    val privacyUrl: String = "",
    val requestUrl: String = "",
    val supportEmail: String = ""
)
data class AccountDeletionEligibilityResponse(val success: Boolean = false, val deletion: AccountDeletionEligibility? = null, val error: String? = null)
data class AccountDeletionRequest(val password: String, val confirmation: String = "DELETE")
data class AccountDeletionResult(
    val id: String = "",
    val status: String = "",
    val sessionRevoked: Boolean = false,
    val retainedCategories: List<String> = emptyList()
)
data class AccountDeletionResponse(val success: Boolean = false, val deletion: AccountDeletionResult? = null, val error: String? = null)

interface AccountPrivacyApi {
    @GET("api/account/deletion") suspend fun deletionEligibility(): Response<AccountDeletionEligibilityResponse>
    @POST("api/account/deletion") suspend fun deleteAccount(@Body body: AccountDeletionRequest): Response<AccountDeletionResponse>
}
