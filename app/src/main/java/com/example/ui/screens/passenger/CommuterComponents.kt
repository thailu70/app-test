package com.example.ui.screens.passenger

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.data.api.TrackedVehicleDto
import com.example.data.entity.RouteEntity
import com.example.data.entity.RouteStopEntity
import com.example.data.entity.SubscriptionEntity
import com.example.ui.components.MiniVehicleMap
import com.example.ui.components.QrCodeCanvas
import com.example.ui.screens.profile.AuthenticatedProfilePhoto
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal val CommuteInk = Color(0xFF06263C)
internal val CommuteTeal = Color(0xFF00695F)
internal val CommuteMint = Color(0xFFE4F3E9)
internal val CommuteCream = Color(0xFFFFFBF4)

internal fun commuteText(lang: AppLanguage, en: String, am: String, om: String): String = when (lang) {
    AppLanguage.ENGLISH -> en
    AppLanguage.AMHARIC -> am
    AppLanguage.OROMO -> om
}

@Composable
fun CommuterWordmark(modifier: Modifier = Modifier) {
    val bitmap = ImageBitmap.imageResource(R.drawable.commuter_reference)
    val painter = remember(bitmap) {
        BitmapPainter(bitmap, srcOffset = IntOffset(42, 44), srcSize = IntSize(310, 73))
    }
    Column(modifier) {
        Image(painter, "PASSO", Modifier.width(142.dp).height(34.dp), contentScale = ContentScale.Fit)
        Text("Go Further, Together.", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = CommuteInk)
    }
}

@Composable
fun CommuterLanguageSwitch(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, compact: Boolean = false) {
    Surface(color = Color(0xFFF1F1EB), shape = CircleShape, border = BorderStroke(1.dp, Color(0xFFE3E6DF))) {
        Row(Modifier.fillMaxWidth().padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AppLanguage.entries.forEach { item ->
                Surface(color = if (language == item) CommuteTeal else Color.Transparent, shape = CircleShape, modifier = Modifier.weight(1f)) {
                    Box(Modifier.selectable(language == item, role = Role.RadioButton, onClick = { onLanguage(item) })
                        .testTag("commuter_language_${item.code}").semantics { contentDescription = item.displayName }.heightIn(min = if (compact) 36.dp else 40.dp).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                        Text(if (compact && item == AppLanguage.OROMO) "Oromoo" else item.displayName, maxLines = 1, softWrap = false, lineHeight = 15.sp, letterSpacing = 0.sp, fontSize = if (compact) 10.sp else 12.sp, fontWeight = FontWeight.SemiBold, color = if (language == item) Color.White else CommuteInk)
                    }
                }
            }
        }
    }
}

@Composable
fun CommuterGreeting(lang: AppLanguage, hasPass: Boolean) {
    Box(Modifier.fillMaxWidth().heightIn(min = 112.dp)) {
        Image(painterResource(R.drawable.commuter_skyline), null, Modifier.align(Alignment.BottomEnd).width(145.dp).height(90.dp), contentScale = ContentScale.Fit)
        Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(commuteText(lang, "YOUR DAILY COMMUTE", "የእርስዎ የዕለት ጉዞ", "IMALA GUYYAA KEETII"), color = Color(0xFF566775), fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold)
            Text(if (hasPass) commuteText(lang, "Your daily ride.", "የዕለት ጉዞዎ፣\nበቀላሉ።", "Imala guyyaa,\nsalphaa taasifame.") else commuteText(lang, "A better commute.", "የተሻለ ጉዞ\nእዚህ ይጀምራል።", "Imalli fooyya'aan\nasitti jalqaba."), color = CommuteInk, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.fillMaxWidth())
            Text(commuteText(lang, "Same people. A clearer,\neasier commute.", "አብረን። ግልጽ እና\nቀላል ጉዞ።", "Namoota tokko. Imala\nsalphaa fi ifa ta'e."), color = Color(0xFF526270), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth(0.66f))
        }
    }
}

@Composable
fun CommuterActiveTripCard(
    subscription: SubscriptionEntity?, route: RouteEntity?, routeName: String?, stops: List<RouteStopEntity>,
    driver: AssignedDriver?, loadingDriver: Boolean, driverError: String?, lang: AppLanguage,
    onRetry: () -> Unit, onShowPass: () -> Unit, onRenew: () -> Unit, paymentAvailable: Boolean,
    driverPortrait: @Composable (String) -> Unit = { AuthenticatedProfilePhoto(it, Modifier.size(56.dp)) }
) {
    val context = LocalContext.current
    val canBoard = canShowPassengerBoardingPass(subscription)
    val pickup = stops.find { it.id == subscription?.pickupStopId }?.stopName
    val destination = stops.find { it.id == subscription?.destinationStopId }?.stopName
    val morning = subscription?.morningSchedule?.takeIf { it.isNotBlank() } ?: route?.morningDeparture.orEmpty()
    val evening = subscription?.eveningSchedule?.takeIf { it.isNotBlank() } ?: route?.eveningDeparture.orEmpty()
    var callError by remember(driver?.phone) { mutableStateOf(false) }
    Surface(color = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth().testTag("commuter_active_trip")) {
        Column {
            Row(Modifier.fillMaxWidth().background(CommuteTeal).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.DirectionsBus, null, Modifier.size(24.dp), tint = Color.White)
                Text(commuteText(lang, if (canBoard) "ACTIVE COMMUTE" else "YOUR COMMUTE", "የእርስዎ ጉዞ", "IMALA KEETII"), Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color.White)
                Text(SimpleDateFormat("EEE, d MMM", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Africa/Addis_Ababa") }.format(Date()), fontSize = 10.sp, color = Color.White)
            }
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(routeName?.takeIf { it.isNotBlank() } ?: commuteText(lang, "Route awaiting assignment", "መስመር እየተጠበቀ ነው", "Karaa eeggachaa jira"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CommuteStop(morning.ifBlank { "—" }, pickup ?: commuteText(lang, "Morning departure", "የጠዋት መነሻ", "Ka'umsa ganamaa"), if (pickup != null) commuteText(lang, "Pickup", "መሳፈሪያ", "Ka'umsa") else null)
                        CommuteStop(commuteText(lang, "Return", "መመለሻ", "Deebii") + " · " + evening.ifBlank { "—" }, destination ?: commuteText(lang, "Return departure", "የመመለሻ መነሻ", "Ka'umsa galgalaa"), if (destination != null) commuteText(lang, "Return pickup", "የመመለሻ መሳፈሪያ", "Ka'umsa deebii") else null)
                    }
                    VerticalDivider(Modifier.height(154.dp), color = Color(0xFFEDF0EA))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (driver == null) {
                            Icon(Icons.Default.AccountCircle, null, Modifier.size(48.dp), tint = Color(0xFFBDCCC4))
                            Text(commuteText(lang, if (loadingDriver) "Loading driver…" else "Driver awaiting assignment", "ሹፌር እየተጠበቀ ነው", "Konkolaachisaa eeggachaa jira"), fontSize = 12.sp, color = CommuteInk)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(40.dp)) { driverPortrait(driver.userId) }
                            Text(driver.fullName.takeIf { it.isNotBlank() } ?: commuteText(lang, "Your driver", "ሹፌርዎ", "Konkolaachisaa kee"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                            }
                            if (driver.phone.any(Char::isDigit)) OutlinedButton(onClick = {
                                try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", driver.phone, null))) }
                                catch (_: android.content.ActivityNotFoundException) { callError = true }
                                catch (_: SecurityException) { callError = true }
                            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp), modifier = Modifier.heightIn(min = 40.dp).testTag("commuter_call_driver"), border = BorderStroke(1.dp, CommuteTeal)) {
                                Icon(Icons.Default.Call, null, Modifier.size(15.dp)); Spacer(Modifier.width(4.dp))
                                Text(commuteText(lang, "Call driver", "ሹፌሩን ይደውሉ", "Bilbili"), fontSize = 11.sp, color = CommuteTeal)
                            }
                            driver.phone.cleanDetail()?.let { Text(it, fontSize = 10.sp, color = CommuteInk) }
                            Text(listOfNotNull(driver.model.cleanDetail(), driver.vehicleColor.cleanDetail(), friendlyVehicleType(driver.vehicleType)).joinToString(" · "), fontSize = 11.sp, lineHeight = 14.sp, color = Color(0xFF526270))
                            driver.plateNumber.cleanDetail()?.let { plate ->
                                Surface(shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, Color(0xFFD4DDD7))) { Text(plate, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CommuteInk) }
                            }
                        }
                    }
                }
                if (driverError != null) TextButton(onClick = onRetry) { Text(commuteText(lang, "Refresh driver details", "የሹፌር መረጃ አድስ", "Odeeffannoo haaromsi"), fontSize = 12.sp) }
                if (callError) Text(driver?.phone.orEmpty(), color = CommuteInk)
                HorizontalDivider(color = Color(0xFFEDF0EA))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        if (subscription != null) {
                            Text(commuteText(lang, "${subscription.daysRemaining.coerceAtLeast(0)} days remaining", "${subscription.daysRemaining.coerceAtLeast(0)} ቀናት ቀርተዋል", "Guyyoota ${subscription.daysRemaining.coerceAtLeast(0)} hafe"), fontSize = 11.sp, color = Color(0xFF526270))
                            subscription.endDate.takeIf { it.isNotBlank() }?.let { Text(commuteText(lang, "Valid until", "የሚያበቃበት ቀን", "Hanga") + " " + it.take(10), fontSize = 10.sp, color = Color(0xFF526270)) }
                        } else Text(commuteText(lang, "Your pass appears after activation.", "የጉዞ ፈቃድዎ ሲነቃ ይታያል።", "Hayyamni erga mirkanaa'ee mul'ata."), fontSize = 11.sp, color = Color(0xFF526270))
                    }
                    if (subscription != null && subscription.subscriptionStatus != "CANCELLED") TextButton(onClick = onRenew, enabled = paymentAvailable, modifier = Modifier.testTag("commuter_payment_action")) {
                        Text(commuteText(lang, if (canBoard) "Renew pass" else "Payment options", if (canBoard) "አድስ" else "የክፍያ አማራጮች", if (canBoard) "Haaromsi" else "Kaffaltii"), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

private fun String.cleanDetail(): String? = trim().takeUnless { it.lowercase() in setOf("", "not provided", "not set", "unknown", "null", "undefined", "n/a", "-") }

@Composable
private fun CommuteStop(time: String, name: String, caption: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.RadioButtonChecked, null, Modifier.size(13.dp), tint = CommuteTeal)
            Box(Modifier.width(2.dp).height(44.dp).background(Color(0xFF50AD96)))
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(time, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = CommuteInk)
            Text(name, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 17.sp, color = CommuteInk)
            caption?.let { Text(it, color = Color(0xFF64717C), fontSize = 11.sp) }
        }
    }
}

@Composable
fun CommuterBoardingPreview(subscription: SubscriptionEntity?, routeName: String?, lang: AppLanguage, onOpen: () -> Unit) {
    val eligible = canShowPassengerBoardingPass(subscription) && !subscription?.qrToken.isNullOrBlank()
    Surface(color = CommuteInk, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("commuter_boarding_preview")) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.QrCode, null, Modifier.size(21.dp), tint = Color.White)
                    Text(commuteText(lang, "YOUR BOARDING PASS", "የመሳፈሪያ ፈቃድዎ", "HAYYAMA YAABBANNOO"), fontSize = 9.sp, letterSpacing = 1.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Text(commuteText(lang, if (eligible) "Show this code\nto board" else "Your pass,\nin one place", if (eligible) "ይህን ኮድ\nበማሳየት ይሳፈሩ" else "የጉዞ ፈቃድዎ\nእዚህ ይታያል", if (eligible) "Koodii kana\nagarsiisi" else "Hayyama kee\nasitti ilaali"), fontSize = 21.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, color = Color.White)
                routeName?.let { Text(it, fontSize = 11.sp, lineHeight = 15.sp, color = Color(0xFFD1E4E5)) }
                Text(commuteText(lang, "Open boarding pass →", "የመሳፈሪያ ፈቃድ ክፈት →", "Hayyama bani →"), fontSize = 11.sp, color = Color(0xFF9DDBCB))
            }
            Surface(color = Color.White, shape = RoundedCornerShape(14.dp), modifier = Modifier.size(116.dp)) {
                if (eligible) QrCodeCanvas(subscription!!.qrToken, Modifier.fillMaxSize().padding(5.dp).testTag("commuter_home_qr"), moduleColor = Color.Black, backgroundColor = Color.White)
                else Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = CommuteTeal) }
            }
        }
    }
}

@Composable
fun CommuterLocationCard(vehicle: TrackedVehicleDto?, message: String?, lang: AppLanguage, onOpen: () -> Unit) {
    Surface(color = CommuteMint, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().testTag("commuter_location")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Sensors, null, tint = CommuteTeal)
                Text(commuteText(lang, "VEHICLE LOCATION", "የተሽከርካሪ ቦታ", "BAKKA KONKOLAATAA"), fontSize = 10.sp, letterSpacing = 1.3.sp, fontWeight = FontWeight.Bold, color = CommuteTeal, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ArrowForward, commuteText(lang, "Open location", "ቦታ ክፈት", "Bakka bani"), tint = CommuteTeal) }
            }
            if (vehicle?.hasGpsLocation == true) MiniVehicleMap(vehicle, mapHeight = 150, serverMessage = message)
            else {
                Text(commuteText(lang, "Stay close\nto your ride.", "የጉዞዎን መረጃ\nይከታተሉ።", "Imala kee\nhordofi."), fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = CommuteInk)
                Text(message?.takeIf { it.isNotBlank() } ?: commuteText(lang, "Your driver's location will appear when GPS sharing starts.", "የሹፌርዎ ቦታ ማጋራት ሲጀምር ይታያል።", "Bakka konkolaachisaa yeroo GPS banamu ni argita."), fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF526270))
            }
        }
    }
}

@Composable
fun CommuterHeader(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CommuterWordmark(Modifier.width(132.dp))
        Box(Modifier.weight(1f)) { CommuterLanguageSwitch(language, onLanguage, compact = true) }
    }
}