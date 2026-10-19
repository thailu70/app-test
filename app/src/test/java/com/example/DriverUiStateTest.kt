package com.example

import com.example.data.api.DriverGpsFix
import com.example.ui.screens.driver.driverHasPreciseFix
import com.example.ui.screens.driver.driverWindowInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DriverUiStateTest {
    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    @Test fun `boarding requires a fresh precise fix with valid coordinates`() {
        val now = at("2026-10-19T04:00:00Z")
        val fix = DriverGpsFix(9.03, 38.74, 12.0, now)

        assertTrue(driverHasPreciseFix(fix, now))
        assertTrue(driverHasPreciseFix(fix.copy(accuracyMeters = 0.0), now))
        assertTrue(driverHasPreciseFix(fix.copy(accuracyMeters = 100.0, timestamp = now - 120_000), now))
        assertFalse(driverHasPreciseFix(null, now))
        assertFalse(driverHasPreciseFix(fix.copy(timestamp = now - 120_001), now))
        assertFalse(driverHasPreciseFix(fix.copy(timestamp = now + 10_001), now))
        assertFalse(driverHasPreciseFix(fix.copy(accuracyMeters = 100.01), now))
        assertFalse(driverHasPreciseFix(fix.copy(accuracyMeters = -1.0), now))
        assertFalse(driverHasPreciseFix(fix.copy(accuracyMeters = Double.NaN), now))
        assertFalse(driverHasPreciseFix(fix.copy(latitude = 90.01), now))
        assertFalse(driverHasPreciseFix(fix.copy(longitude = -180.01), now))
        assertFalse(driverHasPreciseFix(fix.copy(latitude = Double.NaN), now))
        assertFalse(driverHasPreciseFix(fix.copy(longitude = Double.POSITIVE_INFINITY), now))
    }

    @Test fun `daily departure countdown measures until boarding window opens`() {
        // At 06:00 Addis Ababa time, a 07:15 departure opens boarding in 45 minutes.
        val info = driverWindowInfo("07:15", at("2026-10-19T03:00:00Z"))
        assertEquals("7:15 AM", info.timeLabel)
        assertEquals("Boarding window opens in 45 min.", info.message)
        assertEquals("Boarding window opens in 45 min.",
            driverWindowInfo("07:15", at("2026-10-19T03:00:30Z")).message)
    }

    @Test fun `open daily window still requires server GPS confirmation`() {
        val opens = at("2026-10-19T03:45:00Z")
        val closes = at("2026-10-19T04:45:00Z")
        val openMessage = "Departure window open. Your GPS area still needs confirmation."
        assertEquals(openMessage, driverWindowInfo("07:15", opens).message)
        assertEquals(openMessage, driverWindowInfo("07:15", closes).message)
        assertEquals("Departure window passed. Contact your operator.",
            driverWindowInfo("07:15", closes + 1).message)
    }

    @Test fun `intercity ISO timestamps display and compare in Addis Ababa time`() {
        val now = at("2026-10-19T03:00:00Z")
        for (schedule in listOf("2026-10-19T04:15:00Z", "2026-10-19T04:15:00.000Z")) {
            val info = driverWindowInfo(schedule, now)
            assertEquals("7:15 AM", info.timeLabel)
            assertEquals("Boarding window opens in 45 min.", info.message)
        }
        assertEquals("Boarding window opens in 1485 min.",
            driverWindowInfo("2026-10-20T04:15:00Z", now).message)
    }

    @Test fun `recurring daily window follows the nearest day around midnight`() {
        val openMessage = "Departure window open. Your GPS area still needs confirmation."
        assertEquals(openMessage, driverWindowInfo("00:10", at("2026-10-19T20:55:00Z")).message)
        assertEquals(openMessage, driverWindowInfo("23:50", at("2026-10-19T21:05:00Z")).message)
    }

    @Test fun `invalid schedules request operator help instead of implying eligibility`() {
        val now = at("2026-10-19T04:00:00Z")
        for (schedule in listOf("", "not-a-schedule", "24:10", "07:99", "2026-02-30T07:00:00Z")) {
            val info = driverWindowInfo(schedule, now)
            assertEquals(if (schedule.isBlank()) "Time not configured" else schedule, info.timeLabel)
            assertEquals("Check the departure schedule with your operator.", info.message)
        }
    }
}
