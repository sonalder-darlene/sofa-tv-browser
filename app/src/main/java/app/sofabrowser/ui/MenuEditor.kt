package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.Prefs
import app.sofabrowser.R

/**
 * Settings > Menu buttons: a row for each button of the browse menu and, at the bottom, the menu as it will look.
 * OK picks a row up, ▲▼ carry it and OK puts it down (Back puts it back where it was); ▶ reaches the row's switch,
 * which shows or hides the button. Every change is saved at once, in Settings ([Prefs.menuLayout]).
 */
@SuppressLint("ViewConstructor")
class MenuEditor(context: Context, private val prefs: Prefs) : LinearLayout(context) {

    private var layout = prefs.menuLayout
    private val rows = ArrayList<Row>()
    private val hint = Ui.text(context, 28, Ui.p.textMid)
    private val preview = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
    private val previewViews = HashMap<MenuLayout.Item, List<View>>()
    /** The row being carried, and where it was picked up; -1 when none. */
    private var moving = -1
    private var movingFrom = -1
    /** The row in focus (either of its parts), for the hint and the highlight in the preview. */
    private var focused = 0

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(Ui.text(context, 56, Ui.p.textMid, bold = true).apply { setText(R.string.menu_buttons) },
            LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 64.px })
        addView(hint, LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 16.px; bottomMargin = 24.px })

        val list = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; setPadding(0, 8.px, 0, 40.px) }
        layout.entries.indices.forEach { i -> Row(i).also { rows.add(it); list.addView(it.view, LayoutParams(WIDTH.px, ROW_HEIGHT.px).apply { bottomMargin = 4.px }) } }
        list.addView(resetRow(), LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 28.px })
        addView(FocusScrollView(context).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(list) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        // The menu sheet as BrowserMenu draws it, with the row in focus lit as if it were the focused button.
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.p.sheet)
                cornerRadii = FloatArray(8) { if (it < 4) 16.px.toFloat() else 0f }
            }
            setPadding(72.px, 24.px, 72.px, 36.px)
            clipChildren = false
            addView(Ui.text(context, 24, Ui.p.textWeak).apply { setText(R.string.menu_preview) })
            addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, 96.px).apply { topMargin = 12.px })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refresh()
        post { rows.firstOrNull()?.main?.requestFocus() }
    }

    private inner class Row(val index: Int) {
        val icon = ImageView(context)
        val label = Ui.text(context, 32, Ui.p.text)
        /** ▲▼ shown while the row is carried. */
        val arrows = ImageView(context).apply { setImageResource(R.drawable.ic_swap_vert) }
        val main = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(32.px, 0, 24.px, 0)
            addView(icon, LayoutParams(44.px, 44.px))
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 32.px })
            addView(arrows, LayoutParams(44.px, 44.px))
            setOnClickListener { if (moving < 0) pickUp(index) }
            setOnKeyListener { _, code, ev -> carryKey(index, code, ev) }
            setOnFocusChangeListener { _, has -> if (has) focused = index; bind(); if (has && moving < 0) refresh(rowsToo = false) }
        }
        val state = Ui.text(context, 26, Ui.p.textMid)
        val toggle = Toggle(context, true)
        val switch = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            isClickable = true
            setPadding(24.px, 0, 28.px, 0)
            background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 16)
            addView(state)
            addView(toggle)
            setOnClickListener { if (moving < 0) showOrHide(index) }
            setOnFocusChangeListener { _, has -> if (has) focused = index; bind(); if (has && moving < 0) refresh(rowsToo = false) }
        }
        val view = LinearLayout(context).apply {
            clipChildren = false
            addView(main, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(switch, LayoutParams(SWITCH_WIDTH.px, LayoutParams.MATCH_PARENT).apply { marginStart = 8.px })
        }

        fun bind() {
            val e = layout.entries[index]
            val carried = index == moving
            icon.setImageResource(e.item.icon)
            label.setText(e.item.label)
            main.background = if (carried) Ui.rounded(Ui.p.focus, 16, Ui.BRAND, 4) else Ui.fillSelector(Ui.p.fill, Ui.p.focus, 16)
            val lit = main.isFocused || carried
            label.setTextColor(if (lit) Ui.p.onFocus else Ui.p.text)
            icon.setColorFilter(if (lit) Ui.p.onFocus else Ui.p.text)
            arrows.setColorFilter(Ui.BRAND)
            arrows.visibility = if (carried) VISIBLE else INVISIBLE
            // A hidden button keeps its place, greyed.
            val dim = if (e.shown) 1f else 0.45f
            icon.alpha = dim
            label.alpha = dim
            state.setText(when { e.item.locked -> R.string.menu_always_shown; e.shown -> R.string.menu_shown; else -> R.string.menu_hidden })
            state.setTextColor(if (switch.isFocused) Ui.p.onFocusWeak else Ui.p.textMid)
            toggle.on = e.shown
            toggle.visibility = if (e.item.locked) GONE else VISIBLE
            switch.isFocusable = !e.item.locked
            switch.alpha = if (e.item.locked) 0.6f else 1f
            view.alpha = if (moving >= 0 && !carried) 0.45f else 1f
            view.translationZ = if (carried) 8f else 0f
            view.scaleX = if (carried) 1.02f else 1f
            view.scaleY = view.scaleX
        }

        /** Slides the row's content in from [rowsAway] rows away, so a swap reads as the buttons changing places. */
        fun slideFrom(rowsAway: Int) {
            view.translationY = rowsAway * (ROW_HEIGHT + 4).px.toFloat()
            view.animate().translationY(0f).setDuration(140).start()
        }
    }

    private fun resetRow() = Ui.button(context, context.getString(R.string.menu_reset)) {
        Ui.dialog(context).setMessage(R.string.menu_reset_confirm)
            .setPositiveButton(R.string.menu_reset_do) { _, _ ->
                save(MenuLayout.DEFAULT)
                refresh()
                Toast.makeText(context, R.string.menu_reset_done, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }.apply {
        setOnFocusChangeListener { v, has ->
            (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid)
            if (has) { focused = -1; refresh(rowsToo = false) }
        }
    }

    private fun pickUp(index: Int) {
        moving = index
        movingFrom = index
        refresh()
    }

    /** Keys while a row is carried: ▲▼ move it, OK puts it down, Back puts it back; nothing else gets through. */
    private fun carryKey(index: Int, code: Int, ev: KeyEvent): Boolean {
        // A row without a switch: ▶ would otherwise land on the switch of the row above.
        if (moving != index) return code == KeyEvent.KEYCODE_DPAD_RIGHT && layout.entries[index].item.locked
        val down = ev.action == KeyEvent.ACTION_DOWN
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                if (down) step(if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_CHANNEL_UP) -1 else 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (down && ev.repeatCount == 0) putDown()
            // On release, so the activity never sees a Back to close the page with.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> if (!down) putBack()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> Unit
            else -> return false
        }
        return true
    }

    private fun step(by: Int) {
        val from = moving
        val to = from + by
        if (to !in layout.entries.indices) {
            // At the top or bottom already: a small nudge says so.
            rows[from].view.animate().translationY(by * 10.px.toFloat()).setDuration(70).withEndAction {
                rows[from].view.animate().translationY(0f).setDuration(110).start()
            }.start()
            return
        }
        layout = layout.move(from, to)
        moving = to
        rows[to].main.requestFocus()
        rows[to].bind(); rows[from].bind()
        rows[to].slideFrom(-by)
        rows[from].slideFrom(by)
        refresh(rowsToo = false, slid = true)
    }

    private fun putDown() {
        moving = -1
        save(layout)
        refresh()
    }

    private fun putBack() {
        val back = movingFrom
        layout = layout.move(moving, back)
        moving = -1
        rows[back].main.requestFocus()
        refresh()
    }

    private fun showOrHide(index: Int) {
        save(layout.show(index, !layout.entries[index].shown))
        refresh()
    }

    private fun save(l: MenuLayout) {
        layout = l
        prefs.menuLayout = l
    }

    /** Brings the rows, the hint and the preview up to date; [slid] moves the carried button in the preview too. */
    private fun refresh(rowsToo: Boolean = true, slid: Boolean = false) {
        if (rowsToo) rows.forEach { it.bind() }
        val row = rows.getOrNull(focused)
        hint.setText(when {
            moving >= 0 -> R.string.menu_hint_moving
            row == null -> R.string.menu_hint_reset
            row?.switch?.isFocused == true -> R.string.menu_hint_switch
            else -> R.string.menu_hint
        })
        val lit = layout.entries.getOrNull(if (moving >= 0) moving else focused)?.item
        val oldX = previewViews[lit]?.firstOrNull()?.left
        preview.removeAllViews()
        previewViews.clear()
        for (item in layout.shown) {
            val views = if (item == MenuLayout.Item.ZOOM) listOf(previewIcon(R.drawable.ic_zoom_out, item == lit),
                Ui.text(context, 22, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) },
                previewIcon(R.drawable.ic_zoom_in, item == lit))
            else listOf(previewIcon(item.icon, item == lit))
            views.forEach { preview.addView(it, LayoutParams(PREVIEW_ICON.px, PREVIEW_ICON.px).apply { marginEnd = 20.px }) }
            previewViews[item] = views
        }
        if (slid && oldX != null) preview.post {
            val views = previewViews[lit] ?: return@post
            val dx = (oldX - views.first().left).toFloat()
            views.forEach { it.translationX = dx; it.animate().translationX(0f).setDuration(140).start() }
        }
    }

    private fun previewIcon(icon: Int, lit: Boolean) = FrameLayout(context).apply {
        background = Ui.rounded(if (lit) Ui.p.focus else Color.TRANSPARENT, PREVIEW_ICON / 2)
        addView(ImageView(context).apply { setImageResource(icon); setColorFilter(if (lit) Ui.p.onFocus else Ui.p.text) },
            FrameLayout.LayoutParams(36.px, 36.px, Gravity.CENTER))
        scaleX = if (lit) 1.12f else 1f
        scaleY = scaleX
    }

    private companion object {
        const val WIDTH = 1400
        const val ROW_HEIGHT = 96
        const val SWITCH_WIDTH = 300
        const val PREVIEW_ICON = 64
    }
}
