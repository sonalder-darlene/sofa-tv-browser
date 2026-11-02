package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.LinearLayout

/** 80x44 pill switch at the end of a settings row: blue when on. */
@SuppressLint("ViewConstructor")
class Toggle(context: Context, initial: Boolean) : View(context) {
    var on = initial
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { layoutParams = LinearLayout.LayoutParams(80.px, 44.px).apply { marginStart = 24.px } }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        paint.color = if (on) Ui.SWITCH_ON else Ui.p.toggleOff
        c.drawRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, paint)
        paint.color = Ui.p.knob
        val r = h / 2 - 5.px
        c.drawCircle(if (on) width - h / 2 else h / 2, h / 2, r, paint)
    }
}
