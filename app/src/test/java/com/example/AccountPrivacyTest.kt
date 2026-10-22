package com.example

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import com.example.core.localization.AppLanguage
import com.example.data.api.*
import com.example.ui.screens.profile.AccountPrivacyScreen
import com.example.ui.theme.MyApplicationTheme
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.POST
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.coroutines.resume

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountPrivacyTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Api : AccountPrivacyApi {
        var eligibilityCalls = 0
        var deleteCalls = 0
        var lastRequest: AccountDeletionRequest? = null
        var eligibility = Response.success(AccountDeletionEligibilityResponse(true, AccountDeletionEligibility(true,
            consequences = listOf("Sign-in access ends."), retainedCategories = listOf("Payment and fraud-prevention records"))))
        var result: Response<AccountDeletionResponse> = error(401, "Current password is incorrect.")
        var pauseDelete = false
        var continuation: Continuation<Response<AccountDeletionResponse>>? = null
        override suspend fun deletionEligibility(): Response<AccountDeletionEligibilityResponse> { eligibilityCalls++; return eligibility }
        override suspend fun deleteAccount(body: AccountDeletionRequest): Response<AccountDeletionResponse> {
            deleteCalls++; lastRequest = body
            return if (pauseDelete) suspendCoroutine { continuation = it } else result
        }
        companion object {
            fun <T> error(code: Int, text: String): Response<T> = Response.error(code, "{\"error\":\"$text\"}".toResponseBody("application/json".toMediaType()))
        }
    }
    private fun show(api: Api, onBack: () -> Unit = {}, onClosed: (String) -> Unit = {}) {
        compose.setContent { MyApplicationTheme { AccountPrivacyScreen(AppLanguage.ENGLISH, onBack, onClosed, api) } }
        compose.waitForIdle()
    }
    private fun fillAndConfirm() {
        compose.onNodeWithTag("privacy_password").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithTag("privacy_confirmation").performScrollTo().performTextInput("DELETE")
        compose.onNodeWithTag("privacy_delete").performScrollTo().performClick()
    }

    @Test fun `load failure retries and authoritative blockers prevent any delete request`() {
        val api = Api().apply { eligibility = Api.error(503, "Service unavailable.") }
        show(api)
        compose.onNodeWithTag("privacy_error").assertTextEquals("Service unavailable.")
        api.eligibility = Response.success(AccountDeletionEligibilityResponse(true, AccountDeletionEligibility(false,
            blockers = listOf(AccountDeletionBlocker("ACTIVE_TRIP", "Complete your active trip first.")))))
        compose.onNodeWithTag("privacy_retry").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("privacy_blocker").performScrollTo().assertTextEquals("Complete your active trip first.")
        compose.onNodeWithTag("privacy_password").assertDoesNotExist()
        compose.onNodeWithTag("privacy_delete").assertDoesNotExist()
        assertEquals(2, api.eligibilityCalls)
        assertEquals(0, api.deleteCalls)
    }

    @Test fun `password reauthentication failure keeps account open and clears secret after final confirmation`() {
        val api = Api()
        var closed: String? = null
        show(api, onClosed = { closed = it })
        compose.onNodeWithTag("privacy_delete").performScrollTo().assertIsNotEnabled()
        fillAndConfirm()
        assertEquals(0, api.deleteCalls)
        compose.onNodeWithTag("privacy_confirm_final").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("privacy_error").performScrollTo().assertTextEquals("Current password is incorrect.")
        compose.onNodeWithTag("privacy_password").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals("fixture-password", api.lastRequest!!.password)
        assertEquals("DELETE", api.lastRequest!!.confirmation)
        assertNull(closed)
    }

    @Test fun `pending file cleanup is reported honestly and back cannot abandon an in-flight closure`() {
        val api = Api().apply { pauseDelete = true }
        var closed: String? = null
        var backs = 0
        show(api, onBack = { backs++ }, onClosed = { closed = it })
        fillAndConfirm()
        compose.onNodeWithTag("privacy_confirm_final").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("privacy_back").assertIsNotEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals(0, backs)
        assertNull(closed)
        compose.runOnIdle {
            api.continuation!!.resume(Response.success(AccountDeletionResponse(true,
                AccountDeletionResult("delete-fixture", "PENDING_CLEANUP", true, listOf("Financial records")))))
        }
        compose.waitForIdle()
        assertTrue(closed!!.contains("cleanup is still pending"))
        assertTrue(closed!!.contains("retained"))
        assertFalse(closed!!.contains("all data"))
    }

    @Test fun `account API uses current-user deletion path and exact explicit reauthentication body`() {
        assertEquals("api/account/deletion", AccountPrivacyApi::class.java.getDeclaredMethod("deletionEligibility", Continuation::class.java).getAnnotation(GET::class.java)!!.value)
        assertEquals("api/account/deletion", AccountPrivacyApi::class.java.getDeclaredMethod("deleteAccount", AccountDeletionRequest::class.java, Continuation::class.java).getAnnotation(POST::class.java)!!.value)
        val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
        val requestJson = moshi.adapter(AccountDeletionRequest::class.java).toJson(AccountDeletionRequest("fixture-password"))
        val json = org.json.JSONObject(requestJson)
        assertEquals(setOf("password", "confirmation"), json.keys().asSequence().toSet())
        assertEquals("DELETE", json.getString("confirmation"))
        val response = moshi.adapter(AccountDeletionResponse::class.java).fromJson("""{"success":true,"deletion":{"id":"d1","status":"COMPLETED_WITH_RETENTION","sessionRevoked":true,"retainedCategories":["Financial records"]}}""")!!
        assertTrue(response.deletion!!.sessionRevoked)
        assertEquals(listOf("Financial records"), response.deletion!!.retainedCategories)
    }
}
