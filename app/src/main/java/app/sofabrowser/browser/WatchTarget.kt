package app.sofabrowser.browser

/** Picks the page's main video for "Watch". Plain Kotlin, so the rules are unit-tested. */
object WatchTarget {

    /**
     * One frame's largest video, as the top frame reports it: its size in CSS pixels and a point where a tap reaches
     * the video or its player (in view pixels, -1 when every tried point is covered or off screen).
     */
    data class Candidate(val frame: String, val width: Double, val height: Double, val tapX: Double, val tapY: Double) {
        val area get() = width * height

        fun tappable(viewWidth: Int, viewHeight: Int) = tapX in 0.0..<viewWidth.toDouble() && tapY in 0.0..<viewHeight.toDouble()
    }

    /** The largest video with any size, or null when the page has none. */
    fun choose(candidates: List<Candidate>): Candidate? = candidates.filter { it.area > 0 }.maxByOrNull { it.area }
}
