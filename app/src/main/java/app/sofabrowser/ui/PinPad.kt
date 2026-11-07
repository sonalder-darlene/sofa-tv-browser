package app.sofabrowser.ui

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.PrefsMap
import app.sofabrowser.R
import app.sofabrowser.lock.Pin

/** The parental PIN of this install; see [Pin]. */
fun pinOf(context: Context) = Pin(PrefsMap(context.getSharedPreferences("pin", Context.MODE_PRIVATE)))

/**
 * Whether the PIN has been entered for the screen in use. Settings and History stay open after it until the browser
 * is back on screen, Sofa leaves the screen, or ten minutes pass; anything else asks each time.
 */
object PinGate {
    private const val OPEN_MS = 10 * 60_000L
    private var until = 0L
    val isOpen get() = SystemClock.elapsedRealtime() < until
    fun open() { until = SystemClock.elapsedRealtime() + OPEN_MS }
    fun close() { until = 0L }
}

/**
 * Four digit wheels for the remote, as on Android TV's own PIN screens: up and down turn a digit, OK or right goes
 * to the next, left goes back, number keys type straight in; OK on the last digit checks. Digits show only on the
 * wheel in focus, the others as dots. A wrong PIN shakes the row; after too many, the wheels rest until the pause
 * is over. With [choosing], the PIN is typed twice and saved.
 */
class PinPad private constructor(
    private val activity: Activity,
    private val choosing: Boolean,
    private val title: String,
    private val done: (Boolean) -> Unit,
) : Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar) {

    private val pin = pinOf(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val cells = List(Pin.LENGTH) { Wheel(it) }
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var row: LinearLayout
    /** While choosing: the PIN typed the first time. */
    private var first: String? = null
    private var resting = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Ui.p
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.bgTop, p.bgBottom)).apply {
                cornerRadius = 40.px.toFloat()
                setStroke(2.px, p.fillFocused)
            }
            setPadding(96.px, 64.px, 96.px, 56.px)
            clipChildren = false
            clipToPadding = false
        }
        card.addView(FrameLayout(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.BRAND and 0x00FFFFFF or 0x29000000) }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_lock); setColorFilter(Ui.BRAND); A11y.skip(this) },
                FrameLayout.LayoutParams(52.px, 52.px, Gravity.CENTER))
        }, LinearLayout.LayoutParams(104.px, 104.px))
        titleView = Ui.text(context, 44, p.text, bold = true).apply { gravity = Gravity.CENTER; A11y.heading(this) }
        card.addView(titleView, wide().apply { topMargin = 32.px })
        subView = Ui.text(context, 26, p.textMid).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f); A11y.announced(this) }
        card.addView(subView, wide().apply { topMargin = 14.px })
        row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        cells.forEach { row.addView(it, LinearLayout.LayoutParams(136.px, 184.px).apply { marginStart = 14.px; marginEnd = 14.px }) }
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 52.px })
        statusView = Ui.text(context, 26, p.textMid).apply { gravity = Gravity.CENTER; minLines = 1; A11y.announced(this) }
        card.addView(statusView, wide().apply { topMargin = 40.px })
        hintView = Ui.text(context, 22, p.textWeak).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.15f) }
        card.addView(hintView, wide().apply { topMargin = 16.px })
        val root = FrameLayout(context).apply {
            setBackgroundColor(SCRIM)
            clipChildren = false
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        setContentView(root)
        root.alpha = 0f
        root.animate().alpha(1f).setDuration(180).start()
        setOnCancelListener { if (!finished) done(false) }
        setOnDismissListener { handler.removeCallbacksAndMessages(null) }

        stage()
        hintView.text = context.getString(R.string.pin_hint)
        A11y.skip(hintView)
        card.alpha = 0f
        card.scaleX = 0.94f
        card.scaleY = 0.94f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        if (!choosing) pin.waitLeft().takeIf { it > 0 }?.let(::rest)
        cells[0].requestFocus()
    }

    private fun wide() = LinearLayout.LayoutParams(720.px, ViewGroup.LayoutParams.WRAP_CONTENT)

    /** Title and line under it for where the entry is: checking, choosing, or choosing again. */
    private fun stage() {
        titleView.text = when {
            !choosing -> title
            first == null -> context.getString(R.string.pin_choose_title)
            else -> context.getString(R.string.pin_again_title)
        }
        subView.text = context.getString(when {
            !choosing -> R.string.pin_enter
            first == null -> R.string.pin_choose_sub
            else -> R.string.pin_again_sub
        })
    }

    private fun status(text: String, error: Boolean = false) {
        statusView.text = text
        statusView.setTextColor(if (error) ERROR else Ui.p.textMid)
    }

    private fun clear() {
        cells.forEach { it.reset() }
        cells[0].requestFocus()
    }

    private fun submit() {
        val code = cells.joinToString("") { it.value.toString() }
        if (choosing) {
            when (first) {
                null -> { first = code; stage(); status(""); clear() }
                code -> { pin.set(code); success() }
                else -> { first = null; stage(); shake(); status(context.getString(R.string.pin_mismatch), error = true); clear() }
            }
            return
        }
        when (val r = pin.check(code)) {
            Pin.Result.Right -> success()
            is Pin.Result.Wrong -> {
                shake()
                clear()
                if (r.fails >= 3) hintView.text = context.getString(R.string.pin_forgot)
                when {
                    r.wait > 0 -> rest(r.wait)
                    r.triesLeft <= 2 -> status(context.resources.getQuantityString(R.plurals.pin_wrong_tries, r.triesLeft, r.triesLeft), error = true)
                    else -> status(context.getString(R.string.pin_wrong), error = true)
                }
            }
            is Pin.Result.Wait -> rest(r.wait)
        }
    }

    private fun success() {
        finished = true
        cells.forEach { it.accept() }
        handler.postDelayed({ dismiss(); done(true) }, 240)
    }

    /** A pause after too many wrong tries: the wheels dim and ignore keys, a countdown says how long. */
    private fun rest(wait: Long) {
        resting = true
        cells.forEach { it.animate().alpha(0.35f).setDuration(200).start() }
        val tick = object : Runnable {
            override fun run() {
                val left = pin.waitLeft()
                if (left <= 0) {
                    resting = false
                    cells.forEach { it.animate().alpha(1f).setDuration(200).start() }
                    status(context.getString(R.string.pin_try_again))
                    return
                }
                val s = (left + 999) / 1000
                status(context.getString(R.string.pin_resting, "%d:%02d".format(s / 60, s % 60)), error = true)
                handler.postDelayed(this, 1000)
            }
        }
        handler.removeCallbacksAndMessages(null)
        if (wait > 0) tick.run()
    }

    private fun shake() {
        ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, 26f.px, -26f.px, 18f.px, -18f.px, 8f.px, 0f).setDuration(420).start()
    }

    private val Float.px get() = this * Ui.scale

    /** One digit: shown with its neighbours while focused, as a dot once entered, as a dash before. */
    private inner class Wheel(private val index: Int) : LinearLayout(activity) {
        var value = 0
            private set
        private var entered = false
        private val above = Ui.text(context, 28, Ui.p.textWeak).apply { gravity = Gravity.CENTER }
        private val digit = Ui.text(context, 80, Ui.p.text, bold = true).apply { gravity = Gravity.CENTER }
        private val below = Ui.text(context, 28, Ui.p.textWeak).apply { gravity = Gravity.CENTER }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            isFocusable = true
            background = Ui.fillSelector(Ui.p.card, Ui.p.focus, 28)
            addView(above, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(digit, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = 6.px; bottomMargin = 6.px })
            addView(below, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            Ui.focusScale(this, 1.08f) { render() }
            setOnKeyListener { _, code, e -> key(code, e) }
            // One name per wheel and its digit as its state, said as it turns (aloud, as Android's own PIN pad does
            // with a screen reader: there's no other way to know what is set); the digits drawn around it stay silent.
            listOf(above, digit, below).forEach(A11y::skip)
            // Focus never reaches the title, and a TV's screen reader doesn't say this window's: the first wheel's hint says it.
            A11y.role(this, A11y.SEEK_BAR, hint = {
                val how = context.getString(R.string.a11y_pin_hint)
                if (index == 0) "${titleView.text}. ${subView.text} $how" else how
            })
            render()
        }

        fun reset() { value = 0; entered = false; render() }

        /** The right PIN: every wheel turns blue for a moment. */
        fun accept() {
            background = Ui.rounded(Ui.SWITCH_ON, 28)
            digit.setTextColor(Color.WHITE)
            digit.text = DOT
            above.text = ""
            below.text = ""
        }

        private fun render() {
            val focused = hasFocus()
            above.text = if (focused) ((value + 1) % 10).toString() else ""
            below.text = if (focused) ((value + 9) % 10).toString() else ""
            digit.text = when { focused -> value.toString(); entered -> DOT; else -> DASH }
            digit.setTextColor(when { focused -> Ui.p.onFocus; entered -> Ui.p.text; else -> Ui.p.textHint })
            val weak = if (focused) Ui.p.onFocusWeak else Ui.p.textWeak
            above.setTextColor(weak)
            below.setTextColor(weak)
            A11y.state(this, context.getString(R.string.a11y_pin_digit, index + 1, Pin.LENGTH), if (focused) value.toString() else null)
        }

        /** [dir] 1 turns up, -1 down, 0 a typed digit: the new digit slides in from that side. */
        private fun set(v: Int, dir: Int) {
            value = v
            render()
            // A typed digit moves on to the next wheel at once, which is said instead.
            if (dir != 0) A11y.changed(this)
            digit.translationY = dir * 34f.px
            digit.alpha = 0.3f
            digit.animate().translationY(0f).alpha(1f).setDuration(160).start()
        }

        private fun next() {
            entered = true
            if (index < cells.lastIndex) cells[index + 1].requestFocus() else { render(); submit() }
        }

        private fun key(code: Int, e: KeyEvent): Boolean {
            if (code == KeyEvent.KEYCODE_BACK) return false
            if (resting || finished) return true
            val down = e.action == KeyEvent.ACTION_DOWN
            val typed = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            when {
                typed >= 0 -> if (down) { set(typed, 0); next() }
                code == KeyEvent.KEYCODE_DPAD_UP -> if (down) set((value + 1) % 10, 1)
                code == KeyEvent.KEYCODE_DPAD_DOWN -> if (down) set((value + 9) % 10, -1)
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DEL ->
                    if (down && index > 0) cells[index - 1].requestFocus()
                code == KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && index < cells.lastIndex) next()
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    code == KeyEvent.KEYCODE_BUTTON_A -> if (down && e.repeatCount == 0) next()
                else -> return false
            }
            return true
        }
    }

    companion object {
        private const val DOT = "●"
        private const val DASH = "–"
        private const val ERROR = 0xFFFF7A6B.toInt()
        private const val SCRIM = 0xE0000000.toInt()

        /**
         * Runs [action] at once when no PIN is set or it was already entered for this screen; otherwise asks first
         * ([title] says what for). With [stay] the PIN then covers the rest of this screen (Settings, History);
         * without, only this action. [cancelled] runs when the PIN is not entered.
         */
        fun guard(activity: Activity, title: Int, stay: Boolean = false, cancelled: () -> Unit = {}, action: () -> Unit) {
            if (!pinOf(activity).isSet || PinGate.isOpen) { action(); return }
            ask(activity, title) { ok ->
                if (!ok) return@ask cancelled()
                if (stay) PinGate.open()
                action()
            }
        }

        /** Asks for the PIN; [done] gets whether it was entered. */
        fun ask(activity: Activity, title: Int, done: (Boolean) -> Unit) =
            PinPad(activity, choosing = false, title = activity.getString(title), done = done).show()

        /** Asks for a new PIN twice and saves it; [done] gets whether it was saved. */
        fun choose(activity: Activity, done: (Boolean) -> Unit) =
            PinPad(activity, choosing = true, title = "", done = done).show()
    }
}
