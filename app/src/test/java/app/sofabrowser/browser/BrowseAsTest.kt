package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseAsTest {

    private val stored = HashMap<String, String>()
    private val privately = HashMap<String, String>()
    private val sites = SiteModes(stored, privately)

    @Test fun aSiteIsItsRegistrableDomain() {
        assertEquals("example.co.uk", SiteModes.siteOf("www.Example.co.uk"))
        assertEquals("example.com", SiteModes.siteOf("player.cdn.example.com."))
        assertEquals("192.168.1.1", SiteModes.siteOf("192.168.1.1"))
        assertEquals("::1", SiteModes.siteOf("[::1]"))
        assertNull(SiteModes.siteOf(null))
        assertNull(SiteModes.siteOf(""))
    }

    @Test fun everySiteFollowsTheSettingUntilItHasItsOwn() {
        assertEquals("desktop", sites.mode("www.example.com", private = false, default = "desktop"))
        sites.choose("www.example.com", "webview", default = "desktop", private = false)
        assertEquals(mapOf("example.com" to "webview"), stored)
        // The whole site, every host on it.
        assertEquals("webview", sites.mode("video.example.com", private = false, default = "desktop"))
        assertEquals("desktop", sites.mode("example.org", private = false, default = "desktop"))
        // Its own choice holds whatever the setting becomes.
        assertEquals("webview", sites.mode("example.com", private = false, default = "mobile"))
    }

    @Test fun choosingTheSettingsModeMakesTheSiteFollowItAgain() {
        sites.choose("example.com", "webview", default = "desktop", private = false)
        sites.choose("example.com", "desktop", default = "desktop", private = false)
        assertTrue(stored.isEmpty())
        assertNull(sites.own("example.com", private = false))
    }

    @Test fun allSitesDropsTheSitesOwnChoice() {
        sites.choose("example.com", "webview", default = "desktop", private = false)
        sites.choose("example.com", "ipad", default = "desktop", private = true)
        sites.follow("www.example.com")
        assertTrue(stored.isEmpty())
        assertTrue(privately.isEmpty())
    }

    @Test fun privateChoicesStayInMemoryAndCountOnlyThere() {
        sites.choose("example.com", "webview", default = "desktop", private = true)
        assertTrue(stored.isEmpty())
        assertEquals("webview", sites.mode("example.com", private = true, default = "desktop"))
        assertEquals("desktop", sites.mode("example.com", private = false, default = "desktop"))
        assertEquals(emptyList<Pair<String, String>>(), sites.all())
    }

    @Test fun privateBrowsingSeesStoredChoicesAndCanOverrideThem() {
        sites.choose("example.com", "webview", default = "desktop", private = false)
        assertEquals("webview", sites.mode("example.com", private = true, default = "desktop"))
        // Back to the setting in private browsing: held in memory against the stored choice, which stays.
        sites.choose("example.com", "desktop", default = "desktop", private = true)
        assertEquals("desktop", sites.mode("example.com", private = true, default = "desktop"))
        assertEquals("webview", sites.mode("example.com", private = false, default = "desktop"))
        assertEquals(mapOf("example.com" to "webview"), stored)
    }

    @Test fun unknownModesAreIgnored() {
        stored["example.com"] = "netscape"
        assertNull(sites.own("example.com", private = false))
        assertEquals(emptyList<Pair<String, String>>(), sites.all())
        sites.choose("example.org", "netscape", default = "desktop", private = false)
        assertNull(stored["example.org"])
    }

    @Test fun listsStoredChoicesByName() {
        sites.choose("b.example", "mobile", default = "desktop", private = false)
        sites.choose("a.example", "webview", default = "desktop", private = false)
        assertEquals(listOf("a.example" to "webview", "b.example" to "mobile"), sites.all())
        sites.remove("a.example")
        assertEquals(listOf("b.example" to "mobile"), sites.all())
    }

    @Test fun pagesWithoutASiteFollowTheSetting() {
        sites.choose("example.com", "webview", default = "desktop", private = false)
        assertEquals("desktop", sites.mode(null, private = false, default = "desktop"))
    }
}
