package app.sofabrowser.player

/**
 * What was picked by hand in Sofa Player's menu, kept for every later video on any site: the quality, the subtitles
 * ([OFF] or a language) and the sound's language. Null where nothing was picked: Auto, the TV's caption settings,
 * the TV's language. Plain Kotlin, so it is unit-tested; Prefs keeps it.
 */
data class PlayerChoices(val quality: Variants.Quality? = null, val subtitles: String? = null, val audio: String? = null) {

    /** A subtitle track as the stream describes it. */
    data class Track(val language: String?, val forced: Boolean = false, val commentary: Boolean = false)

    companion object {
        /** [subtitles] when they were turned off. */
        const val OFF = "off"

        /**
         * Which of [tracks] is in [language]: the same tag first ("pt-BR"), else the same language ("pt"). Forced
         * tracks (only the foreign lines) and commentaries are never the ones meant.
         */
        fun match(language: String, tracks: List<Track>): Int? {
            val wanted = tag(language) ?: return null
            val usable = tracks.withIndex().filter { !it.value.forced && !it.value.commentary }
            return (usable.firstOrNull { tag(it.value.language) == wanted }
                ?: usable.firstOrNull { tag(it.value.language)?.substringBefore('-') == wanted.substringBefore('-') })?.index
        }

        /** A language as Sofa keeps it ("pt-br"), or null when the stream doesn't say ("und"). */
        fun tag(language: String?): String? = language?.trim()?.lowercase()?.replace('_', '-')?.takeUnless { it.isEmpty() || it == "und" }

        /** [q] as one line for Prefs ("1088|2560|HEVC|HDR10"), "" for Auto. */
        fun encode(q: Variants.Quality?): String = q?.let { listOf(it.height, it.width, it.codec.orEmpty(), it.range.orEmpty()).joinToString("|") }.orEmpty()

        fun decode(line: String?): Variants.Quality? {
            val parts = line?.split('|')?.takeIf { it.size == 4 } ?: return null
            val height = parts[0].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return Variants.Quality(height, parts[2].ifEmpty { null }, parts[3].ifEmpty { null }, parts[1].toIntOrNull() ?: 0)
        }
    }
}
