package app.sofabrowser.browser

import android.app.Activity
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display

/**
 * "Match frame rate" on the TV: asks for the display mode [FrameRate.pick] chose, through Sofa's own window, since
 * WebView draws its video into that window. A window's mode request is the one way an app can make a TV switch
 * refresh rate when the switch isn't seamless; Android drops it by itself while Sofa is hidden or in
 * picture-in-picture. The switch can blank the screen for a second or two, so the video waits, as Android's frame
 * rate guide advises: pause, switch, then 2 s more.
 */
class RefreshRateMatch(private val activity: Activity) {
    private val displays = activity.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var listener: DisplayManager.DisplayListener? = null
    private var requested = 0

    private val display: Display? get() = displays?.getDisplay(Display.DEFAULT_DISPLAY)

    private fun Display.Mode.toMode() = FrameRate.Mode(modeId, physicalWidth, physicalHeight, refreshRate)

    /** The current refresh rate, in Hz. */
    val refreshRate: Float get() = display?.mode?.refreshRate ?: 0f

    /** The other refresh rates the TV offers at its current resolution (none on many TVs). */
    fun otherRates(): List<Float> {
        val d = display ?: return emptyList()
        return FrameRate.otherRates(d.supportedModes.map { it.toMode() }, d.mode.toMode())
    }

    /** Android 12+ has its own "Match content frame rate" setting; Sofa doesn't switch when it is set to Never. */
    val turnedOffInSystem: Boolean
        get() = Build.VERSION.SDK_INT >= 31 &&
            displays?.matchContentFrameRateUserPreference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER

    /** Whether the setting can do anything on this TV. */
    fun available() = !turnedOffInSystem && otherRates().isNotEmpty()

    /**
     * Switches for a video at [fps], if a mode suits it better: [pause] runs first, [resume] once the TV has
     * switched and settled (or after 7 s at most). Returns whether it switched.
     */
    fun match(fps: Double, pause: () -> Unit, resume: () -> Unit): Boolean {
        if (requested != 0 || !available()) return false
        val d = display ?: return false
        val id = FrameRate.pick(d.supportedModes.map { it.toMode() }, d.mode.toMode(), fps) ?: return false
        pause()
        requested = id
        var settled = false
        val settle = Runnable {
            if (settled) return@Runnable
            settled = true
            stopListening()
            handler.postDelayed(resume, SETTLE_MS)
        }
        listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY && display?.mode?.modeId == id) settle.run()
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }.also { displays?.registerDisplayListener(it, handler) }
        handler.postDelayed(settle, SWITCH_TIMEOUT_MS)
        setWindowMode(id)
        return true
    }

    /** Fullscreen ended: back to the TV's own mode, and no pending resume. */
    fun reset() {
        handler.removeCallbacksAndMessages(null)
        stopListening()
        if (requested != 0) { requested = 0; setWindowMode(0) }
    }

    private fun stopListening() {
        listener?.let { displays?.unregisterDisplayListener(it) }
        listener = null
    }

    private fun setWindowMode(id: Int) {
        val w = activity.window
        w.attributes = w.attributes.apply { preferredDisplayModeId = id }
    }

    private companion object {
        const val SWITCH_TIMEOUT_MS = 5000L
        const val SETTLE_MS = 2000L
    }
}
