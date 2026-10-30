package app.sofabrowser.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import kotlin.math.abs
import kotlin.math.floor

/**
 * Media3's video renderer, with Auto allowed what the TV's own decoder says it plays. Media3 also asks for the frame
 * rates the maker measured (`getAchievableFrameRatesFor`), which many TVs list low, so it calls their 4K and 1440p
 * at 60 fps beyond them, and Auto falls to a smaller picture, often at 30 fps, though the page's player (and Media3
 * before 1.5) plays it. Such a picture is [admitted] when the TV's first decoder for it is hardware, decodes its codec,
 * profile and level, and Android says that size at that rate fits; it is decoded by hardware, and Sofa Player watches
 * it for stutter ([stutters]).
 *
 * Test builds: [undersellAt], a height from which pictures count as beyond the decoder as on such a TV ([strict]: with
 * Media3's own rule, as before).
 */
@OptIn(UnstableApi::class)
class TvVideoRenderer(builder: Builder, private val context: Context, private val admitted: MutableList<Format>,
                      private val undersellAt: Int? = null, private val strict: Boolean = false) : MediaCodecVideoRenderer(builder) {

    override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int {
        var result = super.supportsFormat(mediaCodecSelector, format)
        if (!MimeTypes.isVideo(format.sampleMimeType) || format.width <= 0 || format.height <= 0) return result
        if (undersellAt != null && format.height >= undersellAt && RendererCapabilities.getFormatSupport(result) == C.FORMAT_HANDLED) {
            result = withSupport(result, C.FORMAT_EXCEEDS_CAPABILITIES)
        }
        if (strict || RendererCapabilities.getFormatSupport(result) != C.FORMAT_EXCEEDS_CAPABILITIES) return result
        val decoder = mediaCodecSelector.getDecoderInfos(format.sampleMimeType!!, false, false).firstOrNull() ?: return result
        if (!admit(decoder.hardwareAccelerated, decoder.isFormatFunctionallySupported(context, format), platformFits(decoder, format))) return result
        if (admitted.none { same(it, format) }) admitted += format
        return withSupport(result, C.FORMAT_HANDLED)
    }

    /** An admitted picture goes to the hardware decoder, even when a software one says it keeps up. */
    override fun getDecoderInfos(mediaCodecSelector: MediaCodecSelector, format: Format, requiresSecureDecoder: Boolean): List<MediaCodecInfo> {
        val infos = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        return if (admitted.any { same(it, format) }) infos.sortedByDescending { it.hardwareAccelerated } else infos
    }

    /** What Android itself says of the decoder: the size at the rate (an unknown rate: the size alone). */
    private fun platformFits(decoder: MediaCodecInfo, format: Format): Boolean {
        val video = decoder.capabilities?.videoCapabilities ?: return false
        return runCatching {
            if (format.frameRate > 0) video.areSizeAndRateSupported(format.width, format.height, floor(format.frameRate.toDouble()))
            else video.isSizeSupported(format.width, format.height)
        }.getOrDefault(false)
    }

    companion object {
        /** Media3 says beyond, and the first decoder is hardware, plays the codec, profile and level, and fits the size and rate. */
        fun admit(hardware: Boolean, functionally: Boolean, platformFits: Boolean) = hardware && functionally && platformFits

        /**
         * Whether an admitted picture stutters, from its frames [shown] and [dropped] over a few seconds of playing:
         * more than a tenth dropped (a TV that keeps up drops none, but for a burst at a switch).
         */
        fun stutters(shown: Int, dropped: Int) = dropped * 10 > shown + dropped

        /** The same picture: the decoder may report a slightly different size than the playlist (1088 for 1080). */
        fun same(a: Format, b: Format) = a.sampleMimeType == b.sampleMimeType && abs(a.width - b.width) <= 16 &&
            abs(a.height - b.height) <= 16 && (a.frameRate <= 0 || b.frameRate <= 0 || abs(a.frameRate - b.frameRate) < 1)

        private fun withSupport(capabilities: Int, support: Int) =
            (capabilities and RendererCapabilities.FORMAT_SUPPORT_MASK.inv()) or support
    }
}
