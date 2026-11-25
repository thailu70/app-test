package com.example.core.time

import android.icu.text.SimpleDateFormat
import android.icu.util.EthiopicCalendar
import android.icu.util.TimeZone
import android.icu.util.ULocale
import com.example.core.localization.AppLanguage
import java.text.ParsePosition
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** UI dates and clock values are Ethiopian; API values remain ISO Gregorian/EAT. */
internal object EthiopianDateTime {
    const val TIME_ZONE_ID = "Africa/Addis_Ababa"
    private val zone: TimeZone get() = TimeZone.getTimeZone(TIME_ZONE_ID)
    private val javaZone: java.util.TimeZone get() = java.util.TimeZone.getTimeZone(TIME_ZONE_ID)

    data class EthiopianClock(val hour: Int, val minute: Int, val morning: Boolean)

    enum class EthiopianPeriod(val defaultHour: Int, val morning: Boolean) {
        DAY(1, true),
        EVENING(12, false),
        NIGHT(6, false)
    }

    fun locale(language: AppLanguage): ULocale = ULocale("${language.code}_ET@calendar=ethiopic")

    fun todayIso(nowMillis: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = javaZone; isLenient = false }.format(Date(nowMillis))

    /** Date paired with an Ethiopian-clock time (whose day starts six hours before EAT midnight). */
    fun clockDateIso(nowMillis: Long = System.currentTimeMillis()): String = todayIso(nowMillis - 6L * 60 * 60 * 1000)

    fun apiTimestamp(nowMillis: Long = System.currentTimeMillis()): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC"); isLenient = false
        }.format(Date(nowMillis))

    /** Encode Ethiopian picker values so they cannot be mistaken for legacy EAT strings. */
    fun encodeClock(hour: Int, minute: Int, morning: Boolean): String {
        require(hour in 1..12 && minute in 0..59)
        return "ETC:%02d:%02d:%s".format(Locale.US, hour, minute, if (morning) "M" else "E")
    }

    /** Create a picker value from an existing 24-hour EAT value (used for defaults and legacy data). */
    fun clockValueFromEAT(hour: Int, minute: Int): String {
        require(hour in 0..23 && minute in 0..59)
        val clock = clockFromEAT(hour, minute)
        return encodeClock(clock.hour, clock.minute, clock.morning)
    }

    /** Convert the selected Ethiopian date and clock to the existing EAT API fields. */
    fun toApiDateTime(ethiopianDateIso: String, clockValue: String): Pair<String, String>? = runCatching {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(ethiopianDateIso))
        val selected = parseIsoDate(ethiopianDateIso) ?: error("Invalid date")
        val encoded = Regex("ETC:(\\d{2}):(\\d{2}):([ME])").matchEntire(clockValue)
        if (encoded == null) {
            // Existing/API time strings are 24-hour EAT. Preserve them as-is until the user edits them.
            val legacy = Regex("(\\d{1,2}):(\\d{2})").matchEntire(clockValue) ?: error("Invalid time")
            val hour = legacy.groupValues[1].toIntOrNull() ?: error("Invalid time")
            val minute = legacy.groupValues[2].toIntOrNull() ?: error("Invalid time")
            require(hour in 0..23 && minute in 0..59)
            return@runCatching ethiopianDateIso to String.format(Locale.US, "%02d:%02d", hour, minute)
        }
        val clock = parseClock(clockValue) ?: error("Invalid time")
        val ethiopianHourOfDay = (clock.hour % 12) + if (clock.morning) 0 else 12
        val eatTotal = ethiopianHourOfDay + 6
        val dayOffset = eatTotal / 24
        val eatHour = eatTotal % 24
        val calendar = Calendar.getInstance(javaZone, Locale.US).apply {
            time = selected
            add(Calendar.DAY_OF_MONTH, dayOffset)
        }
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = javaZone }.format(calendar.time)
        date to String.format(Locale.US, "%02d:%02d", eatHour, clock.minute)
    }.getOrNull()

    /** ISO timestamp for APIs that accept a single departureAt value. */
    fun toApiTimestamp(ethiopianDateIso: String, clockValue: String): String? =
        toApiDateTime(ethiopianDateIso, clockValue)?.let { (date, time) ->
            runCatching {
                val parser = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = javaZone; isLenient = false }
                val instant = parser.parse("$date $time") ?: return null
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(instant)
            }.getOrNull()
        }

    fun parseClock(value: String?): EthiopianClock? {
        if (value.isNullOrBlank()) return null
        Regex("ETC:(\\d{2}):(\\d{2}):([ME])").matchEntire(value)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (hour !in 1..12 || minute !in 0..59) return null
            return EthiopianClock(hour, minute, match.groupValues[3] == "M")
        }
        // Existing records and saved forms use ordinary 24-hour EAT. Convert for display/editing.
        Regex("(\\d{1,2}):(\\d{2})").matchEntire(value)?.let { match ->
            val eatHour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            if (eatHour !in 0..23 || minute !in 0..59) return null
            val ethiopianHourOfDay = Math.floorMod(eatHour - 6, 24)
            val morning = ethiopianHourOfDay < 12
            val hour = when (val twelveHour = ethiopianHourOfDay % 12) { 0 -> 12; else -> twelveHour }
            return EthiopianClock(hour, minute, morning)
        }
        return null
    }

    fun periodFor(clock: EthiopianClock): EthiopianPeriod = when {
        clock.morning -> EthiopianPeriod.DAY
        !clock.morning && (clock.hour == 12 || clock.hour in 1..5) -> EthiopianPeriod.EVENING
        !clock.morning && clock.hour in 6..11 -> EthiopianPeriod.NIGHT
        else -> EthiopianPeriod.EVENING
    }

    /** Keep the selected hour if it belongs to the requested Ethiopian day part. */
    fun clockInPeriod(clock: EthiopianClock, period: EthiopianPeriod): EthiopianClock =
        if (periodFor(clock) == period) clock
        else EthiopianClock(period.defaultHour, clock.minute, period.morning)

    fun parseIsoDate(value: String?): Date? {
        if (value.isNullOrBlank()) return null
        val parser = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = javaZone; isLenient = false }
        val position = ParsePosition(0)
        return parser.parse(value, position)?.takeIf { position.index == value.length }
    }

    fun formatDate(value: String, language: AppLanguage = AppLanguage.ENGLISH): String {
        val isoDay = value.take(10).takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
        return (isoDay?.let(::parseIsoDate) ?: parseLegacyDate(value))?.let { formatDate(it, language) } ?: value
    }

    fun formatDate(value: Date, language: AppLanguage = AppLanguage.ENGLISH): String =
        SimpleDateFormat("EEE, d MMM y", locale(language)).apply { timeZone = zone }.format(value)

    /** Millisecond instants arrive as UTC; shift back six hours to show the Ethiopian clock day. */
    fun formatClock(valueMillis: Long, language: AppLanguage = AppLanguage.ENGLISH): String =
        formatEthiopianInstant(Date(valueMillis), language, includeDate = false)

    fun formatTimestamp(value: String, language: AppLanguage = AppLanguage.ENGLISH): String {
        if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return formatDate(value, language)
        val instant = parseTimestamp(value) ?: return value
        return formatEthiopianInstant(instant, language, includeDate = true)
    }

    fun formatClock(value: String, language: AppLanguage = AppLanguage.ENGLISH): String {
        parseClock(value)?.let { clock -> return formatClockLabel(clock, language) }
        val instant = parseTimestamp(value) ?: return value
        val calendar = Calendar.getInstance(javaZone, Locale.US).apply { time = instant }
        return formatClockLabel(clockFromEAT(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)), language)
    }

    fun calendarForMillis(millis: Long, language: AppLanguage): EthiopicCalendar =
        EthiopicCalendar(zone, locale(language)).apply { timeInMillis = millis }

    fun calendarForIso(value: String?, language: AppLanguage): EthiopicCalendar =
        calendarForMillis((parseIsoDate(value) ?: Date()).time, language)

    fun monthTitle(calendar: EthiopicCalendar, language: AppLanguage): String =
        SimpleDateFormat("MMMM y", locale(language)).apply { timeZone = zone }.format(Date(calendar.timeInMillis))

    fun isoDateFromCalendar(calendar: EthiopicCalendar): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = javaZone; isLenient = false }.format(Date(calendar.timeInMillis))

    fun compareIsoDates(left: String, right: String): Int = left.compareTo(right)

    private fun formatEthiopianInstant(date: Date, language: AppLanguage, includeDate: Boolean): String {
        val eatCalendar = Calendar.getInstance(javaZone, Locale.US).apply { time = date }
        val clock = clockFromEAT(eatCalendar.get(Calendar.HOUR_OF_DAY), eatCalendar.get(Calendar.MINUTE))
        val ethiopianDay = Date(date.time - 6L * 60 * 60 * 1000)
        val dateText = if (includeDate) "${SimpleDateFormat("EEE, d MMM y", locale(language)).apply { timeZone = zone }.format(ethiopianDay)} · " else ""
        return "$dateText${formatClockLabel(clock, language)}"
    }

    private fun clockFromEAT(eatHour: Int, minute: Int): EthiopianClock {
        val ethiopianHourOfDay = Math.floorMod(eatHour - 6, 24)
        val morning = ethiopianHourOfDay < 12
        val twelveHour = ethiopianHourOfDay % 12
        return EthiopianClock(if (twelveHour == 0) 12 else twelveHour, minute, morning)
    }

    private fun formatClockLabel(clock: EthiopianClock, language: AppLanguage): String {
        val label = when (periodFor(clock)) {
            EthiopianPeriod.DAY -> when (language) { AppLanguage.AMHARIC -> "ቀን"; AppLanguage.OROMO -> "Guyyaa"; else -> "Day" }
            EthiopianPeriod.EVENING -> when (language) { AppLanguage.AMHARIC -> "ማታ"; AppLanguage.OROMO -> "Galgal"; else -> "Evening" }
            EthiopianPeriod.NIGHT -> when (language) { AppLanguage.AMHARIC -> "ሌሊት"; AppLanguage.OROMO -> "Halkan"; else -> "Night" }
        }
        return String.format(Locale.US, "%02d:%02d · %s", clock.hour, clock.minute, label)
    }

    private fun parseTimestamp(value: String): Date? {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX", "yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mmX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "dd MMM yyyy, hh:mm a"
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            val parser = java.text.SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone(if (pattern.endsWith("Z'") || pattern.endsWith("X")) "UTC" else TIME_ZONE_ID)
                isLenient = false
            }
            val position = ParsePosition(0)
            parser.parse(value, position)?.takeIf { position.index == value.length }
        }
    }

    private fun parseLegacyDate(value: String): Date? = listOf("dd MMM yyyy", "MMM d, yyyy", "MMMM d, yyyy")
        .firstNotNullOfOrNull { pattern ->
            val parser = java.text.SimpleDateFormat(pattern, Locale.US).apply { timeZone = javaZone; isLenient = false }
            val position = ParsePosition(0)
            parser.parse(value, position)?.takeIf { position.index == value.length }
        }
}
