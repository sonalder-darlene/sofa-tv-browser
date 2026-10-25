package app.sofabrowser.browser

import java.net.URI

/**
 * What a fetched stream manifest is, so the one a player should open is picked: an HLS master playlist (the
 * variants, audio and subtitles together) over the media playlists the page's player fetches after it. Plain
 * Kotlin, so the rules are unit-tested.
 */
object Playlist {

    enum class Kind {
        /** HLS master or DASH manifest: everything a player needs. */
        MASTER,
        /** One HLS rendition: plays, but may be video without sound or one quality only. */
        MEDIA,
        /** An HLS rendition of subtitles only, never worth opening. */
        SUBTITLES,
        NOT_A_PLAYLIST,
    }

    fun kind(body: String): Kind {
        val text = body.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (text.startsWith("<?xml") || text.startsWith("<MPD")) return if ("<MPD" in text) Kind.MASTER else Kind.NOT_A_PLAYLIST
        if (!text.startsWith("#EXTM3U")) return Kind.NOT_A_PLAYLIST
        if ("#EXT-X-STREAM-INF" in text) return Kind.MASTER
        val segments = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (segments.isNotEmpty() && segments.all { extensionOf(it) in SUBTITLE_EXTENSIONS }) return Kind.SUBTITLES
        return Kind.MEDIA
    }

    /**
     * An HLS playlist with every address made absolute against [base], so a copy served from elsewhere still points
     * at the site's servers: the address lines and the URI="…" attributes (renditions, keys, init segments). An
     * address that isn't a web one is emptied: a copy goes to another app, which may read local files.
     */
    fun absolute(body: String, base: String): String {
        val root = runCatching { URI(base) }.getOrNull() ?: return body
        fun resolve(ref: String) = runCatching { root.resolve(ref.trim()).toString() }.getOrNull()
            ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()
        return body.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                !line.startsWith("#") -> resolve(line)
                else -> URI_ATTRIBUTE.replace(line) { m -> "URI=\"" + resolve(m.groupValues[1]) + "\"" }
            }
        }
    }

    /**
     * An HLS master without the variants whose CODECS list one that [decodable] refuses (each codec as written, e.g.
     * "av01.0.12H.08"), so another player can't pick a picture the TV decodes too slowly; with none left, it is
     * returned whole. Variants that name no codecs stay; renditions (sound, subtitles) are untouched.
     */
    fun decodableOnly(body: String, decodable: (String) -> Boolean): String {
        fun ok(tag: String) = CODECS.find(tag)?.groupValues?.get(1)?.split(',')?.all { decodable(it.trim()) } ?: true
        val lines = body.lines()
        val out = ArrayList<String>(lines.size)
        var kept = 0
        var dropped = 0
        var skipUri = false
        for (line in lines) {
            when {
                skipUri -> if (line.isNotBlank() && !line.startsWith("#")) skipUri = false
                line.startsWith("#EXT-X-STREAM-INF") -> if (ok(line)) { kept++; out += line } else { dropped++; skipUri = true }
                line.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> if (ok(line)) out += line
                else -> out += line
            }
        }
        return if (kept == 0 || dropped == 0) body else out.joinToString("\n")
    }

    private val CODECS = Regex("""CODECS="([^"]*)"""")
    private val URI_ATTRIBUTE = Regex("""URI="([^"]*)"""")
    private val SUBTITLE_EXTENSIONS = setOf("vtt", "webvtt", "srt", "ttml", "dfxp")
}
