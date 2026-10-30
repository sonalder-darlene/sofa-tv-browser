package app.sofabrowser.browser

/** Picks the page's main video for "Watch". Plain Kotlin, so the rules are unit-tested. */
object WatchTarget {

    /**
     * One frame's largest video, as the top frame reports it: its size in CSS pixels and a point where a tap reaches
     * the video or its player (in view pixels, -1 when every tried point is covered or off screen). [muted] and
     * [playing] as it is now, [loop] when it starts over by itself, [duration] in seconds (0 unknown, -1 live).
     */
    data class Candidate(val frame: String, val width: Double, val height: Double, val tapX: Double, val tapY: Double,
                         val muted: Boolean = false, val playing: Boolean = false, val loop: Boolean = false, val duration: Double = 0.0) {
        val area get() = width * height

        fun tappable(viewWidth: Int, viewHeight: Int) = tapX in 0.0..<viewWidth.toDouble() && tapY in 0.0..<viewHeight.toDouble()

        /**
         * Worth Watch starting on its own on any site: not a muted preview already playing, not a loop (background and
         * hero videos), not a clip under [MIN_ALONE_S] (often an ad). A duration not known yet passes.
         */
        val worthAlone get() = !(muted && playing) && !loop && !(duration > 0 && duration < MIN_ALONE_S)
    }

    /** The largest video with any size, or null when the page has none. */
    fun choose(candidates: List<Candidate>): Candidate? = candidates.filter { it.area > 0 }.maxByOrNull { it.area }

    /** Shortest video "Watch by itself" starts on every site, in seconds: as Sofa Player's minimum. */
    const val MIN_ALONE_S = 60.0

    /** "Watch by itself" (Prefs.autoWatch): off, on every site, or on the sites chosen from the menu. */
    enum class Auto { OFF, ALWAYS, SITES }

    /** Whether "Watch by itself" in [mode] runs on a page of [host], with [sites] the chosen ones. */
    fun autoWatches(mode: Auto, host: String?, sites: Set<String>): Boolean = when (mode) {
        Auto.OFF -> false
        Auto.ALWAYS -> host != null
        Auto.SITES -> host != null && host in sites
    }

    /**
     * What "Watch by itself" takes from [candidates] in [mode]: the video Watch would, but on every site only when it is
     * [Candidate.worthAlone] (a smaller one behind a muted hero video is no main video either).
     */
    fun chooseAlone(mode: Auto, candidates: List<Candidate>): Candidate? =
        choose(candidates)?.takeIf { mode != Auto.ALWAYS || it.worthAlone }
}
