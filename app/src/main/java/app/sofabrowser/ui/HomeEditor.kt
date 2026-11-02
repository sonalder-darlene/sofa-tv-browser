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

/**
 * Settings > Start page: the start page's rows and its round buttons in an [ArrangeEditor], with how many rows of
 * bookmarks it shows, saved in [Prefs.homeLayout].
 */
object HomeEditor {

    fun create(context: Context, prefs: Prefs): ArrangeEditor {
        val rows = ArrangeEditor.Section(R.string.home_rows, { prefs.homeLayout.rows }, { prefs.homeLayout = prefs.homeLayout.copy(rows = it) },
            ArrangeEditor.Extra(R.string.home_bookmark_rows, R.string.home_hint_bookmark_rows, { bookmarkRowsLabel(context, prefs.homeLayout.bookmarkRows) }) { changed ->
                val labels = (1..HomeLayout.MAX_BOOKMARK_ROWS).map { bookmarkRowsLabel(context, it) }.toTypedArray()
                Ui.dialog(context).setTitle(R.string.home_bookmark_rows)
                    .setSingleChoiceItems(labels, prefs.homeLayout.bookmarkRows - 1) { d, which ->
                        prefs.homeLayout = prefs.homeLayout.withBookmarkRows(which + 1)
                        d.dismiss()
                        changed()
                    }.show()
            })
        val buttons = ArrangeEditor.Section(R.string.home_buttons, { prefs.homeLayout.buttons }, { prefs.homeLayout = prefs.homeLayout.copy(buttons = it) })
        return ArrangeEditor(context,
            ArrangeEditor.Texts(R.string.home_layout, R.string.home_hint, R.string.home_hint_switch, R.string.home_hint_reset,
                R.string.home_reset, R.string.home_reset_confirm, R.string.home_reset_done),
            listOf(rows, buttons), MiniPage(context) { prefs.homeLayout.copy(rows = rows.value, buttons = buttons.value) }) { prefs.homeLayout = HomeLayout.DEFAULT }
    }

    fun bookmarkRowsLabel(context: Context, rows: Int): String =
        context.resources.getQuantityString(R.plurals.home_bookmark_rows_value, rows, rows, rows * HomeLayout.PER_ROW)

    /** The start page as bars and dots: the round buttons at the top, then a bar of cards for each row, the lit one filled. */
    private class MiniPage(private val context: Context, private val layout: () -> HomeLayout) : ArrangeEditor.Preview {
        private val bar = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        private val lines = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        override val view: View = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(false).also { (it as? GradientDrawable)?.cornerRadii = FloatArray(8) { i -> if (i < 4) 16.px.toFloat() else 0f } }
            setPadding(72.px, 20.px, 72.px, 24.px)
            clipChildren = false
            addView(Ui.text(context, 24, Ui.p.textWeak).apply { setText(R.string.home_preview) })
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.px).apply { topMargin = 10.px })
            // Room for every line there can be, so the list above keeps its height whatever is shown.
            addView(lines, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ((HomeLayout.MAX_BOOKMARK_ROWS + 2) * LINE).px))
        }

        override fun show(lit: Arrangement.Item?, slid: Boolean) {
            val layout = layout()
            bar.removeAllViews()
            bar.addView(View(context).apply { background = Ui.rounded(Ui.p.fillFocused, 14) }, LinearLayout.LayoutParams(300.px, 28.px))
            bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            for (b in layout.buttons.shown) bar.addView(dot(b.icon, b == lit), LinearLayout.LayoutParams(40.px, 40.px).apply { marginStart = 12.px })

            lines.removeAllViews()
            for (row in layout.rows.shown) {
                val count = if (row == HomeLayout.Row.BOOKMARKS) layout.bookmarkRows else 1
                repeat(count) { i -> lines.addView(line(if (i == 0) row.label else 0, row == lit), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LINE.px)) }
            }
        }

        private fun line(label: Int, lit: Boolean) = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.text(context, 20, if (lit) Ui.p.text else Ui.p.textWeak).apply { if (label != 0) setText(label) },
                LinearLayout.LayoutParams(240.px, LinearLayout.LayoutParams.WRAP_CONTENT))
            repeat(8) {
                addView(View(context).apply { background = Ui.rounded(if (lit) Ui.p.focus else Ui.p.card, 4) },
                    LinearLayout.LayoutParams(120.px, 18.px).apply { marginEnd = 16.px })
            }
        }

        private fun dot(icon: Int, lit: Boolean) = FrameLayout(context).apply {
            background = Ui.rounded(if (lit) Ui.p.focus else Color.TRANSPARENT, 20)
            addView(ImageView(context).apply { setImageResource(icon); setColorFilter(if (lit) Ui.p.onFocus else Ui.p.text) },
                FrameLayout.LayoutParams(26.px, 26.px, Gravity.CENTER))
        }

        private companion object { const val LINE = 30 }
    }
}
