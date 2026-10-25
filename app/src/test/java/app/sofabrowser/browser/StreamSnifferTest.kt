package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSnifferTest {

    private val sniffer = StreamSniffer()

    private fun urls() = sniffer.candidates().map { it.url }

    @Test
    fun anHlsPlaylistThePageFetchedIsTheStream() {
        sniffer.request("https://site.test/app.js", listed = false)
        sniffer.request("https://cdn.test/vod/master.m3u8?token=abc", listed = false)
        assertEquals(listOf("https://cdn.test/vod/master.m3u8?token=abc"), urls())
    }

    @Test
    fun dashManifestsAndOddSpellingsCount() {
        sniffer.request("https://cdn.test/a/MANIFEST.MPD#t=1", listed = false)
        sniffer.request("https://cdn.test/b/Index.M3U8", listed = false)
        assertEquals(listOf("https://cdn.test/b/Index.M3U8", "https://cdn.test/a/MANIFEST.MPD#t=1"), urls())
    }

    @Test
    fun adVideosFromListedHostsAreIgnored() {
        sniffer.request("https://cdn.test/film.m3u8", listed = false)
        sniffer.request("https://ads.test/preroll.m3u8", listed = true)
        assertEquals(listOf("https://cdn.test/film.m3u8"), urls())
    }

    @Test
    fun everyManifestIsKeptNewestFirstAndFilesComeLast() {
        // As on Dailymotion: the master, then the video and audio renditions the page's player picked.
        sniffer.request("https://director.test/master.m3u8?sec=1", listed = false)
        sniffer.request("https://cdn.test/h264_hd/manifest.m3u8", listed = false)
        sniffer.request("https://cdn.test/thumb-preview.mp4", listed = false)
        sniffer.request("https://cdn.test/aac/manifest.m3u8", listed = false)
        assertEquals(listOf("https://cdn.test/aac/manifest.m3u8", "https://cdn.test/h264_hd/manifest.m3u8",
            "https://director.test/master.m3u8?sec=1", "https://cdn.test/thumb-preview.mp4"), urls())
    }

    @Test
    fun aLivePlaylistFetchedAgainMovesUpOnce() {
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        sniffer.request("https://cdn.test/live/720p.m3u8", listed = false)
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        assertEquals(listOf("https://cdn.test/master.m3u8", "https://cdn.test/live/720p.m3u8"), urls())
    }

    @Test
    fun theListStaysShort() {
        for (i in 1..40) sniffer.request("https://cdn.test/live/$i.m3u8", listed = false)
        // The 12 newest, then the page's first 3.
        assertEquals(15, urls().size)
        assertEquals("https://cdn.test/live/40.m3u8", urls().first())
        assertEquals(listOf("https://cdn.test/live/3.m3u8", "https://cdn.test/live/2.m3u8", "https://cdn.test/live/1.m3u8"), urls().takeLast(3))
    }

    @Test
    fun theFrameThatFetchedTheStreamIsRemembered() {
        sniffer.request("https://director.test/master.m3u8", listed = false,
            mapOf("referer" to "https://player.test/", "Origin" to "https://player.test", "Accept" to "*/*"))
        val s = sniffer.candidates().single()
        assertEquals("https://player.test/", s.referer)
        assertEquals("https://player.test", s.origin)
        assertTrue(s.manifest)
    }

    @Test
    fun withoutAManifestTheNewestVideoFileIsUsedButNeverSegments() {
        sniffer.request("https://cdn.test/seg-001.ts", listed = false)
        sniffer.request("https://cdn.test/init.m4s", listed = false)
        assertEquals(emptyList<String>(), urls())
        sniffer.request("https://cdn.test/a.webm?x=1", listed = false)
        sniffer.request("https://cdn.test/b.mp4", listed = false)
        assertEquals(listOf("https://cdn.test/b.mp4"), urls())
    }

    @Test
    fun aNewPageStartsOver() {
        sniffer.request("https://cdn.test/film.m3u8", listed = false)
        sniffer.clear()
        assertEquals(emptyList<String>(), urls())
    }

    @Test
    fun onlyWebAddressesCount() {
        sniffer.request("blob:https://site.test/1234", listed = false)
        sniffer.request("data:application/x-mpegurl;base64,AAAA.m3u8", listed = false)
        assertEquals(emptyList<String>(), urls())
    }
}
