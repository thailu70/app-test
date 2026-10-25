package com.example

import com.example.data.api.*
import com.example.data.repository.DriverApplicationRepository
import com.example.ui.screens.auth.AuthDraft
import com.example.ui.screens.auth.driverMonthlyFee
import com.example.ui.screens.auth.driverQuoteKey
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response
import retrofit2.http.POST
import retrofit2.http.Header
import java.lang.reflect.Proxy

class DriverApplicationTest {
    private fun snapshot(progress: DriverApplicationProgress = DriverApplicationProgress(reviewStatus = "AWAITING_ACCEPTANCE"), token: String? = "private-scoped-token") = DriverApplicationResponse(true,
        DriverApplicationAccount("application-fixture", "DRIVER", "Fixture Driver", "+251912000111", "PENDING", vehicleType = "MINIVAN_8"), progress, token)
    private fun fake(block: (String, Array<out Any?>?) -> Any): RoutePassApiService = Proxy.newProxyInstance(RoutePassApiService::class.java.classLoader,
        arrayOf(RoutePassApiService::class.java)) { _, method, args -> block(method.name, args) } as RoutePassApiService
    @After fun clearToken() { ApiClient.setAuthToken(null) }

    @Test fun `driver signup uses shared application contract and cannot create an operational session`() = runBlocking {
        ApiClient.setAuthToken("existing-operational-session")
        val input = DriverApplicationInput("Fixture Driver", "0912000111", "fixture-password", "LIC-1",
            assignedVehiclePlate = "FIXTURE-1", vehicleModel = "Minivan", vehicleType = "MINIVAN_8", routeChoice = "PROPOSED",
            proposedDeparture = "Bole", proposedDestination = "CMC", proposedMonthlyFeeEtb = 20000.0, proposedMonthlyFeeBasis = "PER_VEHICLE")
        var captured: DriverApplicationInput? = null
        val applications = DriverApplicationRepository(fake { method, args ->
            assertEquals("registerDriverApplication", method); captured = args!![0] as DriverApplicationInput
            Response.success(snapshot())
        })
        applications.register(input)
        assertEquals(input, captured)
        assertEquals("AWAITING_ACCEPTANCE", applications.state.value.application!!.onboarding!!.reviewStatus)
        assertNull("The private scoped capability is not exposed as UI state", applications.state.value.application!!.token)
        assertFalse(applications.state.value.application!!.approved)
        assertEquals("existing-operational-session", ApiClient.getAuthToken())
        val method = RoutePassApiService::class.java.methods.single { it.name == "registerDriverApplication" }
        assertEquals("api/onboarding/register", method.getAnnotation(POST::class.java).value)
    }

    @Test fun `pending application credentials stay scoped across profile documents submit and approval refresh`() = runBlocking {
        var current = snapshot()
        val calls = mutableListOf<String>()
        val applications = DriverApplicationRepository(fake { method, args ->
            calls += method
            if (method != "loginDriverApplication") assertEquals("Bearer private-scoped-token", args!![0])
            Response.success(current)
        })
        applications.login("0912000111", "fixture-password")
        applications.upload("LICENSE", byteArrayOf(1), "image/jpeg")
        assertEquals(listOf("loginDriverApplication"), calls)
        assertTrue(applications.state.value.error!!.contains("after your application is accepted"))

        current = snapshot(DriverApplicationProgress(canVerifyAndUpload = true, acceptanceStatus = "ACCEPTED", reviewStatus = "DRAFT"), token = null)
        applications.refresh()
        applications.saveProfile("Emergency Name", "0912000222", "Green")
        applications.upload("LICENSE", byteArrayOf(1, 2, 3), "image/jpeg")
        assertTrue(calls.contains("updateDriverApplication")); assertTrue(calls.contains("uploadDriverApplicationDocument"))

        current = current.copy(onboarding = current.onboarding!!.copy(reviewReady = true, reviewStatus = "SUBMITTED"))
        applications.submit()
        assertFalse(applications.state.value.application!!.editable)
        current = current.copy(account = current.account!!.copy(status = "ACTIVE"), onboarding = current.onboarding!!.copy(reviewStatus = "APPROVED"))
        applications.refresh()
        assertTrue(applications.state.value.application!!.approved)
        assertNull("Approval alone cannot mint an operational token", ApiClient.getAuthToken())
        applications.close()
        assertNull(applications.state.value.application)
    }

    @Test fun `corrections retain document access and request headers explicitly use scoped authorization`() = runBlocking {
        val current = snapshot(DriverApplicationProgress(canVerifyAndUpload = true, acceptanceStatus = "ACCEPTED", reviewStatus = "CORRECTIONS_REQUESTED", reviewNote = "Clearer licence needed"))
        var uploads = 0
        val applications = DriverApplicationRepository(fake { method, args ->
            if (method == "uploadDriverApplicationDocument") { assertEquals("Bearer private-scoped-token", args!![0]); uploads++ }
            Response.success(current)
        })
        applications.login("0912000111", "fixture-password")
        assertTrue(applications.state.value.application!!.editable)
        applications.upload("LICENSE", byteArrayOf(1), "image/png")
        assertEquals(1, uploads)
        val method = RoutePassApiService::class.java.methods.single { it.name == "uploadDriverApplicationDocument" }
        assertEquals("Authorization", method.parameterAnnotations[0].filterIsInstance<Header>().single().value)
    }

    @Test fun `missing session capability and wrong account role cannot establish an application session`() = runBlocking {
        for (response in listOf(snapshot(token = null), snapshot().let { it.copy(account = it.account!!.copy(role = "PASSENGER")) })) {
            val applications = DriverApplicationRepository(fake { _, _ -> Response.success(response) })
            applications.login("0912000111", "fixture-password")
            assertNull(applications.state.value.application)
            assertNotNull(applications.state.value.error)
            assertNull(ApiClient.getAuthToken())
        }
    }

    @Test fun `unacceptable photo files and oversized documents never reach the upload endpoint`() = runBlocking {
        var uploads = 0
        val applications = DriverApplicationRepository(fake { method, _ ->
            if (method == "uploadDriverApplicationDocument") uploads++
            Response.success(snapshot(DriverApplicationProgress(canVerifyAndUpload = true, reviewStatus = "DRAFT")))
        })
        applications.login("0912000111", "fixture-password")
        for ((kind, data, mime) in listOf(Triple("PHOTO", byteArrayOf(1), "application/pdf"),
            Triple("LICENSE", ByteArray(5 * 1024 * 1024 + 1), "application/pdf"), Triple("PHOTO", byteArrayOf(1), "image/webp"))) {
            applications.upload(kind, data, mime)
            assertNotNull(applications.state.value.error)
        }
        assertEquals(0, uploads)
    }

    @Test fun `fixed vehicle quote cannot be edited and stale endpoints cannot reuse an old quote`() {
        val draft = AuthDraft(null, null, null).apply { pickup = "Bole"; destination = "CMC"; vehicleType = "MINIVAN_8" }
        draft.quote = DriverQuoteResponse(true, DriverQuoteMatch("EXACT", "route-1", "Bole → CMC"), DriverQuotePrice("FIXED", 20000.0, 8, 2500.0))
        draft.quotedKey = driverQuoteKey(draft); draft.monthlyFee = "1"
        assertEquals(20000.0, driverMonthlyFee(draft)!!, 0.01)
        draft.destination = "Another destination"
        assertNull(driverMonthlyFee(draft))
        draft.quotedKey = driverQuoteKey(draft)
        draft.quote = DriverQuoteResponse(true, DriverQuoteMatch("NONE"), DriverQuotePrice("PROPOSED", null, 8))
        draft.monthlyFee = "35000.50"
        assertEquals(35000.50, driverMonthlyFee(draft)!!, 0.001)
        for (invalid in listOf("0", "-1", "NaN", "1000001", "1.001")) { draft.monthlyFee = invalid; assertNull(driverMonthlyFee(draft)) }
    }

    @Test fun `server snapshot keeps route fee corrections and uploaded document metadata`() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DriverApplicationResponse::class.java)
        val response = adapter.fromJson("""{"success":true,"account":{"id":"fixture","role":"DRIVER","fullName":"Fixture","phone":"+251912000111","status":"PENDING"},"onboarding":{"acceptanceRequired":true,"acceptanceStatus":"ACCEPTED","canVerifyAndUpload":true,"reviewStatus":"CORRECTIONS_REQUESTED","reviewNote":"Replace insurance","requiredDocuments":["LICENSE","INSURANCE"],"documents":[{"kind":"LICENSE","uploadedAt":"2026-10-24T10:00:00Z"}],"profile":{"emergencyContactName":"Contact","emergencyContactPhone":"+251912000222","vehicleColor":"Green"},"routePreference":{"departure":"Bole","destination":"CMC","proposedMonthlyFeeEtb":20000,"feeLocked":true}}}""")!!
        val progress = response.onboarding!!
        assertEquals("Bole", progress.routePreference.departure)
        assertEquals(20000.0, progress.routePreference.proposedMonthlyFeeEtb!!, 0.001)
        assertEquals("LICENSE", progress.documents.single().kind)
        assertEquals("Replace insurance", progress.reviewNote)
        assertTrue(response.editable); assertFalse(response.approved)
    }

    @Test fun `phone validation accepts supported spellings and rejects malformed mobile numbers`() {
        for (phone in listOf("0912000111", "+251912000111", "251912000111", "912000111", "0912 000-111", "+251 (912) 000111")) assertEquals("+251912000111", canonicalAccountPhone(phone))
        for (phone in listOf("1234567", "091200011", "09120001111", "letters", "0112000111")) assertNull(canonicalAccountPhone(phone))
    }
}
