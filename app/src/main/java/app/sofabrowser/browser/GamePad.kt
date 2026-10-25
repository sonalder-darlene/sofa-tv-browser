package app.sofabrowser.browser

import android.view.KeyEvent
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Game pads (Xbox, DualShock, DualSense, 8BitDo and the like, over Bluetooth or USB), laid out as a console browser
 * lays them out. Android already names the buttons the same on every pad (its key layout files map each model), so
 * only the meaning lives here. Plain Kotlin, so the rules are unit-tested.
 */
object GamePad {

    enum class Action {
        /** Stand-ins for remote keys, so everything the remote does keeps working: A held drags, B leaves menus. */
        SELECT, BACK, PLAY_PAUSE, MENU, PAGE_UP, PAGE_DOWN, SUBTITLES, SEEK_BACK, SEEK_FORWARD,
        WATCH, SEARCH, HOME, HISTORY_BACK, HISTORY_FORWARD, SEEK_BACK_LONG, SEEK_FORWARD_LONG, VIDEO_INFO,
    }

    /** What [keyCode] means while browsing, or in fullscreen video when [fullscreen]; null for keys left alone. */
    fun action(keyCode: Int, fullscreen: Boolean): Action? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A -> Action.SELECT
        KeyEvent.KEYCODE_BUTTON_B -> Action.BACK
        KeyEvent.KEYCODE_BUTTON_X -> if (fullscreen) Action.PLAY_PAUSE else Action.WATCH
        KeyEvent.KEYCODE_BUTTON_Y -> if (fullscreen) Action.SUBTITLES else Action.SEARCH
        KeyEvent.KEYCODE_BUTTON_START -> if (fullscreen) Action.SELECT else Action.MENU
        KeyEvent.KEYCODE_BUTTON_SELECT -> if (fullscreen) Action.VIDEO_INFO else Action.HOME
        KeyEvent.KEYCODE_BUTTON_L1 -> if (fullscreen) Action.SEEK_BACK else Action.HISTORY_BACK
        KeyEvent.KEYCODE_BUTTON_R1 -> if (fullscreen) Action.SEEK_FORWARD else Action.HISTORY_FORWARD
        KeyEvent.KEYCODE_BUTTON_L2 -> if (fullscreen) Action.SEEK_BACK_LONG else Action.PAGE_UP
        KeyEvent.KEYCODE_BUTTON_R2 -> if (fullscreen) Action.SEEK_FORWARD_LONG else Action.PAGE_DOWN
        else -> null
    }

    /** The remote key an action stands in for, or null when it has none and is handled directly. */
    fun remoteKey(action: Action): Int? = when (action) {
        Action.SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
        Action.BACK -> KeyEvent.KEYCODE_BACK
        Action.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        Action.MENU -> KeyEvent.KEYCODE_MENU
        Action.PAGE_UP -> KeyEvent.KEYCODE_PAGE_UP
        Action.PAGE_DOWN -> KeyEvent.KEYCODE_PAGE_DOWN
        Action.SUBTITLES -> KeyEvent.KEYCODE_CAPTIONS
        Action.SEEK_BACK -> KeyEvent.KEYCODE_MEDIA_REWIND
        Action.SEEK_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        else -> null
    }

    /**
     * A stick's position after the dead zone, scaled back to 0..1 and curved, so small pushes aim finely and a full
     * push travels fast. [flat] is the pad's own reported noise, used when larger than the default dead zone.
     */
    fun stick(x: Float, y: Float, flat: Float = 0f): Pair<Float, Float> {
        val dead = maxOf(DEAD_ZONE, flat.coerceAtMost(0.5f))
        val m = sqrt(x * x + y * y)
        if (m <= dead) return 0f to 0f
        val scaled = ((minOf(m, 1f) - dead) / (1f - dead)).pow(CURVE)
        return x / m * scaled to y / m * scaled
    }

    /** Pad models whose buttons are labelled differently; the labels are only shown in hints. */
    enum class Family { XBOX, PLAYSTATION }

    fun family(vendorId: Int): Family = if (vendorId == SONY) Family.PLAYSTATION else Family.XBOX

    /**
     * Turns an analog trigger into one press per pull. Pads that also send L2/R2 keys get both for one pull, so a
     * press counts once whichever arrives first, and the other is ignored for a moment.
     */
    class Triggers {
        private val pulled = BooleanArray(2)
        private val lastPress = LongArray(2) { Long.MIN_VALUE / 2 }

        /** [side] 0 is left, 1 is right. True when this axis value is a new pull. */
        fun axis(side: Int, value: Float, now: Long): Boolean {
            if (!pulled[side] && value >= PULL) {
                pulled[side] = true
                return press(side, now)
            }
            if (pulled[side] && value <= RELEASE) pulled[side] = false
            return false
        }

        /** An L2 or R2 key press: true unless the same pull already counted through the axis. */
        fun key(side: Int, now: Long): Boolean = press(side, now)

        private fun press(side: Int, now: Long): Boolean {
            if (now - lastPress[side] < SAME_PULL_MS) return false
            lastPress[side] = now
            return true
        }
    }

    private const val DEAD_ZONE = 0.18f
    private const val CURVE = 2.2f
    private const val PULL = 0.6f
    private const val RELEASE = 0.3f
    private const val SAME_PULL_MS = 250L
    private const val SONY = 0x054C
}
