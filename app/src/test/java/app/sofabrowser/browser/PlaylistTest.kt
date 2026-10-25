package app.sofabrowser.browser

import app.sofabrowser.browser.Playlist.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistTest {

    // Shortened from Dailymotion's real master (2026-10-25): demuxed audio, subtitles, H.264 and AV1 variants.
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="0_aac_q2",LANGUAGE="zho",NAME="中文",AUTOSELECT=YES,DEFAULT=YES,URI="https://vod3.cf.dmcdn.net/sec2(x)/video/fmp4/1/aac_q2_0/3/manifest.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=6221600,CODECS="mp4a.40.2,avc1.640028",RESOLUTION=1920x816,NAME="1080",SUBTITLES="subtitles",AUDIO="0_aac_q2"
        https://vod3.cf.dmcdn.net/sec2(x)/video/fmp4/1/h264_fhd/3/manifest.m3u8#cell=cf3
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subtitles",LANGUAGE="km",NAME="km",URI="https://www.dailymotion.com/cdn/subtitle/video/x/km.m3u8?sec=y"
    """.trimIndent()

    @Test
    fun aMasterIsRecognised() {
        assertEquals(Kind.MASTER, Playlist.kind(master))
        assertEquals(Kind.MASTER, Playlist.kind("﻿" + master))
    }

    @Test
    fun renditionsAreMediaPlaylists() {
        val audio = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:3.0,\n0.m4s\n#EXTINF:3.0,\n1.m4s\n#EXT-X-ENDLIST"
        assertEquals(Kind.MEDIA, Playlist.kind(audio))
    }

    @Test
    fun subtitlePlaylistsAreNeverWorthOpening() {
        assertEquals(Kind.SUBTITLES, Playlist.kind("#EXTM3U\n#EXTINF:600,\nhttps://cdn.test/subs/fr.vtt?sec=1\n#EXT-X-ENDLIST"))
    }

    @Test
    fun dashAndOtherAnswers() {
        assertEquals(Kind.MASTER, Playlist.kind("<?xml version=\"1.0\"?>\n<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">"))
        assertEquals(Kind.NOT_A_PLAYLIST, Playlist.kind("<html><body>403 Forbidden</body></html>"))
        assertEquals(Kind.NOT_A_PLAYLIST, Playlist.kind("{\"error\":1108}"))
    }

    @Test
    fun aCopyPointsBackAtTheSite() {
        val relative = "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"audio/en.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1\nvideo/720.m3u8?t=1\n#EXT-X-STREAM-INF:BANDWIDTH=2\n/abs/1080.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3\nhttps://other.test/4k.m3u8\n"
        assertEquals("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",URI=\"https://cdn.test/vod/audio/en.m3u8\"\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=1\nhttps://cdn.test/vod/video/720.m3u8?t=1\n#EXT-X-STREAM-INF:BANDWIDTH=2\nhttps://cdn.test/abs/1080.m3u8\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=3\nhttps://other.test/4k.m3u8\n",
            Playlist.absolute(relative, "https://cdn.test/vod/master.m3u8?sec=abc"))
        // Nothing but web addresses: the copy goes to another app.
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\n\n#EXT-X-MAP:URI=\"\"",
            Playlist.absolute("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nfile:///sdcard/a.m3u8\n#EXT-X-MAP:URI=\"content://x/y\"", "https://cdn.test/m.m3u8"))
        // Already absolute: unchanged.
        assertEquals(master, Playlist.absolute(master, "https://cdndirector.dailymotion.com/cdn/manifest/video/x.m3u8"))
    }

    // The copy for another player drops variants the TV can't decode in hardware (here: AV1), while others remain.
    private val withAv1 = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="中文",URI="https://cdn.test/aac/manifest.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="fr",URI="https://cdn.test/subs/fr.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=6221600,CODECS="mp4a.40.2,avc1.640028",RESOLUTION=1920x816,AUDIO="a",SUBTITLES="subs"
        https://cdn.test/h264_fhd/manifest.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4000000,CODECS="mp4a.40.2,av01.0.12H.08",RESOLUTION=3840x1632,AUDIO="a",SUBTITLES="subs"
        https://cdn.test/av1_uhd/manifest.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,CODECS="av01.0.12H.08",URI="https://cdn.test/av1_uhd/iframes.m3u8"
    """.trimIndent()

    private val noAv1: (String) -> Boolean = { !it.startsWith("av01") }

    @Test
    fun undecodableVariantsAreLeftOut() {
        assertEquals("""
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="中文",URI="https://cdn.test/aac/manifest.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="fr",URI="https://cdn.test/subs/fr.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=6221600,CODECS="mp4a.40.2,avc1.640028",RESOLUTION=1920x816,AUDIO="a",SUBTITLES="subs"
            https://cdn.test/h264_fhd/manifest.m3u8
        """.trimIndent(), Playlist.decodableOnly(withAv1, noAv1))
    }

    @Test
    fun allVariantsStayWhenNoneWouldRemain() {
        assertEquals(withAv1, Playlist.decodableOnly(withAv1) { false })
    }

    @Test
    fun variantsWithoutCodecsStay() {
        val plain = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2,CODECS=\"av01.0.08M.08\"\nb.m3u8\n"
        assertEquals("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8\n", Playlist.decodableOnly(plain, noAv1))
    }

    @Test
    fun everythingDecodableIsUnchanged() {
        assertEquals(master, Playlist.decodableOnly(master, noAv1))
    }
}
