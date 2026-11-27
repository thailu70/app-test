package com.example.ui.screens.intercity

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.localization.AppLanguage
import com.example.core.time.EthiopianDateTime
import com.example.data.api.ApiClient
import com.example.data.api.MobilityApi
import com.example.data.api.MobilityRequestInput
import com.example.ui.components.PassoMobilityPalette
import com.example.ui.components.EthiopianDatePickerDialog
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Only form values are saved. Photo bytes and private request access keep their existing storage rules. */
internal data class CargoRequestDraft(
    val origin: String = "", val destination: String = "", val date: String = intercityToday(),
    val time: String = EthiopianDateTime.clockValueFromEAT(8, 0), val weight: String = "", val vehicleType: String = "",
    val details: String = "", val name: String = "", val phone: String = "",
    val pickup: Pair<Double, Double>? = null, val delivery: Pair<Double, Double>? = null
)

private val CargoDraftSaver = listSaver<CargoRequestDraft, Any?>(save = {
    listOf(it.origin, it.destination, it.date, it.time, it.weight, it.vehicleType, it.details, it.name, it.phone,
        it.pickup?.first, it.pickup?.second, it.delivery?.first, it.delivery?.second)
}, restore = {
    CargoRequestDraft(it[0] as String, it[1] as String, it[2] as String, it[3] as String,
        it[4] as String, it[5] as String, it[6] as String, it[7] as String, it[8] as String,
        if (it[9] != null && it[10] != null) (it[9] as Double) to (it[10] as Double) else null,
        if (it[11] != null && it[12] != null) (it[11] as Double) to (it[12] as Double) else null)
})

internal fun cargoDraftErrors(draft: CargoRequestDraft, photo: CargoPhotoState, adult: Boolean): Set<String> = buildSet {
    if (draft.origin.isBlank() || draft.pickup == null) add("pickup")
    if (draft.destination.isBlank() || draft.delivery == null) add("delivery")
    if (draft.pickup != null && draft.pickup == draft.delivery) add("same_location")
    if (mobilityDepartureIso(draft.date, draft.time) == null) add("time")
    val quintals = draft.weight.toDoubleOrNull()
    if (quintals == null || !quintals.isFinite() || quintals <= 0 || quintals > 1000) add("weight")
    if (draft.vehicleType.isBlank()) add("vehicle")
    if (draft.details.isBlank()) add("details")
    if (photo.bytes == null || photo.type !in setOf("image/jpeg", "image/png", "image/webp")) add("photo")
    if (draft.name.isBlank()) add("name")
    if (!Regex("\\+?[0-9]{9,15}").matches(draft.phone.filterNot { it.isWhitespace() || it == '-' })) add("phone")
    if (!adult) add("consent")
}

@Composable internal fun CargoRequestForm(state: MobilityUiState, language: AppLanguage,
    onAdult: (Boolean) -> Unit, send: (MobilityRequestInput, ByteArray?, String?) -> Unit,
    initialDraft: CargoRequestDraft = CargoRequestDraft(), providedPhoto: CargoPhotoState? = null,
    providedApi: MobilityApi? = null, pickerTileDownloadsEnabled: Boolean = true, hideContactDetails: Boolean = false) {
    fun t(en: String, am: String, om: String) = intercityText(language, en, am, om)
    var draft by rememberSaveable(stateSaver = CargoDraftSaver) { mutableStateOf(initialDraft) }
    var expanded by rememberSaveable { mutableIntStateOf(1) }
    var showErrors by remember { mutableStateOf(false) }; var reviewing by remember { mutableStateOf(false) }
    var verifyingPhone by remember { mutableStateOf(false) }; var otpSent by remember { mutableStateOf(false) }
    var phoneCode by remember { mutableStateOf("") }; var otpMessage by remember { mutableStateOf<String?>(null) }; var otpBusy by remember { mutableStateOf(false) }
    var resendSeconds by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<String?>(null) }
    var road by remember { mutableStateOf<String?>(null) }
    val api = providedApi ?: remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    val photo = providedPhoto ?: rememberCargoPhoto()
    val context = LocalContext.current; val focus = LocalFocusManager.current; val scope = rememberCoroutineScope()
    LaunchedEffect(verifyingPhone, otpSent, resendSeconds) {
        if (verifyingPhone && otpSent && resendSeconds > 0) { delay(1_000); resendSeconds-- }
    }
    val enabled = !state.busy && !photo.busy
    val errors = cargoDraftErrors(draft, photo, state.adultConfirmed)
    val routeReady = errors.none { it in setOf("pickup", "delivery", "same_location", "time") }
    val cargoReady = errors.none { it in setOf("weight", "vehicle", "details", "photo") }
    val contactReady = errors.none { it in setOf("name", "phone") }
    val typeName = state.types.find { it.id == draft.vehicleType }?.name ?: draft.vehicleType
    fun submitCargoRequest(token: String?) {
        val (apiDate, apiTime) = requireNotNull(EthiopianDateTime.toApiDateTime(draft.date, draft.time)) { "Choose a valid Ethiopian date and time." }
        send(MobilityRequestInput("CARGO", draft.origin.trim(), draft.destination.trim(), apiDate,
            vehicleType = draft.vehicleType, cargoDetails = draft.details.trim(), customerName = draft.name.trim(),
            customerPhone = draft.phone.trim(), adultConfirmed = state.adultConfirmed,
            originLatitude = draft.pickup?.first, originLongitude = draft.pickup?.second,
            destinationLatitude = draft.delivery?.first, destinationLongitude = draft.delivery?.second,
            estimatedWeightQuintals = draft.weight.toDoubleOrNull(), travelTime = apiTime, otpVerificationToken = token), photo.bytes, photo.type)
    }
    LaunchedEffect(draft.pickup, draft.delivery) {
        val from = draft.pickup; val to = draft.delivery
        road = null
        if (from != null && to != null) {
            road = t("Calculating road distance…", "የመንገድ ርቀት በማስላት ላይ…", "Fageenya karaa shallagaa jira…")
            road = try {
                val result = mobilityBody(api.road(from.first, from.second, to.first, to.second))
                result.distanceKm?.takeIf { result.success }?.let { "${formatIntercityFare(it)} km · ${t("road distance", "የመንገድ ርቀት", "fageenya karaa")}" }
                    ?: t("Road distance unavailable. Drivers can still quote.", "ርቀቱ አልተገኘም። አሽከርካሪዎች ዋጋ ማቅረብ ይችላሉ።", "Fageenyi hin argamne. Konkolaachiftoonni gatii kennuu danda'u.")
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                t("Road distance unavailable. Drivers can still quote.", "ርቀቱ አልተገኘም። አሽከርካሪዎች ዋጋ ማቅረብ ይችላሉ።", "Fageenyi hin argamne. Konkolaachiftoonni gatii kennuu danda'u.")
            }
        }
    }
    if (picking != null) {
        val pickup = picking == "pickup"
        MobilityLocationPicker(t(if (pickup) "Choose pickup location" else "Choose destination location", if (pickup) "መነሻ ቦታ ይምረጡ" else "መድረሻ ቦታ ይምረጡ", if (pickup) "Bakka ka'umsaa filadhu" else "Bakka ga'umsaa filadhu"),
            if (pickup) draft.pickup else draft.delivery, { picking = null },
            { point -> draft = if (pickup) draft.copy(pickup = point) else draft.copy(delivery = point); picking = null },
            onPlaceName = { label -> draft = if (pickup) draft.copy(origin = label) else draft.copy(destination = label) }, language = language,
            initialLabel = if (pickup) draft.origin else draft.destination, providedApi = api,
            tileDownloadsEnabled = pickerTileDownloadsEnabled)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(t("Request cargo transport", "የጭነት መጓጓዣ ይጠይቁ", "Geejjiba fe'umsaa gaafadhu"), color = CommuteInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(t("Get quotes from available drivers.", "ካሉ አሽከርካሪዎች ዋጋ ያግኙ።", "Konkolaachiftoota jiran irraa gatii argadhu."), color = Color(0xFF4A6175), fontSize = 14.sp, lineHeight = 19.sp)
        }
        CargoSection(1, t("Route & pickup", "መስመር እና መነሻ", "Karaa fi ka'umsa"), expanded == 1, routeReady,
            { expanded = if (expanded == 1) 0 else 1 }, enabled) {
            if (expanded == 1) {
                CargoLocationField(t("Pickup location", "መነሻ ቦታ", "Bakka ka'umsaa"), draft.origin,
                    showErrors && ("pickup" in errors || "same_location" in errors), "request_origin", enabled) { picking = "pickup" }
                CargoLocationField(t("Destination location", "መድረሻ ቦታ", "Bakka ga'umsaa"), draft.destination,
                    showErrors && "delivery" in errors, "request_destination", enabled) { picking = "delivery" }
                if (showErrors && "same_location" in errors) CargoError(t("Choose different pickup and destination locations.", "የተለያዩ መነሻ እና መድረሻ ይምረጡ።", "Bakka ka'umsaa fi ga'umsaa adda filadhu."))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Map, null, tint = PassoMobilityPalette.brand, modifier = Modifier.size(22.dp))
                    Text(t("Search places or choose on map", "ቦታ ይፈልጉ ወይም በካርታ ይምረጡ", "Bakka barbaadi yookaan kaartaa irratti filadhu"), color = PassoMobilityPalette.muted, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1.15f)) { CargoDateSelector(draft.date, { draft = draft.copy(date = it) }, enabled, t("Date", "ቀን", "Guyyaa"), language) }
                    Box(Modifier.weight(1f)) { EthiopianTimeField(draft.time, { draft = draft.copy(time = it) }, t("Pickup time", "የመጫኛ ሰዓት", "Yeroo fe'umsaa"), "request_time", enabled, language) }
                }
                Text(listOfNotNull(road, t("Ethiopia time", "የኢትዮጵያ ሰዓት", "Yeroo Itoophiyaa")).joinToString(" · "),
                    color = PassoMobilityPalette.muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.testTag("cargo_road_distance"))
            } else CargoSummary(Icons.Default.Route, draft.origin.ifBlank { t("Choose pickup and destination", "መነሻ እና መድረሻ ይምረጡ", "Ka'umsaa fi ga'umsaa filadhu") }, draft.destination.takeIf { it.isNotBlank() })
        }
        CargoSection(2, t("Cargo & vehicle", "ጭነት እና ተሽከርካሪ", "Fe'umsaa fi konkolaataa"), expanded == 2, cargoReady,
            { expanded = if (expanded == 2) 0 else 2 }, enabled) {
            if (expanded == 2) {
                CargoInput(draft.weight, { draft = draft.copy(weight = it.filter { char -> char.isDigit() || char == '.' }.take(12)) },
                    t("Estimated weight (quintals)", "ግምታዊ ክብደት (ኩንታል)", "Ulfaatina tilmaamaa (quintal)"), "cargo_weight", showErrors && "weight" in errors, enabled, number = true)
                MobilityVehicleChoice(draft.vehicleType, state.types, { draft = draft.copy(vehicleType = it) }, enabled)
                if (showErrors && "vehicle" in errors) CargoError(t("Choose a vehicle type.", "የተሽከርካሪ አይነት ይምረጡ።", "Gosa konkolaataa filadhu."))
                OutlinedTextField(draft.details, { draft = draft.copy(details = it.take(4000)) }, label = { Text(t("Cargo details & handling needs", "የጭነት ዝርዝር እና አያያዝ", "Ibsa fe'umsaa fi kunuunsa barbaachisu")) },
                    supportingText = { Text(t("Include dimensions, package count and loading help.", "መጠን፣ የጥቅል ብዛት እና የመጫኛ እገዛን ይግለጹ።", "Hammi, baay'ina paakeejii fi gargaarsa fe'umsaa ibsi.")) },
                    minLines = 2, enabled = enabled, isError = showErrors && "details" in errors, shape = RoundedCornerShape(13.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
                        focusedTextColor = CommuteInk, unfocusedTextColor = CommuteInk, focusedLabelColor = CommuteTeal, unfocusedLabelColor = Color(0xFF596D7B)),
                    modifier = Modifier.fillMaxWidth().testTag("cargo_details"))
                CargoPhotoChoice(photo, state.busy)
                if (photo.bytes != null) CargoPhotoPreview(photo, Modifier.fillMaxWidth().height(130.dp))
                if (showErrors && "photo" in errors) CargoError(t("Add a photo of your cargo.", "የጭነትዎን ፎቶ ያክሉ።", "Suuraa fe'umsa keetii dabali."))
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                if (photo.bytes != null) CargoPhotoPreview(photo, Modifier.size(62.dp))
                else Icon(Icons.Default.LocalShipping, null, tint = PassoMobilityPalette.brand, modifier = Modifier.size(25.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (typeName.isBlank()) t("Choose cargo and vehicle", "ጭነት እና ተሽከርካሪ ይምረጡ", "Fe'umsaa fi konkolaataa filadhu") else "$typeName${draft.weight.takeIf { it.isNotBlank() }?.let { " · $it quintals" }.orEmpty()}", fontSize = 13.sp, color = PassoMobilityPalette.text)
                    draft.details.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, fontSize = 12.sp, color = Color(0xFF617285)) }
                }
            }
        }
        if (!hideContactDetails) CargoSection(3, t("Contact details", "የመገኛ ዝርዝር", "Odeeffannoo quunnamtii"), expanded == 3, contactReady,
            { expanded = if (expanded == 3) 0 else 3 }, enabled) {
            if (expanded == 3) {
                CargoInput(draft.name, { draft = draft.copy(name = it.take(160)) }, t("Your full name", "ሙሉ ስምዎ", "Maqaa guutuu kee"), "request_name", showErrors && "name" in errors, enabled)
                CargoInput(draft.phone, { draft = draft.copy(phone = it.take(25)) }, t("Phone number", "ስልክ ቁጥር", "Lakkoofsa bilbilaa"), "request_phone", showErrors && "phone" in errors, enabled, phone = true)
            } else CargoSummary(Icons.Default.PersonOutline, draft.name.ifBlank { t("Add your name and phone", "ስምዎን እና ስልክዎን ያክሉ", "Maqaa fi bilbila kee dabali") },
                draft.phone.takeIf { it.isNotBlank() }?.let { t("Phone added", "ስልክ ተጨምሯል", "Bilbilli dabalameera") })
        }
        Surface(color = PassoMobilityPalette.card, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) { onAdult(!state.adultConfirmed) }.padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(state.adultConfirmed, { onAdult(it) }, enabled = enabled, modifier = Modifier.testTag("cargo_consent"), colors = CheckboxDefaults.colors(checkedColor = CommuteTeal))
                Text(t("I am 18 or older and may share these contact details.", "ዕድሜዬ 18 ወይም ከዚያ በላይ ነው፤ ይህን የመገኛ መረጃ ለማጋራት ፈቃድ አለኝ።", "Umuriin koo 18 ol; odeeffannoo quunnamtii kana qooduu nan danda'a."), color = PassoMobilityPalette.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
            }
        }
        if (showErrors && "consent" in errors) CargoError(t("Please confirm the age and contact permission statement.", "የዕድሜ እና የመገኛ ፈቃድ መግለጫውን ያረጋግጡ።", "Ibsa umurii fi hayyama quunnamtii mirkaneessi."))
        if (errors.isEmpty()) Surface(color = CommuteMint, shape = RoundedCornerShape(11.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = CommuteTeal, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(t("All required details added", "ሁሉም አስፈላጊ ዝርዝሮች ተጨምረዋል", "Odeeffannoon barbaachisu hundi dabalameera"), fontSize = 11.sp, lineHeight = 14.sp, color = CommuteTeal)
            }
        }
        MobilityButton(t("Review request", "ጥያቄውን ይገምግሙ", "Gaaffii ilaali"), enabled, "cargo_review") {
            focus.clearFocus(); showErrors = true
            if (errors.isEmpty()) reviewing = true
            else expanded = when { !routeReady -> 1; !cargoReady -> 2; !contactReady -> 3; else -> 0 }
        }
        Text(t("You choose a driver before confirming a booking.", "ቦታ ማስያዙን ከማረጋገጥዎ በፊት አሽከርካሪ ይምረጡ።", "Ajaja mirkaneessuu dura konkolaachisaa filatta."), fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFF617285), modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    if (reviewing) AlertDialog(onDismissRequest = { reviewing = false }, containerColor = CommuteCream, tonalElevation = 0.dp,
        titleContentColor = CommuteInk, textContentColor = CommuteInk,
        title = { Text(t("Review cargo request", "የጭነት ጥያቄን ይገምግሙ", "Gaaffii fe'umsaa ilaali"), color = CommuteInk, fontWeight = FontWeight.Bold) }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${draft.origin} → ${draft.destination}", fontWeight = FontWeight.Bold, color = CommuteInk)
            Text("${EthiopianDateTime.formatDate(draft.date, language)} · ${EthiopianDateTime.formatClock(draft.time, language)} · ${t("Ethiopia time", "የኢትዮጵያ ሰዓት", "Yeroo Itoophiyaa")}")
            road?.let { Text(it, color = CommuteTeal) }
            HorizontalDivider(); Text("$typeName · ${draft.weight} quintals", fontWeight = FontWeight.Bold); Text(draft.details)
            CargoPhotoPreview(photo, Modifier.fillMaxWidth().height(120.dp))
            HorizontalDivider(); Text(draft.name); Text(draft.phone)
            Text(t("Available drivers can send offers. You choose your driver and agreed price. No payment is collected when you send this request.", "ያሉ አሽከርካሪዎች ዋጋ ማቅረብ ይችላሉ። አሽከርካሪዎን እና ዋጋዎን እርስዎ ይምረጡ። ይህን ጥያቄ ሲልኩ ክፍያ አይሰበሰብም።", "Konkolaachiftoonni gatii kennu. Konkolaachisaa fi gatii filatta. Gaaffii kana erguuf kaffaltiin hin jiru."), fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (errors.isEmpty()) {
                reviewing = false
                if (hideContactDetails) submitCargoRequest(null)
                else { verifyingPhone = true; otpSent = false; phoneCode = ""; otpMessage = null; resendSeconds = 0 }
            }
        }, enabled = enabled && errors.isEmpty(), modifier = Modifier.testTag("mobility_send_request")) { Text(t("Send request", "ጥያቄ ላክ", "Gaaffii ergi"), color = CommuteTeal, fontWeight = FontWeight.Bold) }
    }, dismissButton = { TextButton(onClick = { reviewing = false }) { Text(t("Edit details", "ዝርዝሮችን አርትዕ", "Odeeffannoo sirreessi")) } })
    fun sendCargoOtp() {
        if (otpBusy || resendSeconds > 0) return
        otpBusy = true
        scope.launch {
            try {
                val result = mobilityBody(api.requestContactOtp(mapOf("phone" to draft.phone.trim(), "purpose" to "CARGO_REQUEST")))
                check(result.success && result.delivery == "SENT") { result.error ?: "The code could not be sent. Try again." }
                otpSent = true; phoneCode = ""; otpMessage = null; resendSeconds = 45
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { otpMessage = com.example.core.network.userFacingNetworkError(failure) }
            finally { otpBusy = false }
        }
    }
    if (verifyingPhone) Dialog(onDismissRequest = { if (!otpBusy) verifyingPhone = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (!otpSent) Surface(color = Color.White, shape = RoundedCornerShape(30.dp), shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth(.91f).testTag("cargo_phone_dialog")) {
            Column(Modifier.padding(horizontal = 21.dp, vertical = 19.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CommuteMint, shape = RoundedCornerShape(17.dp), modifier = Modifier.size(54.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.PhoneAndroid, null, tint = Color(0xFF087C42), modifier = Modifier.size(29.dp)) }
                    }
                    Text(t("Verify your phone number", "ስልክ ቁጥርዎን ያረጋግጡ", "Lakkoofsa bilbilaa kee mirkaneessi"), Modifier.weight(1f).padding(start = 13.dp), color = CommuteInk, fontSize = 21.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold)
                    IconButton(onClick = { if (!otpBusy) verifyingPhone = false }) { Icon(Icons.Default.Close, "Close", tint = Color(0xFF55656C)) }
                }
                Text(t("We’ll send a one-time verification code (OTP) to this number before publishing your cargo request.", "የጭነት ጥያቄዎን ከማተምዎ በፊት የአንድ ጊዜ ማረጋገጫ ኮድ ወደዚህ ቁጥር እንልካለን።", "Gaaffii fe'umsaa kee maxxansuu dura koodii yeroo tokko lakkoofsa kanaaf ergina."), color = Color(0xFF586870), fontSize = 13.sp, lineHeight = 19.sp)
                Surface(color = Color.White, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.7.dp, Color(0xFF079653)), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.height(57.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🇪🇹", fontSize = 21.sp); Text("  +251", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        VerticalDivider(Modifier.padding(horizontal = 12.dp).height(36.dp), color = Color(0xFFD9E0E3))
                        Text(cargoPhoneLocal(draft.phone), color = CommuteInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).testTag("cargo_otp_phone"))
                    }
                }
                otpMessage?.let { Text(it, color = if (it.contains("sent", true)) CommuteTeal else Color(0xFFB13B42), fontSize = 12.sp) }
                Button(onClick = ::sendCargoOtp, enabled = !otpBusy && resendSeconds == 0, modifier = Modifier.fillMaxWidth().height(54.dp).testTag("cargo_send_otp"), shape = RoundedCornerShape(28.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF079653))) {
                    Text(if (otpBusy) t("Sending code…", "ኮዱን በመላክ ላይ…", "Koodii ergaa jira…") else if (resendSeconds > 0) t("Resend in 00:${"%02d".format(resendSeconds)}", "በ00:${"%02d".format(resendSeconds)} እንደገና ላክ", "00:${"%02d".format(resendSeconds)} booda irra deebi'i") else t("Send OTP", "OTP ላክ", "OTP ergi"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowForward, null)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF07864E), modifier = Modifier.size(22.dp))
                    VerticalDivider(Modifier.padding(horizontal = 10.dp).height(35.dp), color = Color(0xFFDDE3E4))
                    Text(t("Your number is used to confirm this cargo request and receive trip updates.", "ቁጥርዎ ይህን ጥያቄ ለማረጋገጥና የጉዞ ማሻሻያዎችን ለመቀበል ይጠቅማል።", "Lakkoofsi kee gaaffii kana mirkaneessuu fi haaromsa imalaa argachuuf fayyada."), color = Color(0xFF617078), fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
        } else {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
            Surface(color = Color(0xFFF8FBFC), shape = RoundedCornerShape(0.dp), modifier = Modifier.fillMaxSize().testTag("cargo_otp_dialog")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (!otpBusy) { otpSent = false; phoneCode = ""; otpMessage = null } }) { Icon(Icons.Default.ArrowBack, "Back", tint = CommuteInk) }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = ::sendCargoOtp, enabled = !otpBusy && resendSeconds == 0) {
                            Text(if (resendSeconds > 0) t("Resend in 00:${"%02d".format(resendSeconds)}", "በ00:${"%02d".format(resendSeconds)} እንደገና ላክ", "00:${"%02d".format(resendSeconds)} booda irra deebi'i") else t("Resend code", "ኮዱን እንደገና ላክ", "Koodii irra deebi'i"), color = CommuteTeal, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(42.dp))
                    Surface(color = CommuteMint, shape = RoundedCornerShape(34.dp), modifier = Modifier.size(112.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.VerifiedUser, null, tint = Color(0xFF079653), modifier = Modifier.size(68.dp)) }
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(t("Verify your number", "ቁጥርዎን ያረጋግጡ", "Lakkoofsa kee mirkaneessi"), color = CommuteInk, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(7.dp))
                    Text(t("Enter the 6-digit code sent to", "ወደዚህ የተላከውን 6 አሃዝ ኮድ ያስገቡ", "Koodii lakkoofsa 6 qabu gara kana ergame galchi"), color = Color(0xFF596871), fontSize = 15.sp, textAlign = TextAlign.Center)
                    Text(draft.phone, color = CommuteInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(25.dp))
                    CargoOtpCodeField(phoneCode, enabled = !otpBusy, focusRequester = focusRequester, modifier = Modifier.fillMaxWidth().testTag("cargo_guest_otp")) { phoneCode = it }
                    otpMessage?.let { Text(it, Modifier.padding(top = 9.dp), color = if (it.contains("sent", true)) CommuteTeal else Color(0xFFAC2F2F), fontSize = 12.sp) }
                    Spacer(Modifier.height(25.dp))
                    Button(onClick = {
                        if (otpBusy) return@Button
                        otpBusy = true
                        scope.launch {
                            try {
                                val result = mobilityBody(api.verifyContactOtp(mapOf("phone" to draft.phone.trim(), "purpose" to "CARGO_REQUEST", "code" to phoneCode.trim())))
                                check(result.success) { result.error ?: "That code is incorrect or expired." }
                                val token = result.verificationToken?.takeIf { it.length >= 20 } ?: error("The code could not be confirmed. Try again.")
                                verifyingPhone = false; submitCargoRequest(token)
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { otpMessage = com.example.core.network.userFacingNetworkError(failure) }
                            finally { otpBusy = false }
                        }
                    }, enabled = !otpBusy && phoneCode.length == 6, shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF079653)), modifier = Modifier.fillMaxWidth().height(54.dp).testTag("cargo_verify_otp")) {
                        Text(if (otpBusy) t("Verifying…", "በማረጋገጥ ላይ…", "Mirkaneessaa jira…") else t("Verify & Continue", "አረጋግጥ እና ቀጥል", "Mirkaneessi fi itti fufi"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.width(9.dp)); Icon(Icons.Default.ArrowForward, null)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(t("Didn’t receive the code?", "ኮዱ አልደረሰዎትም?", "Koodiin si hin geenye?"), color = CommuteInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = ::sendCargoOtp, enabled = !otpBusy && resendSeconds == 0) {
                        Text(if (resendSeconds > 0) t("⟳  Resend in 00:${"%02d".format(resendSeconds)}", "⟳ በ00:${"%02d".format(resendSeconds)} እንደገና ላክ", "⟳ 00:${"%02d".format(resendSeconds)} booda irra deebi'i") else t("⟳  Resend Code", "⟳ ኮዱን እንደገና ላክ", "⟳ Koodii irra deebi'i"), color = if (resendSeconds == 0) CommuteTeal else Color(0xFF96A1A6), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(7.dp))
                    Surface(color = CommuteMint.copy(alpha = .76f), shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, null, tint = CommuteInk); Text("  ${t("Code expires in 5 minutes", "ኮዱ በ5 ደቂቃ ውስጥ ያበቃል", "Koodiin daqiiqaa 5 keessatti dhuma")}", color = CommuteInk, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun cargoPhoneLocal(phone: String): String {
    val value = phone.trim().replace(" ", "").replace("-", "")
    return when {
        value.startsWith("+251") -> value.removePrefix("+251").removePrefix("0").ifBlank { value }
        value.startsWith("251") -> value.removePrefix("251").removePrefix("0").ifBlank { value }
        value.startsWith("0") -> value.drop(1)
        else -> value
    }
}

@Composable private fun CargoOtpCodeField(value: String, enabled: Boolean, focusRequester: FocusRequester,
    modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(modifier) {
        BasicTextField(value = value, onValueChange = { onChange(it.filter(Char::isDigit).take(6)) }, enabled = enabled,
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), cursorBrush = SolidColor(CommuteTeal),
            modifier = Modifier.size(1.dp).alpha(.01f).focusRequester(focusRequester).testTag("cargo_guest_otp_input"))
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { focusRequester.requestFocus() }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(6) { index ->
                val focused = index == value.length && value.length < 6
                Surface(color = Color.White, shape = RoundedCornerShape(11.dp), border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) CommuteTeal else Color(0xFFD9E1E4)), modifier = Modifier.weight(1f).height(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(value.getOrNull(index)?.toString().orEmpty(), color = CommuteInk, fontSize = 21.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable private fun CargoSection(number: Int, title: String, expanded: Boolean, complete: Boolean,
    toggle: () -> Unit, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = PassoMobilityPalette.card, contentColor = PassoMobilityPalette.text, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, PassoMobilityPalette.border), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = toggle).heightIn(min = 36.dp).testTag("cargo_section_$number")
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = CommuteTeal, shape = CircleShape, modifier = Modifier.size(31.dp)) { Box(contentAlignment = Alignment.Center) { Text(number.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
                Text(title, color = PassoMobilityPalette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (complete && !expanded) Icon(Icons.Default.CheckCircle, "Complete", tint = CommuteTeal, modifier = Modifier.size(17.dp))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight, if (expanded) "Collapse" else "Expand", tint = PassoMobilityPalette.text, modifier = Modifier.size(22.dp))
            }
            content()
        }
    }
}

@Composable internal fun CargoLocationField(label: String, value: String, error: Boolean, tag: String, enabled: Boolean, choose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        CargoOutlinedSlot(label, error, tag, enabled, onClick = choose, showFloatingLabel = false) {
            Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Text(value.ifBlank { label }, color = if (value.isBlank()) Color(0xFF617285) else CommuteInk, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = CommuteInk, modifier = Modifier.size(20.dp))
        }
        if (error) CargoError("Select and confirm this location on the map.")
    }
}

@Composable private fun CargoOutlinedSlot(label: String, error: Boolean, tag: String, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit, showFloatingLabel: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Box(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Surface(color = Color.White, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else Color(0xFFC7CED2)),
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
        if (showFloatingLabel) Text(label, Modifier.offset(x = 10.dp, y = (-6).dp).background(Color.White).padding(horizontal = 3.dp), color = Color(0xFF617285), fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@Composable private fun CargoDateSelector(value: String, onValue: (String) -> Unit, enabled: Boolean, label: String, language: AppLanguage) {
    var open by remember { mutableStateOf(false) }
    val formatted = remember(value, language) { EthiopianDateTime.formatDate(value, language) }
    CargoOutlinedSlot(label, false, "cargo_pickup_date", enabled, onClick = { open = true }) {
        Icon(Icons.Default.CalendarMonth, null, tint = CommuteTeal, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp))
        Text(formatted, color = CommuteInk, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = CommuteInk, modifier = Modifier.size(16.dp))
    }
    if (open) EthiopianDatePickerDialog(value, EthiopianDateTime.todayIso(), language,
        onDismiss = { open = false }, onSelect = { onValue(it); open = false })
}

@Composable private fun CargoInput(value: String, onValue: (String) -> Unit, label: String, tag: String,
    error: Boolean, enabled: Boolean, number: Boolean = false, phone: Boolean = false) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = error, supportingText = if (error) ({ Text(if (phone) "Enter a valid phone number, including country code if needed." else "Please complete this field.") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = when { phone -> KeyboardType.Phone; number -> KeyboardType.Decimal; else -> KeyboardType.Text }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.White, unfocusedContainerColor = Color.White,
            focusedTextColor = CommuteInk, unfocusedTextColor = CommuteInk, focusedLabelColor = CommuteTeal, unfocusedLabelColor = Color(0xFF596D7B)),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable private fun CargoSummary(icon: ImageVector, first: String, second: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, tint = CommuteTeal, modifier = Modifier.size(23.dp))
        Text(first + second?.let { " · $it" }.orEmpty(), color = PassoMobilityPalette.text, fontSize = 13.sp, maxLines = 2)
    }
}

@Composable private fun CargoError(text: String) { Text(text, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }

@Composable private fun CargoPhotoPreview(photo: CargoPhotoState, modifier: Modifier) {
    var bitmap by remember(photo.bytes) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(photo.bytes) {
        val bytes = photo.bytes ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1; while (bounds.outWidth / sample > 600 || bounds.outHeight / sample > 600) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    bitmap?.let { Image(it.asImageBitmap(), "Selected cargo photo", modifier = modifier.clip(RoundedCornerShape(9.dp)).testTag("cargo_photo_preview"), contentScale = ContentScale.Crop) }
}
