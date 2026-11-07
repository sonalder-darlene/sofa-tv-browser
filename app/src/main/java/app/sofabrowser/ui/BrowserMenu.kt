package app.sofabrowser.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R

/**
 * The browse menu, opened with BACK: an address pill above a row of labelled action buttons. Like BrowsHere's,
 * it remembers the last button used and doesn't hide on its own. Its buttons and their order follow [layout].
 */
class BrowserMenu(context: Context, private val actions: Actions, val layout: Arrangement<MenuLayout.Item>) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    enum class Action { HOME, WATCH, AUTO_WATCH, BACK, FORWARD, RELOAD, FIND, BOOKMARK, PIN, HISTORY, BOOKMARKS, DOWNLOADS, ADBLOCK, ALLOW_ONCE, USER_AGENT, SCROLL, POINTER, ZOOM_OUT, ZOOM_IN, SETTINGS }

    interface Actions {
        fun onAddress()
        fun onAction(action: Action)
        /** OK on the tab at [index] of the strip. */
        fun onTab(index: Int)
        /** OK held on the tab at [index]. */
        fun onTabHeld(index: Int)
        fun onNewTab()
    }

    /** A tab in the strip: what it's called, whether it's the one in sight, and whether that name is its address. */
    data class TabChip(val label: String, val current: Boolean, val address: Boolean = false)

    data class State(
        val address: String,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        val loading: Boolean,
        val bookmarked: Boolean,
        val adBlockOn: Boolean,
        /** Requests, pop-ups and redirects blocked on this page. */
        val blocked: Int,
        val desktop: Boolean,
        val pointer: Boolean,
        val zoomPercent: Int,
        val privateMode: Boolean,
        /** A pop-up or redirect was just blocked and can still be opened once. */
        val allowPending: Boolean,
        /** "Watch by itself" runs on this site (on every site, or this one is chosen). */
        val autoWatchHere: Boolean,
        /** The tabs of the side in sight, in order. */
        val tabs: List<TabChip>,
    )

    private val buttons = LinkedHashMap<Action, LabeledIconButton>()
    private lateinit var address: TextView
    /** The tabs (when there are two or more) and New tab, right of the address. */
    private lateinit var strip: LinearLayout
    private var stripTabs: List<TabChip>? = null
    private var zoomLabel: TextView? = null
    private var lastFocused = Action.HOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0f)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.p.sheet)
                cornerRadii = FloatArray(8) { if (it < 4) 16.px.toFloat() else 0f }
            }
            setPadding(72.px, 40.px, 72.px, 24.px)
        }

        val addressRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        address = Ui.text(context, 30, Ui.p.textMid).apply {
            isFocusable = true
            isClickable = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32.px, 0, 32.px, 0)
            background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 44)
            setCompoundDrawablesRelative(tinted(R.drawable.ic_search), null, null, null)
            compoundDrawablePadding = 20.px
            setOnFocusChangeListener { v, has ->
                (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid)
                v.compoundDrawablesRelative[0]?.setTint(if (has) Ui.p.onFocus else Ui.p.textMid)
            }
            setOnClickListener { dismiss(); actions.onAddress() }
        }
        addressRow.addView(address, LinearLayout.LayoutParams(ADDRESS_WIDE.px, 88.px))
        strip = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        addressRow.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(strip)
        }, LinearLayout.LayoutParams(0, 88.px, 1f).apply { marginStart = 24.px })
        root.addView(addressRow)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 40.px, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        fun add(action: Action, icon: Int, label: Int, first: Boolean = false) {
            val b = LabeledIconButton(context, icon, context.getString(label)) { actions.onAction(action) }
            b.onFocused = { lastFocused = action }
            buttons[action] = b
            row.addView(b, if (first) 0 else -1, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 28.px
            })
        }
        for (item in layout.shown) when (item) {
            MenuLayout.Item.ZOOM -> {
                add(Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out)
                zoomLabel = Ui.text(context, 26, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
                row.addView(zoomLabel, LinearLayout.LayoutParams(100.px, 80.px))
                add(Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in)
            }
            else -> {
                add(Action.valueOf(item.name), item.icon, item.label)
                // Shown only after a block, next to the shield it belongs with; the menu then opens with it focused.
                if (item == MenuLayout.Item.ADBLOCK) add(Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once)
            }
        }
        // With the shield hidden, "Allow once" goes first in the row.
        if (Action.ALLOW_ONCE !in buttons) add(Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once, first = true)

        root.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(row)
        })
        setContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun show(state: State) {
        if (!isShowing) show()
        update(state)
        // A pending "Allow once" is why the menu was opened, most likely.
        val first = if (state.allowPending) Action.ALLOW_ONCE else lastFocused
        val usable = { b: LabeledIconButton -> b.isEnabled && b.visibility == android.view.View.VISIBLE }
        (buttons[first]?.takeIf(usable) ?: buttons.values.firstOrNull(usable))?.requestFocus()
    }

    fun update(s: State) {
        if (!::address.isInitialized) return
        address.text = s.address.ifEmpty { context.getString(R.string.search_hint) }
        if (s.tabs != stripTabs) showTabs(s.tabs)
        buttons[Action.ALLOW_ONCE]?.visibility = if (s.allowPending) android.view.View.VISIBLE else android.view.View.GONE
        buttons[Action.BACK]?.setEnabledLook(s.canGoBack)
        buttons[Action.FORWARD]?.setEnabledLook(s.canGoForward)
        buttons[Action.RELOAD]?.set(if (s.loading) R.drawable.ic_close else R.drawable.ic_refresh,
            context.getString(if (s.loading) R.string.stop else R.string.reload))
        buttons[Action.BOOKMARK]?.set(if (s.bookmarked) R.drawable.ic_star_filled else R.drawable.ic_star,
            context.getString(if (s.bookmarked) R.string.bookmarked else R.string.bookmark))
        buttons[Action.ADBLOCK]?.set(if (s.adBlockOn) R.drawable.ic_shield_filled else R.drawable.ic_shield,
            when {
                !s.adBlockOn -> context.getString(R.string.adblock_site_off)
                s.blocked > 0 -> context.resources.getQuantityString(R.plurals.adblock_site_on_count, s.blocked, s.blocked)
                else -> context.getString(R.string.adblock_site_on)
            })
        buttons[Action.AUTO_WATCH]?.let { b ->
            b.visibility = if (s.privateMode) android.view.View.GONE else android.view.View.VISIBLE
            b.set(R.drawable.ic_autoplay, context.getString(if (s.autoWatchHere) R.string.auto_watch_on else R.string.auto_watch_off))
        }
        buttons[Action.USER_AGENT]?.set(if (s.desktop) R.drawable.ic_desktop_windows else R.drawable.ic_smartphone,
            context.getString(R.string.user_agent))
        buttons[Action.POINTER]?.set(if (s.pointer) R.drawable.ic_mouse else R.drawable.ic_keyboard,
            context.getString(if (s.pointer) R.string.pointer_on else R.string.pointer_off))
        zoomLabel?.text = context.getString(R.string.percent, s.zoomPercent)
    }

    /**
     * The strip: one chip per tab when there are two or more (the one in sight outlined), then New tab. The address
     * gets narrower to make room. Focus stays at the same place in the strip when it changes under it.
     */
    private fun showTabs(tabs: List<TabChip>) {
        stripTabs = tabs
        val focused = (0 until strip.childCount).firstOrNull { strip.getChildAt(it).hasFocus() }
        strip.removeAllViews()
        address.layoutParams = address.layoutParams.apply { width = (if (tabs.size > 1) ADDRESS_NARROW else ADDRESS_WIDE).px }
        if (tabs.size > 1) tabs.forEachIndexed { i, tab ->
            strip.addView(chip(tab.label, tab.current) { actions.onTab(i) }.apply {
                // An address keeps its site and the end where it differs from the others.
                if (tab.address) ellipsize = TextUtils.TruncateAt.MIDDLE
                isLongClickable = true
                setOnLongClickListener { actions.onTabHeld(i); true }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 88.px).apply { marginEnd = 16.px })
        }
        strip.addView(chip(context.getString(R.string.new_tab), false, R.drawable.ic_add) { actions.onNewTab() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 88.px))
        focused?.let { strip.getChildAt(it.coerceAtMost(strip.childCount - 1)).requestFocus() }
    }

    private fun chip(label: String, current: Boolean, icon: Int = 0, onClick: () -> Unit) = Ui.text(context, 26, Ui.p.textMid).apply {
        text = label
        isFocusable = true
        isClickable = true
        maxLines = 1
        maxWidth = 320.px
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(28.px, 0, 28.px, 0)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(Ui.p.focus, 44))
            addState(intArrayOf(), if (current) Ui.rounded(Ui.p.fillFocused, 44, Ui.BRAND, 3) else Ui.rounded(Ui.p.fillFocused, 44))
        }
        if (icon != 0) { setCompoundDrawablesRelative(tinted(icon), null, null, null); compoundDrawablePadding = 12.px }
        setOnFocusChangeListener { v, has ->
            (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid)
            v.compoundDrawablesRelative[0]?.setTint(if (has) Ui.p.onFocus else Ui.p.textMid)
        }
        setOnClickListener { onClick() }
    }

    private fun tinted(res: Int) = context.getDrawable(res)?.mutate()?.apply {
        setBounds(0, 0, 40.px, 40.px)
        setTint(Ui.p.textMid)
    }

    private companion object {
        const val ADDRESS_WIDE = 1100
        const val ADDRESS_NARROW = 700
    }
}
