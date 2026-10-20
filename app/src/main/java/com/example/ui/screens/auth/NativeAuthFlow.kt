package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.data.api.ApiClient
import com.example.data.api.TransportCompanyDto
import com.example.ui.screens.passenger.*
import com.example.ui.viewmodel.AppRole
import com.example.ui.viewmodel.MainViewModel
import kotlinx.coroutines.CancellationException

/** Account secrets intentionally live only in memory, never SavedState or preferences. */
internal class AuthDraft(routeId: String?, routeName: String?, tier: String?) {
    val isCustomCommute = routeName == "Requested commuter route" && routeId.isNullOrBlank()
    var name by mutableStateOf("")
    var phone by mutableStateOf("")
    var password by mutableStateOf("")
    var confirmation by mutableStateOf("")
    var code by mutableStateOf("")
    var verification by mutableStateOf("")
    var selectedRoute by mutableStateOf(routeId.orEmpty())
    var selectedRouteName by mutableStateOf(routeName.orEmpty())
    var tier by mutableStateOf(tier ?: "Economy")
    var license by mutableStateOf("")
    var service by mutableStateOf("Daily Commuter")
    var plate by mutableStateOf("")
    var vehicleModel by mutableStateOf("")
    var vehicleType by mutableStateOf("MINIBUS_14")
    var company by mutableStateOf("")
    var routeMode by mutableStateOf("existing")
    var pickup by mutableStateOf("")
    var destination by mutableStateOf("")
    var departure by mutableStateOf("06:30")
    var journey by mutableStateOf("Two way")
}

internal fun authText(language: AppLanguage, en: String, am: String, om: String): String = when (language) {
    AppLanguage.ENGLISH -> en
    AppLanguage.AMHARIC -> am
    AppLanguage.OROMO -> om
}

@Composable
internal fun NativeAuthFlow(
    model: MainViewModel, role: AppRole, initialRegister: Boolean, routeId: String?, routeName: String?,
    tier: String?, onExit: () -> Unit, modifier: Modifier = Modifier
) {
    val language by model.currentLanguage.collectAsState()
    val routes by model.allRoutes.collectAsState()
    val authError by model.authError.collectAsState()
    val authBusy by model.authBusy.collectAsState()
    val registrationMessage by model.registrationMessage.collectAsState()
    val driver = role == AppRole.DRIVER
    val draft = remember(role, routeId, routeName, tier) { AuthDraft(routeId, routeName, tier) }
    var register by remember(role, initialRegister) { mutableStateOf(initialRegister) }
    var step by remember(role) { mutableIntStateOf(0) }
    var otpBusy by remember { mutableStateOf(false) }
    val busy = otpBusy || authBusy
    var waitingForAuth by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var epoch by remember { mutableIntStateOf(0) }
    var companies by remember { mutableStateOf<List<TransportCompanyDto>>(emptyList()) }
    var companyError by remember { mutableStateOf<String?>(null) }
    fun t(en: String, am: String, om: String) = authText(language, en, am, om)
    fun invalidatePhone() { draft.code = ""; draft.verification = ""; epoch++ }
    fun switchMode(value: Boolean) {
        epoch++; register = value; step = 0; otpBusy = false; message = null; waitingForAuth = false
        draft.phone = ""; draft.password = ""; draft.confirmation = ""; draft.code = ""; draft.verification = ""
        if (value) draft.name = ""
        model.clearRegistrationMessage()
    }
    fun back() {
        epoch++; otpBusy = false; message = null; waitingForAuth = false
        if (register && step > 0) step-- else onExit()
    }
    BackHandler { back() }
    LaunchedEffect(registrationMessage) {
        if (driver && !registrationMessage.isNullOrBlank()) {
            register = false; step = 0; draft.password = ""; draft.confirmation = ""; draft.verification = ""
        }
    }
    LaunchedEffect(routes) {
        if (draft.selectedRoute.isBlank() && routeName != "Requested commuter route") {
            routes.firstOrNull { it.active }?.let { draft.selectedRoute = it.id; draft.selectedRouteName = it.name }
        }
    }
    LaunchedEffect(draft.service, step) {
        if (driver && register && step == 2 && draft.service == "Intercity") {
            companyError = null
            try {
                val response = ApiClient.getService().getTransportCompanies()
                if (response.isSuccessful && response.body()?.success == true) {
                    companies = response.body()?.companies.orEmpty()
                    if (companies.none { it.name == draft.company }) draft.company = ""
                    if (companies.isEmpty()) companyError = "No registered transport companies are available."
                } else companyError = "Could not load companies. Return and try again."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { companyError = "Could not load companies. Check your connection and try again." }
        }
    }
    fun requestCode() {
        val problem = when {
            draft.name.trim().isBlank() -> t("Enter your full name.", "ሙሉ ስምዎን ያስገቡ።", "Maqaa guutuu kee galchi.")
            !Regex("^\\+?[0-9 ()-]{7,24}$").matches(draft.phone.trim()) -> t("Enter a valid mobile number.", "ትክክለኛ ስልክ ያስገቡ።", "Lakkoofsa bilbilaa sirrii galchi.")
            draft.password.length < 10 -> t("Use a password with at least 10 characters.", "ቢያንስ 10 ፊደላት ያሉት የይለፍ ቃል ይጠቀሙ።", "Jecha iccitii yoo xiqqaate qubee 10 qabu fayyadami.")
            draft.password != draft.confirmation -> t("The passwords do not match.", "የይለፍ ቃሎቹ አይመሳሰሉም።", "Jechoonni iccitii wal hin fakkaatan.")
            else -> null
        }
        if (problem != null) { message = problem; return }
        message = null; waitingForAuth = false; otpBusy = true; draft.verification = ""; draft.code = ""
        val requestEpoch = ++epoch
        val phone = draft.phone.trim()
        model.requestRegistrationOtp(phone) { result, error ->
            if (requestEpoch == epoch && draft.phone.trim() == phone && register) {
                otpBusy = false; message = error
                if (error == null) step = 1
            }
        }
    }
    fun verifyCode() {
        if (draft.code.length != 6 || draft.code.any { !it.isDigit() }) return
        otpBusy = true; message = null
        val requestEpoch = ++epoch
        val phone = draft.phone.trim()
        model.verifyRegistrationOtp(phone, draft.code) { token, error ->
            if (requestEpoch == epoch && draft.phone.trim() == phone && register) {
                otpBusy = false
                if (!token.isNullOrBlank() && error == null) {
                    draft.verification = token; step = 2; message = null
                } else message = error ?: t("The code could not be verified.", "ኮዱ አልተረጋገጠም።", "Koodiin hin mirkanoofne.")
            }
        }
    }
    fun submit() {
        val problem = when {
            draft.verification.isBlank() -> "Verify your phone number before registering."
            !driver && draft.isCustomCommute && draft.pickup.isBlank() -> "Enter your pickup location."
            !driver && draft.isCustomCommute && draft.destination.isBlank() -> "Enter your destination."
            !driver && !draft.isCustomCommute && routes.none { it.id == draft.selectedRoute && it.active } -> "Choose an active daily route to create your account."
            driver && draft.license.isBlank() -> "Enter your commercial driving licence number."
            driver && draft.service == "Intercity" && companies.none { it.name == draft.company } -> "Choose a registered transport company."
            driver && draft.service != "Intercity" && (draft.plate.isBlank() || draft.vehicleModel.isBlank()) -> "Enter your vehicle plate and model."
            driver && draft.service != "Intercity" && draft.routeMode == "existing" && routes.none { it.id == draft.selectedRoute && it.active } -> "Choose an active route or propose a route."
            driver && draft.service != "Intercity" && draft.routeMode == "custom" && (draft.pickup.isBlank() || draft.destination.isBlank() || !Regex("^([01][0-9]|2[0-3]):[0-5][0-9]$").matches(draft.departure)) -> "Enter pickup, destination and a valid 24-hour departure time (HH:mm)."
            else -> null
        }
        if (problem != null) { message = problem; return }
        waitingForAuth = true; message = null
        val intercity = driver && draft.service == "Intercity"
        val proposed = driver && draft.routeMode == "custom"
        model.register(
            fullName = draft.name.trim(), phone = draft.phone.trim(), email = "", password = draft.password, role = role,
            licenseNumber = draft.license.trim(), companyName = if (intercity) draft.company else "",
            assignedVehiclePlate = if (intercity) "" else draft.plate.trim(), vehicleModel = if (intercity) "" else draft.vehicleModel.trim(),
            vehicleType = draft.vehicleType, appliedRouteId = if (intercity || proposed || (!driver && draft.isCustomCommute)) "" else draft.selectedRoute,
            appliedRouteName = when {
                intercity -> "Driver service preference: City Travel | Transport company: " + draft.company + " | Tier: " + draft.tier
                proposed -> "Driver service preference: Daily Commuter | Proposed route | Pickup: ${draft.pickup} | Destination: ${draft.destination} | Departure: ${draft.departure} | Journey: ${draft.journey} | Tier: ${draft.tier}"
                driver -> "Driver service preference: Daily Commuter | Route: ${draft.selectedRouteName} | Tier: ${draft.tier}"
                draft.isCustomCommute -> "Requested commuter route | Pickup: ${draft.pickup} | Destination: ${draft.destination} | Journey: ${draft.journey} | Tier: ${draft.tier}"
                else -> draft.selectedRouteName
            }, otpVerificationToken = draft.verification
        )
    }
    if (help) AlertDialog(onDismissRequest = { help = false },
        title = { Text(t("Sign-in help", "የመግቢያ እርዳታ", "Gargaarsa seensaa")) },
        text = { Text(t("Please contact your PASSO administrator for help resetting your password.", "የይለፍ ቃልዎን ለመቀየር የPASSO አስተዳዳሪዎን ያነጋግሩ።", "Jecha iccitii kee jijjiiruuf bulchaa PASSO qunnami.")) },
        confirmButton = { TextButton(onClick = { help = false }) { Text("OK") } })

    BoxWithConstraints(modifier.fillMaxSize().background(CommuteCream).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        val headerHeight = if (register) (maxHeight * .26f).coerceIn(170.dp, 240.dp) else (maxHeight * .40f).coerceIn(220.dp, 340.dp)
        LazyColumn(Modifier.fillMaxSize().testTag("auth_content"), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                if (!register || step == 0) AuthPhotoHeader(driver, register, language, model::setLanguage, ::back, Modifier.height(headerHeight))
                else AuthCompactHeader(language, model::setLanguage, ::back)
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).testTag("auth_form_card"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AuthRoleBadge(driver, language)
                    if (!register || step == 0) AuthIdentityTitle(driver, register, language)
                    if (register) AuthStepProgress(step, driver, language)
                    if (!register || step == 0) {
                        if (register) AuthField(draft.name, { draft.name = it; message = null }, t("Full name", "ሙሉ ስም", "Maqaa guutuu"),
                            Modifier.fillMaxWidth().testTag("auth_full_name_input"), enabled = !busy, language = language)
                        AuthField(draft.phone, { draft.phone = it; invalidatePhone(); message = null }, t("Mobile phone number", "የሞባይል ስልክ ቁጥር", "Lakkoofsa bilbilaa"),
                            Modifier.fillMaxWidth().testTag("auth_phone_input"), phone = true, enabled = !busy, language = language)
                        AuthField(draft.password, { draft.password = it; message = null }, t("Password", "የይለፍ ቃል", "Jecha iccitii"),
                            Modifier.fillMaxWidth().testTag("auth_password_input"), password = true, enabled = !busy,
                            helper = if (register) t("Use at least 10 characters.", "ቢያንስ 10 ፊደላት ይጠቀሙ።", "Yoo xiqqaate qubee 10 fayyadami.") else null, language = language)
                        if (register) AuthField(draft.confirmation, { draft.confirmation = it; message = null }, t("Confirm password", "የይለፍ ቃል ያረጋግጡ", "Jecha iccitii mirkaneessi"),
                            Modifier.fillMaxWidth().testTag("auth_confirm_password_input"), password = true, enabled = !busy, language = language)
                        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { help = true }, modifier = Modifier.testTag("auth_password_help")) { Text(t("Need help signing in?", "ለመግባት እርዳታ ይፈልጋሉ?", "Seenuuf gargaarsa barbaaddaa?"), color = CommuteTeal, fontSize = 12.sp) }
                        }
                    }
                    if (register && step == 1) {
                        Text(t("Verify your phone", "ስልክዎን ያረጋግጡ", "Bilbila kee mirkaneessi"), color = CommuteInk, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Text(t("Enter the 6-digit code sent to ", "ወደዚህ የተላከውን 6 አሃዝ ኮድ ያስገቡ፦ ", "Koodii lakkoofsa 6 gara kanaatti ergame galchi: ") + draft.phone, color = CommuteInk, fontSize = 14.sp)
                        AuthField(draft.code, { draft.code = it.filter(Char::isDigit).take(6); draft.verification = ""; message = null }, t("SMS verification code", "የSMS ማረጋገጫ ኮድ", "Koodii mirkaneessaa SMS"),
                            Modifier.fillMaxWidth().testTag("auth_otp_input"), phone = true, enabled = !busy, language = language, leadingIcon = Icons.Default.Sms, placeholder = t("6-digit code", "6 አሃዝ ኮድ", "Koodii lakkoofsa 6"))
                        Text(t("Code expires after 10 minutes.", "ኮዱ ከ10 ደቂቃ በኋላ ያልቃል።", "Koodiin daqiiqaa 10 booda darba."), color = CommuteInk, fontSize = 12.sp)
                        AuthPrimaryButton(t("Verify phone", "ስልክ አረጋግጥ", "Bilbila mirkaneessi"), ::verifyCode,
                            Modifier.testTag("auth_otp_verify"), enabled = !busy && draft.code.length == 6, busy = busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = ::requestCode, enabled = !busy, modifier = Modifier.testTag("auth_otp_resend")) { Text(t("Resend code", "ኮድ እንደገና ላክ", "Koodii irra deebi'i"), color = CommuteTeal) }
                            TextButton(onClick = { invalidatePhone(); step = 0; message = null }, enabled = !busy, modifier = Modifier.testTag("auth_phone_edit")) { Text(t("Change number", "ቁጥር ቀይር", "Lakkoofsa jijjiiri"), color = CommuteTeal) }
                        }
                    }
                    if (register && step == 2) {
                        AuthRegistrationDetails(draft, driver, routes.filter { it.active }, companies, companyError, language, !busy)
                    }
                    (message ?: authError?.takeIf { waitingForAuth })?.let { Text(it, color = androidx.compose.ui.graphics.Color(0xFFAC2F2F), fontSize = 12.sp, modifier = Modifier.testTag("auth_error")) }
                    registrationMessage?.let { Text(it, color = CommuteTeal, fontSize = 13.sp, modifier = Modifier.testTag("auth_registration_message")) }
                    if (!register || step == 0) AuthPrimaryButton(
                        if (register) t("Send verification code", "የማረጋገጫ ኮድ ላክ", "Koodii mirkaneessaa ergi") else t("Sign in", "ግባ", "Seeni"),
                        onClick = {
                            if (register) requestCode()
                            else if (draft.phone.isBlank() || draft.password.isBlank()) message = t("Enter your phone number and password.", "ስልክ ቁጥርዎን እና የይለፍ ቃልዎን ያስገቡ።", "Lakkoofsa bilbilaa fi jecha iccitii galchi.")
                            else { waitingForAuth = true; message = null; model.clearRegistrationMessage(); model.login(draft.phone.trim(), draft.password, role) }
                        }, modifier = Modifier.testTag("auth_submit_button"), enabled = !busy, busy = busy)
                    if (register && step == 2) AuthPrimaryButton(
                        if (driver) t("Submit driver registration", "የሹፌር ምዝገባ ላክ", "Galmee konkolaachisaa ergi") else t("Create account", "መለያ ፍጠር", "Herrega uumi"),
                        ::submit, Modifier.testTag(if (driver) "auth_driver_submit" else "auth_passenger_submit"), enabled = !busy, busy = busy)
                    if (register && step == 0) Text(t("We’ll text a 6-digit code to verify your number.", "ቁጥርዎን ለማረጋገጥ 6 አሃዝ ኮድ በSMS እንልካለን።", "Lakkoofsa kee mirkaneessuuf koodii 6 SMS dhaan ergina."), fontSize = 11.sp, color = CommuteInk)
                    if (driver && register && step == 0) Text(t("Next: licence and service details. Approval required.", "ቀጣይ፦ ፈቃድ እና የአገልግሎት ዝርዝር። ፈቃድ ያስፈልጋል።", "Itti aansuun: eeyyamaa fi odeeffannoo tajaajilaa. Hayyamni barbaachisa."), color = CommuteInk, fontSize = 10.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Text(if (register) t("Already registered?", "ተመዝግበዋል?", "Duraan galmoofteettaa?") else t("New to PASSO?", "ለPASSO አዲስ ነዎት?", "PASSO irratti haaraa?"), color = CommuteInk, fontSize = 12.sp)
                        TextButton(onClick = { switchMode(!register) }, enabled = !busy, modifier = Modifier.testTag(if (register) "auth_signin_link" else "auth_signup_link")) {
                            Text(if (register) t("Sign in", "ግባ", "Seeni") else if (driver) t("Create driver account", "የሹፌር መለያ ፍጠር", "Herrega konkolaachisaa uumi") else t("Create an account", "መለያ ፍጠር", "Herrega uumi"), color = CommuteTeal, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (!register && driver) Text(t("New driver accounts need approval before sign-in.", "አዲስ የሹፌር መለያዎች ከመግባት በፊት ፈቃድ ይፈልጋሉ።", "Herregni konkolaachisaa haaraan seenuun dura hayyama barbaada."), color = CommuteInk, fontSize = 11.sp)
                    if (!register && !driver) TextButton(onClick = onExit, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("auth_browse_routes")) {
                        Icon(Icons.Default.DirectionsBus, null, tint = CommuteTeal); Spacer(Modifier.width(6.dp))
                        Text(t("Browse daily routes", "የዕለት መስመሮችን ያስሱ", "Karaalee guyyaa ilaali"), color = CommuteTeal, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthCompactHeader(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("auth_header_back")) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
        Column(Modifier.weight(1f)) {
            Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(105.dp).height(28.dp))
            Text("Go Further, Together.", color = CommuteInk, fontSize = 9.sp)
        }
        Box(Modifier.weight(1.3f)) { CommuterLanguageSwitch(language, onLanguage, compact = true) }
    }
}
