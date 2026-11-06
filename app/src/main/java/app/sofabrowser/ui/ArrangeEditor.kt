package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.R

/**
 * A Settings page that puts things in order and shows or hides them (Menu buttons, Start page): a row for each, in
 * one or more [Section]s, and at the bottom a [Preview] of the result. OK picks a row up, ▲▼ carry it within its
 * section and OK puts it down (Back puts it back where it was); ▶ reaches the row's switch, which shows or hides it.
 * Every change is saved at once.
 */
@SuppressLint("ViewConstructor")
class ArrangeEditor(
    context: Context,
    private val texts: Texts,
    private val sections: List<Section<*>>,
    private val preview: Preview,
    /** Puts everything back as it was by default, in Settings; the sections are read again after it. */
    private val resetAll: () -> Unit,
) : LinearLayout(context) {

    /** The page's words: its title, the hint line for a row, its switch and the Reset button, and the Reset texts. */
    class Texts(val title: Int, val hint: Int, val hintSwitch: Int, val hintReset: Int,
                val reset: Int, val resetConfirm: Int, val resetDone: Int)

    /** One list of the page: [load] reads it from Settings and [save] writes it; [extra] is a row of its own after it. */
    class Section<T : Arrangement.Item>(val title: Int?, private val load: () -> Arrangement<T>,
                                        private val save: (Arrangement<T>) -> Unit, val extra: Extra? = null) {
        var value = load()
            private set

        fun reload() { value = load() }
        fun move(from: Int, to: Int) { value = value.move(from, to) }
        fun keep() { save(value); reload() }
        fun toggle(index: Int) { value = value.show(index, !value.entries[index].shown); keep() }
    }

    /** A row that opens a choice, such as how many rows of bookmarks; [pick] calls its argument once the value changed. */
    class Extra(val label: Int, val hint: Int, val value: () -> String, val pick: (changed: () -> Unit) -> Unit)

    /** The result as it will look; [show] draws it again with [lit] (the row in focus or carried) lit, [slid] after a move. */
    interface Preview {
        val view: View
        fun show(lit: Arrangement.Item?, slid: Boolean)
    }

    private val rows = ArrayList<Row>()
    private val extraValues = ArrayList<Pair<Extra, TextView>>()
    private val hint = Ui.text(context, 28, Ui.p.textMid)
    /** The row being carried, and where it was picked up. */
    private var moving: Row? = null
    private var movingFrom = -1
    /** The row in focus (either of its parts), for the hint and the preview; null on an extra row or Reset. */
    private var focused: Row? = null
    private var focusedHint = texts.hint

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(Ui.text(context, 56, Ui.p.textMid, bold = true).apply { setText(texts.title) },
            LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 64.px })
        addView(hint, LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 16.px; bottomMargin = 24.px })

        val list = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false; setPadding(0, 8.px, 0, 40.px) }
        for (section in sections) {
            section.title?.let { title ->
                list.addView(Ui.text(context, 28, Ui.p.textWeak).apply { setText(title); setPadding(32.px, 28.px, 0, 14.px) },
                    LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT))
            }
            section.value.entries.indices.forEach { i ->
                Row(section, i).also { rows.add(it); list.addView(it.view, LayoutParams(WIDTH.px, ROW_HEIGHT.px).apply { bottomMargin = 4.px }) }
            }
            section.extra?.let { list.addView(extraRow(it), LayoutParams(WIDTH.px, ROW_HEIGHT.px).apply { topMargin = 12.px; bottomMargin = 4.px }) }
        }
        list.addView(resetRow(), LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 28.px })
        addView(FocusScrollView(context).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(list) },
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(preview.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        refresh()
        post { rows.firstOrNull()?.main?.requestFocus() }
    }

    private inner class Row(val section: Section<*>, val index: Int) {
        val entry get() = section.value.entries[index]
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
            setOnClickListener { if (moving == null) pickUp(this@Row) }
            setOnKeyListener { _, code, ev -> carryKey(this@Row, code, ev) }
            setOnFocusChangeListener { _, has -> focusChanged(has) }
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
            setOnClickListener { if (moving == null) showOrHide(this@Row) }
            setOnFocusChangeListener { _, has -> focusChanged(has) }
        }
        val view = LinearLayout(context).apply {
            clipChildren = false
            addView(main, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            addView(switch, LayoutParams(SWITCH_WIDTH.px, LayoutParams.MATCH_PARENT).apply { marginStart = 8.px })
        }

        private fun focusChanged(has: Boolean) {
            if (has) focused = this
            bind()
            if (has && moving == null) refresh(rowsToo = false)
        }

        fun bind() {
            val e = entry
            val carried = moving === this
            icon.setImageResource(e.item.icon)
            label.setText(e.item.label)
            main.background = if (carried) Ui.rounded(Ui.p.focus, 16, Ui.BRAND, 4) else Ui.fillSelector(Ui.p.fill, Ui.p.focus, 16)
            val lit = main.isFocused || carried
            label.setTextColor(if (lit) Ui.p.onFocus else Ui.p.text)
            icon.setColorFilter(if (lit) Ui.p.onFocus else Ui.p.text)
            arrows.setColorFilter(Ui.BRAND)
            arrows.visibility = if (carried) VISIBLE else INVISIBLE
            // A hidden one keeps its place, greyed.
            val dim = if (e.shown) 1f else 0.45f
            icon.alpha = dim
            label.alpha = dim
            state.setText(when { e.item.locked -> R.string.menu_always_shown; e.shown -> R.string.menu_shown; else -> R.string.menu_hidden })
            state.setTextColor(if (switch.isFocused) Ui.p.onFocusWeak else Ui.p.textMid)
            toggle.on = e.shown
            toggle.visibility = if (e.item.locked) GONE else VISIBLE
            switch.isFocusable = !e.item.locked
            switch.alpha = if (e.item.locked) 0.6f else 1f
            view.alpha = if (moving != null && !carried) 0.45f else 1f
            view.translationZ = if (carried) 8f else 0f
            view.scaleX = if (carried) 1.02f else 1f
            view.scaleY = view.scaleX
        }

        /** Slides the row's content in from [rowsAway] rows away, so a swap reads as the two changing places. */
        fun slideFrom(rowsAway: Int) {
            view.translationY = rowsAway * (ROW_HEIGHT + 4).px.toFloat()
            view.animate().translationY(0f).setDuration(140).start()
        }
    }

    private fun rowsOf(section: Section<*>) = rows.filter { it.section === section }

    private fun extraRow(extra: Extra): View {
        val label = Ui.text(context, 32, Ui.p.text).apply { setText(extra.label) }
        val value = Ui.text(context, 28, Ui.p.textMid).apply { text = extra.value() }
        extraValues.add(extra to value)
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(108.px, 0, 32.px, 0)
            background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 16)
            addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
            setOnClickListener { extra.pick { value.text = extra.value(); refresh(rowsToo = false) } }
            setOnFocusChangeListener { _, has ->
                label.setTextColor(if (has) Ui.p.onFocus else Ui.p.text)
                value.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textMid)
                if (has) { focused = null; focusedHint = extra.hint; refresh(rowsToo = false) }
            }
        }
    }

    private fun resetRow() = Ui.button(context, context.getString(texts.reset)) {
        Ui.dialog(context).setMessage(texts.resetConfirm)
            .setPositiveButton(R.string.menu_reset_do) { _, _ ->
                resetAll()
                sections.forEach { it.reload() }
                extraValues.forEach { (extra, view) -> view.text = extra.value() }
                refresh()
                Toast.makeText(context, texts.resetDone, Toast.LENGTH_SHORT).show()
            }.setNegativeButton(R.string.cancel, null).show()
    }.apply {
        setOnFocusChangeListener { v, has ->
            (v as TextView).setTextColor(if (has) Ui.p.onFocus else Ui.p.textMid)
            if (has) { focused = null; focusedHint = texts.hintReset; refresh(rowsToo = false) }
        }
    }

    private fun pickUp(row: Row) {
        moving = row
        movingFrom = row.index
        refresh()
    }

    /** Keys while a row is carried: ▲▼ move it, OK puts it down, Back puts it back; nothing else gets through. */
    private fun carryKey(row: Row, code: Int, ev: KeyEvent): Boolean {
        // A row without a switch: ▶ would otherwise land on the switch of the row above.
        if (moving !== row) return code == KeyEvent.KEYCODE_DPAD_RIGHT && row.entry.item.locked
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
        val row = moving ?: return
        val list = rowsOf(row.section)
        val from = row.index
        val to = from + by
        if (to !in list.indices) {
            // At the top or bottom of its list already: a small nudge says so.
            row.view.animate().translationY(by * 10.px.toFloat()).setDuration(70).withEndAction {
                row.view.animate().translationY(0f).setDuration(110).start()
            }.start()
            return
        }
        row.section.move(from, to)
        moving = list[to]
        list[to].main.requestFocus()
        list[to].bind(); row.bind()
        list[to].slideFrom(-by)
        row.slideFrom(by)
        refresh(rowsToo = false, slid = true)
    }

    private fun putDown() {
        val row = moving ?: return
        moving = null
        row.section.keep()
        refresh()
    }

    private fun putBack() {
        val row = moving ?: return
        val back = rowsOf(row.section)[movingFrom]
        row.section.move(row.index, movingFrom)
        moving = null
        back.main.requestFocus()
        refresh()
    }

    private fun showOrHide(row: Row) {
        row.section.toggle(row.index)
        refresh()
    }

    /** Brings the rows, the hint and the preview up to date; [slid] moves the carried one in the preview too. */
    private fun refresh(rowsToo: Boolean = true, slid: Boolean = false) {
        if (rowsToo) rows.forEach { it.bind() }
        val row = focused
        hint.setText(when {
            moving != null -> R.string.menu_hint_moving
            row == null -> focusedHint
            row.switch.isFocused -> texts.hintSwitch
            else -> texts.hint
        })
        preview.show((moving ?: row)?.entry?.item, slid)
    }

    private companion object {
        const val WIDTH = 1400
        const val ROW_HEIGHT = 96
        const val SWITCH_WIDTH = 300
    }
}
