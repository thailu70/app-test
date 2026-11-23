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
import com.example.data.api.DriverApplicationInput
import com.example.data.api.DriverApplicationResponse
import com.example.data.api.DriverApplicationAccount
import com.example.data.api.DriverApplicationProgress
import com.example.data.api.DriverQuoteResponse
import com.example.data.api.DriverQuoteMatch
import com.example.data.api.DriverQuotePrice
import com.example.data.api.CommuteSuggestionsResponse
import com.example.data.api.AccountRecoveryRequest
import com.example.data.api.AccountRecoveryReset
import com.example.data.api.AccountRecoveryResponse
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
    private val otpLogins = mutableListOf<Map<String, String>>()
    private val registrations = mutableListOf<RegisterRequest>()
    private val driverApplications = mutableListOf<DriverApplicationInput>()
    private val applicationLogins = mutableListOf<LoginRequest>()
    private val applicationOtpLogins = mutableListOf<Map<String, String>>()
    private var pendingDriverLogin = false
    private val recoveryRequests = mutableListOf<AccountRecoveryRequest>()
    private val recoveryResets = mutableListOf<AccountRecoveryReset>()
    private var applicationResponse = DriverApplicationResponse(true,
        DriverApplicationAccount("pending-driver", "DRIVER", "Native Test Driver", "+251912345678", "PENDING", vehicleType = "COACH_53"),
        DriverApplicationProgress(canVerifyAndUpload = true, reviewStatus = "DRAFT", requiredDocuments = listOf("LICENSE", "VEHICLE_REGISTRATION", "INSURANCE", "PHOTO")), token = "scoped-application-fixture")
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
                    if (pendingDriverLogin) Response.error<AuthResponse>(403, """{"success":false,"code":"PENDING","error":"Application is pending."}""".toResponseBody("application/json".toMediaType()))
                    else Response.error<AuthResponse>(401, """{"success":false,"error":"Test credentials were not accepted."}""".toResponseBody("application/json".toMediaType()))
                }
                "requestOtp" -> {
                    @Suppress("UNCHECKED_CAST") val request = args!![0] as Map<String, String>
                    requestedCodes += request
                    Response.success(OtpResponse(true, message = "Verification code sent by SMS.", delivery = "SENT"))
                }
                "verifyOtp" -> {
                    @Suppress("UNCHECKED_CAST") val request = args!![0] as Map<String, String>
                    verifiedCodes += request
                    if (request["code"] != "123456") Response.error<OtpResponse>(400,
                        """{"success":false,"error":"Incorrect verification code."}""".toResponseBody("application/json".toMediaType()))
                    else Response.success(OtpResponse(true, verificationToken = "private-test-phone-verification"))
                }
                "loginWithOtp" -> {
                    @Suppress("UNCHECKED_CAST") val request = args!![0] as Map<String, String>
                    otpLogins += request
                    if (pendingDriverLogin && request["role"] == "DRIVER")
                        Response.error<AuthResponse>(403, """{"success":false,"code":"PENDING","error":"Application is pending."}""".toResponseBody("application/json".toMediaType()))
                    else Response.error<AuthResponse>(401, """{"success":false,"error":"Test credentials were not accepted."}""".toResponseBody("application/json".toMediaType()))
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
                "driverCommuteSuggestions" -> Response.success(CommuteSuggestionsResponse(true))
                "driverCommuteQuote" -> Response.success(DriverQuoteResponse(true, DriverQuoteMatch("EXACT", "route-auth-test", "Bole → CMC"), DriverQuotePrice("FIXED", 132500.0, 53, 2500.0)))
                "registerDriverApplication", "registerDriverApplicationWithOtp" -> { driverApplications += args!![0] as DriverApplicationInput; Response.success(applicationResponse) }
                "loginDriverApplication" -> { applicationLogins += args!![0] as LoginRequest; Response.success(applicationResponse) }
                "loginDriverApplicationWithOtp" -> { @Suppress("UNCHECKED_CAST") val request = args!![0] as Map<String, String>; applicationOtpLogins += request; Response.success(applicationResponse) }
                "driverApplication" -> Response.success(applicationResponse.copy(token = null))
                "requestAccountRecovery" -> { recoveryRequests += args!![0] as AccountRecoveryRequest; Response.success(AccountRecoveryResponse(true, "If the account matches, a reset code will be sent.")) }
                "resetAccountPassword" -> { recoveryResets += args!![0] as AccountRecoveryReset; Response.success(AccountRecoveryResponse(true, "Password updated. Sign in with your new password.")) }
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

    private fun show(role: AppRole, signup: Boolean = false, customRoute: Boolean = false, fontScale: Float = 1f, taxi: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MyApplicationTheme {
                    AuthScreen(model, initialPortal = role, initialRegisterMode = signup,
                        initialRouteId = if (role == AppRole.PASSENGER && !customRoute) "route-auth-test" else null,
                        initialRouteName = if (customRoute) "Requested commuter route" else if (role == AppRole.PASSENGER) "Bole → CMC" else null,
                        initialServiceTier = "Economy", initialTaxi = taxi)
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

    @Test fun taxiPassengerCreatesAccountWithoutChoosingDailyRouteOrSubscription() {
        show(AppRole.PASSENGER, signup = true, taxi = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Fixture Taxi Passenger")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithText("Your passenger account").assertExists()
        compose.onNodeWithTag("auth_route_route-auth-test").assertDoesNotExist()
        compose.onNodeWithTag("auth_tier_Economy").assertDoesNotExist()
        confirmAdultForRegistration("auth_passenger_submit")
        compose.onNodeWithTag("auth_passenger_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { registrations.isNotEmpty() }
        val request = registrations.single()
        assertEquals("TAXI", request.serviceType)
        assertNull(request.appliedRouteId); assertNull(request.appliedRouteName); assertNull(request.serviceClass)
    }

    @Test fun meterTaxiDriverDropdownSkipsCommuteProposalAndPreservesVehicleApprovalDetails() {
        completeDriverIdentityWithOtp("Fixture Taxi Driver")
        compose.onNodeWithTag("auth_service").performScrollTo().performClick()
        compose.onNodeWithTag("auth_service_Meter Taxi").performClick()
        compose.onNodeWithTag("auth_driver_pickup_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_driver_preferred_origin").assertDoesNotExist()
        compose.onNodeWithTag("auth_driver_license_input").performScrollTo().performTextInput("LIC-TAXI-QA")
        compose.onNodeWithTag("auth_driver_vehicle_plate_input").performScrollTo().performTextInput("QA-TAXI")
        compose.onNodeWithTag("auth_driver_vehicle_model_input").performScrollTo().performTextInput("Fixture Sedan")
        compose.onNodeWithTag("auth_driver_vehicle_color_input").performScrollTo().performTextInput("Teal")
        compose.onNodeWithTag("auth_driver_contact_name_input").performScrollTo().performTextInput("Fixture Emergency Contact")
        compose.onNodeWithTag("auth_driver_contact_phone_input").performScrollTo().performTextInput("0912000222")
        confirmAdultForRegistration("auth_driver_submit")
        compose.onNodeWithTag("auth_driver_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { driverApplications.isNotEmpty() }
        val request = driverApplications.single()
        assertEquals("TAXI", request.serviceType); assertEquals("LIC-TAXI-QA", request.licenseNumber)
        assertEquals("QA-TAXI", request.assignedVehiclePlate); assertEquals("Fixture Sedan", request.vehicleModel)
        assertNull(request.proposedDeparture); assertNull(request.proposedDestination); assertNull(request.proposedMonthlyFeeEtb)
    }

    private fun assertPrimaryInsideViewport() {
        compose.onNodeWithTag("auth_submit_button").assertIsDisplayed()
        val button = compose.onNodeWithTag("auth_submit_button").fetchSemanticsNode().boundsInWindow
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInWindow
        assertTrue("The account action must fit completely inside the portrait screen", button.top >= viewport.top && button.bottom <= viewport.bottom + 1f)
    }

    private fun applicationNode(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("driver_application_screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun requestAndVerifyLoginCode(phone: String = "0912345678") {
        compose.onNodeWithTag("auth_phone_input").performTextInput(phone)
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        compose.onNodeWithTag("auth_login_otp_input").performTextInput("123456")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { otpLogins.isNotEmpty() && !model.authBusy.value }
    }

    private fun completeDriverIdentityWithOtp(name: String = "Native Test Driver", phone: String = "0912345678") {
        show(AppRole.DRIVER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput(name)
        compose.onNodeWithTag("auth_phone_input").performTextInput(phone)
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        assertEquals(mapOf("phone" to "+251" + phone.removePrefix("0"), "purpose" to "REGISTRATION"), requestedCodes.single())
        compose.onNodeWithTag("auth_driver_registration_otp").performTextInput("123456")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth_details").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun passengerLoginMatchesMockupAndSubmitsPassengerRole() {
        show(AppRole.PASSENGER)
        assertPrimaryInsideViewport()
        capture("01-passenger-login")
        requestAndVerifyLoginCode()
        assertEquals(mapOf("phone" to "+251912345678", "role" to "PASSENGER", "verificationToken" to "private-test-phone-verification"), otpLogins.single())
        assertEquals(mapOf("phone" to "+251912345678", "purpose" to "LOGIN", "code" to "123456"), verifiedCodes.single())
        assertTrue(logins.isEmpty())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun driverLoginMatchesMockupAndSubmitsDriverRole() {
        show(AppRole.DRIVER)
        assertPrimaryInsideViewport()
        capture("03-driver-login")
        requestAndVerifyLoginCode()
        assertEquals(mapOf("phone" to "+251912345678", "role" to "DRIVER", "verificationToken" to "private-test-phone-verification"), otpLogins.single())
        assertEquals(mapOf("phone" to "+251912345678", "purpose" to "LOGIN", "code" to "123456"), verifiedCodes.single())
        assertTrue(logins.isEmpty())
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
        compose.onNodeWithTag("auth_password_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_confirm_password_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_verification_notice").assertExists()
        compose.onNodeWithTag("auth_step_2").assertDoesNotExist()
        compose.onNodeWithTag("auth_otp_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_driver_license_input").assertDoesNotExist()
        assertPrimaryInsideViewport()
        capture("04-driver-sign-up")
        assertTrue(registrations.isEmpty())
        assertTrue(requestedCodes.isEmpty())
        assertFalse(model.isAuthenticated.value)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Driver")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        compose.onNodeWithTag("auth_driver_registration_otp").assertIsDisplayed()
        compose.onNodeWithTag("auth_details").assertDoesNotExist()
    }

    @Test fun driverRegistrationRequiresPhoneOtpBeforeOpeningDriverDetails() {
        show(AppRole.DRIVER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Driver")
        compose.onNodeWithTag("auth_phone_input").performTextInput("123")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitForIdle()
        assertTrue(requestedCodes.isEmpty())
        compose.onNodeWithText("Enter a valid mobile number.").assertExists()
        compose.onNodeWithTag("auth_details").assertDoesNotExist()

        compose.onNodeWithTag("auth_phone_input").performTextReplacement("0912345678")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        compose.onNodeWithTag("auth_driver_registration_otp").performTextInput("000000")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { !model.authBusy.value }
        assertEquals(1, requestedCodes.size)
        assertEquals(listOf("000000"), verifiedCodes.map { it["code"] })
        compose.onNodeWithTag("auth_error").assertExists()
        compose.onNodeWithTag("auth_details").assertDoesNotExist()

        compose.onNodeWithTag("auth_driver_registration_otp").performTextReplacement("123456")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth_details").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf("000000", "123456"), verifiedCodes.map { it["code"] })
        assertEquals("REGISTRATION", verifiedCodes.last()["purpose"])
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_driver_license_input").assertExists()
        assertTrue("Continuing to details must not create an account", registrations.isEmpty())
        assertFalse(model.isAuthenticated.value)
        capture("05-driver-details-after-phone-verification")
    }

    private fun completeVerifiedDriverRegistration() {
        completeDriverIdentityWithOtp()
        compose.onNodeWithTag("auth_details").assertExists()
        compose.onNodeWithTag("auth_driver_license_input").performScrollTo().performTextInput("LIC-TEST-24")
        compose.onNodeWithTag("auth_driver_vehicle_plate_input").performScrollTo().performTextInput("AA-TEST-24")
        compose.onNodeWithTag("auth_driver_vehicle_model_input").performScrollTo().performTextInput("Toyota Hiace")
        compose.onNodeWithTag("auth_driver_vehicle_color_input").performScrollTo().performTextInput("Green")
        compose.onNodeWithTag("auth_driver_contact_name_input").performScrollTo().performTextInput("Emergency Contact")
        compose.onNodeWithTag("auth_driver_contact_phone_input").performScrollTo().performTextInput("0912000222")
        compose.onNodeWithTag("auth_driver_pickup_input").performScrollTo().performTextInput("Bole")
        compose.onNodeWithTag("auth_driver_destination_input").performScrollTo().performTextInput("CMC")
        confirmAdultForRegistration("auth_driver_submit")
    }

    private fun confirmAdultForRegistration(submitTag: String) {
        compose.onNodeWithTag(submitTag).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("auth_adult_confirmation").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag(submitTag).performScrollTo().assertIsEnabled()
    }

    @Test fun passengerAdultAcknowledgmentIsRequiredAndCanBeWithdrawnWithoutCreatingAnAccount() {
        show(AppRole.PASSENGER, signup = true)
        compose.onNodeWithTag("auth_full_name_input").performTextInput("Native Test Passenger")
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_confirm_password_input").performTextInput("valid-test-password")
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.onNodeWithTag("auth_adult_confirmation").performScrollTo().assertIsOff()
        compose.onNodeWithText("I am 18 or older. Passo accounts are for adults only.").assertExists()
        confirmAdultForRegistration("auth_passenger_submit")
        compose.onNodeWithTag("auth_adult_confirmation").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("auth_passenger_submit").performScrollTo().assertIsNotEnabled()
        for (language in listOf(AppLanguage.AMHARIC, AppLanguage.OROMO)) {
            compose.runOnIdle { model.setLanguage(language) }
            compose.onNodeWithTag("auth_adult_confirmation").performScrollTo().assertIsOff()
            compose.onNodeWithTag("auth_passenger_submit").performScrollTo().assertIsNotEnabled()
        }
        assertTrue(registrations.isEmpty())
        assertTrue(driverApplications.isEmpty())
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun verifiedDriverSubmissionPreservesLicenceVehicleAndPendingApproval() {
        completeVerifiedDriverRegistration()
        assertEquals("Registration details use the injected catalog client", 1, vehicleCatalogRequests)
        compose.onNodeWithTag("auth_driver_vehicle_type").performScrollTo()
        compose.onNodeWithText("Executive Coach · 53 seats").assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth_driver_fee_fixed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth_driver_submit").performScrollTo().performClick()
        try {
            compose.waitUntil(10_000) { driverApplications.isNotEmpty() && model.driverApplications.state.value.application != null }
        } catch (timeout: ComposeTimeoutException) {
            capture("06-driver-submit-timeout")
            throw AssertionError("Driver submission did not finish: requests=${driverApplications.size}, state=${model.driverApplications.state.value}", timeout)
        }
        val request = driverApplications.single()
        assertEquals("DRIVER", request.role)
        assertEquals("Native Test Driver", request.fullName)
        assertEquals("0912345678", request.phone)
        assertEquals("REGISTRATION", requestedCodes.single()["purpose"])
        assertEquals("REGISTRATION", verifiedCodes.single()["purpose"])
        assertEquals("LIC-TEST-24", request.licenseNumber)
        assertEquals("AA-TEST-24", request.assignedVehiclePlate)
        assertEquals("Toyota Hiace", request.vehicleModel)
        assertEquals("Green", request.vehicleColor)
        assertEquals("Emergency Contact", request.emergencyContactName)
        assertEquals("0912000222", request.emergencyContactPhone)
        assertEquals("COACH_53", request.vehicleType)
        assertEquals("PROPOSED", request.routeChoice)
        assertEquals("Bole", request.proposedDeparture)
        assertEquals("CMC", request.proposedDestination)
        assertEquals(132500.0, request.proposedMonthlyFeeEtb!!, 0.01)
        assertEquals("PER_VEHICLE", request.proposedMonthlyFeeBasis)
        assertEquals("private-test-phone-verification", request.verificationToken)
        assertEquals("", request.password)
        assertTrue("Driver signup must not use the untracked legacy account endpoint", registrations.isEmpty())
        compose.onNodeWithTag("driver_application_screen").assertExists()
        applicationNode("driver_application_upload_LICENSE").assertIsEnabled()
        compose.onNodeWithTag("driver_application_portal_signin").assertDoesNotExist()
        assertFalse("Pending drivers must not receive an authenticated session", model.isAuthenticated.value)
        assertTrue(logins.isEmpty())
        assertNull(ApiClient.getAuthToken())
    }

    @Test fun pendingDriverSignInOpensApplicationWithoutOperationalAuthenticationAndApprovalNeedsExplicitSignIn() {
        pendingDriverLogin = true
        show(AppRole.DRIVER)
        requestAndVerifyLoginCode()
        compose.waitUntil(5_000) { applicationOtpLogins.isNotEmpty() && model.driverApplications.state.value.application != null }
        assertEquals(mapOf("phone" to "+251912345678", "verificationToken" to "private-test-phone-verification"), applicationOtpLogins.single())
        compose.onNodeWithTag("driver_application_screen").assertExists()
        applicationNode("driver_application_upload_LICENSE").assertIsEnabled()
        assertFalse(model.isAuthenticated.value)
        assertNull(ApiClient.getAuthToken())

        applicationResponse = applicationResponse.copy(onboarding = DriverApplicationProgress(
            acceptanceStatus = "ACCEPTED", canVerifyAndUpload = true, reviewStatus = "CORRECTIONS_REQUESTED", reviewNote = "Please upload a clearer licence.", requiredDocuments = listOf("LICENSE")))
        applicationNode("driver_application_refresh").performClick()
        compose.waitUntil(5_000) { model.driverApplications.state.value.application?.onboarding?.reviewStatus == "CORRECTIONS_REQUESTED" }
        applicationNode("driver_application_feedback").assertTextEquals("Please upload a clearer licence.")
        applicationNode("driver_application_upload_LICENSE").assertIsEnabled()
        applicationNode("driver_application_final_submit").assertIsNotEnabled()
        assertFalse(model.isAuthenticated.value)

        applicationResponse = applicationResponse.copy(account = applicationResponse.account!!.copy(status = "ACTIVE"),
            onboarding = applicationResponse.onboarding!!.copy(reviewStatus = "APPROVED", reviewReady = true))
        applicationNode("driver_application_refresh").performClick()
        compose.waitUntil(5_000) { model.driverApplications.state.value.application?.approved == true }
        applicationNode("driver_application_portal_signin").assertIsEnabled()
        assertFalse("An application refresh cannot create an operational login", model.isAuthenticated.value)
        assertNull(ApiClient.getAuthToken())
        capture("09-driver-application-approved")
    }

    @Test fun successfulEmptyVehicleCatalogDoesNotOfferFallbackTypesOrSubmitAnUnavailableVehicle() {
        vehicleCatalog = VehicleTypesResponse(true, emptyList())
        completeVerifiedDriverRegistration()
        assertEquals(1, vehicleCatalogRequests)
        compose.onNodeWithTag("auth_driver_vehicle_type").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("No vehicle types are currently available. Contact your administrator.").assertExists()
        compose.onNodeWithTag("auth_driver_submit").performScrollTo().performClick()
        compose.onNodeWithText("Choose an available vehicle type.").assertExists()
        assertTrue(registrations.isEmpty())
    }

    @Test fun unverifiedPassengerSubmissionKeepsSelectedDailyRouteTierAndServerAuthenticationGate() {
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
        compose.onNodeWithTag("auth_tier_Deluxe").performScrollTo().performClick().assertIsSelected()
        confirmAdultForRegistration("auth_passenger_submit")
        compose.onNodeWithTag("auth_passenger_submit").performScrollTo().performClick()
        compose.waitUntil(5_000) { registrations.isNotEmpty() }
        val request = registrations.single()
        assertEquals("PASSENGER", request.role)
        assertEquals("route-auth-test", request.appliedRouteId)
        assertEquals("Bole → CMC", request.appliedRouteName)
        assertEquals("DELUXE", request.serviceClass)
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
        confirmAdultForRegistration("auth_passenger_submit")
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
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        compose.onNodeWithTag("auth_login_otp_input").performTextInput("123456")
        for (attempt in 1..3) {
            compose.onNodeWithTag("auth_submit_button").assertIsEnabled().performClick()
            compose.waitUntil(5_000) { otpLogins.size == attempt && verifiedCodes.size == attempt && !model.authBusy.value }
            compose.onNodeWithTag("auth_submit_button").assertIsEnabled()
            assertEquals("Test credentials were not accepted.", model.authError.value)
        }
        assertEquals(3, otpLogins.size)
        assertFalse(model.isAuthenticated.value)
    }

    @Test fun driverSignInUsesSmsCodeAndDoesNotOfferPasswordRecovery() {
        show(AppRole.DRIVER)
        compose.onNodeWithTag("auth_phone_input").performTextInput("0912345678")
        compose.onNodeWithTag("auth_password_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_password_help").assertDoesNotExist()
        compose.onNodeWithTag("auth_submit_button").performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        compose.onNodeWithTag("auth_login_otp_input").assertIsDisplayed()
        assertFalse(model.isAuthenticated.value)
        assertNull(ApiClient.getAuthToken())
        assertTrue(registrations.isEmpty()); assertTrue(driverApplications.isEmpty())
        assertTrue(recoveryRequests.isEmpty()); assertTrue(recoveryResets.isEmpty())
        capture("10-driver-sms-sign-in")
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
        confirmAdultForRegistration("auth_passenger_submit")
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
        compose.onNodeWithTag("auth_password_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_submit_button").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_signup_link").performScrollTo().performClick()
        capture("07-small-driver-login-oromo")

        compose.onNodeWithTag("auth_full_name_input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_phone_input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_password_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_confirm_password_input").assertDoesNotExist()
        compose.onNodeWithTag("auth_submit_button").performScrollTo().assertIsDisplayed()
        capture("08-small-driver-sign-up-oromo")
        compose.onNodeWithTag("auth_full_name_input").performScrollTo().performTextInput("Native Test Driver")
        compose.onNodeWithTag("auth_phone_input").performScrollTo().performTextInput("0912345678")
        compose.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
        compose.waitUntil(5_000) { requestedCodes.isNotEmpty() }
        compose.onNodeWithTag("auth_driver_registration_otp").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("auth_driver_registration_otp").performTextInput("123456")
        compose.onNodeWithTag("auth_submit_button").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("auth_details").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("auth_details").assertIsDisplayed()
        assertEquals(AppLanguage.OROMO, model.currentLanguage.value)
        assertTrue(logins.isEmpty())
        assertTrue(registrations.isEmpty())
        assertEquals("REGISTRATION", requestedCodes.single()["purpose"])
        assertEquals("REGISTRATION", verifiedCodes.single()["purpose"])
        assertFalse(model.isAuthenticated.value)
    }
}
