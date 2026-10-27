package com.example.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.R
import com.example.data.api.DriverApplicationProgress
import com.example.data.api.canonicalAccountPhone
import com.example.data.repository.DriverApplicationRepository
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

internal fun driverApplicationStatus(progress: DriverApplicationProgress): String = when {
    progress.reviewStatus == "APPROVED" -> "Approved"
    progress.reviewStatus == "CORRECTIONS_REQUESTED" -> "Changes requested"
    progress.acceptanceStatus == "REJECTED" || progress.reviewStatus == "REJECTED" -> "Application needs changes"
    progress.reviewStatus == "SUBMITTED" -> "Final review in progress"
    progress.canVerifyAndUpload -> "Complete your application"
    else -> "Waiting for initial acceptance"
}

@Composable
internal fun DriverApplicationScreen(applications: DriverApplicationRepository, language: AppLanguage,
    onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val state by applications.state.collectAsState()
    val snapshot = state.application ?: return
    val account = snapshot.account ?: return
    val progress = snapshot.onboarding ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun t(en: String, am: String, om: String) = authText(language, en, am, om)
    var contactName by remember(account.id) { mutableStateOf(progress.profile.emergencyContactName) }
    var contactPhone by remember(account.id) { mutableStateOf(progress.profile.emergencyContactPhone) }
    var vehicleColor by remember(account.id) { mutableStateOf(progress.profile.vehicleColor) }
    var selectedKind by remember { mutableStateOf("PHOTO") }
    var localError by remember { mutableStateOf<String?>(null) }
    var editingRoute by remember { mutableStateOf(false) }
    val detailsDirty = contactName.trim() != progress.profile.emergencyContactName ||
        canonicalAccountPhone(contactPhone) != canonicalAccountPhone(progress.profile.emergencyContactPhone) ||
        vehicleColor.trim() != progress.profile.vehicleColor
    val routeDraft = remember(account.id, progress.routePreference) { AuthDraft(null, null, null).apply {
        pickup = progress.routePreference.departure; destination = progress.routePreference.destination
        vehicleType = account.vehicleType
    } }
    LaunchedEffect(progress.profile) {
        contactName = progress.profile.emergencyContactName; contactPhone = progress.profile.emergencyContactPhone; vehicleColor = progress.profile.vehicleColor
    }
    BackHandler(onBack = onBack)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val kind = selectedKind
        if (uri != null) scope.launch {
            localError = null
            try {
                val (bytes, mime) = withContext(Dispatchers.IO) {
                    val type = context.contentResolver.getType(uri).orEmpty().lowercase().substringBefore(';').let { if (it == "image/jpg") "image/jpeg" else it }
                    val data = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val count = input.read(buffer); if (count < 0) break
                            require(output.size() + count <= 5 * 1024 * 1024) { "Each document must be 5 MB or smaller." }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    } ?: error("This file cannot be read. Please choose it again.")
                    data to type
                }
                applications.upload(kind, bytes, mime)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { localError = failure.message ?: "Could not open the selected file." }
        }
    }
    fun documentName(kind: String) = when (kind) {
        "LICENSE" -> t("Driving licence", "መንጃ ፈቃድ", "Eeyyama konkolaachisummaa")
        "VEHICLE_REGISTRATION" -> t("Vehicle registration", "የተሽከርካሪ ምዝገባ", "Galmee konkolaataa")
        "INSURANCE" -> t("Insurance", "መድን", "Inshuraansii")
        "PHOTO" -> t("Profile photo", "የመለያ ፎቶ", "Suuraa profaayilii")
        else -> kind
    }
    Box(modifier.fillMaxSize().background(CommuteCream).windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
        LazyColumn(Modifier.fillMaxSize().testTag("driver_application_screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, enabled = !state.busy, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowBack, t("Back", "ተመለስ", "Deebi'i"), tint = CommuteInk) }
                    Image(painterResource(R.drawable.auth_passo_wordmark), "PASSO", Modifier.width(100.dp).height(32.dp))
                    Box(Modifier.weight(1f).padding(start = 8.dp)) { CommuterLanguageSwitch(language, onLanguage, compact = true) }
                }
                Text(t("Your driver application", "የሹፌር ማመልከቻዎ", "Iyyata konkolaachisaa kee"), color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            }
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = CommuteTeal, contentColor = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(account.fullName, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        val status = when (driverApplicationStatus(progress)) {
                            "Approved" -> t("Approved", "ተፈቅዷል", "Hayyamameera")
                            "Changes requested" -> t("Changes requested", "ማስተካከያ ተጠይቋል", "Fooyya'iinsi gaafatameera")
                            "Application needs changes" -> t("Application needs changes", "ማመልከቻው ማስተካከያ ይፈልጋል", "Iyyanni fooyya'iinsa barbaada")
                            "Final review in progress" -> t("Final review in progress", "የመጨረሻ ግምገማ በሂደት ላይ", "Gamaaggamni dhumaa adeemsa irra jira")
                            "Complete your application" -> t("Complete your application", "ማመልከቻዎን ያጠናቅቁ", "Iyyata kee xumuri")
                            else -> t("Waiting for initial acceptance", "የመጀመሪያ ተቀባይነት በመጠባበቅ ላይ", "Fudhatama jalqabaa eegaa jira")
                        }
                        Text(status, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("driver_application_status"))
                        Text(if (snapshot.approved) t("Your documents and application have been approved. Sign in to open your driver portal.", "ሰነዶችዎ እና ማመልከቻዎ ተፈቅደዋል። የሹፌር ፖርታል ለመክፈት ይግቡ።", "Sanadoonni fi iyyanni kee hayyamamaniiru. Poortaalii konkolaachisaa banuuf seeni.")
                            else if (!progress.canVerifyAndUpload) t("An administrator must first accept your route proposal. Refresh here to check for updates.", "አስተዳዳሪው መጀመሪያ የመስመር ጥቆማዎን መቀበል አለበት። ለውጦችን ለማየት ያድሱ።", "Bulchaan dursee yaada karaa kee fudhachuu qaba. Jijjiirama ilaaluuf haaromsi.")
                            else t("Required documents and final approval are needed before driving or passenger allocation. Phone verification is optional for now.", "ከሥራ እና ከተሳፋሪ ምደባ በፊት ሰነዶች እና የመጨረሻ ፈቃድ ያስፈልጋሉ። ስልክ ማረጋገጥ ለአሁኑ አማራጭ ነው።", "Hojii fi ramaddii imaltootaa dura sanadoonni fi hayyamni dhumaa barbaachisu. Mirkaneessi bilbilaa ammaaf filannoo dha."), fontSize = 13.sp)
                    }
                }
            }
            if (progress.reviewNote.isNotBlank()) item {
                Text(t("Administrator feedback", "የአስተዳዳሪ አስተያየት", "Yaada bulchaa"), color = CommuteInk, fontWeight = FontWeight.Bold)
                Text(progress.reviewNote, color = CommuteInk, modifier = Modifier.testTag("driver_application_feedback"))
            }
            item {
                OutlinedButton(onClick = { scope.launch { applications.refresh() } }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("driver_application_refresh")) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(t("Refresh application status", "የማመልከቻ ሁኔታን ያድሱ", "Haala iyyataa haaromsi"))
                }
            }
            if (progress.acceptanceRequired && progress.routePreference.departure.isNotBlank()) item {
                Surface(shape = RoundedCornerShape(20.dp), color = Color.White, border = BorderStroke(1.dp, Color(0xFFE5E2DA))) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${progress.routePreference.departure} → ${progress.routePreference.destination}", color = CommuteInk, fontWeight = FontWeight.Bold)
                        progress.routePreference.proposedMonthlyFeeEtb?.let { Text("ETB ${formatDriverFee(it)} / " + t("month · whole vehicle", "ወር · ሙሉ ተሽከርካሪ", "ji'a · konkolaataa guutuu"), color = CommuteTeal) }
                    }
                }
            }
            if (progress.acceptanceRequired && progress.reviewStatus !in setOf("SUBMITTED", "APPROVED")) item {
                TextButton(onClick = { editingRoute = !editingRoute }, enabled = !state.busy, modifier = Modifier.testTag("driver_application_edit_route")) {
                    Text(t("Update route proposal", "የመስመር ጥቆማን ያዘምኑ", "Yaada karaa haaromsi"), color = CommuteTeal)
                }
                if (editingRoute) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(t("Changing your route or fee requires initial acceptance again.", "መስመር ወይም ዋጋ መቀየር የመጀመሪያ ተቀባይነትን እንደገና ይፈልጋል።", "Karaa ykn kaffaltii jijjiiruun fudhatama jalqabaa irra deebi'ee barbaada."), color = CommuteInk, fontSize = 12.sp)
                    if (account.serviceType == "DAILY_COMMUTE") DriverRouteProposalFields(routeDraft, applications, language, !state.busy)
                    else {
                        AuthField(routeDraft.pickup, { routeDraft.pickup = it }, t("Preferred departure", "የሚመርጡት መነሻ", "Ka'umsa filatame"), enabled = !state.busy, language = language)
                        AuthField(routeDraft.destination, { routeDraft.destination = it }, t("Preferred destination", "የሚመርጡት መድረሻ", "Ga'umsa filatame"), enabled = !state.busy, language = language)
                    }
                    AuthPrimaryButton(t("Save route proposal", "የመስመር ጥቆማ አስቀምጥ", "Yaada karaa olkaa'i"), onClick = {
                        if (account.serviceType == "DAILY_COMMUTE") driverMonthlyFee(routeDraft)?.let { fee -> scope.launch { applications.saveRoute(routeDraft.pickup, routeDraft.destination, account.vehicleType, fee) } }
                        else scope.launch { applications.savePreferredRoute(routeDraft.pickup, routeDraft.destination, account.vehicleType) }
                    }, enabled = !state.busy && if (account.serviceType == "DAILY_COMMUTE") !routeDraft.quoteLoading && driverMonthlyFee(routeDraft) != null else routeDraft.pickup.trim().length >= 2 && routeDraft.destination.trim().length >= 2)
                }
            }
            if (progress.canInitiallySubmit && !progress.canVerifyAndUpload && progress.reviewStatus != "AWAITING_ACCEPTANCE") item {
                AuthPrimaryButton(t("Resubmit for initial acceptance", "ለመጀመሪያ ተቀባይነት እንደገና ላክ", "Fudhatama jalqabaaf irra deebi'ii ergi"), { scope.launch { applications.submit() } }, enabled = !state.busy,
                    modifier = Modifier.testTag("driver_application_initial_submit"))
            }
            if (progress.canVerifyAndUpload) {
                item {
                    Text(t("Required documents", "የሚፈለጉ ሰነዶች", "Sanadoota barbaachisan"), color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(t("Clear JPEG, PNG or PDF files, up to 5 MB each. Your photo must be JPEG or PNG.", "እያንዳንዱ እስከ 5 ሜባ የሆኑ JPEG፣ PNG ወይም PDF ይጠቀሙ። ፎቶ JPEG ወይም PNG መሆን አለበት።", "JPEG, PNG ykn PDF ifa ta'e, tokkoon tokkoon hanga 5 MB. Suuraan JPEG ykn PNG haa ta'u."), color = CommuteInk, fontSize = 12.sp)
                }
                progress.requiredDocuments.forEach { kind -> item(key = kind) {
                    val uploaded = progress.documents.any { it.kind == kind }
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (uploaded) Icons.Default.CheckCircle else Icons.Default.Description, null, tint = CommuteTeal)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(documentName(kind), color = CommuteInk, fontWeight = FontWeight.SemiBold)
                                Text(if (uploaded) t("Uploaded", "ተልኳል", "Olkaa'ameera") else t("Required", "ያስፈልጋል", "Barbaachisaa"), color = CommuteInk, fontSize = 11.sp)
                            }
                            if (snapshot.editable) TextButton(onClick = { selectedKind = kind; picker.launch(if (kind == "PHOTO") arrayOf("image/jpeg", "image/png") else arrayOf("image/jpeg", "image/png", "application/pdf")) }, enabled = !state.busy,
                                modifier = Modifier.testTag("driver_application_upload_$kind")) { Text(if (uploaded) t("Replace", "ቀይር", "Bakka buusi") else t("Upload", "ላክ", "Olkaa'i"), color = CommuteTeal) }
                        }
                    }
                } }
                item {
                    Text(t("Contact and vehicle details", "የእውቂያ እና የተሽከርካሪ ዝርዝር", "Odeeffannoo qunnamtii fi konkolaataa"), color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AuthField(vehicleColor, { vehicleColor = it.take(40) }, t("Vehicle color", "የተሽከርካሪ ቀለም", "Halluu konkolaataa"), Modifier.testTag("driver_application_vehicle_color"), enabled = snapshot.editable && !state.busy, language = language)
                        AuthField(contactName, { contactName = it.take(100) }, t("Emergency contact name", "የአደጋ ጊዜ ተጠሪ ስም", "Maqaa nama yeroo balaa"), Modifier.testTag("driver_application_contact_name"), enabled = snapshot.editable && !state.busy, language = language)
                        AuthField(contactPhone, { contactPhone = it.take(24) }, t("Emergency contact phone", "የአደጋ ጊዜ ተጠሪ ስልክ", "Bilbila nama yeroo balaa"), Modifier.testTag("driver_application_contact_phone"), phone = true, enabled = snapshot.editable && !state.busy, language = language)
                        if (snapshot.editable) AuthPrimaryButton(t("Save details", "ዝርዝር አስቀምጥ", "Odeeffannoo olkaa'i"), { scope.launch { applications.saveProfile(contactName, contactPhone, vehicleColor) } },
                            enabled = !state.busy && contactName.trim().length >= 2 && contactPhone.trim().length >= 7 && vehicleColor.trim().length >= 2,
                            modifier = Modifier.testTag("driver_application_save_details"))
                    }
                }
                if (snapshot.editable) item {
                    AuthPrimaryButton(t("Submit for final review", "ለመጨረሻ ግምገማ ላክ", "Gamaaggama dhumaaf ergi"), { scope.launch { applications.submit() } }, enabled = !state.busy && progress.reviewReady && !detailsDirty && !editingRoute,
                        modifier = Modifier.testTag("driver_application_final_submit"))
                    if (!progress.reviewReady || detailsDirty || editingRoute) Text(t("Complete the required documents, save your details and close the route editor before submitting.", "ከመላክዎ በፊት ሰነዶችን ያጠናቅቁ፣ ዝርዝሮችን ያስቀምጡ እና የመስመር አርታኢውን ይዝጉ።", "Erguun dura sanadoota xumuri, odeeffannoo olkaa'i, gulaalaa karaa cufi."), color = CommuteInk, fontSize = 12.sp)
                }
            }
            if (snapshot.approved) item {
                AuthPrimaryButton(t("Sign in to driver portal", "ወደ ሹፌር ፖርታል ግባ", "Poortaalii konkolaachisaa seeni"), onSignIn, enabled = !state.busy, modifier = Modifier.testTag("driver_application_portal_signin"))
            }
            item {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                (localError ?: state.error)?.let { Text(it, color = Color(0xFFAC2F2F), modifier = Modifier.testTag("driver_application_error")) }
                state.message?.let { Text(it, color = CommuteTeal) }
                Text("info@passo.live", color = CommuteTeal, fontSize = 13.sp)
                Text(t("Your application sign-in only opens registration steps. It does not permit driving or passenger allocation.", "የማመልከቻ መግቢያ የምዝገባ እርምጃዎችን ብቻ ይከፍታል። ሥራ ወይም ተሳፋሪ ምደባን አይፈቅድም።", "Seensi iyyataa tarkaanfii galmee qofa bana. Hojii ykn ramaddii imaltootaa hin hayyamu."), color = CommuteInk, fontSize = 11.sp)
            }
        }
    }
}
