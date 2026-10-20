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
 * it remembers the last button used and doesn't hide on its own.
 */
class BrowserMenu(context: Context, private val actions: Actions) : Dialog(context, android.R.style.Theme_Translucent_NoTitleBar) {

    enum class Action { HOME, WATCH, BACK, FORWARD, RELOAD, FIND, BOOKMARK, PIN, HISTORY, BOOKMARKS, DOWNLOADS, ADBLOCK, ALLOW_ONCE, USER_AGENT, SCROLL, POINTER, ZOOM_OUT, ZOOM_IN, SETTINGS }

    interface Actions {
        fun onAddress()
        fun onAction(action: Action)
    }

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
    )

    private val buttons = LinkedHashMap<Action, LabeledIconButton>()
    private lateinit var address: TextView
    private lateinit var zoomLabel: TextView
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
        addressRow.addView(address, LinearLayout.LayoutParams(1100.px, 88.px))
        root.addView(addressRow)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(0, 40.px, 0, 0)
            clipChildren = false
            clipToPadding = false
        }
        fun add(action: Action, icon: Int, label: Int) {
            val b = LabeledIconButton(context, icon, context.getString(label)) { actions.onAction(action) }
            b.onFocused = { lastFocused = action }
            buttons[action] = b
            row.addView(b, LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = 28.px
            })
        }
        add(Action.HOME, R.drawable.ic_home, R.string.home)
        // Watch is the action a TV is most often used for: next to Home.
        add(Action.WATCH, R.drawable.ic_play_circle, R.string.watch)
        add(Action.BACK, R.drawable.ic_arrow_back, R.string.back)
        add(Action.FORWARD, R.drawable.ic_arrow_forward, R.string.forward)
        add(Action.RELOAD, R.drawable.ic_refresh, R.string.reload)
        add(Action.FIND, R.drawable.ic_find_in_page, R.string.find_in_page)
        add(Action.BOOKMARK, R.drawable.ic_star, R.string.bookmark)
        add(Action.PIN, R.drawable.ic_add, R.string.pin_to_home)
        add(Action.HISTORY, R.drawable.ic_history, R.string.history)
        add(Action.BOOKMARKS, R.drawable.ic_bookmarks, R.string.bookmarks)
        add(Action.DOWNLOADS, R.drawable.ic_download, R.string.downloads)
        add(Action.ADBLOCK, R.drawable.ic_shield, R.string.adblock_site)
        // Shown only after a block, next to the shield it belongs with; the menu then opens with it focused.
        add(Action.ALLOW_ONCE, R.drawable.ic_open_in_new, R.string.allow_once)
        add(Action.USER_AGENT, R.drawable.ic_desktop_windows, R.string.user_agent)
        add(Action.SCROLL, R.drawable.ic_swap_vert, R.string.scroll_mode)
        add(Action.POINTER, R.drawable.ic_mouse, R.string.pointer)
        add(Action.ZOOM_OUT, R.drawable.ic_zoom_out, R.string.zoom_out)
        zoomLabel = Ui.text(context, 26, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) }
        row.addView(zoomLabel, LinearLayout.LayoutParams(100.px, 80.px))
        add(Action.ZOOM_IN, R.drawable.ic_zoom_in, R.string.zoom_in)
        add(Action.SETTINGS, R.drawable.ic_settings, R.string.settings)

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
        (buttons[first]?.takeIf { it.isEnabled && it.visibility == android.view.View.VISIBLE } ?: buttons[Action.HOME])?.requestFocus()
    }

    fun update(s: State) {
        if (!::address.isInitialized) return
        address.text = s.address.ifEmpty { context.getString(R.string.search_hint) }
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
        buttons[Action.USER_AGENT]?.set(if (s.desktop) R.drawable.ic_desktop_windows else R.drawable.ic_smartphone,
            context.getString(R.string.user_agent))
        buttons[Action.POINTER]?.set(if (s.pointer) R.drawable.ic_mouse else R.drawable.ic_keyboard,
            context.getString(if (s.pointer) R.string.pointer_on else R.string.pointer_off))
        zoomLabel.text = context.getString(R.string.percent, s.zoomPercent)
    }

    private fun tinted(res: Int) = context.getDrawable(res)?.mutate()?.apply {
        setBounds(0, 0, 40.px, 40.px)
        setTint(Ui.p.textMid)
    }
}
