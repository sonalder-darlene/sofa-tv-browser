package app.sofabrowser.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFramesTest {

    private val frames = PlaybackFrames()

    @Test
    fun aPlayingFrameKeepsTheScreenOn() {
        assertFalse(frames.anyPlaying)
        frames.report("top", playing = true)
        assertTrue(frames.anyPlaying)
        frames.report("top", playing = false)
        assertFalse(frames.anyPlaying)
    }

    @Test
    fun theScreenStaysOnWhileAnyFramePlays() {
        frames.report("top", playing = true)
        frames.report("embed", playing = true)
        frames.report("top", playing = false)
        assertTrue(frames.anyPlaying)
        frames.report("embed", playing = false)
        assertFalse(frames.anyPlaying)
    }

    @Test
    fun aNewPageForgetsEveryFrame() {
        frames.report("embed", playing = true)
        frames.clear()
        assertFalse(frames.anyPlaying)
    }
}
