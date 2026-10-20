package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSnifferTest {

    private val sniffer = StreamSniffer()

    @Test
    fun anHlsPlaylistTheePageFetchedIsTheStream() {
        sniffer.request("https://site.test/app.js", listed = false)
        sniffer.request("https://cdn.test/vod/master.m3u8?token=abc", listed = false)
        assertEquals("https://cdn.test/vod/master.m3u8?token=abc", sniffer.best())
    }

    @Test
    fun dashManifestsAndOddSpellingsCount() {
        sniffer.request("https://cdn.test/a/MANIFEST.MPD#t=1", listed = false)
        assertEquals("https://cdn.test/a/MANIFEST.MPD#t=1", sniffer.best())
        sniffer.request("https://cdn.test/b/Index.M3U8", listed = false)
        assertEquals("https://cdn.test/b/Index.M3U8", sniffer.best())
    }

    @Test
    fun adVideosFromListedHostsAreIgnored() {
        sniffer.request("https://cdn.test/film.m3u8", listed = false)
        sniffer.request("https://ads.test/preroll.m3u8", listed = true)
        assertEquals("https://cdn.test/film.m3u8", sniffer.best())
    }

    @Test
    fun aManifestBeatsLaterSingleFilesAndTheNewestManifestWins() {
        sniffer.request("https://cdn.test/old.m3u8", listed = false)
        sniffer.request("https://cdn.test/film.m3u8", listed = false)
        sniffer.request("https://cdn.test/thumb-preview.mp4", listed = false)
        assertEquals("https://cdn.test/film.m3u8", sniffer.best())
    }

    @Test
    fun withoutAManifestTheNewestVideoFileIsUsedButNeverSegments() {
        sniffer.request("https://cdn.test/seg-001.ts", listed = false)
        sniffer.request("https://cdn.test/init.m4s", listed = false)
        assertNull(sniffer.best())
        sniffer.request("https://cdn.test/a.webm?x=1", listed = false)
        sniffer.request("https://cdn.test/b.mp4", listed = false)
        assertEquals("https://cdn.test/b.mp4", sniffer.best())
    }

    @Test
    fun aNewPageStartsOver() {
        sniffer.request("https://cdn.test/film.m3u8", listed = false)
        sniffer.clear()
        assertNull(sniffer.best())
    }

    @Test
    fun onlyWebAddressesCount() {
        sniffer.request("blob:https://site.test/1234", listed = false)
        sniffer.request("data:application/x-mpegurl;base64,AAAA.m3u8", listed = false)
        assertNull(sniffer.best())
    }
}
