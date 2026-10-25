package app.sofabrowser.browser

import app.sofabrowser.browser.PlayerRequest.Launch
import app.sofabrowser.browser.PlayerRequest.Refusal
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerRequestTest {

    @Test
    fun aDirectFileIsHandedOverWithItsTitleAndPosition() {
        assertEquals(
            Launch("https://cdn.test/films/a.mp4", "video/mp4", "A film", 754_200, emptyMap()),
            PlayerRequest.build("https://cdn.test/films/a.mp4", "A film", positionSeconds = 754.2, protected = false),
        )
    }

    @Test
    fun streamsAssembledInThePageOrProtectedCantBeHandedOver() {
        assertEquals(Refusal.IN_PAGE, PlayerRequest.build("blob:https://site.test/5f1e", "x", 0.0, protected = false))
        assertEquals(Refusal.IN_PAGE, PlayerRequest.build("data:video/mp4;base64,AAAA", "x", 0.0, protected = false))
        assertEquals(Refusal.IN_PAGE, PlayerRequest.build("file:///sdcard/a.mp4", "x", 0.0, protected = false))
        assertEquals(Refusal.PROTECTED, PlayerRequest.build("https://cdn.test/a.mpd", "x", 0.0, protected = true))
        assertEquals(Refusal.NO_VIDEO, PlayerRequest.build(null, "x", 0.0, protected = false))
        assertEquals(Refusal.NO_VIDEO, PlayerRequest.build(" ", "x", 0.0, protected = false))
    }

    private fun mimeOf(url: String) = (PlayerRequest.build(url, null, 0.0, protected = false) as Launch).mime

    @Test
    fun eachStreamGetsATypePlayersAccept() {
        assertEquals("video/mp4", mimeOf("https://cdn.test/a.MP4?token=1"))
        assertEquals("video/webm", mimeOf("https://cdn.test/a.webm"))
        assertEquals("video/x-matroska", mimeOf("https://cdn.test/a.mkv#t=10"))
        // Manifests and unknown addresses: mpv-android only accepts them as video/*, and VLC and MX Player read
        // the content anyway.
        assertEquals("video/*", mimeOf("https://cdn.test/live/master.m3u8"))
        assertEquals("video/*", mimeOf("https://cdn.test/vod/manifest.mpd?x=.mp4"))
        assertEquals("video/*", mimeOf("https://cdn.test/play?id=42"))
    }

    @Test
    fun aStreamAssembledInThePageIsHandedOverThroughTheManifestThePageFetched() {
        val l = PlayerRequest.build("blob:https://site.test/5f1e", "Film", 61.0, protected = false,
            sniffed = "https://cdn.test/vod/master.m3u8?sig=1") as Launch
        assertEquals("https://cdn.test/vod/master.m3u8?sig=1", l.uri)
        assertEquals("video/*", l.mime)
        assertEquals(61_000, l.positionMs)
        // A real address on the element wins over anything sniffed, and DRM is never handed over.
        assertEquals("https://cdn.test/a.mp4",
            (PlayerRequest.build("https://cdn.test/a.mp4", null, 0.0, false, sniffed = "https://cdn.test/x.m3u8") as Launch).uri)
        assertEquals(Refusal.PROTECTED, PlayerRequest.build("blob:https://s.test/1", null, 0.0, true, sniffed = "https://cdn.test/x.mpd"))
    }

    @Test
    fun onlyHeadersWithAValueAreSent() {
        val l = PlayerRequest.build("https://cdn.test/a.mp4", null, 0.0, protected = false,
            headers = mapOf("User-Agent" to "Mozilla/5.0", "Cookie" to "", "Referer" to "https://site.test/watch")) as Launch
        assertEquals(mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://site.test/watch"), l.headers)
    }

    @Test
    fun theStartIsLeftToThePlayerNearTheBeginningAndBlankTitlesAreDropped() {
        val l = PlayerRequest.build("https://cdn.test/a.mp4", "  ", positionSeconds = 0.4, protected = false) as Launch
        assertEquals(null, l.positionMs)
        assertEquals(null, l.title)
    }

    @Test
    fun thePagesSubtitlesGoAlongInTheUsersLanguageFirst() {
        val en = PlayerRequest.Subtitle("https://cdn.test/en.vtt", "English", "en")
        val fr = PlayerRequest.Subtitle("https://cdn.test/fr.vtt", "Français", "fr")
        val l = PlayerRequest.build("https://cdn.test/a.mp4", null, 0.0, protected = false,
            subtitles = listOf(en, fr, en), language = "fr") as Launch
        assertEquals(listOf(fr, en), l.subtitles)
    }

    @Test
    fun onlyVlcReadsThePositionAsALong() {
        // VLC: "Key position expected Long but value was a java.lang.Integer"; mpv-android, MX Player and Just Player read an Int.
        assertEquals(true, PlayerRequest.positionIsLong("org.videolan.vlc"))
        assertEquals(false, PlayerRequest.positionIsLong("com.brouken.player"))
        assertEquals(false, PlayerRequest.positionIsLong("is.xyz.mpv"))
        assertEquals(false, PlayerRequest.positionIsLong(null))
    }

    @Test
    fun hlsIsOfferedUnderEachSpellingBeforeTheGenericType() {
        assertEquals(listOf("application/x-mpegURL", "application/vnd.apple.mpegurl", "application/x-mpegurl", "video/*"),
            PlayerRequest.types(PlayerRequest.HLS, "com.example.player"))
        assertEquals(listOf("video/mp4", "video/*"), PlayerRequest.types("video/mp4", "com.example.player"))
        assertEquals(listOf("video/*"), PlayerRequest.types("video/*", "com.example.player"))
    }

    @Test
    fun vlcAndTheChooserGetTheGenericVideoTypeFirst() {
        // VLC's StartActivity hands only video/* intents, extras and all, to its player; others play from 0:00.
        assertEquals(listOf("video/*", "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/x-mpegurl"),
            PlayerRequest.types(PlayerRequest.HLS, PlayerRequest.VLC))
        assertEquals(listOf("video/*", "application/x-mpegURL", "application/vnd.apple.mpegurl", "application/x-mpegurl"),
            PlayerRequest.types(PlayerRequest.HLS, null))
        assertEquals(listOf("video/mp4", "video/*"), PlayerRequest.types("video/mp4", PlayerRequest.VLC))
    }
}
