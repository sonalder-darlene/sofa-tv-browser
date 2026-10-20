package app.sofabrowser.browser

import app.sofabrowser.browser.ProtectedMedia.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtectedMediaTest {

    @Test
    fun askModeAsksOnlyForSitesWithoutAnAnswer() {
        assertEquals(Decision.ASK, ProtectedMedia.decide(ProtectedMedia.ASK, remembered = null))
        assertEquals(Decision.GRANT, ProtectedMedia.decide(ProtectedMedia.ASK, remembered = true))
        assertEquals(Decision.DENY, ProtectedMedia.decide(ProtectedMedia.ASK, remembered = false))
    }

    @Test
    fun allowAndBlockAnswerEverySiteWhateverItsStoredAnswer() {
        for (remembered in listOf(null, true, false)) {
            assertEquals(Decision.GRANT, ProtectedMedia.decide(ProtectedMedia.ALLOW, remembered))
            assertEquals(Decision.DENY, ProtectedMedia.decide(ProtectedMedia.BLOCK, remembered))
        }
    }

    @Test
    fun anUnknownModeAsks() {
        assertEquals(Decision.ASK, ProtectedMedia.decide("", remembered = null))
    }
}
