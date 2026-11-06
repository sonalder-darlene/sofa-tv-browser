package app.sofabrowser.ui

import app.sofabrowser.ui.Dates.Ago
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class DatesTest {
    private val berlin = TimeZone.getTimeZone("Europe/Berlin")

    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0): Long =
        Calendar.getInstance(berlin).apply { clear(); set(y, m - 1, d, h, min) }.timeInMillis

    @Test
    fun minutesAndHoursWithinTheSameDay() {
        val now = at(2026, 11, 6, 15, 0)
        assertEquals(Ago.Minutes(1), Dates.ago(now - 20_000, now, berlin))
        assertEquals(Ago.Minutes(59), Dates.ago(at(2026, 11, 6, 14, 1), now, berlin))
        assertEquals(Ago.Hours(1), Dates.ago(at(2026, 11, 6, 14, 0), now, berlin))
        assertEquals(Ago.Hours(14), Dates.ago(at(2026, 11, 6, 0, 30), now, berlin))
    }

    @Test
    fun calendarDaysAfterMidnight() {
        val now = at(2026, 11, 6, 0, 30)
        assertEquals(Ago.Minutes(40), Dates.ago(at(2026, 11, 5, 23, 50), now, berlin))
        assertEquals(Ago.Yesterday, Dates.ago(at(2026, 11, 5, 22, 0), now, berlin))
        // Two calendar days back is "2 days ago", not a word in the TV's language.
        assertEquals(Ago.Days(2), Dates.ago(at(2026, 11, 4, 9, 0), now, berlin))
        assertEquals(Ago.Days(30), Dates.ago(at(2026, 10, 7), now, berlin))
        assertEquals(Ago.Long, Dates.ago(at(2026, 10, 6), now, berlin))
    }

    @Test
    fun daysAcrossAYearEndAndAClockChange() {
        assertEquals(1, Dates.daysBetween(at(2025, 12, 31, 23, 0), at(2026, 1, 1, 1, 0), berlin))
        assertEquals(0, Dates.daysBetween(at(2026, 1, 1, 0, 1), at(2026, 1, 1, 23, 59), berlin))
        // The night of 25 October 2026 lasts 25 hours in Berlin.
        assertEquals(1, Dates.daysBetween(at(2026, 10, 25, 0, 30), at(2026, 10, 26, 0, 10), berlin))
        assertEquals(7, Dates.daysBetween(at(2026, 10, 20), at(2026, 10, 27), berlin))
    }

    @Test
    fun datesAreEnglishWhateverTheTvsLanguage() {
        val before = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.GERMANY)
        try {
            assertEquals("6 Nov 2026", Dates.date(at(2026, 11, 6), berlin))
            assertEquals("Thursday 5 November", Dates.day(at(2026, 11, 5), at(2026, 11, 6), berlin))
            assertEquals("Wednesday 31 December 2025", Dates.day(at(2025, 12, 31), at(2026, 11, 6), berlin))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}
