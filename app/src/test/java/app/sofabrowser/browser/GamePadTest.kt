package app.sofabrowser.browser

import android.view.KeyEvent
import app.sofabrowser.browser.GamePad.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GamePadTest {

    @Test
    fun faceButtonsWorkLikeTheRemoteEverywhere() {
        for (fullscreen in listOf(false, true)) {
            assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, key(KeyEvent.KEYCODE_BUTTON_A, fullscreen))
            assertEquals(KeyEvent.KEYCODE_BACK, key(KeyEvent.KEYCODE_BUTTON_B, fullscreen))
        }
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, key(KeyEvent.KEYCODE_BUTTON_X, true))
    }

    @Test
    fun browsingButtonsSearchNavigateAndPage() {
        // Watch even when the video already plays in the page: a pad has its own play button in fullscreen.
        assertEquals(Action.WATCH, GamePad.action(KeyEvent.KEYCODE_BUTTON_X, false))
        assertEquals(Action.SEARCH, GamePad.action(KeyEvent.KEYCODE_BUTTON_Y, false))
        assertEquals(Action.MENU, GamePad.action(KeyEvent.KEYCODE_BUTTON_START, false))
        assertEquals(Action.HOME, GamePad.action(KeyEvent.KEYCODE_BUTTON_SELECT, false))
        assertEquals(Action.HISTORY_BACK, GamePad.action(KeyEvent.KEYCODE_BUTTON_L1, false))
        assertEquals(Action.HISTORY_FORWARD, GamePad.action(KeyEvent.KEYCODE_BUTTON_R1, false))
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, key(KeyEvent.KEYCODE_BUTTON_L2, false))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, key(KeyEvent.KEYCODE_BUTTON_R2, false))
        assertEquals(KeyEvent.KEYCODE_MENU, key(KeyEvent.KEYCODE_BUTTON_START, false))
    }

    @Test
    fun fullscreenButtonsDriveTheVideo() {
        assertEquals(KeyEvent.KEYCODE_CAPTIONS, key(KeyEvent.KEYCODE_BUTTON_Y, true))
        assertEquals(KeyEvent.KEYCODE_MEDIA_REWIND, key(KeyEvent.KEYCODE_BUTTON_L1, true))
        assertEquals(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, key(KeyEvent.KEYCODE_BUTTON_R1, true))
        assertEquals(Action.SEEK_BACK_LONG, GamePad.action(KeyEvent.KEYCODE_BUTTON_L2, true))
        assertEquals(Action.SEEK_FORWARD_LONG, GamePad.action(KeyEvent.KEYCODE_BUTTON_R2, true))
        assertEquals(Action.VIDEO_INFO, GamePad.action(KeyEvent.KEYCODE_BUTTON_SELECT, true))
        assertEquals(KeyEvent.KEYCODE_DPAD_CENTER, key(KeyEvent.KEYCODE_BUTTON_START, true))
    }

    @Test
    fun otherKeysAreLeftAlone() {
        assertNull(GamePad.action(KeyEvent.KEYCODE_DPAD_UP, false))
        assertNull(GamePad.action(KeyEvent.KEYCODE_BUTTON_THUMBL, false))
        assertNull(GamePad.action(KeyEvent.KEYCODE_BUTTON_MODE, true))
    }

    @Test
    fun aRestingStickIsStillAndAFullPushIsFullSpeed() {
        assertEquals(0f to 0f, GamePad.stick(0.1f, -0.1f))
        assertEquals(0f to 0f, GamePad.stick(0.25f, 0f, flat = 0.3f))
        val (x, y) = GamePad.stick(1f, 0f)
        assertEquals(1f, x, 1e-4f)
        assertEquals(0f, y, 1e-4f)
        // Diagonal pushes past the edge are clamped, not faster.
        val (dx, dy) = GamePad.stick(1f, 1f)
        assertTrue(abs(dx * dx + dy * dy - 1f) < 1e-3f)
    }

    @Test
    fun smallPushesAimFinely() {
        val half = GamePad.stick(0.59f, 0f).first
        assertTrue("half a push should be well under half speed: $half", half in 0.05f..0.3f)
        val more = GamePad.stick(0.8f, 0f).first
        assertTrue(more > half)
    }

    @Test
    fun aTriggerPullCountsOnceEvenWhenThePadAlsoSendsAKey() {
        val t = GamePad.Triggers()
        assertFalse(t.axis(1, 0.4f, 0))
        assertTrue(t.axis(1, 0.7f, 10))
        assertFalse(t.axis(1, 1f, 20))
        assertFalse("the key for the same pull", t.key(1, 30))
        assertFalse(t.axis(1, 0.1f, 400))
        assertTrue("a second pull", t.axis(1, 0.9f, 500))
        assertTrue("the other trigger is separate", t.key(0, 510))
    }

    @Test
    fun padsThatOnlySendKeysStillPage() {
        val t = GamePad.Triggers()
        assertTrue(t.key(0, 0))
        assertTrue(t.key(0, 600))
    }

    @Test
    fun playStationPadsAreRecognisedForTheirLabels() {
        assertEquals(GamePad.Family.PLAYSTATION, GamePad.family(0x054C))
        assertEquals(GamePad.Family.XBOX, GamePad.family(0x045E))
        assertEquals(GamePad.Family.XBOX, GamePad.family(0x2DC8))
    }

    private fun key(code: Int, fullscreen: Boolean) = GamePad.action(code, fullscreen)?.let(GamePad::remoteKey)
}
