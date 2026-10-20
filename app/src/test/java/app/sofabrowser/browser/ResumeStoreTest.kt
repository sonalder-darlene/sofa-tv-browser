package app.sofabrowser.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeStoreTest {

    private val storage = HashMap<String, String>()
    private val store = ResumeStore(storage)
    private val day = 24 * 3600 * 1000L

    @Test
    fun aLongFilmLeftPartWayIsOfferedAgain() {
        store.save("https://films.test/watch/42", position = 750.0, duration = 5400.0, private = false, now = 0)
        assertEquals(750.0, store.offer("https://films.test/watch/42", now = day)!!, 0.0)
    }

    @Test
    fun thePageIsKeyedWithoutTrackingOrTimeParametersAndNeverStoredAsIs() {
        store.save("https://films.test/watch?v=42&utm_source=x&fbclid=y&t=10#c", 750.0, 5400.0, private = false, now = 0)
        assertEquals(750.0, store.offer("https://films.test/watch?v=42", now = 0)!!, 0.0)
        assertTrue(storage.keys.none { "films.test" in it })
    }

    @Test
    fun videosThatDifferOnlyByQueryAreDifferentFilms() {
        store.save("https://films.test/watch?v=42", 750.0, 5400.0, private = false, now = 0)
        assertNull(store.offer("https://films.test/watch?v=43", now = 0))
    }

    @Test
    fun privateBrowsingWritesNothing() {
        store.save("https://films.test/a", 750.0, 5400.0, private = true, now = 0)
        assertTrue(storage.isEmpty())
    }

    @Test
    fun shortVideosTheStartAndTheEndAreNotWorthOffering() {
        store.save("https://clips.test/a", 120.0, 240.0, private = false, now = 0)
        store.save("https://films.test/b", 20.0, 5400.0, private = false, now = 0)
        assertNull(store.offer("https://clips.test/a", now = 0))
        assertNull(store.offer("https://films.test/b", now = 0))
        store.save("https://films.test/c", 3000.0, 5400.0, private = false, now = 0)
        store.save("https://films.test/c", 5370.0, 5400.0, private = false, now = 1)
        assertNull(store.offer("https://films.test/c", now = 2))
    }

    @Test
    fun startingTheFilmAgainKeepsThePlaceUntilItIsPassed() {
        store.save("https://films.test/a", 750.0, 5400.0, private = false, now = 0)
        store.save("https://films.test/a", 0.0, 5400.0, private = false, now = 1)
        assertEquals(750.0, store.offer("https://films.test/a", now = 2)!!, 0.0)
    }

    @Test
    fun oldPositionsExpire() {
        store.save("https://films.test/a", 750.0, 5400.0, private = false, now = 0)
        assertNull(store.offer("https://films.test/a", now = 31 * day))
    }

    @Test
    fun onlyTheMostRecentHundredAreKept() {
        for (i in 0..120) store.save("https://films.test/$i", 750.0, 5400.0, private = false, now = i.toLong())
        assertEquals(100, storage.size)
        assertNull(store.offer("https://films.test/0", now = 200))
        assertEquals(750.0, store.offer("https://films.test/120", now = 200)!!, 0.0)
    }

    @Test
    fun clearingForgetsEverything() {
        store.save("https://films.test/a", 750.0, 5400.0, private = false, now = 0)
        store.clear()
        assertTrue(storage.isEmpty())
    }
}
