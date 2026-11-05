package app.sofabrowser.screen

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import app.sofabrowser.browser.GamePad
import app.sofabrowser.ui.PadHelp

/**
 * Game pads: each button stands in for a remote key or a browser action (GamePad has the layout), and the sticks aim
 * and scroll the pointer. Elsewhere (menus, lists, the bar) Android's own handling applies: A and B act as OK and
 * Back, and the stick moves the focus.
 */
class PadInput(private val s: Screen, private val video: VideoSession, private val watch: Watch) {

    private val triggers = GamePad.Triggers()
    private val inFullscreen get() = s.fullscreen.visibility == View.VISIBLE

    /** A pad's button; false leaves [event] to the remote's handling. */
    fun key(event: KeyEvent): Boolean {
        val action = GamePad.action(event.keyCode, inFullscreen) ?: return false
        seen(event.device)
        val trigger = when (event.keyCode) { KeyEvent.KEYCODE_BUTTON_L2 -> 0; KeyEvent.KEYCODE_BUTTON_R2 -> 1; else -> -1 }
        val stand = GamePad.remoteKey(action)
        when {
            trigger >= 0 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && triggers.key(trigger, event.eventTime)) press(action)
            }
            // Down, repeats and up all go through, so holding A drags and long-presses as holding OK does.
            stand != null -> s.activity.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, stand, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> press(action)
        }
        return true
    }

    private fun press(action: GamePad.Action) {
        GamePad.remoteKey(action)?.let { code ->
            val now = SystemClock.uptimeMillis()
            s.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            s.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            return
        }
        val home = s.homeShown
        val web = s.web
        when (action) {
            GamePad.Action.WATCH -> if (!home) watch.start()
            GamePad.Action.SEARCH -> s.startSearch(voice = false, current = if (home) null else web?.url)
            GamePad.Action.HOME -> if (!home) s.showHome()
            GamePad.Action.HISTORY_BACK -> if (!home && web?.canGoBack() == true) s.browser.goBack()
            GamePad.Action.HISTORY_FORWARD -> if (!home && web?.canGoForward() == true) s.browser.goForward()
            GamePad.Action.SEEK_BACK_LONG, GamePad.Action.SEEK_FORWARD_LONG -> if (inFullscreen) {
                video.command("seekBy", if (action == GamePad.Action.SEEK_BACK_LONG) -30.0 else 30.0)
                s.fullscreen.bar.show(focusScrub = true)
            }
            GamePad.Action.VIDEO_INFO -> if (inFullscreen) video.toggleVideoInfo()
            else -> {}
        }
    }

    /** The sticks drive the pointer that is in use, and the analog triggers page; false leaves the event to Android. */
    fun motion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        seen(event.device)
        val inFullscreen = inFullscreen
        for (side in 0..1) {
            val axes = if (side == 0) intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
            else intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            val pull = axes.maxOf { event.getAxisValue(it) }
            val code = if (side == 0) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2
            if (triggers.axis(side, pull, event.eventTime)) GamePad.action(code, inFullscreen)?.let(::press)
        }
        val pointer = when {
            s.homeShown || s.menu.showing -> null
            inFullscreen -> s.fullscreen.pointer.takeIf { it.cursorEnabled }
            s.findBar.hasFocus() -> null
            else -> s.cursor.takeIf { it.cursorEnabled }
        } ?: return false
        val device = event.device
        fun flat(axis: Int) = device?.getMotionRange(axis, event.source)?.flat ?: 0f
        // The right stick is Z and RZ on almost every pad Android knows; a few older layouts report RX and RY.
        val (rx, ry) = if (device == null || device.getMotionRange(MotionEvent.AXIS_Z) != null) MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        else MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        val (ax, ay) = GamePad.stick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), flat(MotionEvent.AXIS_X))
        val (sx, sy) = GamePad.stick(event.getAxisValue(rx), event.getAxisValue(ry), flat(rx))
        return pointer.stick(ax, ay, sx, sy)
    }

    private val padsSeen = HashSet<Int>()

    /** The first time a pad is used in a session, the first few times ever, a card shows its buttons. */
    private fun seen(device: InputDevice?) {
        val prefs = s.prefs
        if (device == null || !padsSeen.add(device.id) || prefs.padHints >= PAD_HINTS) return
        prefs.padHints++
        PadHelp.showHint(s.activity, s.root, GamePad.family(device.vendorId))
    }

    private companion object {
        /** How many times, ever, the game pad card is shown. */
        const val PAD_HINTS = 3
    }
}
