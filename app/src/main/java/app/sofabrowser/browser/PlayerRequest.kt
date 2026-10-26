package app.sofabrowser.browser

/**
 * What "Play in…" hands to another player: data in, a description of the intent out (the Android side only
 * builds it). Plain Kotlin, so the rules are unit-tested.
 */
object PlayerRequest {

    sealed interface Result

    /** Open [uri] as [mime]; the rest are extras players read when they know them. */
    data class Launch(val uri: String, val mime: String, val title: String?, val positionMs: Int?,
                      val headers: Map<String, String>, val subtitles: List<Subtitle> = emptyList()) : Result

    /** A subtitle file the page gives its video (a <track>): address, label, language, and whether the page shows it. */
    data class Subtitle(val url: String, val label: String, val language: String, val showing: Boolean = false)

    enum class Refusal : Result { NO_VIDEO, IN_PAGE, PROTECTED }

    /**
     * [source] is the video element's address, [positionSeconds] where it is now, [protected] whether it plays
     * through DRM. Only http(s) addresses can be opened elsewhere: a blob: or data: stream exists only inside the
     * page, so then the stream the page fetched ([sniffed], see [StreamSniffer]) is handed over, if there is one.
     * [headers] go along for players that read them; empty ones are dropped. [subtitles] are the page's own files,
     * the one in [language] first.
     */
    fun build(source: String?, title: String?, positionSeconds: Double, protected: Boolean,
              headers: Map<String, String> = emptyMap(), sniffed: String? = null,
              subtitles: List<Subtitle> = emptyList(), language: String = ""): Result {
        if (source.isNullOrBlank()) return Refusal.NO_VIDEO
        if (protected) return Refusal.PROTECTED
        val uri = if (isWeb(source)) source else sniffed?.takeIf(::isWeb) ?: return Refusal.IN_PAGE
        // Near the start, let the player begin (or resume) as it likes.
        val position = if (positionSeconds >= 1) (positionSeconds * 1000).toInt() else null
        val subs = subtitles.distinctBy { it.url }.sortedByDescending { language.isNotEmpty() && it.language.startsWith(language, ignoreCase = true) }
        return Launch(uri, mimeOf(uri), title?.trim()?.ifEmpty { null }, position, headers.filterValues { it.isNotBlank() }, subs)
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

    /**
     * The type of Sofa's own HLS copies (StreamCopyProvider), as Media3 spells it: Just Player asks the provider for
     * the type and hands it to Media3 as it is.
     */
    const val HLS = "application/x-mpegURL"

    /**
     * The intent types to offer [mime] under to [packageName] (null: Android's chooser), in order. Android matches
     * types case-sensitively, so HLS goes under each spelling players declare, and the generic video type every tested
     * player takes comes last, except for VLC and the chooser: VLC's StartActivity passes only intents of a video
     * type to its player with their extras (the position), and plays anything else from the start. The chooser still
     * lists players that take only HLS, under it.
     */
    fun types(mime: String, packageName: String?): List<String> {
        val own = if (mime == HLS) listOf(HLS, "application/vnd.apple.mpegurl", "application/x-mpegurl") else listOf(mime)
        val genericFirst = !mime.startsWith("video/") && (packageName == null || packageName == VLC)
        return (if (genericFirst) listOf("video/*") + own else own + "video/*").distinct()
    }

    /** Whether [packageName] reads the "position" extra (ms) as a Long: VLC does and drops anything else; others an Int. */
    fun positionIsLong(packageName: String?) = packageName == VLC

    /** VLC's package, the one player whose intent handling differs (see [types] and [positionIsLong]). */
    const val VLC = "org.videolan.vlc"
}
