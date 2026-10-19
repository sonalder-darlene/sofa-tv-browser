package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.widget.ScrollView

/**
 * A vertical scroller that keeps the focused row [margin] away from its top and bottom edges. A plain ScrollView
 * stops as soon as the row fits, which leaves a focus-scaled row touching the screen edge and cut off.
 */
@SuppressLint("ViewConstructor")
class FocusScrollView(context: Context, private val margin: Int = 120.px) : ScrollView(context) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect).apply { inset(0, -margin) })
}
