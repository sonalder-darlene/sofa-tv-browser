package app.sofabrowser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import java.text.DateFormat
import java.util.Date

/**
 * History or bookmarks as one focusable list. Returns the chosen URL in [SearchActivity.EXTRA_URL].
 *
 * Only the rows on screen exist as views (a [ListView]), and an icon is looked up when its row is shown, so a list of
 * thousands of imported bookmarks costs about what a screenful does.
 */
class ListActivity : Activity() {

    private val db by lazy { Db.get(this) }
    private val history get() = intent.getStringExtra(EXTRA_MODE) == MODE_HISTORY
    private lateinit var list: ListView
    private lateinit var empty: View
    private val adapter = Rows()
    private val transfer by lazy { BookmarkTransfer(this) { load() } }

    private sealed interface Item
    private class Header(val label: String) : Item
    private class Entry(val title: String, val url: String, val meta: String?, val delete: () -> Unit) : Item

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.background(false)
            setPadding(116.px, 72.px, 116.px, 0)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        setTitle(if (history) R.string.history else R.string.bookmarks)
        header.addView(Ui.title(this).apply {
            text = getString(if (history) R.string.history else R.string.bookmarks)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (!history) header.addView(Ui.button(this, getString(R.string.bookmarks_transfer)) { transfer.showMenu() },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = 20.px })
        header.addView(Ui.button(this, getString(R.string.delete_all)) { guardHistory { confirmDeleteAll() } })
        // The gap under the heading belongs to it: the list scrolls its own top padding away with the first row.
        root.addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 24.px })

        list = FocusListView(this).apply {
            itemsCanFocus = true
            selector = ColorDrawable(Color.TRANSPARENT)
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = 16.px
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(0, 0, 0, 72.px)
            adapter = this@ListActivity.adapter
        }
        val frame = FrameLayout(this)
        frame.addView(list)
        empty = Ui.text(this, 32, Ui.p.textWeak).apply {
            text = getString(if (history) R.string.history_empty else R.string.bookmarks_empty)
            gravity = Gravity.CENTER
        }
        frame.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        load()
    }

    private fun load() {
        val items = ArrayList<Item>()
        if (history) {
            var lastHeader: String? = null
            val time = DateFormat.getTimeInstance(DateFormat.SHORT)
            db.history().forEach { h ->
                val header = dayLabel(h.visitedAt)
                if (header != lastHeader) {
                    items += Header(header)
                    lastHeader = header
                }
                items += Entry(h.title, h.url, time.format(Date(h.visitedAt))) { db.removeHistory(h.id) }
            }
        } else {
            db.bookmarks().forEach { b -> items += Entry(b.title, b.url, null) { db.removeBookmark(b.url) } }
        }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        focusRow(items.indexOfFirst { it is Entry })
    }

    /**
     * Focus on the row at [position] (or the nearest row before it), scrolled into sight; [top] keeps it that far from
     * the list's top, as a deleted row's neighbour stays where the deleted row was.
     */
    private fun focusRow(position: Int, top: Int? = null) {
        val at = (position.coerceAtMost(adapter.count - 1) downTo 0).firstOrNull { adapter.items[it] is Entry } ?: return
        if (top != null) list.setSelectionFromTop(at, top) else list.setSelection(at)
        list.post { list.getChildAt(at - list.firstVisiblePosition)?.findViewWithTag<View>(BODY)?.requestFocus() }
    }

    private fun remove(entry: Entry) = guardHistory {
        val at = adapter.items.indexOf(entry).takeIf { it >= 0 } ?: return@guardHistory
        val top = list.getChildAt(at - list.firstVisiblePosition)?.top
        entry.delete()
        val items = ArrayList(adapter.items).apply { removeAt(at) }
        // A day heading with no visit left under it goes too, and what followed moves up one more place.
        var next = at
        if (items.getOrNull(at - 1) is Header && items.getOrNull(at) !is Entry) { items.removeAt(at - 1); next = at - 1 }
        adapter.items = items
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        focusRow(next, top)
    }

    private fun dayLabel(t: Long): String = when (Dates.daysBetween(t, System.currentTimeMillis())) {
        0 -> getString(R.string.today)
        1 -> getString(R.string.yesterday)
        else -> Dates.day(t)
    }

    /** The list's rows: day headings (History) and entries, each kind made once per visible slot and reused. */
    private inner class Rows : BaseAdapter() {
        var items: List<Item> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] is Entry

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val item = items[position]) {
                is Header -> ((convertView as? TextView) ?: headerView()).apply { text = item.label }
                is Entry -> ((convertView as? RowView) ?: RowView(this@ListActivity)).apply { bind(item) }
            }

        private fun headerView() = Ui.text(this@ListActivity, 30, Ui.p.textWeak).apply {
            setPadding(0, 20.px, 0, 4.px)
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        }
    }

    /** One entry: the page (OK opens it) and, to its right, a delete button shown while the row has focus. */
    private inner class RowView(context: Context) : LinearLayout(context) {
        private var entry: Entry? = null
        private val icon = ImageView(context)
        private val title = Ui.text(context, 34, Ui.p.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val meta = Ui.text(context, 24, Ui.p.textWeak).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, 10.px, 0, 0)
        }
        private val body = LinearLayout(context).apply {
            tag = BODY
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.fillFocused, 16)
            foreground = Ui.focusBorder(16)
            setPadding(40.px, 0, 40.px, 0)
            setOnClickListener {
                val url = entry?.url ?: return@setOnClickListener
                setResult(RESULT_OK, Intent().putExtra(SearchActivity.EXTRA_URL, url))
                finish()
            }
        }
        private val removeIcon = ImageView(context).apply { setImageResource(R.drawable.ic_delete); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        private val remove = FrameLayout(context).apply {
            isFocusable = true
            isClickable = true
            contentDescription = context.getString(R.string.delete)
            A11y.role(this, A11y.BUTTON)
            A11y.skip(removeIcon)
            background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 40)
            addView(removeIcon, FrameLayout.LayoutParams(40.px, 40.px, Gravity.CENTER))
            visibility = View.INVISIBLE
            setOnClickListener { entry?.let(::remove) }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
            body.addView(icon, LayoutParams(64.px, 64.px).apply { marginEnd = 32.px })
            val texts = LinearLayout(context).apply { orientation = VERTICAL }
            texts.addView(title)
            texts.addView(meta)
            body.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(body, LayoutParams(0, 136.px, 1f))
            addView(remove, LayoutParams(80.px, 80.px).apply { marginStart = 24.px })
            remove.setOnFocusChangeListener { _, has ->
                removeIcon.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
                if (!has && !body.hasFocus()) remove.visibility = View.INVISIBLE
            }
            body.setOnFocusChangeListener { v, has ->
                Ui.animateScale(v, if (has) 1.02f else 1f, has)
                if (has) remove.visibility = View.VISIBLE else if (!remove.hasFocus()) remove.visibility = View.INVISIBLE
            }
        }

        fun bind(e: Entry) {
            entry = e
            icon.setImageDrawable(Favicons.get(context).drawable(e.url, e.title, 128))
            title.text = e.title.ifBlank { e.url }
            meta.text = listOfNotNull(e.meta, UrlInput.display(e.url)).joinToString("  ·  ")
            // A reused row starts as an unfocused one.
            if (!body.hasFocus()) { body.scaleX = 1f; body.scaleY = 1f }
            if (!body.hasFocus() && !remove.hasFocus()) remove.visibility = View.INVISIBLE
        }
    }

    /** A list that keeps the focused row [margin] away from its top and bottom edges, as [FocusScrollView] does. */
    private class FocusListView(context: Context, private val margin: Int = 120.px) : ListView(context) {
        // ListView keeps this much of the next row in sight when the arrows move focus (its "scroll preview").
        override fun getVerticalFadingEdgeLength() = margin
    }

    /** A bookmarks file from the TV's document picker. */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == BookmarkTransfer.REQUEST_FILE && resultCode == RESULT_OK && uri != null) transfer.onPicked(uri)
    }

    /** Deleting History asks for the parental PIN when one is set, once for this visit; bookmarks never do. */
    private fun guardHistory(action: () -> Unit) =
        if (history) PinPad.guard(this, R.string.pin_title_history, stay = true, action = action) else action()

    private fun confirmDeleteAll() {
        Ui.dialog(this)
            .setMessage(if (history) R.string.confirm_clear_history else R.string.confirm_clear_bookmarks)
            .setPositiveButton(R.string.delete_all) { _, _ ->
                if (history) db.clearHistory() else db.clearBookmarks()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        private const val BODY = "body"
    }
}
