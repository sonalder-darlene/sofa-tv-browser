package app.sofabrowser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterListTest {

    @Test
    fun aNewInstallHasTheTwoBundledListsOnAndEasyListAndEasyPrivacyOff() {
        val lists = FilterList.migrate(null, downloaded = false)
        assertEquals(listOf(FilterList.STEVENBLACK, FilterList.POPUPS, FilterList.EASYLIST, FilterList.EASYPRIVACY), lists.map { it.id })
        assertTrue(lists.filter { it.bundled }.all { it.enabled })
        assertTrue(lists.filter { it.stronger }.none { it.enabled || it.bundled })
    }

    @Test
    fun theDefaultAddressFromSofa11IsNotACustomList() {
        assertEquals(4, FilterList.migrate(FilterList.STEVENBLACK_URL, downloaded = true).size)
    }

    @Test
    fun easyListSetAsTheAddressInSofa11IsTheEasyListSwitchTurnedOn() {
        val lists = FilterList.migrate(FilterList.EASYLIST_URL, downloaded = true)
        assertEquals(4, lists.size)
        assertTrue(lists.single { it.id == FilterList.EASYLIST }.enabled)
        assertFalse(lists.single { it.id == FilterList.STEVENBLACK }.enabled)
    }

    @Test
    fun storedListsGetTheMissingOnesAndAnAddedEasyListBecomesTheSwitch() {
        val added = FilterList("u123", "EasyList", FilterList.EASYLIST_URL, rules = 9)
        val stored = FilterList.defaults().take(2) + added
        val (lists, renamed) = FilterList.complete(stored)
        assertEquals(mapOf("u123" to FilterList.EASYLIST), renamed)
        assertEquals(9, lists.single { it.id == FilterList.EASYLIST }.rules)
        assertTrue(lists.single { it.id == FilterList.EASYLIST }.enabled)
        assertFalse(lists.single { it.id == FilterList.EASYPRIVACY }.enabled)
        assertEquals(4, lists.size)
        assertEquals(stored.take(2) + FilterList.defaults().drop(2), FilterList.complete(FilterList.defaults().take(2)).first)
    }

    @Test
    fun aListIsDueWhenOlderThanItAsksBetweenADayAndAWeek() {
        val hour = 60 * 60 * 1000L
        val now = 1000 * 24 * hour
        val easy = FilterList(FilterList.EASYLIST, "EasyList", FilterList.EASYLIST_URL, updated = now - 95 * hour, expiresHours = 96)
        assertFalse(FilterList.isDue(easy, installed = 0, now = now))
        assertTrue(FilterList.isDue(easy.copy(updated = now - 97 * hour), installed = 0, now = now))
        // Never downloaded: due at once.
        assertTrue(FilterList.isDue(easy.copy(updated = 0), installed = 0, now = now))
        // "Expires: 1 hour" still waits a day, "30 days" no more than a week.
        assertFalse(FilterList.isDue(easy.copy(updated = now - 2 * hour, expiresHours = 1), installed = 0, now = now))
        assertTrue(FilterList.isDue(easy.copy(updated = now - 8 * 24 * hour, expiresHours = 720), installed = 0, now = now))
        // A bundled copy is as fresh as the installed version of Sofa.
        val bundled = FilterList.defaults().first()
        assertFalse(FilterList.isDue(bundled, installed = now - 24 * hour, now = now))
        assertTrue(FilterList.isDue(bundled, installed = now - 5 * 24 * hour, now = now))
    }

    @Test
    fun aCustomAddressIsKeptAndStillReplacesTheHostsListWhenItWasDownloaded() {
        val url = "https://lists.example/my-hosts.txt"
        val used = FilterList.migrate(url, downloaded = true)
        val custom = used.single { it.id == FilterList.CUSTOM_ID }
        assertEquals(url, custom.url)
        assertEquals("lists.example", custom.name)
        assertTrue(custom.enabled)
        assertFalse(used.single { it.id == FilterList.STEVENBLACK }.enabled)
        assertTrue(used.single { it.id == FilterList.POPUPS }.enabled)

        // Set but never downloaded: the bundled list was still the one in use.
        val notUsed = FilterList.migrate(url, downloaded = false)
        assertTrue(notUsed.single { it.id == FilterList.STEVENBLACK }.enabled)
        assertTrue(notUsed.any { it.id == FilterList.CUSTOM_ID })
    }

    @Test
    fun addressesAreReadWithoutAndroid() {
        assertEquals("ads.example", RequestUrl.hostOf("https://user@ADS.example.:8080/x?y"))
        assertEquals("::1", RequestUrl.hostOf("http://[::1]:80/"))
        assertNull(RequestUrl.hostOf("data:text/plain,hi"))
        assertEquals("//a.test/p?q=1", RequestUrl("https://a.test/p?q=1").ssp)
    }

    @Test
    fun strongerCountsAsChosenWhenOnOrOnceDownloaded() {
        val fresh = FilterList.defaults()
        assertFalse(FilterList.strongerChosen(fresh))
        fun with(change: (FilterList) -> FilterList) = fresh.map { if (it.id == FilterList.EASYLIST) change(it) else it }
        assertTrue(FilterList.strongerChosen(with { it.copy(enabled = true) }))
        assertTrue(FilterList.strongerChosen(with { it.copy(updated = 1_700_000_000_000) }))
        assertTrue(FilterList.strongerChosen(with { it.copy(error = "timeout") }))
        assertFalse(FilterList.strongerChosen(fresh.map { if (it.bundled) it.copy(updated = 1_700_000_000_000) else it }))
    }

    @Test
    fun sitesUseTheCommonCountryAndHostingSuffixes() {
        assertEquals("bbc.co.uk", Sites.of("www.news.bbc.co.uk"))
        assertEquals("co.uk", Sites.of("co.uk"))
        assertEquals("example.com", Sites.of("a.b.example.com"))
        assertEquals("me.github.io", Sites.of("me.github.io"))
        assertEquals("192.168.1.2", Sites.of("192.168.1.2"))
        assertEquals("www.example", Sites.withoutSuffix("www.example.com.au"))
    }
}
