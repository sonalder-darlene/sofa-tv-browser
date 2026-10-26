package app.sofabrowser.browser

import app.sofabrowser.browser.LinkMenu.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkMenuTest {

    @Test
    fun aWebLinkCanBeOpenedElsewhereCopiedAndShared() {
        assertEquals(listOf(Action.OPEN_ELSEWHERE, Action.COPY_LINK, Action.SHARE_LINK), LinkMenu.actions("https://example.org/a", null))
    }

    @Test
    fun aLinkToAFileCanAlsoBeDownloaded() {
        assertEquals(listOf(Action.OPEN_ELSEWHERE, Action.COPY_LINK, Action.SHARE_LINK, Action.DOWNLOAD_LINK),
            LinkMenu.actions("https://example.org/files/film.mkv?token=1", null))
    }

    @Test
    fun fileLinksAreToldFromPages() {
        assertTrue(LinkMenu.looksLikeFile("https://example.org/a/report.PDF"))
        assertTrue(LinkMenu.looksLikeFile("https://example.org/x.zip#part"))
        assertFalse(LinkMenu.looksLikeFile("https://example.org/a/index.html"))
        assertFalse(LinkMenu.looksLikeFile("https://example.org/watch.php?v=1.mp4"))
        assertFalse(LinkMenu.looksLikeFile("https://example.org/some/page"))
        assertFalse(LinkMenu.looksLikeFile("https://example.org"))
        assertFalse(LinkMenu.looksLikeFile("https://www.example.org/"))
        assertFalse(LinkMenu.looksLikeFile("https://example.org/v1.2/notes"))
    }

    @Test
    fun anImageLinkOffersBothTheLinkAndThePicture() {
        assertEquals(listOf(Action.OPEN_ELSEWHERE, Action.COPY_LINK, Action.SHARE_LINK,
            Action.OPEN_IMAGE, Action.DOWNLOAD_IMAGE, Action.COPY_IMAGE, Action.SHARE_IMAGE),
            LinkMenu.actions("https://example.org/a", "https://example.org/p.jpg"))
    }

    @Test
    fun otherSchemesGoToOtherAppsButAreNotDownloads() {
        assertEquals(listOf(Action.OPEN_ELSEWHERE, Action.COPY_LINK, Action.SHARE_LINK), LinkMenu.actions("mailto:a@example.org", null))
    }

    @Test
    fun scriptLinksAndEmptyHitsGiveNoMenu() {
        assertTrue(LinkMenu.actions("javascript:void(0)", null).isEmpty())
        assertTrue(LinkMenu.actions(" ", "").isEmpty())
        assertTrue(LinkMenu.actions(null, null).isEmpty())
    }

    @Test
    fun picturesHeldInThePageCanOnlyBeSaved() {
        assertEquals(listOf(Action.DOWNLOAD_IMAGE), LinkMenu.actions(null, "data:image/png;base64,AAAA"))
        assertEquals(listOf(Action.DOWNLOAD_IMAGE), LinkMenu.actions(null, "blob:https://example.org/1-2"))
        assertEquals(listOf(Action.DOWNLOAD_LINK), LinkMenu.actions("blob:https://example.org/3", null))
    }

    @Test
    fun theTitleIsTheShortenedAddress() {
        assertEquals("example.org/a", LinkMenu.title("https://www.example.org/a", "https://cdn.example.org/p.jpg"))
        assertEquals("cdn.example.org/p.jpg", LinkMenu.title(null, "https://cdn.example.org/p.jpg"))
        assertEquals("", LinkMenu.title(null, "data:image/png;base64,AAAA"))
        val long = LinkMenu.title("https://example.org/" + "x".repeat(200), null, max = 30)
        assertEquals(30, long.length)
        assertTrue(long.endsWith("…"))
    }
}
