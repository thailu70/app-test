package com.example.ui.screens.intercity

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.localization.AppLanguage
import com.example.data.api.IntercityBooking
import com.example.data.api.IntercityDeparture
import com.example.data.api.IntercitySeatsResponse
import com.example.ui.components.QrCodeCanvas
import com.example.ui.screens.passenger.CommuteCream
import com.example.ui.screens.passenger.CommuteInk
import com.example.ui.screens.passenger.CommuteMint
import com.example.ui.screens.passenger.CommuteTeal

private val BookingMuted = Color(0xFF5D6A78)
private val BookingBorder = Color(0xFFE7E3DB)

@Composable
internal fun IntercitySeatSelection(
    departure: IntercityDeparture,
    inventory: IntercitySeatsResponse?,
    selectedSeat: Int?,
    busy: Boolean,
    language: AppLanguage,
    onSeat: (Int) -> Unit,
    onHold: () -> Unit,
    onRefresh: () -> Unit
) {
    val availableInventory = inventory?.takeIf { it.success }
    val seats = availableInventory?.seats?.coerceAtLeast(0) ?: 0
    val reserved = availableInventory?.reserved.orEmpty().toSet()
    val selectedAvailable = selectedSeat != null && selectedSeat in 1..seats && selectedSeat !in reserved
    Surface(color = Color.White, shape = RoundedCornerShape(23.dp), modifier = Modifier.fillMaxWidth().testTag("intercity_seat_selection")) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text(intercityText(language, "Choose your seat", "መቀመጫዎን ይምረጡ", "Teessoo kee filadhu"), color = CommuteInk, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            Text("${departure.origin} → ${departure.destination}", color = CommuteInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(formatIntercityTime(departure.departureAt), color = BookingMuted, fontSize = 13.sp)
            Text(departure.companyName, color = CommuteTeal, fontSize = 13.sp)
            HorizontalDivider(color = BookingBorder)
            Text(intercityText(language, "Select an available seat number. Numbers do not show the vehicle's physical seating layout.", "ክፍት የመቀመጫ ቁጥር ይምረጡ። ቁጥሮቹ የተሽከርካሪውን የመቀመጫ አቀማመጥ አያሳዩም።", "Lakkoofsa teessoo banaa filadhu. Lakkoofsi kun tartiiba teessoo konkolaataa hin agarsiisu."), color = BookingMuted, fontSize = 12.sp, lineHeight = 17.sp)
            if (availableInventory == null) {
                Text(intercityText(language, if (busy) "Loading seats…" else "Seat availability could not be loaded.", if (busy) "መቀመጫዎች እየተጫኑ ነው…" else "የመቀመጫ መረጃ አልተጫነም።", if (busy) "Teessoowwan fe'amaa jiru…" else "Odeeffannoon teessoo hin fe'amne."), color = BookingMuted, fontSize = 13.sp)
            } else if (seats == 0) {
                Text(intercityText(language, "No seats are available.", "ክፍት መቀመጫ የለም።", "Teessoon banaan hin jiru."), color = BookingMuted)
            } else {
                (1..seats).toList().chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        row.forEach { number ->
                            val occupied = number in reserved
                            val selected = selectedSeat == number
                            Surface(color = when { occupied -> Color(0xFFEDEEEA); selected -> CommuteTeal; else -> CommuteMint }, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) CommuteTeal else BookingBorder), modifier = Modifier.weight(1f)) {
                                Column(Modifier.selectable(selected = selected, enabled = !busy && !occupied, role = Role.RadioButton, onClick = { onSeat(number) }).testTag("intercity_seat_$number").heightIn(min = 56.dp).padding(7.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(Icons.Default.EventSeat, null, Modifier.size(19.dp), tint = when { occupied -> Color(0xFF929A96); selected -> Color.White; else -> CommuteTeal })
                                    Text(number.toString(), color = when { occupied -> BookingMuted; selected -> Color.White; else -> CommuteInk }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                    SeatLegend(CommuteMint, intercityText(language, "Available", "ክፍት", "Banaa"))
                    SeatLegend(CommuteTeal, intercityText(language, "Selected", "የተመረጠ", "Filatame"))
                    SeatLegend(Color(0xFFEDEEEA), intercityText(language, "Reserved", "የተያዘ", "Qabame"))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(intercityText(language, "Per passenger", "ለአንድ መንገደኛ", "Imalaa tokkoof"), color = BookingMuted, fontSize = 11.sp)
                    Text("ETB ${formatIntercityFare(availableInventory?.fareEtb ?: departure.fareEtb)}", color = CommuteTeal, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.testTag("intercity_refresh_seats")) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp))
                    Text(intercityText(language, "Refresh", "አድስ", "Haaromsi"))
                }
            }
            Button(onClick = onHold, enabled = !busy && selectedAvailable, colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("intercity_hold")) {
                Text(intercityText(language, "Continue with seat ${selectedSeat ?: "—"}", "በመቀመጫ ${selectedSeat ?: "—"} ቀጥል", "Teessoo ${selectedSeat ?: "—"} waliin itti fufi"), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SeatLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(9.dp).background(color, RoundedCornerShape(3.dp)))
        Text(label, color = BookingMuted, fontSize = 10.sp)
    }
}

@Composable
internal fun IntercityCheckoutForm(
    booking: IntercityBooking,
    busy: Boolean,
    language: AppLanguage,
    onPay: (String, String) -> Unit,
    onCheck: () -> Unit
) {
    var name by rememberSaveable(booking.id) { mutableStateOf(booking.passengerName.orEmpty()) }
    var phone by rememberSaveable(booking.id) { mutableStateOf(booking.passengerPhone.orEmpty()) }
    val closed = booking.status.uppercase() in setOf("EXPIRED", "CANCELLED", "CONFIRMED", "BOARDED") || booking.paymentStatus == "PAID"
    Surface(color = Color.White, shape = RoundedCornerShape(23.dp), modifier = Modifier.fillMaxWidth().testTag("intercity_checkout")) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Text(intercityText(language, "Your journey details", "የጉዞዎ ዝርዝር", "Odeeffannoo imala keetii"), color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = 23.sp)
            Text("${booking.departure.origin} → ${booking.departure.destination}", color = CommuteInk, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(formatIntercityTime(booking.departure.departureAt), color = BookingMuted, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(intercityText(language, "Seat ${booking.seatNumber}", "መቀመጫ ${booking.seatNumber}", "Teessoo ${booking.seatNumber}"), color = CommuteInk, modifier = Modifier.weight(1f))
                Text("ETB ${formatIntercityFare(booking.amountEtb)}", color = CommuteTeal, fontWeight = FontWeight.Bold, fontSize = 21.sp)
            }
            HorizontalDivider(color = BookingBorder)
            BookingStatus(booking, language)
            if (booking.expiresAt.isNotBlank() && booking.paymentStatus != "PAID") Text(intercityText(language, "Seat hold expires: ", "መቀመጫው የሚለቀቅበት ሰዓት፦ ", "Yeroon qabannaa teessoo: ") + formatIntercityTime(booking.expiresAt), color = BookingMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.testTag("intercity_hold_expiry"))
            OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy && !closed, label = { Text(intercityText(language, "Passenger full name", "የመንገደኛ ሙሉ ስም", "Maqaa guutuu imalaa")) }, leadingIcon = { Icon(Icons.Default.PersonOutline, null) }, singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().testTag("intercity_passenger_name"))
            OutlinedTextField(value = phone, onValueChange = { phone = it }, enabled = !busy && !closed, label = { Text(intercityText(language, "Mobile phone number", "የሞባይል ስልክ ቁጥር", "Lakkoofsa bilbilaa")) }, leadingIcon = { Icon(Icons.Default.Phone, null) }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().testTag("intercity_passenger_phone"))
            Surface(color = CommuteMint, shape = RoundedCornerShape(13.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Icon(Icons.Default.Lock, null, tint = CommuteTeal, modifier = Modifier.size(19.dp))
                    Text(intercityText(language, "Continue to secure Telebirr payment. Your Telebirr PIN is entered only in Telebirr.", "ወደ ደህንነቱ የተጠበቀ የቴሌብር ክፍያ ይቀጥሉ። የቴሌብር ፒንዎን በቴሌብር ብቻ ያስገቡ።", "Gara kaffaltii Telebirr amansiisaatti itti fufi. PIN Telebirr kee Telebirr keessatti qofa galchi."), color = CommuteInk, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
            Button(onClick = { onPay(name.trim(), phone.trim()) }, enabled = !busy && !closed && name.isNotBlank() && phone.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = CommuteTeal), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("intercity_pay")) {
                Icon(Icons.Default.Payments, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                Text(intercityText(language, "Pay with Telebirr", "በቴሌብር ክፍያ ይፈጽሙ", "Telebirr dhaan kaffali"), fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onCheck, enabled = !busy, border = BorderStroke(1.dp, CommuteTeal), shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 45.dp).testTag("intercity_check_payment")) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(intercityText(language, "Check payment status", "የክፍያ ሁኔታ ያረጋግጡ", "Haala kaffaltii ilaali"), color = CommuteTeal)
            }
        }
    }
}

@Composable
private fun BookingStatus(booking: IntercityBooking, language: AppLanguage) {
    val label = when (booking.status) {
        "BOARDED" -> intercityText(language, "Boarded", "ተሳፍረዋል", "Yaabbatame")
        "CONFIRMED" -> if (booking.paymentStatus == "PAID") intercityText(language, "Confirmed · Paid", "ተረጋግጧል · ተከፍሏል", "Mirkanaa'e · Kaffalame") else intercityText(language, "Awaiting payment confirmation", "የክፍያ ማረጋገጫ እየተጠበቀ ነው", "Mirkaneessa kaffaltii eeggachaa")
        "CANCELLED" -> intercityText(language, "Cancelled", "ተሰርዟል", "Haqame")
        "EXPIRED" -> intercityText(language, "Seat hold expired", "የመቀመጫ ጊዜ አልፏል", "Yeroon qabannaa darbe")
        else -> intercityText(language, "Awaiting payment confirmation", "የክፍያ ማረጋገጫ እየተጠበቀ ነው", "Mirkaneessa kaffaltii eeggachaa")
    }
    val settled = booking.paymentStatus == "PAID" && booking.status in setOf("CONFIRMED", "BOARDED")
    Surface(color = if (settled) CommuteMint else CommuteCream, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(if (settled) Icons.Default.CheckCircle else Icons.Default.Schedule, null, Modifier.size(16.dp), tint = CommuteTeal)
            Text(label, color = CommuteTeal, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable
internal fun IntercityTicketCard(
    booking: IntercityBooking,
    language: AppLanguage,
    onOpen: () -> Unit,
    compact: Boolean = false,
    onSavePdf: (() -> Unit)? = null
) {
    val canBoard = canShowIntercityQr(booking)
    Surface(color = if (compact) Color(0xFFF4F8F0) else Color.White, shape = RoundedCornerShape(23.dp), border = BorderStroke(1.dp, Color(0xFFDCEDE1)), modifier = Modifier.fillMaxWidth().testTag("intercity_ticket_card_${booking.id}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${booking.departure.origin} → ${booking.departure.destination}", color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = if (compact) 16.sp else 22.sp, lineHeight = if (compact) 20.sp else 27.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp), tint = CommuteInk)
                        Text(formatIntercityTime(booking.departure.departureAt), color = BookingMuted, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EventSeat, null, Modifier.size(16.dp), tint = CommuteInk)
                        Text(intercityText(language, "Seat ${booking.seatNumber}", "መቀመጫ ${booking.seatNumber}", "Teessoo ${booking.seatNumber}"), color = BookingMuted, fontSize = 12.sp)
                    }
                    BookingStatus(booking, language)
                }
                if (compact && canBoard) QrCodeCanvas(booking.qrToken!!, Modifier.size(80.dp).testTag("intercity_ticket_qr"), moduleColor = Color.Black)
            }
            if (!compact) {
                if (!booking.passengerName.isNullOrBlank()) Text(booking.passengerName, color = CommuteInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("ETB ${formatIntercityFare(booking.amountEtb)}", color = CommuteTeal, fontWeight = FontWeight.Bold, fontSize = 21.sp)
                if (canBoard) {
                    QrCodeCanvas(booking.qrToken!!, Modifier.align(Alignment.CenterHorizontally).size(224.dp).testTag("intercity_ticket_qr"), moduleColor = Color.Black)
                    Text(intercityText(language, "Show this QR code or your ticket number to board.", "ለመሳፈር ይህን የQR ኮድ ወይም የትኬት ቁጥርዎን ያሳዩ።", "Yaabbachuuf QR kana yookaan lakkoofsa tikitii kee agarsiisi."), color = BookingMuted, fontSize = 13.sp, lineHeight = 18.sp)
                } else if (booking.status == "BOARDED") {
                    Text(intercityText(language, "Boarding completed. Keep this journey receipt.", "መሳፈር ተጠናቋል። ይህን የጉዞ ደረሰኝ ያስቀምጡ።", "Yaabbannoon xumurameera. Nagahee imalaa kana kaa'i."), color = BookingMuted, fontSize = 13.sp, lineHeight = 18.sp)
                } else Text(intercityText(language, "Your boarding code appears after payment is confirmed.", "ክፍያው ከተረጋገጠ በኋላ የመሳፈሪያ ኮድዎ ይታያል።", "Kaffaltiin erga mirkanaa'ee koodiin yaabbannoo kee ni mul'ata."), color = BookingMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            if (!booking.ticketNumber.isNullOrBlank() && booking.paymentStatus == "PAID") {
                Text(intercityText(language, "Ticket number", "የትኬት ቁጥር", "Lakkoofsa tikitii"), color = BookingMuted, fontSize = 11.sp)
                Text(booking.ticketNumber, color = CommuteInk, fontWeight = FontWeight.Bold, fontSize = if (compact) 15.sp else 23.sp, modifier = Modifier.testTag("intercity_ticket_number"))
            }
            if (compact) TextButton(onClick = onOpen, modifier = Modifier.align(Alignment.End).testTag("intercity_view_ticket")) {
                Text(intercityText(language, "View ticket", "ትኬት ይመልከቱ", "Tikitii ilaali"), color = CommuteInk)
                Icon(Icons.Default.ChevronRight, null, tint = CommuteInk, modifier = Modifier.size(19.dp))
            }
            if (!compact && onSavePdf != null && booking.paymentStatus == "PAID" && booking.status in setOf("CONFIRMED", "BOARDED")) OutlinedButton(onClick = onSavePdf, border = BorderStroke(1.dp, CommuteTeal), shape = RoundedCornerShape(13.dp), modifier = Modifier.fillMaxWidth().testTag("intercity_save_ticket")) {
                Icon(Icons.Default.SaveAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp))
                Text(intercityText(language, "Save ticket PDF", "የትኬት PDF ያስቀምጡ", "PDF tikitii olkaa'i"), color = CommuteTeal)
            }
        }
    }
}
