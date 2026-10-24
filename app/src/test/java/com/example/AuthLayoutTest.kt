package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.room.Room
import com.example.core.localization.AppLanguage
import com.example.data.api.ApiClient
import com.example.data.api.AuthResponse
import com.example.data.api.LoginRequest
import com.example.data.api.OtpResponse
import com.example.data.api.RegisterRequest
import com.example.data.api.RoutePassApiService
import com.example.data.api.TransportCompaniesResponse
import com.example.data.api.TransportCompanyDto
import com.example.data.api.UserDto
import com.example.data.api.VehicleTypeOption
import com.example.data.api.VehicleTypesResponse
import com.example.data.database.TransportDatabase
import com.example.data.entity.RouteEntity
import com.example.data.repository.TransportRepository
import com.example.ui.screens.auth.AuthScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response
import java.io.File
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: TransportDatabase
    private lateinit var model: MainViewModel
    private val logins = mutableListOf<LoginRequest>()
    private val registrations = mutableListOf<RegisterRequest>()
    private val requestedCodes = mutableListOf<Map<String, String>>()
    private val verifiedCodes = mutableListOf<Map<String, String>>()
    private var vehicleCatalog = VehicleTypesResponse(true, listOf(VehicleTypeOption("COACH_53", "Executive Coach", 53)))
    private var vehicleCatalogRequests = 0

    @Before fun createLocalAuthSession() {
        ApiClient.setAuthToken(null)
        val fakeApi = Proxy.newProxyInstance(
            RoutePassApiService::class.java.classLoader,
            arrayOf(RoutePassApiService::class.java)
        ) { _, method, args ->
            when (method.name) {
                "login" -> {
                    logins += args!![0] as LoginRequest
                    // Verify request wiring without opening an authenticated websocket session.
                    Response.error<AuthResponse>(401, """{"success":false,"error":"Test credentials were not accepted."}""".toResponseBody("application/json".toMediaType()))
                }
                "requestOtp" -> {
                    @Suppress("UNCHECKED_CAST") val request = args!![0] as Map<String, String>
                    requestedCodes += request
                    Response.success(OtpResponse(true, message = "Verification code sent by SMS."))
                }
                "verifyOtp" -> {
                    @Suppress("UNCHECKED_CAST") val request = args!![0] as Map<String, String>
                    verifiedCodes += request
                    Response.success(OtpResponse(true, verificationToken = "private-test-phone-verification"))
                }
                "register" -> {
                    val request = args!![0] as RegisterRequest
                    registrations += request
                    if (request.role == "DRIVER") Response.success(AuthResponse(true, token = null,
                        user = UserDto("pending-driver", "DRIVER", request.fullName, request.phone, status = "PENDING")))
                    else Response.error<AuthResponse>(503, """{"success":false,"error":"Test registration recorded."}""".toResponseBody("application/json".toMediaType()))
                }
                "getTransportCompanies" -> Response.success(TransportCompaniesResponse(true, listOf(TransportCompanyDto("company-test", "Test Transport"))))
                "getVehicleTypes" -> { vehicleCatalogRequests++; Response.success(vehicleCatalog) }
                else -> Response.error<Any>(503, "{}".toResponseBody("application/json".toMediaType()))
            }
        } as RoutePassApiService
        compose.runOnIdle {
            val directExecutor = java.util.concurrent.Executor { task -> task.run() }
            database = Room.inMemoryDatabaseBuilder(compose.activity, TransportDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor(directExecutor)
                .setTransactionExecutor(directExecutor)
                .build()
            runBlocking { database.transportDao().insertRoute(RouteEntity(
                "route-auth-test", "Bole → CMC", "ቦሌ → ሲኤምሲ", "Daily commute", "07:15", "17:30", 12.0, 2500.0)) }
            model = MainViewModel(TransportRepository(database.transportDao(), fakeApi))
            compose.activity.viewModelStore.put("auth-layout-test", model)
        }
    }

    @After fun closeLocalAuthSession() {
        compose.runOnIdle { compose.activity.viewModelStore.clear() }
        database.close()
        ApiClient.setAuthToken(null)
    }

    private fun show(role: AppRole, signup: Boolean = false, customRoute: Boolean = false, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyApplicationTheme {
                    AuthScreen(model, initialPortal = role, initialRegisterMode = signup,
                        initialRouteId = if (role == AppRole.PASSENGER && !customRoute) "route-auth-test" else null,
                        initialRouteName = if (customRoute) "Requested commuter route" else if (role == AppRole.PASSENGER) "Bole → CMC" else null,
                        initialServiceTier = "Economy")
                }
            }
        }
        compose.onNodeWithTag("auth_photo_header").assertExists()
        compose.onNodeWithTag("auth_phone_input").assertExists()
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
        }
        val directory = File("build/auth-previews").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertPrimaryInsideViewport() {
        compose.onNodeWithTag("auth_submit_button").assertIsDisplayed()
        val button = compose.onNodeWithTag("auth_submit_button").fetchSemanticsNode().boundsInWindow
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInWindow
        assertTrue("The account action must fit completely inside the portrait screen", button.top >= viewport.top && button.bottom <= viewport.bottom + 1f)
    }

    @Test fun passengerLoginMatchesMockupAndSubmitsPassengerRole() {
        show(AppRole.PASSENGER)
        assertPrimaryInsideViewport()
        capture("01-passenger-login")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { logins.isNotEmpty() }
        assertEquals(LoginRequest("0912345678", "valid-test-password", "PASSENGER"), logins.single())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun driverLoginMatchesMockupAndSubmitsDriverRole() {
        show(AppRole.DRIVER)
        assertPrimaryInsideViewport()
        capture("03-driver-login")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { logins.isNotEmpty() }
        assertEquals(LoginRequest("0912345678", "valid-test-password", "DRIVER"), logins.single())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun passengerSignupFirstStepMatchesMockupAndWaitsForExplicitRegistration() {
        show(AppRole.PASSENGER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").assertIsDisplayed()
        compose.onNodeWithTag("auth_confirm_password_input").assertIsDisplayed()
        compose.onNodeWithTag("auth_verification_notice").assertExists()
        compose.onNodeWithTag("auth_step_2").assertDoesNotExist()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        assertPrimaryInsideViewport()
        capture("02-passenger-sign-up")
        assertTrue(registrations.isEmpty())
        assertTrue(requestedCodes.isEmpty())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun driverSignupFirstStepMatchesMockupAndKeepsIdentityBeforeDriverDetails() {
        show(AppRole.DRIVER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").assertIsDisplayed()
        compose.onNodeWithTag("auth_confirm_password_input").assertIsDisplayed()
        compose.onNodeWithTag("auth_verification_notice").assertExists()
        compose.onNodeWithTag("auth_step_2").assertDoesNotExist()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_driver_license_input").assertDoesNotExist()
        assertPrimaryInsideViewport()
        capture("04-driver-sign-up")
        assertTrue(registrations.isEmpty())
        assertTrue(requestedCodes.isEmpty())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun accountValidationStillPrecedesDriverDetailsWithoutRequestingPhoneVerification() {
        show(AppRole.DRIVER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Driver")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("short")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("short")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitForIdle()
        assertTrue(requestedCodes.isEmpty())
        compose.onNodeWithText("Use a password with at least 10 characters.").assertExists()
        compose.onNodeWithTag("auth_details").assertDoesNotExist()

        compose.onNodeWithTag("auth_password_input").performTextReplacement("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextReplacement("different-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitForIdle()
        assertTrue(requestedCodes.isEmpty())
        compose.onNodeWithText("The passwords do not match.").assertExists()
        compose.onNodeWithTag("auth_details").assertDoesNotExist()

        compose.onNodeWithTag("auth_confirm_password_input").performTextReplacement("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_otp_verify").assertDoesNotExist()
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_driver_license_input").assertExists()
        assertTrue("Continuing to details must not create an account", registrations.isEmpty())
        assertFalse(model.isAuthenticated.value)
        capture("05-driver-details-verification-optional")
    }

    private fun completeDriverDetailsWithoutPhoneVerification() {
        show(AppRole.DRIVER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Driver")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        compose.onNodeWithTag("auth_driver_license_input").performScrollTo().performTextInput("LIC-TEST-24")
        compose.onNodeWithTag("auth_driver_vehicle_plate_input").performScrollTo().performTextInput("AA-TEST-24")
        compose.onNodeWithTag("auth_driver_vehicle_model_input").performScrollTo().performTextInput("Toyota Hiace")
        compose.onNodeWithTag("auth_route_route-auth-test").performScrollTo().performClick()
    }

    @Test fun unverifiedDriverSubmissionPreservesLicenceVehicleAndPendingApproval() {
        completeDriverDetailsWithoutPhoneVerification()
        assertEquals("Registration details use the injected catalog client", 1, vehicleCatalogRequests)
        compose.onNodeWithTag("auth_driver_vehicle_type").performScrollTo()
        compose.onNodeWithText("Executive Coach · 53 seats").assertExists()
        compose.onNodeWithTag("auth_driver_submit").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { registrations.isNotEmpty() &&
                (!model.registrationMessage.value.isNullOrBlank() || !model.authError.value.isNullOrBlank()) }
        } catch (timeout: ComposeTimeoutException) {
            capture("06-driver-submit-timeout")
            throw AssertionError("Driver submission did not finish: requests=${registrations.size}, busy=${model.authBusy.value}, error=${model.authError.value}", timeout)
        }
        assertTrue("Pending driver registration should complete: ${model.authError.value}", !model.registrationMessage.value.isNullOrBlank())
        val request = registrations.single()
        assertEquals("DRIVER", request.role)
        assertEquals("Native Test Driver", request.fullName)
        assertEquals("0912345678", request.phone)
        assertNull("Deferred account verification must not invent a phone proof", request.otpVerificationToken)
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        assertEquals("LIC-TEST-24", request.licenseNumber)
        assertEquals("AA-TEST-24", request.assignedVehiclePlate)
        assertEquals("Toyota Hiace", request.vehicleModel)
        assertEquals("COACH_53", request.vehicleType)
        assertFalse("Pending drivers must not receive an authenticated session", model.isAuthenticated.value)
        assertTrue(logins.isEmpty())
        assertNull(ApiClient.getAuthToken())
    }

    @Test fun successfulEmptyVehicleCatalogDoesNotOfferFallbackTypesOrSubmitAnUnavailableVehicle() {
        vehicleCatalog = VehicleTypesResponse(true, emptyList())
        completeDriverDetailsWithoutPhoneVerification()
        assertEquals(1, vehicleCatalogRequests)
        compose.onNodeWithTag("auth_driver_vehicle_type").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("No vehicle types are currently available. Contact your administrator.").assertExists()
        compose.onNodeWithTag("auth_driver_submit").performScrollTo().performClick()
        compose.onNodeWithText("Choose an available vehicle type.").assertExists()
        assertTrue(registrations.isEmpty())
    }

    @Test fun unverifiedPassengerSubmissionKeepsSelectedDailyRouteAndServerAuthenticationGate() {
        show(AppRole.PASSENGER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Passenger")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        compose.onNodeWithTag("auth_route_route-auth-test").performScrollTo().performClick()
        compose.onNodeWithTag("auth_tier_Economy").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("auth_passenger_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { registrations.isNotEmpty() }
        val request = registrations.single()
        assertEquals("PASSENGER", request.role)
        assertEquals("route-auth-test", request.appliedRouteId)
        assertEquals("Bole → CMC", request.appliedRouteName)
        assertNull("Deferred account verification must not invent a phone proof", request.otpVerificationToken)
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        assertNull(request.licenseNumber)
        assertNull(request.assignedVehiclePlate)
        assertTrue(logins.isEmpty())
        assertFalse("Registration must wait for server acceptance", model.isAuthenticated.value)
    }

    @Test fun changingAccountPhoneRevalidatesIdentityAndUsesLatestPhoneWithoutSms() {
        show(AppRole.PASSENGER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Passenger")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_header_back").performScrollTo().performClick()
        compose.onNodeWithTag("auth_phone_input").performTextReplacement("invalid")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithText("Enter a valid mobile number.").assertExists()
        compose.onNodeWithTag("auth_details").assertDoesNotExist()
        assertTrue(registrations.isEmpty())

        compose.onNodeWithTag("auth_phone_input").performTextReplacement("0912345679")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_route_route-auth-test").performScrollTo().performClick()
        compose.onNodeWithTag("auth_passenger_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { registrations.isNotEmpty() }
        assertEquals("0912345679", registrations.single().phone)
        assertNull(registrations.single().otpVerificationToken)
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun repeatedIdenticalLoginFailuresAlwaysReenableNativeSignIn() {
        show(AppRole.PASSENGER)
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        for (attempt in 1..3) {
            compose.onNodeWithTag("auth_submit_button").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { logins.size == attempt && !model.authBusy.value }
            compose.onNodeWithTag("auth_submit_button").assertIsEnabled()
            assertEquals("Test credentials were not accepted.", model.authError.value)
        }
        assertEquals(3, logins.size)
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun unverifiedPassengerCanRequestCustomCommuteWithoutPretendingAnExistingRouteWasAssigned() {
        show(AppRole.PASSENGER, signup = true, customRoute = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Passenger")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        compose.onNodeWithTag("auth_route_route-auth-test").assertDoesNotExist()
        compose.onNodeWithTag("auth_passenger_pickup_input").performScrollTo().performTextInput("Alem Gena")
        compose.onNodeWithTag("auth_passenger_destination_input").performScrollTo().performTextInput("CMC")
        compose.onNodeWithTag("auth_journey_Two way").performScrollTo().performClick()
        compose.onNodeWithTag("auth_tier_Economy").performScrollTo().performClick()
        compose.onNodeWithTag("auth_passenger_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { registrations.isNotEmpty() }
        val request = registrations.single()
        assertNull("A proposed route must not submit a fabricated route identifier", request.appliedRouteId)
        assertEquals("Requested commuter route | Pickup: Alem Gena | Destination: CMC | Journey: Two way | Tier: Economy", request.appliedRouteName)
        assertNull("Deferred account verification must not invent a phone proof", request.otpVerificationToken)
        assertTrue(requestedCodes.isEmpty())
        assertTrue(verifiedCodes.isEmpty())
        assertEquals("PASSENGER", request.role)
        assertFalse(model.isAuthenticated.value)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xhdpi")
    fun narrowLargeTextKeepsOromoDriverAccountControlsReachableWithoutRequests() {
        show(AppRole.DRIVER, fontScale = 1.2f)
        compose.onNodeWithTag("auth_language_om").performClick()
        assertEquals(AppLanguage.OROMO, model.currentLanguage.value)
        compose.onNodeWithTag("auth_phone_input").performScrollTo().performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performScrollTo().performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performScrollTo().assertIsDisplayed()
        capture("07-small-driver-login-oromo")

        compose.onNodeWithTag("auth_signup_link").performScrollTo().performClick()
        compose.onNodeWithTag("auth_full_name_input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_phone_input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_password_input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_confirm_password_input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_submit_button").performScrollTo().assertIsDisplayed()
        capture("08-small-driver-sign-up-oromo")
        assertEquals(AppLanguage.OROMO, model.currentLanguage.value)
        assertTrue(logins.isEmpty())
        assertTrue(registrations.isEmpty())
        assertTrue(requestedCodes.isEmpty())
        assertFalse(model.isAuthenticated.value)
    }
}
