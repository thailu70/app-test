package com.example.data.repository

import com.example.data.api.*
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

data class DriverApplicationState(
    val application: DriverApplicationResponse? = null, val busy: Boolean = false,
    val error: String? = null, val message: String? = null
)

/** Recruitment credentials remain in memory and can only be sent to onboarding endpoints. */
class DriverApplicationRepository(private val api: RoutePassApiService) {
    private var token: String? = null
    private var generation = 0
    private val gate = Mutex()
    private val mutable = MutableStateFlow(DriverApplicationState())
    val state = mutable.asStateFlow()

    fun close() { generation++; token = null; mutable.value = DriverApplicationState() }
    private fun authorization(): String = "Bearer " + (token ?: error("Sign in to your driver application again."))

    private suspend fun action(message: String? = null, newSession: Boolean = false,
        block: suspend () -> Response<DriverApplicationResponse>) = gate.withLock {
        val operation = generation
        mutable.value = mutable.value.copy(busy = true, error = null, message = null)
        try {
            val response = block()
            val result = checked(response)
            require(result.account?.role == "DRIVER" && result.onboarding != null) { "The driver application response is incomplete. Please try again." }
            if (newSession) require(!result.token.isNullOrBlank()) { "Sign-in could not be completed. Please try again." }
            if (operation == generation) {
                if (newSession) token = result.token
                mutable.value = DriverApplicationState(result.copy(token = null), message = message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (operation == generation) mutable.value = mutable.value.copy(error = failure.message ?: "Could not update your application. Try again.")
        } finally {
            if (operation == generation) mutable.value = mutable.value.copy(busy = false)
        }
    }

    suspend fun register(input: DriverApplicationInput) = action("Your application was received.", true) { api.registerDriverApplication(input) }
    suspend fun login(phone: String, password: String) = action(newSession = true) { api.loginDriverApplication(LoginRequest(phone.trim(), password, "DRIVER")) }
    suspend fun refresh() = action { api.driverApplication(authorization()) }
    suspend fun saveProfile(name: String, phone: String, color: String) = action("Your details were saved.") {
        check(mutable.value.application?.editable == true) { "Your application is not open for changes. Refresh its status first." }
        api.updateDriverApplication(authorization(), mapOf("emergencyContactName" to name.trim(), "emergencyContactPhone" to phone.trim(), "vehicleColor" to color.trim()))
    }
    suspend fun saveRoute(departure: String, destination: String, vehicleType: String, fee: Double) = action("Your route proposal was saved.") {
        val application = mutable.value.application ?: error("Sign in to your application again.")
        check(application.onboarding?.acceptanceRequired == true && application.onboarding?.reviewStatus !in setOf("SUBMITTED", "APPROVED")) { "Your application is not open for route changes." }
        api.updateDriverApplication(authorization(), mapOf("routeChoice" to "PROPOSED", "proposedDeparture" to departure.trim(),
            "proposedDestination" to destination.trim(), "vehicleType" to vehicleType, "proposedMonthlyFeeEtb" to fee, "proposedMonthlyFeeBasis" to "PER_VEHICLE"))
    }
    suspend fun savePreferredRoute(departure: String, destination: String, vehicleType: String) = action("Your preferred route was saved.") {
        val application = mutable.value.application ?: error("Sign in to your application again.")
        check(application.account?.serviceType in setOf("GROUP_TRAVEL", "CARGO") && application.onboarding?.reviewStatus !in setOf("SUBMITTED", "APPROVED")) { "Your application is not open for route changes." }
        require(departure.trim().length >= 2 && destination.trim().length >= 2) { "Enter your preferred departure and destination." }
        api.updateDriverApplication(authorization(), mapOf("routeChoice" to "PROPOSED", "proposedDeparture" to departure.trim(),
            "proposedDestination" to destination.trim(), "vehicleType" to vehicleType))
    }
    suspend fun upload(kind: String, bytes: ByteArray, mime: String) = action("Your document was uploaded.") {
        check(mutable.value.application?.editable == true) { "Document uploads open after your application is accepted." }
        require(bytes.isNotEmpty() && bytes.size <= 5 * 1024 * 1024) { "Each document must be between 1 byte and 5 MB." }
        require(mime in setOf("image/jpeg", "image/png", "application/pdf") && (kind != "PHOTO" || mime != "application/pdf")) { "Choose a JPEG or PNG photo, or a PDF for other documents." }
        api.uploadDriverApplicationDocument(authorization(), kind, bytes.toRequestBody(mime.toMediaType()))
    }
    suspend fun submit() = action("Your application was sent for review.") { api.submitDriverApplication(authorization()) }

    suspend fun suggestions(field: String, query: String, departure: String = ""): List<CommutePlace> {
        val response = api.driverCommuteSuggestions(field, query.trim(), departure.trim())
        if (!response.isSuccessful || response.body()?.success != true) error(errorMessage(response))
        return response.body()?.places.orEmpty()
    }
    suspend fun quote(departure: String, destination: String, type: String): DriverQuoteResponse {
        val response = api.driverCommuteQuote(departure.trim(), destination.trim(), type)
        if (!response.isSuccessful || response.body()?.success != true) error(errorMessage(response))
        return response.body() ?: error("Could not load the monthly vehicle fee. Try again.")
    }

    private fun checked(response: Response<DriverApplicationResponse>): DriverApplicationResponse {
        val body = response.body()
        if (!response.isSuccessful || body?.success != true) error(body?.error ?: errorMessage(response))
        return body
    }
    private fun errorMessage(response: Response<*>): String {
        val text = response.errorBody()?.string().orEmpty()
        val error = runCatching { Moshi.Builder().build().adapter(Map::class.java).fromJson(text)?.get("error") as? String }.getOrNull()
        return error?.takeIf { it.isNotBlank() } ?: when (response.code()) {
            401 -> "Your application session expired. Sign in again."
            413 -> "Each document must be 5 MB or smaller."
            429 -> "Too many attempts. Please wait before trying again."
            else -> "Could not update your driver application. Check your connection and try again."
        }
    }
}
