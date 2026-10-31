package com.example.ui.screens.intercity

import android.app.TimePickerDialog
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.api.ApiClient
import com.example.data.api.MobilityApi
import com.example.data.api.MobilityRequestInput
import com.example.ui.screens.passenger.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Only form values are saved. Photo bytes and private request access keep their existing storage rules. */
internal data class CargoRequestDraft(
    val origin: String = "", val destination: String = "", val date: String = intercityToday(),
    val time: String = "08:00", val weight: String = "", val vehicleType: String = "",
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
    val kg = draft.weight.toDoubleOrNull()
    if (kg == null || !kg.isFinite() || kg <= 0) add("weight")
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
    providedApi: MobilityApi? = null) {
    fun t(en: String, am: String, om: String) = intercityText(language, en, am, om)
    var draft by rememberSaveable(stateSaver = CargoDraftSaver) { mutableStateOf(initialDraft) }
    var expanded by rememberSaveable { mutableIntStateOf(1) }
    var showErrors by remember { mutableStateOf(false) }; var reviewing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }
    var road by remember { mutableStateOf<String?>(null) }
    val api = providedApi ?: remember { ApiClient.createFeatureService(MobilityApi::class.java) }
    val photo = providedPhoto ?: rememberCargoPhoto()
    val context = LocalContext.current; val focus = LocalFocusManager.current
    val enabled = !state.busy && !photo.busy
    val errors = cargoDraftErrors(draft, photo, state.adultConfirmed)
    val routeReady = errors.none { it in setOf("pickup", "delivery", "same_location", "time") }
    val cargoReady = errors.none { it in setOf("weight", "vehicle", "details", "photo") }
    val contactReady = errors.none { it in setOf("name", "phone") }
    val typeName = state.types.find { it.id == draft.vehicleType }?.name ?: draft.vehicleType
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
            initialLabel = if (pickup) draft.origin else draft.destination)
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
                    Icon(Icons.Default.Map, null, tint = CommuteTeal, modifier = Modifier.size(22.dp))
                    Text(t("Search places or choose on map", "ቦታ ይፈልጉ ወይም በካርታ ይምረጡ", "Bakka barbaadi yookaan kaartaa irratti filadhu"), color = CommuteTeal, fontSize = 12.sp, lineHeight = 17.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1.15f)) { CargoDateSelector(draft.date, { draft = draft.copy(date = it) }, enabled, t("Date", "ቀን", "Guyyaa")) }
                    CargoOutlinedSlot(t("Time", "ሰዓት", "Yeroo"), false, "request_time", enabled, Modifier.weight(1f), onClick = {
                        val parts = draft.time.split(':'); TimePickerDialog(context, { _, hour, minute -> draft = draft.copy(time = String.format(Locale.US, "%02d:%02d", hour, minute)) }, parts.firstOrNull()?.toIntOrNull() ?: 8, parts.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
                    }) {
                        Icon(Icons.Default.Schedule, null, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(5.dp)); Text(draft.time, color = CommuteInk)
                    }
                }
                Text(listOfNotNull(road, t("Ethiopia time", "የኢትዮጵያ ሰዓት", "Yeroo Itoophiyaa")).joinToString(" · "),
                    color = CommuteTeal, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.testTag("cargo_road_distance"))
            } else CargoSummary(Icons.Default.Route, draft.origin.ifBlank { t("Choose pickup and destination", "መነሻ እና መድረሻ ይምረጡ", "Ka'umsaa fi ga'umsaa filadhu") }, draft.destination.takeIf { it.isNotBlank() })
        }
        CargoSection(2, t("Cargo & vehicle", "ጭነት እና ተሽከርካሪ", "Fe'umsaa fi konkolaataa"), expanded == 2, cargoReady,
            { expanded = if (expanded == 2) 0 else 2 }, enabled) {
            if (expanded == 2) {
                CargoInput(draft.weight, { draft = draft.copy(weight = it.filter { char -> char.isDigit() || char == '.' }.take(12)) },
                    t("Estimated weight (kg)", "ግምታዊ ክብደት (ኪ.ግ)", "Ulfaatina tilmaamaa (kg)"), "cargo_weight", showErrors && "weight" in errors, enabled, number = true)
                MobilityVehicleChoice(draft.vehicleType, state.types, { draft = draft.copy(vehicleType = it) }, enabled)
                if (showErrors && "vehicle" in errors) CargoError(t("Choose a vehicle type.", "የተሽከርካሪ አይነት ይምረጡ።", "Gosa konkolaataa filadhu."))
                OutlinedTextField(draft.details, { draft = draft.copy(details = it.take(4000)) }, label = { Text(t("Cargo details & handling needs", "የጭነት ዝርዝር እና አያያዝ", "Ibsa fe'umsaa fi kunuunsa barbaachisu")) },
                    supportingText = { Text(t("Include dimensions, package count and loading help.", "መጠን፣ የጥቅል ብዛት እና የመጫኛ እገዛን ይግለጹ።", "Hammi, baay'ina paakeejii fi gargaarsa fe'umsaa ibsi.")) },
                    minLines = 2, enabled = enabled, isError = showErrors && "details" in errors, shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().testTag("cargo_details"))
                CargoPhotoChoice(photo, state.busy)
                if (photo.bytes != null) CargoPhotoPreview(photo, Modifier.fillMaxWidth().height(130.dp))
                if (showErrors && "photo" in errors) CargoError(t("Add a photo of your cargo.", "የጭነትዎን ፎቶ ያክሉ።", "Suuraa fe'umsa keetii dabali."))
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                if (photo.bytes != null) CargoPhotoPreview(photo, Modifier.size(62.dp))
                else Icon(Icons.Default.LocalShipping, null, tint = CommuteTeal, modifier = Modifier.size(25.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (typeName.isBlank()) t("Choose cargo and vehicle", "ጭነት እና ተሽከርካሪ ይምረጡ", "Fe'umsaa fi konkolaataa filadhu") else "$typeName${draft.weight.takeIf { it.isNotBlank() }?.let { " · $it kg" }.orEmpty()}", fontSize = 13.sp, color = CommuteInk)
                    draft.details.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 1, fontSize = 12.sp, color = Color(0xFF617285)) }
                }
            }
        }
        CargoSection(3, t("Contact details", "የመገኛ ዝርዝር", "Odeeffannoo quunnamtii"), expanded == 3, contactReady,
            { expanded = if (expanded == 3) 0 else 3 }, enabled) {
            if (expanded == 3) {
                CargoInput(draft.name, { draft = draft.copy(name = it.take(160)) }, t("Your full name", "ሙሉ ስምዎ", "Maqaa guutuu kee"), "request_name", showErrors && "name" in errors, enabled)
                CargoInput(draft.phone, { draft = draft.copy(phone = it.take(25)) }, t("Phone number", "ስልክ ቁጥር", "Lakkoofsa bilbilaa"), "request_phone", showErrors && "phone" in errors, enabled, phone = true)
            } else CargoSummary(Icons.Default.PersonOutline, draft.name.ifBlank { t("Add your name and phone", "ስምዎን እና ስልክዎን ያክሉ", "Maqaa fi bilbila kee dabali") },
                draft.phone.takeIf { it.isNotBlank() }?.let { t("Phone added", "ስልክ ተጨምሯል", "Bilbilli dabalameera") })
        }
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) { onAdult(!state.adultConfirmed) }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(state.adultConfirmed, { onAdult(it) }, enabled = enabled, modifier = Modifier.testTag("cargo_consent"), colors = CheckboxDefaults.colors(checkedColor = CommuteTeal))
            Text(t("I am 18 or older and may share these contact details.", "ዕድሜዬ 18 ወይም ከዚያ በላይ ነው፤ ይህን የመገኛ መረጃ ለማጋራት ፈቃድ አለኝ።", "Umuriin koo 18 ol; odeeffannoo quunnamtii kana qooduu nan danda'a."), color = CommuteInk, fontSize = 11.sp, modifier = Modifier.weight(1f))
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
    if (reviewing) AlertDialog(onDismissRequest = { reviewing = false }, title = { Text(t("Review cargo request", "የጭነት ጥያቄን ይገምግሙ", "Gaaffii fe'umsaa ilaali"), color = CommuteInk) }, text = {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${draft.origin} → ${draft.destination}", fontWeight = FontWeight.Bold, color = CommuteInk)
            Text("${draft.date} · ${draft.time} · ${t("Ethiopia time", "የኢትዮጵያ ሰዓት", "Yeroo Itoophiyaa")}")
            road?.let { Text(it, color = CommuteTeal) }
            HorizontalDivider(); Text("$typeName · ${draft.weight} kg", fontWeight = FontWeight.Bold); Text(draft.details)
            CargoPhotoPreview(photo, Modifier.fillMaxWidth().height(120.dp))
            HorizontalDivider(); Text(draft.name); Text(draft.phone)
            Text(t("Available drivers can send offers. You choose your driver and agreed price. No payment is collected when you send this request.", "ያሉ አሽከርካሪዎች ዋጋ ማቅረብ ይችላሉ። አሽከርካሪዎን እና ዋጋዎን እርስዎ ይምረጡ። ይህን ጥያቄ ሲልኩ ክፍያ አይሰበሰብም።", "Konkolaachiftoonni gatii kennu. Konkolaachisaa fi gatii filatta. Gaaffii kana erguuf kaffaltiin hin jiru."), fontSize = 12.sp)
        }
    }, confirmButton = {
        TextButton(onClick = {
            if (errors.isEmpty()) {
                reviewing = false
                send(MobilityRequestInput("CARGO", draft.origin.trim(), draft.destination.trim(), draft.date,
                    vehicleType = draft.vehicleType, cargoDetails = draft.details.trim(), customerName = draft.name.trim(),
                    customerPhone = draft.phone.trim(), adultConfirmed = state.adultConfirmed,
                    originLatitude = draft.pickup?.first, originLongitude = draft.pickup?.second,
                    destinationLatitude = draft.delivery?.first, destinationLongitude = draft.delivery?.second,
                    estimatedWeightKg = draft.weight.toDoubleOrNull(), travelTime = draft.time), photo.bytes, photo.type)
            }
        }, enabled = enabled && errors.isEmpty(), modifier = Modifier.testTag("mobility_send_request")) { Text(t("Send request", "ጥያቄ ላክ", "Gaaffii ergi"), color = CommuteTeal, fontWeight = FontWeight.Bold) }
    }, dismissButton = { TextButton(onClick = { reviewing = false }) { Text(t("Edit details", "ዝርዝሮችን አርትዕ", "Odeeffannoo sirreessi")) } })
}

@Composable private fun CargoSection(number: Int, title: String, expanded: Boolean, complete: Boolean,
    toggle: () -> Unit, enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = Color.White, shape = RoundedCornerShape(17.dp), border = BorderStroke(1.dp, Color(0xFFE3E6E5)), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = toggle).heightIn(min = 36.dp).testTag("cargo_section_$number")
                .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(color = CommuteTeal, shape = CircleShape, modifier = Modifier.size(31.dp)) { Box(contentAlignment = Alignment.Center) { Text(number.toString(), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) } }
                Text(title, color = CommuteInk, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (complete && !expanded) Icon(Icons.Default.CheckCircle, "Complete", tint = CommuteTeal, modifier = Modifier.size(17.dp))
                Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight, if (expanded) "Collapse" else "Expand", tint = CommuteInk, modifier = Modifier.size(22.dp))
            }
            content()
        }
    }
}

@Composable private fun CargoLocationField(label: String, value: String, error: Boolean, tag: String, enabled: Boolean, choose: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        CargoOutlinedSlot(label, error, tag, enabled, onClick = choose) {
            Icon(Icons.Default.LocationOn, null, tint = CommuteTeal, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(9.dp))
            Text(value.ifBlank { label }, color = if (value.isBlank()) Color(0xFF617285) else CommuteInk, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2, modifier = Modifier.weight(1f))
            Icon(Icons.Default.ChevronRight, null, tint = CommuteInk, modifier = Modifier.size(20.dp))
        }
        if (error) CargoError("Select and confirm this location on the map.")
    }
}

@Composable private fun CargoOutlinedSlot(label: String, error: Boolean, tag: String, enabled: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Box(modifier.fillMaxWidth().padding(top = 6.dp)) {
        Surface(color = Color.White, shape = RoundedCornerShape(11.dp), border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else Color(0xFFC7CED2)),
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag)) {
            Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
        }
        Text(label, Modifier.offset(x = 10.dp, y = (-6).dp).background(Color.White).padding(horizontal = 3.dp), color = Color(0xFF617285), fontSize = 11.sp, lineHeight = 14.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CargoDateSelector(value: String, onValue: (String) -> Unit, enabled: Boolean, label: String) {
    var open by remember { mutableStateOf(false) }
    val parser = remember { java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") } }
    val formatted = remember(value) { runCatching { java.text.SimpleDateFormat("EEE, d MMM yyyy", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(parser.parse(value)!!) }.getOrDefault(value) }
    CargoOutlinedSlot(label, false, "cargo_pickup_date", enabled, onClick = { open = true }) {
        Icon(Icons.Default.CalendarMonth, null, tint = CommuteTeal, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(6.dp))
        Text(formatted, color = CommuteInk, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1, modifier = Modifier.weight(1f))
        Icon(Icons.Default.ChevronRight, null, tint = CommuteInk, modifier = Modifier.size(16.dp))
    }
    if (open) {
        val today = parser.parse(intercityToday())!!.time
        val picker = rememberDatePickerState(initialSelectedDateMillis = runCatching { parser.parse(value)?.time }.getOrNull(), selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= today
        })
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { onValue(parser.format(java.util.Date(it))) }; open = false }) { Text("Choose") } },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }) { DatePicker(picker) }
    }
}

@Composable private fun CargoInput(value: String, onValue: (String) -> Unit, label: String, tag: String,
    error: Boolean, enabled: Boolean, number: Boolean = false, phone: Boolean = false) {
    OutlinedTextField(value, onValue, label = { Text(label) }, singleLine = true, enabled = enabled,
        isError = error, supportingText = if (error) ({ Text(if (phone) "Enter a valid phone number, including country code if needed." else "Please complete this field.") }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = when { phone -> KeyboardType.Phone; number -> KeyboardType.Decimal; else -> KeyboardType.Text }),
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable private fun CargoSummary(icon: ImageVector, first: String, second: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Icon(icon, null, tint = CommuteTeal, modifier = Modifier.size(23.dp))
        Text(first + second?.let { " · $it" }.orEmpty(), color = CommuteInk, fontSize = 13.sp, maxLines = 2)
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
