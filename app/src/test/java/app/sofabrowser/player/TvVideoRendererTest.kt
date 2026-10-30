package app.sofabrowser.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvVideoRendererTest {

    private fun video(width: Int, height: Int, fps: Float, mime: String = MimeTypes.VIDEO_H264) =
        Format.Builder().setSampleMimeType(mime).setWidth(width).setHeight(height).setFrameRate(fps).build()

    @Test
    fun admitsOnlyAHardwareDecoderThatAndroidSaysFits() {
        assertTrue(TvVideoRenderer.admit(hardware = true, functionally = true, platformFits = true))
        assertFalse(TvVideoRenderer.admit(hardware = false, functionally = true, platformFits = true))
        assertFalse(TvVideoRenderer.admit(hardware = true, functionally = false, platformFits = true))
        assertFalse(TvVideoRenderer.admit(hardware = true, functionally = true, platformFits = false))
    }

    @Test
    fun stuttersWhenMoreThanATenthIsDropped() {
        assertFalse(TvVideoRenderer.stutters(480, 0))
        assertFalse(TvVideoRenderer.stutters(450, 50))
        assertTrue(TvVideoRenderer.stutters(440, 60))
        assertTrue(TvVideoRenderer.stutters(100, 380))
        assertFalse(TvVideoRenderer.stutters(0, 0))
    }

    @Test
    fun theDecodersSizeMatchesThePlaylists() {
        assertTrue(TvVideoRenderer.same(video(1920, 1080, 60f), video(1920, 1088, 60f)))
        assertTrue(TvVideoRenderer.same(video(1280, 536, 60f), video(1280, 544, Format.NO_VALUE.toFloat())))
        assertFalse(TvVideoRenderer.same(video(1920, 1080, 60f), video(1920, 1080, 30f)))
        assertFalse(TvVideoRenderer.same(video(3840, 2160, 60f), video(2560, 1440, 60f)))
        assertFalse(TvVideoRenderer.same(video(3840, 2160, 60f), video(3840, 2160, 60f, MimeTypes.VIDEO_AV1)))
    }
}
