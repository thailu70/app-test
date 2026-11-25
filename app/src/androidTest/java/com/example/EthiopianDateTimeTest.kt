package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.core.localization.AppLanguage
import com.example.core.time.EthiopianDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EthiopianDateTimeTest {
    @Test fun apiDateRoundTripsAndDisplaysEthiopianYear() {
        val iso = "2026-11-25"
        val displayed = EthiopianDateTime.formatDate(iso, AppLanguage.ENGLISH)
        assertTrue("Expected an Ethiopian-calendar year in '$displayed'", displayed.contains("2019"))
        assertFalse("Should not display the Gregorian year in '$displayed'", displayed.contains("2026"))
        assertEquals(iso, EthiopianDateTime.isoDateFromCalendar(EthiopianDateTime.calendarForIso(iso, AppLanguage.ENGLISH)))
    }

    @Test fun timestampsUseEthiopianClockAndCalendar() {
        val instant = EthiopianDateTime.toApiTimestamp("2026-11-25", EthiopianDateTime.encodeClock(1, 0, morning = true))!!
        val displayed = EthiopianDateTime.formatTimestamp(instant, AppLanguage.ENGLISH)
        assertTrue("Expected 1:00 Ethiopian day in '$displayed'", displayed.contains("01:00") && displayed.contains("Day"))
        assertTrue("Expected Ethiopian year in '$displayed'", displayed.contains("2019"))
    }

    @Test fun ethiopianClockConvertsToApiEatAndHandlesDateRollover() {
        assertEquals("2026-11-25" to "07:00", EthiopianDateTime.toApiDateTime("2026-11-25", EthiopianDateTime.encodeClock(1, 0, morning = true)))
        assertEquals("2026-11-26" to "00:00", EthiopianDateTime.toApiDateTime("2026-11-25", EthiopianDateTime.encodeClock(6, 0, morning = false)))
        assertEquals("2026-11-25" to "18:00", EthiopianDateTime.toApiDateTime("2026-11-25", EthiopianDateTime.encodeClock(12, 0, morning = false)))
    }

    @Test fun pickerShowsEthiopianTimeAndSimplifiedDayParts() {
        val morning = EthiopianDateTime.clockValueFromEAT(7, 0)
        assertTrue(EthiopianDateTime.formatClock(morning, AppLanguage.ENGLISH).contains("01:00 · Day"))
        assertTrue(EthiopianDateTime.formatClock(morning, AppLanguage.AMHARIC).contains("ቀን"))
        val lateMorning = EthiopianDateTime.formatClock(EthiopianDateTime.encodeClock(5, 30, true), AppLanguage.AMHARIC)
        val afternoon = EthiopianDateTime.formatClock(EthiopianDateTime.encodeClock(8, 30, true), AppLanguage.AMHARIC)
        assertTrue(lateMorning.contains("ቀን")); assertFalse(lateMorning.contains("ከረፋዱ"))
        assertTrue(afternoon.contains("ቀን")); assertFalse(afternoon.contains("ከሰዓት"))
        assertTrue(EthiopianDateTime.formatClock(EthiopianDateTime.encodeClock(2, 15, false), AppLanguage.AMHARIC).contains("ማታ"))
    }

    @Test fun pickerOffersDayEveningAndNightWithoutChangingApiValues() {
        val day = EthiopianDateTime.clockInPeriod(EthiopianDateTime.EthiopianClock(5, 30, true), EthiopianDateTime.EthiopianPeriod.DAY)
        assertEquals(EthiopianDateTime.EthiopianClock(5, 30, true), day)
        assertTrue(EthiopianDateTime.formatClock(EthiopianDateTime.encodeClock(day.hour, day.minute, day.morning), AppLanguage.AMHARIC).contains("ቀን"))
        assertEquals("2026-11-25" to "11:30", EthiopianDateTime.toApiDateTime("2026-11-25", EthiopianDateTime.encodeClock(day.hour, day.minute, day.morning)))

        val evening = EthiopianDateTime.clockInPeriod(EthiopianDateTime.EthiopianClock(2, 45, true), EthiopianDateTime.EthiopianPeriod.EVENING)
        assertEquals(EthiopianDateTime.EthiopianClock(12, 45, false), evening)
        assertEquals("2026-11-25" to "18:45", EthiopianDateTime.toApiDateTime("2026-11-25", EthiopianDateTime.encodeClock(evening.hour, evening.minute, evening.morning)))

        for (period in EthiopianDateTime.EthiopianPeriod.values()) {
            val selected = EthiopianDateTime.clockInPeriod(EthiopianDateTime.EthiopianClock(2, 15, true), period)
            assertEquals(period, EthiopianDateTime.periodFor(selected))
            assertEquals(15, selected.minute)
        }
    }

    @Test fun legacyEatTimeValuesRemainCompatible() {
        assertEquals("2026-11-25" to "08:15", EthiopianDateTime.toApiDateTime("2026-11-25", "08:15"))
        assertTrue(EthiopianDateTime.formatClock("08:15", AppLanguage.ENGLISH).contains("02:15 · Day"))
    }

    @Test fun utcInstantIsShownAsEthiopianClock() {
        val displayed = EthiopianDateTime.formatTimestamp("2026-11-25T00:00:00.000Z", AppLanguage.ENGLISH)
        assertTrue("Expected UTC midnight to display as Ethiopian 09:00 night in '$displayed'", displayed.contains("09:00") && displayed.contains("Night"))
    }
}
