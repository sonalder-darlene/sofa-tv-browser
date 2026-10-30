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

    @Test
    fun watchByItselfRunsWhereTheSettingSays() {
        val chosen = setOf("stream.example")
        assertFalse(WatchTarget.autoWatches(WatchTarget.Auto.OFF, "stream.example", chosen))
        assertTrue(WatchTarget.autoWatches(WatchTarget.Auto.ALWAYS, "news.example", chosen))
        assertTrue(WatchTarget.autoWatches(WatchTarget.Auto.SITES, "stream.example", chosen))
        assertFalse(WatchTarget.autoWatches(WatchTarget.Auto.SITES, "news.example", chosen))
        assertFalse(WatchTarget.autoWatches(WatchTarget.Auto.ALWAYS, null, chosen))
    }

    @Test
    fun onEverySiteWatchByItselfLeavesPreviewsLoopsAndClipsAlone() {
        val film = video("film", 960.0, 540.0)
        val hero = film.copy(muted = true, playing = true)
        assertEquals(film, WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(film)))
        assertNull(WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(hero)))
        assertNull(WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(film.copy(loop = true))))
        assertNull(WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(film.copy(duration = 30.0))))
        // Muted but waiting for a press, live, or not loaded yet: a main video still.
        assertEquals(film.copy(muted = true), WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(film.copy(muted = true))))
        assertEquals(film.copy(duration = -1.0), WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(film.copy(duration = -1.0))))
        // Behind a muted hero, a small video is no main video.
        assertNull(WatchTarget.chooseAlone(WatchTarget.Auto.ALWAYS, listOf(hero, video("thumb", 320.0, 180.0))))
        // On a chosen site, Watch's own choice.
        assertEquals(hero, WatchTarget.chooseAlone(WatchTarget.Auto.SITES, listOf(hero)))
    }
}
