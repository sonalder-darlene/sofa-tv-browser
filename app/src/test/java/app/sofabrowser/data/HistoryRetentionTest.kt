package app.sofabrowser.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRetentionTest {

    private val day = 24 * 3600 * 1000L
    private val now = 1_000 * day

    @Test
    fun aPeriodRemovesWhatIsOlder() {
        assertEquals(now - 7 * day, HistoryRetention.cutoff(7, now))
        assertEquals(now - 90 * day, HistoryRetention.cutoff(90, now))
    }

    @Test
    fun alwaysKeepsEverythingAndNeverKeepsNothing() {
        assertNull(HistoryRetention.cutoff(HistoryRetention.ALWAYS, now))
        assertEquals(now, HistoryRetention.cutoff(HistoryRetention.NEVER, now))
        assertTrue(HistoryRetention.records(HistoryRetention.ALWAYS))
        assertTrue(HistoryRetention.records(30))
        assertFalse(HistoryRetention.records(HistoryRetention.NEVER))
    }

    @Test
    fun theOldSwitchCarriesOver() {
        assertEquals(90, HistoryRetention.fromOldSwitch(rememberHistory = true))
        assertEquals(HistoryRetention.NEVER, HistoryRetention.fromOldSwitch(rememberHistory = false))
    }
}
