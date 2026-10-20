package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R

/**
 * Find in page: a query field (typed, or spoken with the mic), "3 of 12", previous, next and close. The WebView
 * highlights the matches itself; the bar only drives it.
 */
@SuppressLint("ViewConstructor")
class FindBar(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun find(query: String)
        fun findNext(forward: Boolean)
        fun findByVoice()
        fun closeFind()
    }

    val field: EditText
    private val count: TextView
    private val mic: ImageView
    private val next: ImageView
    private val search = Runnable { actions.find(field.text.toString()) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        background = Ui.rounded(Ui.p.sheet, 24)
        setPadding(32.px, 12.px, 20.px, 12.px)

        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_find_in_page)
            setColorFilter(Ui.p.textMid)
        }, LayoutParams(40.px, 40.px).apply { marginEnd = 20.px })
        field = EditText(context).apply {
            hint = context.getString(R.string.find_hint)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setTextColor(Ui.p.text)
            setHintTextColor(Ui.p.textHint)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30.px.toFloat())
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    // A short pause so each keystroke on a TV keyboard doesn't restart the search.
                    removeCallbacks(search)
                    postDelayed(search, 250)
                }
            })
            // Focus stays here: moving it to Next now would let the Enter key's release press Next too.
            setOnEditorActionListener { _, _, _ ->
                removeCallbacks(search)
                actions.find(text.toString())
                hideKeyboard()
                true
            }
        }
        addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        count = Ui.text(context, 26, Ui.p.textMid).apply { setPadding(16.px, 0, 16.px, 0) }
        addView(count)
        mic = button(R.drawable.ic_mic, R.string.voice_search) { actions.findByVoice() }
        addView(mic)
        addView(button(R.drawable.ic_keyboard_arrow_up, R.string.find_previous) { actions.findNext(false) })
        next = button(R.drawable.ic_keyboard_arrow_down, R.string.find_next) { actions.findNext(true) }
        addView(next)
        addView(button(R.drawable.ic_close, R.string.find_close) { actions.closeFind() })
    }

    private fun button(icon: Int, label: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        contentDescription = context.getString(label)
        isFocusable = true
        isClickable = true
        setPadding(14.px, 14.px, 14.px, 14.px)
        background = Ui.fillSelector(android.graphics.Color.TRANSPARENT, Ui.p.focus, 36)
        setColorFilter(Ui.p.text)
        setOnFocusChangeListener { _, has -> setColorFilter(if (has) Ui.p.onFocus else Ui.p.text) }
        setOnClickListener { onClick() }
        layoutParams = LayoutParams(72.px, 72.px).apply { marginStart = 8.px }
    }

    val showing get() = visibility == VISIBLE

    /** Shows the bar with the keyboard up; [voice] shows the mic. */
    fun open(voice: Boolean) {
        mic.visibility = if (voice) VISIBLE else GONE
        visibility = VISIBLE
        count.text = ""
        field.requestFocus()
        field.selectAll()
        post { context.getSystemService(InputMethodManager::class.java).showSoftInput(field, 0) }
    }

    fun close() {
        removeCallbacks(search)
        hideKeyboard()
        visibility = GONE
    }

    /** Shows a spoken query and searches for it. */
    fun setQuery(text: String) {
        field.setText(text)
        field.setSelection(text.length)
        removeCallbacks(search)
        actions.find(text)
        next.requestFocus()
    }

    fun setListening(active: Boolean) {
        field.hint = context.getString(if (active) R.string.voice_listening else R.string.find_hint)
    }

    /** [active] counts from 0, as WebView reports it. */
    fun setMatches(active: Int, total: Int) {
        count.text = when {
            field.text.isEmpty() -> ""
            total == 0 -> context.getString(R.string.find_none)
            else -> context.getString(R.string.find_count, active + 1, total)
        }
    }

    private fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(field.windowToken, 0)
    }

    // Up and Down from the field reach the buttons too, so the D-pad alone can step through matches.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && field.hasFocus() &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)) {
            next.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
