package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * A round icon button with its label shown underneath only while focused (BrowsHere's top bar and menu style).
 * The button has a fixed width; the label may spill sideways over its neighbours, so hidden labels never make
 * the spacing uneven. On/off state is shown by the icon itself (filled when on, outlined when off).
 */
@SuppressLint("ViewConstructor")
class LabeledIconButton(context: Context, icon: Int, label: String, onClick: () -> Unit) : FrameLayout(context) {
    private val circle = FrameLayout(context)
    private val image = ImageView(context)
    private val text: TextView = Ui.text(context, 24, Ui.p.text)

    /** Called when the button gains focus. */
    var onFocused: (() -> Unit)? = null

    init {
        isFocusable = true
        isClickable = true
        clipChildren = false
        clipToPadding = false
        contentDescription = label
        // The label under the icon only shows the name the button already has for a screen reader.
        A11y.role(this, A11y.BUTTON)
        A11y.skip(text)
        setOnClickListener { if (isEnabled) onClick() }

        circle.background = Ui.fillSelector(Color.TRANSPARENT, Ui.p.focus, 40)
        circle.isDuplicateParentStateEnabled = true
        image.setImageResource(icon)
        circle.addView(image, LayoutParams(48.px, 48.px, Gravity.CENTER))
        addView(circle, LayoutParams(80.px, 80.px, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        text.text = label
        text.gravity = Gravity.CENTER_HORIZONTAL
        text.maxLines = 1
        text.visibility = INVISIBLE
        addView(text, LayoutParams(LABEL_WIDTH.px, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = 92.px
        })
        layoutParams = LayoutParams(WIDTH.px, 128.px)
        tint(false)
        setOnFocusChangeListener { _, has -> focused(has) }
    }

    fun set(icon: Int, label: String) {
        image.setImageResource(icon)
        text.text = label
        contentDescription = label
        tint(hasFocus())
        if (hasFocus()) text.post(::keepOnScreen)
    }

    fun setEnabledLook(enabled: Boolean) {
        isEnabled = enabled
        isFocusable = enabled
        alpha = if (enabled) 1f else 0.35f
    }

    private fun focused(has: Boolean) {
        if (has) onFocused?.invoke()
        text.visibility = if (has) VISIBLE else INVISIBLE
        if (has) text.post(::keepOnScreen)
        tint(has)
        Ui.animateScale(circle, if (has) 1.12f else 1f, has)
    }

    /** A button at the row's edge shifts its label inwards, so a long one (a state, a site's choice) shows whole. */
    private fun keepOnScreen() {
        text.translationX = 0f
        val shown = text.paint.measureText(text.text.toString()).coerceAtMost(text.width.toFloat())
        val at = IntArray(2).also(text::getLocationOnScreen)
        val left = at[0] + (text.width - shown) / 2
        // What the row shows of itself (it, or a scrolling parent, cuts off whatever lies beyond), else the screen.
        val area = android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, 0)
        (parent as? android.view.View)?.getGlobalVisibleRect(area)
        val edge = EDGE.px.toFloat()
        text.translationX = when {
            left + shown > area.right - edge -> area.right - edge - left - shown
            left < area.left + edge -> area.left + edge - left
            else -> 0f
        }
    }

    private fun tint(focused: Boolean) = image.setColorFilter(if (focused) Ui.p.onFocus else Ui.p.text)

    companion object {
        const val WIDTH = 104
        private const val LABEL_WIDTH = 520
        /** The least space between a label and the edge of its row. */
        private const val EDGE = 8
    }
}
