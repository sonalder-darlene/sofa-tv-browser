package app.sofabrowser.browser

import app.sofabrowser.browser.WatchTarget.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchTargetTest {

    private fun video(fid: String, w: Double, h: Double, tapX: Double = 10.0, tapY: Double = 10.0) =
        Candidate(fid, w, h, tapX, tapY)

    @Test
    fun theLargestVideoOnThePageIsTheMainOne() {
        val ad = video("ad", 300.0, 250.0)
        val film = video("film", 960.0, 540.0)
        assertEquals(film, WatchTarget.choose(listOf(ad, film)))
    }

    @Test
    fun noVideoMeansNothingToWatch() {
        assertNull(WatchTarget.choose(emptyList()))
        assertNull(WatchTarget.choose(listOf(video("hidden", 0.0, 0.0))))
    }

    @Test
    fun aVideoWithoutAnUncoveredPointOnScreenCantBeTapped() {
        assertTrue(video("a", 960.0, 540.0, 100.0, 100.0).tappable(viewWidth = 1920, viewHeight = 1080))
        assertFalse(video("a", 960.0, 540.0, -1.0, -1.0).tappable(1920, 1080))
        assertFalse(video("a", 960.0, 540.0, 100.0, 1500.0).tappable(1920, 1080))
    }
}
