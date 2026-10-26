package app.sofabrowser.player

import androidx.media3.common.MimeTypes
import app.sofabrowser.browser.PlayerRequest

/**
 * The page's own subtitle files (its `<track>` elements) as a fallback for Sofa Player: the stream's subtitles come
 * first, so the page's go along only when an HLS master lists none of its own (DASH and files don't say up front).
 */
object PageSubtitles {

    fun forPlayer(subtitles: List<PlayerRequest.Subtitle>, master: String?): List<PlayerRequest.Subtitle> =
        if (streamHasOwn(master)) emptyList() else subtitles.distinctBy { it.url }

    /** Whether an HLS [master] lists subtitles or closed captions. */
    fun streamHasOwn(master: String?): Boolean = master.orEmpty().lineSequence().any { line ->
        line.startsWith("#EXT-X-MEDIA:") && (line.contains("TYPE=SUBTITLES") || line.contains("TYPE=CLOSED-CAPTIONS"))
    }

    /** The format, by the file's extension: `<track>` takes WebVTT, so that is the rule; sites sometimes give others. */
    fun mimeOf(url: String): String = when (url.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase()) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ttml", "dfxp" -> MimeTypes.APPLICATION_TTML
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.TEXT_VTT
    }
}
