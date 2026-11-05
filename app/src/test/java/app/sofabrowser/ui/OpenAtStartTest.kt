package app.sofabrowser.ui

import app.sofabrowser.ui.OpenAtStart.Choice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAtStartTest {

    @Test
    fun theIdsNeverChange() {
        // Stored in Settings and written by a settings export.
        assertEquals(listOf("home", "page", "last"), Choice.entries.map { it.id })
    }

    @Test
    fun nothingStoredIsTheStartPage() {
        assertEquals(Choice.START_PAGE, OpenAtStart.choice(null))
        assertEquals(Choice.START_PAGE, OpenAtStart.choice(""))
        assertEquals(Choice.START_PAGE, OpenAtStart.choice("tabs"))
        assertEquals(Choice.LAST_PAGE, OpenAtStart.choice("last"))
    }

    @Test
    fun theStartPageOpensNothing() {
        assertNull(OpenAtStart.address(Choice.START_PAGE, "https://example.com/", "https://example.org/", restored = false))
    }

    @Test
    fun aWebPageOpensOnlyOnAFreshStart() {
        assertEquals("https://example.com/tv", OpenAtStart.address(Choice.WEB_PAGE, "https://example.com/tv", "", restored = false))
        // A rebuilt screen keeps what the user was on (today: the start page) rather than the chosen page.
        assertNull(OpenAtStart.address(Choice.WEB_PAGE, "https://example.com/tv", "", restored = true))
        assertNull(OpenAtStart.address(Choice.WEB_PAGE, "", "", restored = false))
    }

    @Test
    fun whereILeftOffOpensTheLastPageEvenWhenRebuilt() {
        assertEquals("https://example.org/watch?v=1", OpenAtStart.address(Choice.LAST_PAGE, "", "https://example.org/watch?v=1", restored = false))
        assertEquals("https://example.org/watch?v=1", OpenAtStart.address(Choice.LAST_PAGE, "", "https://example.org/watch?v=1", restored = true))
        // Left on the start page.
        assertNull(OpenAtStart.address(Choice.LAST_PAGE, "https://example.com/", "", restored = false))
    }

    @Test
    fun onlyWhereILeftOffRemembersAndNeverPrivatePages() {
        val page = "https://example.org/a#b"
        assertEquals(page, OpenAtStart.toRemember(Choice.LAST_PAGE, page, private = false, clearOnExit = false))
        assertEquals("", OpenAtStart.toRemember(Choice.LAST_PAGE, null, private = false, clearOnExit = false))
        assertEquals("", OpenAtStart.toRemember(Choice.LAST_PAGE, page, private = true, clearOnExit = false))
        assertEquals("", OpenAtStart.toRemember(Choice.LAST_PAGE, page, private = false, clearOnExit = true))
        assertEquals("", OpenAtStart.toRemember(Choice.START_PAGE, page, private = false, clearOnExit = false))
        assertEquals("", OpenAtStart.toRemember(Choice.WEB_PAGE, page, private = false, clearOnExit = false))
        // An error page, a block screen's blank, a download.
        assertEquals("", OpenAtStart.toRemember(Choice.LAST_PAGE, "about:blank", private = false, clearOnExit = false))
        assertEquals("", OpenAtStart.toRemember(Choice.LAST_PAGE, "data:text/html,x", private = false, clearOnExit = false))
    }

    @Test
    fun typedAddressesBecomeWebPages() {
        assertEquals("https://example.com", OpenAtStart.webAddress("example.com"))
        assertEquals("https://example.com/tv?x=1", OpenAtStart.webAddress("  example.com/tv?x=1 "))
        assertEquals("https://example.com/", OpenAtStart.webAddress("https://example.com/"))
        assertEquals("http://192.168.1.1:8096/web", OpenAtStart.webAddress("http://192.168.1.1:8096/web"))
        assertEquals("https://localhost:8080", OpenAtStart.webAddress("localhost:8080"))
        assertEquals("https://example.com/", OpenAtStart.webAddress("HTTPS://example.com/"))
        assertEquals("https://app.example.tv/desktop/#!/", OpenAtStart.webAddress("https://app.example.tv/desktop/#!/"))
        assertEquals("http://[::1]:8080/", OpenAtStart.webAddress("http://[::1]:8080/"))
        assertEquals("https://bücher.example/", OpenAtStart.webAddress("bücher.example/"))
        assertEquals("https://user@example.com/", OpenAtStart.webAddress("https://user@example.com/"))
        // Typed after the "https://" the field starts with.
        assertEquals("https://example.com", OpenAtStart.webAddress("https://https://example.com"))
        assertEquals("http://example.com", OpenAtStart.webAddress("https://http://example.com"))
    }

    @Test
    fun anythingButAWebPageIsRefused() {
        for (typed in listOf("", "   ", "https://", "http://", "javascript:alert(1)", "about:blank", "data:text/html,x",
            "file:///sdcard/a.html", "content://x/y", "intent://x#Intent;end", "mailto:a@example.com",
            "chrome://flags", "https:///path", "https://.", "two words.com", "https://exa mple.com",
            // Typed after an address already in the field.
            "https://example.orgabout:blank", "example.com:port", "https://example.com:123456/", "https://exa<mple.com/",
            "https://[nope]/")) {
            assertNull(typed, OpenAtStart.webAddress(typed))
        }
    }

    @Test
    fun settingsNamesThePageByItsSite() {
        assertEquals("example.com", OpenAtStart.label("https://www.example.com/tv"))
        assertEquals("tv.example.com", OpenAtStart.label("https://tv.example.com/"))
        assertEquals("http://192.168.1.1:8096", OpenAtStart.label("http://192.168.1.1:8096/web"))
    }
}
