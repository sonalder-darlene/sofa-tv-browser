package app.sofabrowser.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import app.sofabrowser.Prefs
import app.sofabrowser.R

/** Settings > Menu buttons: the browse menu's buttons in an [ArrangeEditor], saved in [Prefs.menuLayout]. */
object MenuEditor {

    fun create(context: Context, prefs: Prefs): ArrangeEditor {
        val section = ArrangeEditor.Section(null, { prefs.menuLayout }, { prefs.menuLayout = it })
        return ArrangeEditor(context,
            ArrangeEditor.Texts(R.string.menu_buttons, R.string.menu_hint, R.string.menu_hint_switch, R.string.menu_hint_reset,
                R.string.menu_reset, R.string.menu_reset_confirm, R.string.menu_reset_done),
            listOf(section), Sheet(context) { section.value }) { prefs.menuLayout = MenuLayout.DEFAULT }
    }

    /** The menu sheet as BrowserMenu draws it, with the row in focus lit as if it were the focused button. */
    private class Sheet(private val context: Context, private val layout: () -> Arrangement<MenuLayout.Item>) : ArrangeEditor.Preview {
        private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        private val views = HashMap<MenuLayout.Item, List<View>>()

        override val view: View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Ui.p.sheet)
                cornerRadii = FloatArray(8) { if (it < 4) 16.px.toFloat() else 0f }
            }
            setPadding(72.px, 24.px, 72.px, 36.px)
            clipChildren = false
            addView(Ui.text(context, 24, Ui.p.textWeak).apply { setText(R.string.menu_preview) })
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 96.px).apply { topMargin = 12.px })
        }

        override fun show(lit: Arrangement.Item?, slid: Boolean) {
            val oldX = views[lit]?.firstOrNull()?.left
            row.removeAllViews()
            views.clear()
            for (item in layout().shown) {
                val v = if (item == MenuLayout.Item.ZOOM) listOf(icon(R.drawable.ic_zoom_out, item == lit),
                    Ui.text(context, 22, Ui.p.textMid).apply { gravity = Gravity.CENTER; text = context.getString(R.string.percent, 100) },
                    icon(R.drawable.ic_zoom_in, item == lit))
                else listOf(icon(item.icon, item == lit))
                v.forEach { row.addView(it, LinearLayout.LayoutParams(ICON.px, ICON.px).apply { marginEnd = 20.px }) }
                views[item] = v
            }
            if (slid && oldX != null) row.post {
                val moved = views[lit] ?: return@post
                val dx = (oldX - moved.first().left).toFloat()
                moved.forEach { it.translationX = dx; it.animate().translationX(0f).setDuration(140).start() }
            }
        }

        private fun icon(icon: Int, lit: Boolean) = FrameLayout(context).apply {
            background = Ui.rounded(if (lit) Ui.p.focus else Color.TRANSPARENT, ICON / 2)
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(if (lit) Ui.p.onFocus else Ui.p.text) },
                FrameLayout.LayoutParams(36.px, 36.px, Gravity.CENTER))
            scaleX = if (lit) 1.12f else 1f
            scaleY = scaleX
        }

        private companion object { const val ICON = 64 }
    }
}
