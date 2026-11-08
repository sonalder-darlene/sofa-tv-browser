package app.sofabrowser.adblock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** [AdBlocker.afterQueue]: what the settings backup uses to see the lists after the changes already asked for. */
class AfterQueueTest {

    private val worker = Executors.newSingleThreadExecutor()

    @After
    fun stop() {
        worker.shutdownNow()
    }

    @Test
    fun readsAfterTheChangesAlreadyQueued() {
        val lists = AtomicReference(listOf("a"))
        worker.execute { Thread.sleep(300); lists.set(listOf("a", "b")) } // a list added, still downloading
        assertEquals(listOf("a", "b"), AdBlocker.afterQueue(worker, 5_000) { lists.get() })
    }

    @Test
    fun anIdleWorkerAnswersAtOnce() {
        val started = System.nanoTime()
        assertEquals("x", AdBlocker.afterQueue(worker, 5_000) { "x" })
        assertEquals(true, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
    }

    @Test
    fun aWorkerStuckLongerThanTheWaitGivesWhatIsThereNow() {
        val release = CountDownLatch(1)
        val lists = AtomicReference(listOf("a"))
        worker.execute { release.await(); lists.set(listOf("a", "b")) }
        val started = System.nanoTime()
        assertEquals(listOf("a"), AdBlocker.afterQueue(worker, 200) { lists.get() })
        val waited = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertEquals(true, waited in 200..2_000)
        release.countDown()
    }
}
