package app.sofabrowser.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinTest {

    private var clock = 1_000_000_000L
    private val data = HashMap<String, String>()
    private val pin = Pin(data) { clock }

    @Test
    fun nothingIsLockedUntilAPinIsSet() {
        assertFalse(pin.isSet)
        assertEquals(0, pin.waitLeft())
        pin.set("2580")
        assertTrue(pin.isSet)
        pin.remove()
        assertFalse(pin.isSet)
        assertTrue(data.isEmpty())
    }

    @Test
    fun theRightPinPassesAndAWrongOneDoesNot() {
        pin.set("2580")
        assertEquals(Pin.Result.Right, pin.check("2580"))
        assertTrue(pin.check("2581") is Pin.Result.Wrong)
        assertTrue(pin.check("258") is Pin.Result.Wrong)
        assertTrue(pin.check("25800") is Pin.Result.Wrong)
    }

    @Test
    fun onlyASaltedHashIsKept() {
        pin.set("2580")
        assertFalse(data.values.any { "2580" in it })
        val first = data.toMap()
        pin.set("2580")
        // A new salt each time: the same PIN never gives the same stored value.
        assertNotEquals(first["hash"], data["hash"])
        assertEquals(Pin.Result.Right, pin.check("2580"))
    }

    @Test
    fun onlyFourDigitsAreValid() {
        assertTrue(Pin.valid("0000"))
        assertFalse(Pin.valid("123"))
        assertFalse(Pin.valid("12a4"))
        assertFalse(Pin.valid("12345"))
    }

    @Test
    fun wrongTriesLeadToAPauseThatGrows() {
        pin.set("2580")
        repeat(Pin.FREE_TRIES - 1) {
            val r = pin.check("0000") as Pin.Result.Wrong
            assertEquals(0, r.wait)
            assertEquals(Pin.FREE_TRIES - 1 - it, r.triesLeft)
        }
        val fifth = pin.check("0000") as Pin.Result.Wrong
        assertEquals(30_000L, fifth.wait)
        // Even the right PIN waits for the pause.
        assertTrue(pin.check("2580") is Pin.Result.Wait)
        clock += 30_000
        val sixth = pin.check("0000") as Pin.Result.Wrong
        assertEquals(60_000L, sixth.wait)
        clock += 60_000
        assertEquals(Pin.Result.Right, pin.check("2580"))
        // The right PIN starts the count again.
        assertEquals(0, (pin.check("0000") as Pin.Result.Wrong).wait)
    }

    @Test
    fun thePauseIsCapped() {
        assertEquals(0L, Pin.pause(Pin.FREE_TRIES - 1))
        assertEquals(30 * 60_000L, Pin.pause(Pin.FREE_TRIES + 20))
    }

    @Test
    fun thePauseSurvivesARestartAndAClockTurnedBack() {
        pin.set("2580")
        repeat(Pin.FREE_TRIES) { pin.check("0000") }
        // A new Pin on the same stored data, as after a restart.
        val again = Pin(data) { clock }
        assertEquals(30_000L, again.waitLeft())
        clock -= 3_600_000
        assertEquals(30_000L, again.waitLeft())
        clock += 29_000
        assertEquals(1_000L, again.waitLeft())
    }
}
