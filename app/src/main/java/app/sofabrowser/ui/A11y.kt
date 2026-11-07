package app.sofabrowser.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo

/**
 * What a screen reader (TalkBack) needs from Sofa's own views, which are laid out and drawn by hand: a name for a
 * control that shows only an icon or a colour, the role and state of one that switches, is chosen or holds a value,
 * headings to find the parts of a screen, and changes said as they happen. Platform calls only, each where the TV's
 * Android has it: headings from Android 9, spoken states from Android 11 (before that the state is said with the name).
 */
object A11y {
    const val BUTTON = "android.widget.Button"
    const val SWITCH = "android.widget.Switch"
    const val SEEK_BAR = "android.widget.SeekBar"

    /** A screen reader is on: TalkBack, or another service that speaks. */
    fun screenReaderOn(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.isEnabled && am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_SPOKEN).isNotEmpty()
    }

    /** [v] is only decoration, or says what a neighbour already says: a screen reader leaves it out. */
    fun skip(v: View) {
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** [v] and everything in it is a picture of something (a preview of the start page): a screen reader leaves it out. */
    fun skipAll(v: View) {
        v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    /** [v] titles a screen or a part of one: a screen reader says "heading" and can jump between them. */
    fun heading(v: View) {
        if (Build.VERSION.SDK_INT >= 28) v.isAccessibilityHeading = true
    }

    /** What [v] says is read out whenever it changes, wherever the focus is: a notice, a status line. */
    fun announced(v: View) {
        v.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /**
     * A screen reader reads [v] as a [role] (one of the class names above) and asks for its state each time: [checked]
     * for something on or off (said again when a press flips it), [selected] for the one chosen among several.
     */
    fun role(v: View, role: String?, checked: (() -> Boolean)? = null, selected: (() -> Boolean)? = null, hint: (() -> CharSequence?)? = null) {
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                role?.let { info.className = it }
                checked?.let { info.isCheckable = true; info.isChecked = it() }
                selected?.let { info.isSelected = it() }
                // How to use it (said after a pause), for a control the remote works in its own way.
                if (hint != null && Build.VERSION.SDK_INT >= 26) info.hintText = hint()
            }

            override fun onInitializeAccessibilityEvent(host: View, event: AccessibilityEvent) {
                super.onInitializeAccessibilityEvent(host, event)
                role?.let { event.className = it }
                checked?.let { event.isChecked = it() }
            }
        }
    }

    /**
     * [v]'s value changed under focus (a digit turned, a time moved, a row carried): a screen reader says its new
     * state. TalkBack says nothing for a mere state change, only for this event, as Android's own seek bar sends it.
     */
    fun changed(v: View) {
        if (v.isFocused) v.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
    }

    /**
     * [v] is a row of [count] items called [name] (a start page row): a screen reader names it as focus comes into it,
     * since its title above is plain text that focus never reaches.
     */
    fun row(v: View, name: CharSequence, count: () -> Int) {
        v.contentDescription = name
        v.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val n = count()
                info.collectionInfo = if (Build.VERSION.SDK_INT >= 33) AccessibilityNodeInfo.CollectionInfo(1, n, false)
                    else @Suppress("DEPRECATION") AccessibilityNodeInfo.CollectionInfo.obtain(1, n, false)
            }
        }
    }

    /**
     * [v] is called [name] and now reads [state] (a digit, a time, "Hidden"); a screen reader says the new state when
     * it changes while [v] has focus.
     */
    fun state(v: View, name: CharSequence, state: CharSequence?) {
        if (Build.VERSION.SDK_INT >= 30) {
            v.contentDescription = name
            v.stateDescription = state
        } else {
            v.contentDescription = if (state.isNullOrEmpty()) name else "$name, $state"
        }
    }
}
