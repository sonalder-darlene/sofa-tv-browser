package app.sofabrowser.player

import androidx.media3.common.MimeTypes
import app.sofabrowser.browser.PlayerRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSubtitlesTest {

    private val en = PlayerRequest.Subtitle("https://cdn.test/en.vtt", "English", "en", showing = true)
    private val fr = PlayerRequest.Subtitle("https://cdn.test/fr.vtt", "Français", "fr")

    private val plainMaster = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CLOSED-CAPTIONS=NONE
        https://cdn.test/360.m3u8
    """.trimIndent()

    private val masterWithSubtitles = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="English",LANGUAGE="en",URI="https://cdn.test/subs/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,SUBTITLES="subs"
        https://cdn.test/360.m3u8
    """.trimIndent()

    private val masterWithCaptions = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=CLOSED-CAPTIONS,GROUP-ID="cc",NAME="English",INSTREAM-ID="CC1"
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CLOSED-CAPTIONS="cc"
        https://cdn.test/360.m3u8
    """.trimIndent()

    @Test fun thePagesSubtitlesGoAlongWhenTheStreamHasNone() {
        assertEquals(listOf(en, fr), PageSubtitles.forPlayer(listOf(en, fr, en), plainMaster))
        assertEquals(listOf(en), PageSubtitles.forPlayer(listOf(en), null))
    }

    @Test fun theStreamsOwnSubtitlesComeFirst() {
        assertTrue(PageSubtitles.forPlayer(listOf(en, fr), masterWithSubtitles).isEmpty())
        assertTrue(PageSubtitles.forPlayer(listOf(en, fr), masterWithCaptions).isEmpty())
        assertFalse(PageSubtitles.streamHasOwn(plainMaster))
    }

    @Test fun theFormatFollowsTheFilesExtension() {
        assertEquals(MimeTypes.TEXT_VTT, PageSubtitles.mimeOf("https://cdn.test/en.vtt?token=1"))
        assertEquals(MimeTypes.TEXT_VTT, PageSubtitles.mimeOf("https://cdn.test/subtitles/en"))
        assertEquals(MimeTypes.APPLICATION_SUBRIP, PageSubtitles.mimeOf("https://cdn.test/en.SRT"))
        assertEquals(MimeTypes.APPLICATION_TTML, PageSubtitles.mimeOf("https://cdn.test/en.dfxp#t"))
        assertEquals(MimeTypes.TEXT_SSA, PageSubtitles.mimeOf("https://cdn.test/en.ass"))
    }
}
