package app.sofabrowser.browser

/**
 * What "Play in…" hands to another player: data in, a description of the intent out (the Android side only
 * builds it). Plain Kotlin, so the rules are unit-tested.
 */
object PlayerRequest {

    sealed interface Result

    /** Open [uri] as [mime]; the rest are extras players read when they know them. */
    data class Launch(val uri: String, val mime: String, val title: String?, val positionMs: Int?,
                      val headers: Map<String, String>) : Result

    enum class Refusal : Result { NO_VIDEO, IN_PAGE, PROTECTED }

    /**
     * [source] is the video element's address, [positionSeconds] where it is now, [protected] whether it plays
     * through DRM. Only http(s) addresses can be opened elsewhere: a blob: or data: stream exists only inside the
     * page, so then the stream the page fetched ([sniffed], see [StreamSniffer]) is handed over, if there is one.
     * [headers] go along for players that read them; empty ones are dropped.
     */
    fun build(source: String?, title: String?, positionSeconds: Double, protected: Boolean,
              headers: Map<String, String> = emptyMap(), sniffed: String? = null): Result {
        if (source.isNullOrBlank()) return Refusal.NO_VIDEO
        if (protected) return Refusal.PROTECTED
        val uri = if (isWeb(source)) source else sniffed?.takeIf(::isWeb) ?: return Refusal.IN_PAGE
        // Near the start, let the player begin (or resume) as it likes.
        val position = if (positionSeconds >= 1) (positionSeconds * 1000).toInt() else null
        return Launch(uri, mimeOf(uri), title?.trim()?.ifEmpty { null }, position, headers.filterValues { it.isNotBlank() })
    }

    private fun isWeb(url: String) = url.startsWith("https://") || url.startsWith("http://")

    // A container type when the extension says it; otherwise the generic video type, which every tested player
    // accepts (mpv-android takes manifests only that way or by extension, and VLC and MX Player read the content).
    private fun mimeOf(url: String): String = when (extensionOf(url)) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        else -> "video/*"
    }
}
