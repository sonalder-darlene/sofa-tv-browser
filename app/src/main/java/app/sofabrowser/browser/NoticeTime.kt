package app.sofabrowser.browser

/** How long a notice over the video stays: long enough to read from the sofa, longer for more words. */
object NoticeTime {
    const val BASE_MS = 5000L
    private const val PER_TEN_WORDS_MS = 1000L
    const val MAX_MS = 12_000L

    /** 5 s, plus 1 s for every 10 words, at most [MAX_MS]. */
    fun ms(text: String): Long {
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        return (BASE_MS + words / 10 * PER_TEN_WORDS_MS).coerceAtMost(MAX_MS)
    }
}
