package com.example.ui.screens.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.api.TransportCompanyDto
import com.example.data.api.VehicleTypeOption
import com.example.data.entity.RouteEntity
import com.example.data.repository.DriverApplicationRepository
import com.example.ui.screens.passenger.*

@Composable
internal fun AuthRegistrationDetails(
    draft: AuthDraft, driver: Boolean, routes: List<RouteEntity>, companies: List<TransportCompanyDto>,
    companyError: String?, language: AppLanguage, enabled: Boolean,
    vehicleTypes: List<VehicleTypeOption> = VehicleTypeOption.DEFAULTS,
    vehicleTypesLoading: Boolean = false, vehicleTypesError: String? = null,
    applications: DriverApplicationRepository? = null
) {
    fun t(en: String, am: String, om: String) = authText(language, en, am, om)
    Column(Modifier.fillMaxWidth().testTag("auth_details"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (driver) t("Driver details", "የሹፌር ዝርዝር", "Odeeffannoo konkolaachisaa")
            else if (draft.isCustomCommute) t("Request your daily route", "የዕለት መስመርዎን ይጠይቁ", "Karaa guyyaa kee gaafadhu")
            else t("Choose your daily route", "የዕለት መስመርዎን ይምረጡ", "Karaa guyyaa kee filadhu"),
            color = CommuteInk, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (driver) {
            Text(t("Apply now, follow your status, and upload documents after initial acceptance.", "አሁን ያመልክቱ፣ ሁኔታዎን ይከታተሉ፣ ከመጀመሪያ ተቀባይነት በኋላ ሰነዶችን ይላኩ።", "Amma iyyadhu, haala kee hordofi, fudhatama jalqabaa booda sanadoota olkaa'i."), fontSize = 13.sp, color = CommuteInk)
            AuthField(draft.license, { draft.license = it }, t("Commercial driving licence number", "የንግድ መንጃ ፈቃድ ቁጥር", "Lakkoofsa eeyyama konkolaachisummaa"),
                Modifier.fillMaxWidth().testTag("auth_driver_license_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.Badge)
            Text(t("Service type", "የአገልግሎት ዓይነት", "Gosa tajaajilaa"), color = CommuteInk, fontWeight = FontWeight.SemiBold)
            AuthChoiceChips(listOf("Daily Commuter" to t("Daily commute", "የዕለት ጉዞ", "Imala guyyaa"), "Intercity" to t("Intercity", "የከተማ መካከል", "Magaalotaa"),
                "Group Travel" to t("Group travel", "የቡድን ጉዞ", "Imala garee"), "Cargo" to t("Cargo", "ጭነት", "Fe'umsa")),
                draft.service, enabled, { draft.service = it }, "auth_service")
            if (draft.service == "Intercity") {
                AuthChoiceDropdown(draft.company, t("Transport company", "የትራንስፖርት ኩባንያ", "Dhaabbata geejjibaa"), companies.map { it.id to it.name }, { draft.company = it }, enabled, "auth_driver_company")
                companyError?.let { Text(it, color = Color(0xFFAC2F2F), fontSize = 12.sp) }
                Text(t("Your company will assign a vehicle and trip after approval.", "ከተፈቀደ በኋላ ኩባንያዎ ተሽከርካሪ እና ጉዞ ይመድባል።", "Erga hayyamame booda dhaabbanni kee konkolaataa fi imala siif ramada."), color = CommuteInk, fontSize = 13.sp)
            } else {
                AuthField(draft.plate, { draft.plate = it.uppercase() }, t("Your vehicle plate number", "የተሽከርካሪዎ ሰሌዳ ቁጥር", "Lakkoofsa gabatee konkolaataa kee"),
                    Modifier.fillMaxWidth().testTag("auth_driver_vehicle_plate_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.DirectionsBus)
                AuthField(draft.vehicleModel, { draft.vehicleModel = it }, t("Vehicle make / model", "የተሽከርካሪ ሞዴል", "Gosa / moodeela konkolaataa"),
                    Modifier.fillMaxWidth().testTag("auth_driver_vehicle_model_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.DirectionsBus)
                AuthChoiceDropdown(draft.vehicleType, t("Vehicle type", "የተሽከርካሪ ዓይነት", "Gosa konkolaataa"),
                    vehicleTypes.map { it.code to if (draft.service == "Cargo") it.name else "${it.name} · ${it.capacity} seats" },
                    { draft.vehicleType = it }, enabled && !vehicleTypesLoading && vehicleTypes.isNotEmpty(), "auth_driver_vehicle_type")
                if (vehicleTypesLoading) Text(t("Loading vehicle types…", "የተሽከርካሪ ዓይነቶችን በመጫን ላይ…", "Gosa konkolaataa fe'aa jira…"), color = CommuteInk, fontSize = 12.sp)
                else if (vehicleTypes.isEmpty()) Text(t("No vehicle types are currently available. Contact your administrator.", "አሁን የተሽከርካሪ ዓይነቶች የሉም። አስተዳዳሪዎን ያነጋግሩ።", "Gosti konkolaataa hin jiru. Bulchaa kee qunnami."), color = Color(0xFFAC2F2F), fontSize = 12.sp)
                vehicleTypesError?.let { Text(it, color = Color(0xFFAC2F2F), fontSize = 12.sp) }
                if (draft.service == "Daily Commuter" && applications != null) DriverRouteProposalFields(draft, applications, language, enabled)
                else {
                    AuthField(draft.pickup, { draft.pickup = it }, t("Preferred departure", "የሚመርጡት መነሻ", "Ka'umsa filatame"), Modifier.fillMaxWidth().testTag("auth_driver_preferred_origin"), enabled = enabled, language = language)
                    AuthField(draft.destination, { draft.destination = it }, t("Preferred destination", "የሚመርጡት መድረሻ", "Ga'umsa filatame"), Modifier.fillMaxWidth().testTag("auth_driver_preferred_destination"), enabled = enabled, language = language)
                    Text(if (draft.service == "Cargo") t("After approval, assigned cargo requests appear in your driver portal.", "ከተፈቀደ በኋላ የተመደቡ ጭነቶች በፖርታልዎ ይታያሉ።", "Hayyama booda gaaffileen fe'umsaa poortaalii kee irratti mul'atu.")
                        else t("After approval, propose trips with a per-seat price for admin review.", "ከተፈቀደ በኋላ ጉዞ እና የመቀመጫ ዋጋ ለግምገማ ያቅርቡ።", "Hayyama booda imalaa fi gatii teessoo gamaaggamaaf dhiyeessi."), color = CommuteInk, fontSize = 12.sp)
                }
            }
        }
        if (!driver && !draft.isCustomCommute) {
            if (routes.isEmpty()) Text(t("No active daily routes are available. Return to daily routes to request a new route.", "ንቁ የዕለት መስመሮች የሉም። አዲስ መስመር ለመጠየቅ ወደ መስመሮች ይመለሱ።", "Karaaleen guyyaa hojii irra jiran hin jiran. Karaa haaraa gaafachuuf deebi'i."),
                color = CommuteInk, fontSize = 13.sp)
            routes.forEach { route ->
                val selected = draft.selectedRoute == route.id
                Surface(color = if (selected) CommuteMint else Color.White, shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CommuteTeal else Color(0xFFE5E2DA)),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) {
                        draft.selectedRoute = route.id; draft.selectedRouteName = route.name
                    }.testTag("auth_route_${route.id}")) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.Route, null, tint = CommuteTeal)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(route.name, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("${route.morningDeparture} · ${route.eveningDeparture}", color = CommuteInk, fontSize = 12.sp)
                            if (!driver) Text("ETB ${route.basePriceEtb.toInt()} / month · Economy", color = CommuteTeal, fontSize = 12.sp)
                        }
                        if (selected) Icon(Icons.Default.CheckCircle, t("Selected route", "የተመረጠ መስመር", "Karaa filatame"), tint = CommuteTeal)
                    }
                }
            }
        }
        if (!driver && draft.isCustomCommute) {
            AuthField(draft.pickup, { draft.pickup = it }, t("Pickup location", "የመነሻ ቦታ", "Bakka ka'umsaa"),
                Modifier.fillMaxWidth().testTag("auth_passenger_pickup_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.LocationOn)
            AuthField(draft.destination, { draft.destination = it }, t("Destination", "መድረሻ", "Bakka ga'umsaa"),
                Modifier.fillMaxWidth().testTag("auth_passenger_destination_input"), enabled = enabled, language = language, leadingIcon = Icons.Default.LocationOn)
            AuthChoiceChips(listOf("One way" to t("One way", "አንድ መንገድ", "Karaa tokko"), "Two way" to t("Two way", "ደርሶ መልስ", "Deebii waliin")),
                draft.journey, enabled, { draft.journey = it }, "auth_journey")
        }
        if (!driver) {
            Text(t("Preferred tier", "የሚመርጡት ደረጃ", "Sadarkaa filatame"), color = CommuteInk, fontWeight = FontWeight.SemiBold)
            AuthChoiceChips(listOf("Economy" to "Economy", "Deluxe" to "Deluxe", "VIP" to "VIP"), draft.tier, enabled, { draft.tier = it }, "auth_tier")
        }
        if (!driver) Text(t("Registration does not confirm a paid pass. Your route offer and payment remain separate.", "ምዝገባ የተከፈለ ፓስ አያረጋግጥም። የመስመር ቅናሽ እና ክፍያ በተለየ ይቀጥላሉ።", "Galmeen paasiin kaffalameera jechuu miti. Yaadni karaa fi kaffaltiin addaatti itti fufa."), fontSize = 12.sp, color = CommuteInk)
        Row(Modifier.fillMaxWidth().toggleable(value = draft.adultConfirmed, enabled = enabled, role = Role.Checkbox,
            onValueChange = { draft.adultConfirmed = it }).testTag("auth_adult_confirmation").padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = draft.adultConfirmed, onCheckedChange = null, enabled = enabled)
            Spacer(Modifier.width(10.dp))
            Text(t("I am 18 or older. Passo accounts are for adults only.", "ዕድሜዬ 18 ወይም ከዚያ በላይ ነው። የPasso መለያዎች ለአዋቂዎች ብቻ ናቸው።", "Umriin koo 18 yookaan isaa ol. Herregni Passo ga'eessota qofaaf."),
                color = CommuteInk, fontSize = 13.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun AuthChoiceChips(options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
      options.chunked(if (options.size > 3) 2 else options.size.coerceAtLeast(1)).forEach { row ->
       Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        row.forEach { (value, label) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, enabled = enabled,
                label = { Text(label, fontSize = 12.sp) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = CommuteMint, selectedLabelColor = CommuteTeal),
                modifier = Modifier.weight(1f).testTag("${tag}_$value"))
        }
       }
      }
    }
}

@Composable
private fun AuthChoiceDropdown(value: String, label: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit, enabled: Boolean, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled, shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFFE5E2DA)), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, color = CommuteInk)
                Text(options.firstOrNull { it.first == value }?.second ?: "Choose", color = CommuteInk, fontWeight = FontWeight.SemiBold)
            }
            Icon(Icons.Default.ArrowDropDown, "Choose", tint = CommuteTeal)
        }
        DropdownMenu(open, { open = false }) {
            options.forEach { (key, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(key); open = false }) }
        }
    }
}
