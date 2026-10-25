package app.sofabrowser.browser

import app.sofabrowser.browser.Playlist.Kind
import app.sofabrowser.browser.StreamSniffer.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResolverTest {

    private fun s(url: String) = Stream(url, manifest = true, referer = null, origin = null)

    @Test
    fun theMasterWinsOverNewerRenditions() {
        // Newest first, as on Dailymotion: audio rendition, video rendition, then the master.
        val read = listOf(s("aac.m3u8") to Kind.MEDIA, s("h264.m3u8") to Kind.MEDIA, s("master.m3u8") to Kind.MASTER)
        assertEquals("master.m3u8", StreamResolver.choose(read)?.url)
    }

    @Test
    fun withoutAMasterTheNewestRenditionThatIsNotSubtitles() {
        val read = listOf(s("fr.m3u8") to Kind.SUBTITLES, s("720.m3u8") to Kind.MEDIA, s("480.m3u8") to Kind.MEDIA)
        assertEquals("720.m3u8", StreamResolver.choose(read)?.url)
        assertNull(StreamResolver.choose(listOf(s("fr.m3u8") to Kind.SUBTITLES, s("x") to Kind.NOT_A_PLAYLIST)))
    }

    @Test
    fun theFrameIsTheRefererOrElseTheOrigin() {
        assertEquals("https://player.test/watch", StreamResolver.frameOf(Stream("u", true, "https://player.test/watch", "https://player.test")))
        assertEquals("https://player.test/", StreamResolver.frameOf(Stream("u", true, null, "https://player.test")))
        assertNull(StreamResolver.frameOf(Stream("u", true, null, null)))
    }

    // hls.js and the like fetch a new variant playlist at each quality switch: the master, fetched first, must stay
    // within reach. Bodies come from the page (fromPage), so nothing here touches the network.

    private val masterBody = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.test/360.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nhttps://cdn.test/1080.m3u8\n"
    private val variantBody = "#EXTM3U\n#EXTINF:4,\nseg.ts\n#EXT-X-ENDLIST"

    private fun pageOf(sniffer: StreamSniffer): Map<String, Pair<String, String>> =
        sniffer.candidates().filter { it.manifest }.associate { it.url to (it.url to if ("master" in it.url) masterBody else variantBody) }

    private fun resolved(sniffer: StreamSniffer) =
        StreamResolver("ua", { null }).resolve(sniffer.candidates(), pageOf(sniffer), checkOutside = false)?.url

    private fun read(sniffer: StreamSniffer) = StreamResolver.manifestsToRead(sniffer.candidates()).map { it.url }

    @Test
    fun theMasterSurvivesManyVariantFetches() {
        val sniffer = StreamSniffer()
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        for (i in 1..30) sniffer.request("https://cdn.test/level$i.m3u8", listed = false)
        assertEquals("https://cdn.test/master.m3u8", resolved(sniffer))
    }

    @Test
    fun aNewerMasterWins() {
        val sniffer = StreamSniffer()
        sniffer.request("https://cdn.test/a/master.m3u8", listed = false)
        for (i in 1..20) sniffer.request("https://cdn.test/a/level$i.m3u8", listed = false)
        sniffer.request("https://cdn.test/b/master.m3u8", listed = false)
        for (i in 1..4) sniffer.request("https://cdn.test/b/level$i.m3u8", listed = false)
        assertEquals("https://cdn.test/b/master.m3u8", resolved(sniffer))
    }

    @Test
    fun aLivePlaylistFetchedAgainAndAgainDoesNotPushTheMasterOut() {
        val sniffer = StreamSniffer()
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        for (round in 1..50) for (level in 1..3) sniffer.request("https://cdn.test/live/$level.m3u8?r=$round", listed = false)
        assertEquals("https://cdn.test/master.m3u8", resolved(sniffer))
    }

    @Test
    fun theReadsStayFew() {
        val sniffer = StreamSniffer()
        for (i in 1..100) sniffer.request("https://cdn.test/level$i.m3u8", listed = false)
        val urls = read(sniffer)
        assertEquals(15, urls.size)
        assertEquals("https://cdn.test/level100.m3u8", urls.first())
        assertEquals(listOf("https://cdn.test/level3.m3u8", "https://cdn.test/level2.m3u8", "https://cdn.test/level1.m3u8"), urls.takeLast(3))
    }

    @Test
    fun theMasterIsReadWhenNothingWasPushedOutYet() {
        // hls.js with ten levels: the master and ten level playlists, all still among the twelve kept.
        val sniffer = StreamSniffer()
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        for (i in 1..10) sniffer.request("https://cdn.test/level$i.m3u8", listed = false)
        assertEquals(true, "https://cdn.test/master.m3u8" in read(sniffer))
        assertEquals("https://cdn.test/master.m3u8", resolved(sniffer))
    }
    @Test
    fun theMasterStaysWithinReachWhenFetchedAgain() {
        // The master fetched again (so no longer the oldest), then more level playlists: it is still read.
        val sniffer = StreamSniffer()
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        for (i in 1..6) sniffer.request("https://cdn.test/level$i.m3u8", listed = false)
        sniffer.request("https://cdn.test/master.m3u8", listed = false)
        for (i in 7..12) sniffer.request("https://cdn.test/level$i.m3u8", listed = false)
        assertEquals(true, "https://cdn.test/master.m3u8" in read(sniffer))
        assertEquals("https://cdn.test/master.m3u8", resolved(sniffer))
    }

    @Test
    fun aNewerMasterWinsAfterManyOfItsOwnLevels() {
        // A new video on the same page, whose player then switched levels eight times.
        val sniffer = StreamSniffer()
        sniffer.request("https://cdn.test/a/master.m3u8", listed = false)
        for (i in 1..3) sniffer.request("https://cdn.test/a/level$i.m3u8", listed = false)
        sniffer.request("https://cdn.test/b/master.m3u8", listed = false)
        for (i in 1..8) sniffer.request("https://cdn.test/b/level$i.m3u8", listed = false)
        assertEquals("https://cdn.test/b/master.m3u8", resolved(sniffer))
    }
}
