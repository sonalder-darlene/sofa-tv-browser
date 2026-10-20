package app.sofabrowser.browser

/**
 * Which frames of the page have a video playing. Each frame's relay script reports for itself under a random id,
 * since WebView doesn't say which frame sent a message. Plain Kotlin, so the rules are unit-tested.
 */
class PlaybackFrames {
    private val playing = HashSet<String>()

    val anyPlaying: Boolean get() = playing.isNotEmpty()

    fun report(frame: String, playing: Boolean) {
        if (playing) this.playing.add(frame) else this.playing.remove(frame)
    }

    /** A new page: its frames start over, and the old ones can no longer report. */
    fun clear() = playing.clear()
}
