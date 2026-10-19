package com.example.ui.screens.intercity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.example.R
import com.example.core.localization.AppLanguage
import com.example.data.api.IntercityDeparture
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteMint
import com.example.ui.screens.passenger.CommuteTeal
import com.example.ui.screens.passenger.CommuterLanguageSwitch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal fun intercityText(language: AppLanguage, en: String, am: String, om: String): String = when (language) {
    AppLanguage.ENGLISH -> en
    AppLanguage.AMHARIC -> am
    AppLanguage.OROMO -> om
}

private val IntercityMuted = Color(0xFF5D6A78)
private val IntercityBorder = Color(0xFFE7E3DB)

/** Only clean photographic/brand regions are reused. All labels and controls remain native. */
@Composable
internal fun IntercityReferenceCrop(
    x: Int, y: Int, width: Int, height: Int, modifier: Modifier,
    description: String? = null, scale: ContentScale = ContentScale.Crop
) {
    val bitmap = ImageBitmap.imageResource(R.drawable.passo_intercity_reference)
    val painter = remember(bitmap, x, y, width, height) {
        BitmapPainter(bitmap, srcOffset = IntOffset(x, y), srcSize = IntSize(width, height))
    }
    Image(painter, description, modifier, contentScale = scale)
}

@Composable
internal fun IntercityHero(
    language: AppLanguage,
    onLanguage: (AppLanguage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth().height(246.dp).testTag("intercity_hero")) {
        Image(painterResource(R.drawable.intercity_hero), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0x7A001D24)))))
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Column(Modifier.weight(1f)) {
                    IntercityReferenceCrop(41, 44, 347, 78, Modifier.fillMaxWidth().height(35.dp), "PASSO", ContentScale.Fit)
                    Text("Go Further, Together.", color = CommuteInk, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.weight(1.12f)) { CommuterLanguageSwitch(language, onLanguage, compact = true) }
            }
            Text(intercityText(language, "PEOPLE · PLACES · A BRIGHTER AFRICA", "ሰዎች · ቦታዎች · ብሩህ አፍሪካ", "NAMOOTA · BAKKEEWWAN · AFRIKAA IFAA"), color = CommuteInk, fontSize = 7.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(intercityText(language, "More places.\nCloser together.", "ተጨማሪ ቦታዎች።\nይበልጥ በአንድነት።", "Bakkeewwan dabalataa.\nWalitti dhihoo."), color = Color.White, fontSize = 23.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold)
                    Text(intercityText(language, "SAME ROADS. BRIGHTER TOMORROWS.", "አንድ መንገድ። ብሩህ ነገ።", "KARAA TOKKO. BORUU IFAA."), color = Color.White, fontSize = 7.sp, lineHeight = 11.sp, letterSpacing = 1.4.sp, modifier = Modifier.padding(top = 7.dp))
                }
                Surface(onClick = onBack, color = Color.White.copy(alpha = 0.94f), shape = CircleShape, modifier = Modifier.size(40.dp).testTag("intercity_back")) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, intercityText(language, "Back", "ተመለስ", "Deebi'i"), tint = CommuteInk, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.height(25.dp))
        }
    }
}

@Composable
internal fun IntercitySearchPanel(
    origin: String,
    destination: String,
    dateLabel: String,
    suggestions: List<String>,
    serviceTab: Int,
    language: AppLanguage,
    onOrigin: (String) -> Unit,
    onDestination: (String) -> Unit,
    onSwap: () -> Unit,
    onDate: () -> Unit,
    onSearch: () -> Unit,
    onServiceTab: (Int) -> Unit,
    busy: Boolean = false,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    Surface(color = CommuteCream, shape = RoundedCornerShape(23.dp), shadowElevation = 6.dp, modifier = modifier.fillMaxWidth().testTag("intercity_search_panel")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Icons.Default.DirectionsBus to intercityText(language, "Intercity\npassenger", "የከተማ መካከል\nመንገደኛ", "Imalaa\nmagaalota"),
                    Icons.Default.Inventory2 to intercityText(language, "Parcel\ncargo", "የዕቃ\nጭነት", "Fe'umsa\nxixiqqaa"),
                    Icons.Default.Groups to intercityText(language, "Group\ntravel", "የቡድን\nጉዞ", "Imala\ngaree")
                ).forEachIndexed { index, (icon, label) ->
                    val selected = serviceTab == index
                    Column(Modifier.weight(1f).clickable(role = Role.Tab) { onServiceTab(index) }.testTag("intercity_service_tab_$index"), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(Modifier.heightIn(min = 46.dp).padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(icon, null, Modifier.size(20.dp), tint = if (selected) CommuteTeal else IntercityMuted)
                            Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) CommuteTeal else IntercityMuted)
                        }
                        Box(Modifier.fillMaxWidth().height(if (selected) 2.dp else 1.dp).background(if (selected) CommuteTeal else IntercityBorder))
                    }
                }
            }
            if (serviceTab == 0) {
                Box(Modifier.fillMaxWidth()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        IntercityCityInput(origin, intercityText(language, "From", "መነሻ", "Irraa"), suggestions, onOrigin, language, "intercity_from", Modifier.weight(1f))
                        IntercityCityInput(destination, intercityText(language, "To", "መድረሻ", "Gara"), suggestions, onDestination, language, "intercity_to", Modifier.weight(1f))
                    }
                    Surface(onClick = { focusManager.clearFocus(); onSwap() }, color = CommuteCream, shape = CircleShape, border = BorderStroke(1.dp, IntercityBorder), modifier = Modifier.align(Alignment.Center).size(34.dp).testTag("intercity_swap")) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.SwapHoriz, intercityText(language, "Swap cities", "ከተሞችን ቀይር", "Magaalota jijjiiri"), tint = CommuteInk, modifier = Modifier.size(22.dp)) }
                    }
                }
                Surface(onClick = { focusManager.clearFocus(); onDate() }, color = CommuteCream, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, IntercityBorder), modifier = Modifier.fillMaxWidth().testTag("intercity_date")) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        Icon(Icons.Default.CalendarMonth, null, Modifier.size(24.dp), tint = CommuteInk)
                        Column(Modifier.weight(1f)) {
                            Text(intercityText(language, "Travel date", "የጉዞ ቀን", "Guyyaa imalaa"), color = IntercityMuted, fontSize = 11.sp, lineHeight = 14.sp)
                            Text(dateLabel, color = CommuteInk, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Icon(Icons.Default.KeyboardArrowDown, null, tint = CommuteInk)
                    }
                }
                Button(onClick = { focusManager.clearFocus(); onSearch() }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal, contentColor = Color.White), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("intercity_search")) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White) else Icon(Icons.Default.Search, null, Modifier.size(23.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(intercityText(language, "Search routes", "መስመሮችን ፈልግ", "Karaa barbaadi"), Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(23.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(if (serviceTab == 1) Icons.Default.Inventory2 else Icons.Default.Groups, null, tint = CommuteTeal, modifier = Modifier.size(32.dp))
                    Text(intercityText(language, "Not available yet", "ገና አልተጀመረም", "Ammaaf hin jiru"), color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(intercityText(language, "Choose Intercity passenger to find a route.", "መስመር ለማግኘት የከተማ መካከል መንገደኛን ይምረጡ።", "Karaa argachuuf imalaa magaalota filadhu."), color = IntercityMuted, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun IntercityCityInput(
    value: String, label: String, suggestions: List<String>, onValue: (String) -> Unit,
    language: AppLanguage, tag: String, modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val choices = remember(suggestions, value) { suggestions.distinct().filter { value.isBlank() || it.contains(value, ignoreCase = true) }.take(7) }
    val focusManager = LocalFocusManager.current
    Box(modifier) {
        Surface(color = CommuteCream, shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, IntercityBorder)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).clickable { fieldFocus.requestFocus() }.padding(start = 9.dp, end = 15.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.LocationOn, null, Modifier.size(23.dp), tint = CommuteTeal)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, color = IntercityMuted, fontSize = 11.sp, lineHeight = 14.sp)
                    BasicTextField(value, onValueChange = { onValue(it); expanded = true }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.merge(TextStyle(color = CommuteInk, fontSize = 14.sp, fontWeight = FontWeight.Bold)),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(CommuteTeal),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 23.dp).testTag(tag).focusRequester(fieldFocus).onFocusChanged { if (it.isFocused) expanded = true },
                        decorationBox = { inner ->
                            if (value.isBlank()) Text(intercityText(language, "City", "ከተማ", "Magaalaa"), color = IntercityMuted, fontSize = 13.sp)
                            inner()
                        })
                }
            }
        }
        DropdownMenu(expanded = expanded && choices.isNotEmpty(), onDismissRequest = { expanded = false }, properties = PopupProperties(focusable = false), modifier = Modifier.widthIn(min = 180.dp, max = 270.dp)) {
            choices.forEach { city ->
                DropdownMenuItem(text = { Text(city, color = CommuteInk) }, onClick = { onValue(city); expanded = false; focusManager.clearFocus() }, leadingIcon = { Icon(Icons.Default.LocationOn, null, tint = CommuteTeal) })
            }
        }
    }
}

internal fun formatIntercityTime(value: String): String {
    val date = listOf("yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX").firstNotNullOfOrNull { pattern ->
        runCatching { SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value) }.getOrNull()
    } ?: return value
    return SimpleDateFormat("EEE, d MMM · h:mm a", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("Africa/Addis_Ababa") }.format(date)
}

internal fun formatIntercityFare(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply { maximumFractionDigits = 2; minimumFractionDigits = 0 }.format(value)

@Composable
internal fun IntercityDepartureCard(
    departure: IntercityDeparture,
    photoIndex: Int,
    saved: Boolean,
    language: AppLanguage,
    onSave: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val available = departure.availableSeats > 0
    Surface(color = CommuteCream, shape = RoundedCornerShape(19.dp), shadowElevation = 3.dp, modifier = modifier.width(225.dp).testTag("intercity_departure_${departure.id}")) {
        Column {
            Box(Modifier.fillMaxWidth().height(129.dp)) {
                when (Math.floorMod(photoIndex, 3)) {
                    0 -> IntercityReferenceCrop(25, 981, 215, 161, Modifier.matchParentSize())
                    1 -> IntercityReferenceCrop(312, 981, 210, 161, Modifier.matchParentSize())
                    else -> IntercityReferenceCrop(589, 1026, 248, 116, Modifier.matchParentSize())
                }
                Surface(onClick = onSave, shape = CircleShape, color = Color(0xA0002335), modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(36.dp).testTag("intercity_save_${departure.id}")) {
                    Box(contentAlignment = Alignment.Center) { Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, intercityText(language, if (saved) "Remove saved route" else "Save route", if (saved) "የተቀመጠውን አስወግድ" else "መስመር አስቀምጥ", if (saved) "Karaa kuufame haqi" else "Karaa kuusi"), tint = Color.White, modifier = Modifier.size(22.dp)) }
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${departure.origin} → ${departure.destination}", color = CommuteInk, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(departure.companyName, color = CommuteTeal, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                IntercityDetailLine(Icons.Default.Schedule, formatIntercityTime(departure.departureAt))
                IntercityDetailLine(Icons.Default.EventSeat, if (available) intercityText(language, "${departure.availableSeats} seats available", "${departure.availableSeats} ወንበሮች አሉ", "Teessoo ${departure.availableSeats} jira") else intercityText(language, "Sold out", "ሙሉ ነው", "Guuteera"))
                listOfNotNull(departure.vehicleModel?.takeIf { it.isNotBlank() }, departure.vehicleType?.takeIf { it.isNotBlank() }).distinct().joinToString(" · ").takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 10.sp, lineHeight = 14.sp, color = IntercityMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(intercityText(language, "Per passenger", "በመንገደኛ", "Imalaa tokkoof"), color = IntercityMuted, fontSize = 10.sp, lineHeight = 13.sp)
                        Text("ETB ${formatIntercityFare(departure.fareEtb)}", color = CommuteTeal, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
                    }
                    FilledIconButton(onClick = onSelect, enabled = available, colors = IconButtonDefaults.filledIconButtonColors(containerColor = CommuteMint, contentColor = CommuteInk), modifier = Modifier.size(43.dp).testTag("intercity_select_${departure.id}")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, intercityText(language, "Choose seats", "ወንበር ይምረጡ", "Teessoo filadhu"), Modifier.size(23.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun IntercityDetailLine(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Icon(icon, null, Modifier.size(15.dp), tint = CommuteInk)
        Text(value, color = IntercityMuted, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

@Composable
internal fun IntercityCommunityBanner(language: AppLanguage, modifier: Modifier = Modifier) {
    Surface(color = CommuteTeal, shape = RoundedCornerShape(20.dp), modifier = modifier.fillMaxWidth().testTag("intercity_community")) {
        Row(Modifier.heightIn(min = 119.dp), verticalAlignment = Alignment.CenterVertically) {
            IntercityReferenceCrop(20, 1630, 185, 190, Modifier.width(90.dp).height(128.dp))
            Column(Modifier.weight(1f).padding(13.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(intercityText(language, "Same people. More places.\nA brighter Africa.", "አንድ ሕዝብ። ተጨማሪ ቦታዎች።\nብሩህ አፍሪካ።", "Namoota tokko. Bakkeewwan hedduu.\nAfrikaa ifaa."), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 19.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommunityValue(Icons.Default.Groups, intercityText(language, "Together", "በአንድነት", "Waliin"), Modifier.weight(1f))
                    CommunityValue(Icons.Default.Eco, intercityText(language, "Further", "ወደ ፊት", "Dabalataan"), Modifier.weight(1f))
                    CommunityValue(Icons.Default.FavoriteBorder, intercityText(language, "Connected", "ተገናኝተን", "Walitti"), Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CommunityValue(icon: ImageVector, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, null, Modifier.size(15.dp), tint = Color.White)
        Text(label, fontSize = 9.sp, color = Color.White, lineHeight = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
