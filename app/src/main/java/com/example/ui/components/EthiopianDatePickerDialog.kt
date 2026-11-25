package com.example.ui.components

import android.icu.util.Calendar
import com.example.core.localization.AppLanguage
import com.example.core.time.EthiopianDateTime
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Ethiopian-calendar date picker. Emits the selected day as a Gregorian yyyy-MM-dd API value. */
@Composable
internal fun EthiopianDatePickerDialog(
    initialIsoDate: String?,
    minimumIsoDate: String = EthiopianDateTime.todayIso(),
    language: AppLanguage,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val initialCalendar = remember(initialIsoDate, minimumIsoDate, language) {
        EthiopianDateTime.calendarForIso(initialIsoDate ?: minimumIsoDate, language).apply {
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }
    var visibleMonthMillis by remember(initialIsoDate, minimumIsoDate, language) {
        mutableLongStateOf(initialCalendar.timeInMillis)
    }
    var selectedIso by remember(initialIsoDate, minimumIsoDate) { androidx.compose.runtime.mutableStateOf(initialIsoDate) }
    val month = EthiopianDateTime.calendarForMillis(visibleMonthMillis, language).apply { set(Calendar.DAY_OF_MONTH, 1) }
    val firstWeekday = month.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY
    val daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    val gridDays: List<Int?> = List(firstWeekday) { null } + (1..daysInMonth).map { it }
    val minIso = minimumIsoDate
    val weekdays = when (language) {
        AppLanguage.AMHARIC -> listOf("እሑ", "ሰኞ", "ማክ", "ረቡ", "ሐሙ", "ዓር", "ቅዳ")
        AppLanguage.OROMO -> listOf("Dil", "Wix", "Rob", "Kam", "Jim", "San", "Sab")
        AppLanguage.ENGLISH -> listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }
    val brand = Color(0xFF07866F)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp),
            color = Color(0xFFFFFEFC),
            shape = RoundedCornerShape(26.dp),
            shadowElevation = 18.dp
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (language) {
                        AppLanguage.AMHARIC -> "የኢትዮጵያ ቀን ይምረጡ"
                        AppLanguage.OROMO -> "Guyyaa Itoophiyaa filadhu"
                        AppLanguage.ENGLISH -> "Choose a date"
                    },
                    color = Color(0xFF09233D), fontWeight = FontWeight.Bold, fontSize = 19.sp
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val previous = EthiopianDateTime.calendarForMillis(month.timeInMillis, language)
                        previous.add(Calendar.MONTH, -1)
                        previous.set(Calendar.DAY_OF_MONTH, previous.getActualMaximum(Calendar.DAY_OF_MONTH))
                        if (EthiopianDateTime.compareIsoDates(EthiopianDateTime.isoDateFromCalendar(previous), minIso) >= 0) {
                            previous.set(Calendar.DAY_OF_MONTH, 1)
                            visibleMonthMillis = previous.timeInMillis
                        }
                    }, enabled = run {
                        val previous = EthiopianDateTime.calendarForMillis(month.timeInMillis, language)
                        previous.add(Calendar.MONTH, -1)
                        previous.set(Calendar.DAY_OF_MONTH, previous.getActualMaximum(Calendar.DAY_OF_MONTH))
                        EthiopianDateTime.compareIsoDates(EthiopianDateTime.isoDateFromCalendar(previous), minIso) >= 0
                    }) { Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = brand) }
                    Text(EthiopianDateTime.monthTitle(month, language), Modifier.weight(1f), color = Color(0xFF09233D), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    IconButton(onClick = {
                        month.add(Calendar.MONTH, 1)
                        month.set(Calendar.DAY_OF_MONTH, 1)
                        visibleMonthMillis = month.timeInMillis
                    }) { Icon(Icons.Default.ChevronRight, contentDescription = "Next month", tint = brand) }
                }
                Row(Modifier.fillMaxWidth()) {
                    weekdays.forEach { day ->
                        Box(Modifier.weight(1f).height(32.dp), contentAlignment = Alignment.Center) {
                            Text(day, color = Color(0xFF71808A), fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        }
                    }
                }
                gridDays.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        (week + List(7 - week.size) { null }).forEach { day ->
                            Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                                if (day != null) {
                                    val candidate = EthiopianDateTime.calendarForMillis(month.timeInMillis, language).apply { set(Calendar.DAY_OF_MONTH, day) }
                                    val iso = EthiopianDateTime.isoDateFromCalendar(candidate)
                                    val enabled = iso >= minIso
                                    val selected = selectedIso == iso
                                    Box(
                                        Modifier.size(36.dp).clip(CircleShape)
                                            .background(if (selected) brand else Color.Transparent)
                                            .clickable(enabled = enabled) { selectedIso = iso },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(day.toString(), color = when { selected -> Color.White; enabled -> Color(0xFF09233D); else -> Color(0xFFB7BEC2) }, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, fontSize = 14.sp)
                                    }
                                } else Spacer(Modifier.size(36.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) {
                        Text(when (language) { AppLanguage.AMHARIC -> "ሰርዝ"; AppLanguage.OROMO -> "Dhiisi"; AppLanguage.ENGLISH -> "Cancel" }, color = brand)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { selectedIso?.let(onSelect); onDismiss() }, enabled = selectedIso != null) {
                        Text(when (language) { AppLanguage.AMHARIC -> "ምረጥ"; AppLanguage.OROMO -> "Filadhu"; AppLanguage.ENGLISH -> "Choose" }, color = brand, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
