package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Folder
import app.sofabrowser.data.FolderTree
import app.sofabrowser.screen.padded
import java.text.DateFormat
import java.util.Date

/**
 * History or bookmarks as one focusable list. Returns the chosen URL in [SearchActivity.EXTRA_URL].
 *
 * Only the rows on screen exist as views (a [ListView]), and an icon is looked up when its row is shown, so a list of
 * thousands of imported bookmarks costs about what a screenful does.
 *
 * Bookmarks show one folder at a time ([EXTRA_FOLDER] to start in one): an "Up to…" row, its folders A to Z, then its
 * bookmarks newest first. Everything works with D-pad, OK and Back. Back retraces the folders visited (the trail)
 * before leaving the screen; the Up row goes to the folder this one is in. Every action leaves focus on a row in sight.
 */
class ListActivity : Activity() {

    private val db by lazy { Db.get(this) }
    private val history get() = intent.getStringExtra(EXTRA_MODE) == MODE_HISTORY
    private lateinit var list: FocusListView
    private lateinit var heading: TextView
    private lateinit var headerButtons: LinearLayout
    private lateinit var empty: TextView
    private val adapter = Rows()

    /**
     * Holds focus while the list changes its rows, so the list has no row of its own to put focus back on: it would
     * focus whatever row now sits there, and a screen reader would say it. Never reachable with the arrows.
     */
    private lateinit var parking: View
    private val transfer by lazy { BookmarkTransfer(this) { load() } }

    /** The folder on screen (null: the top level), the folders as read at the last [load], and their bookmark counts. */
    private var folder: Long? = null
    private var tree = FolderTree(emptyList())
    private var counts: Map<Long?, Int> = emptyMap()

    /** Where Back goes: each folder left by opening another or by the Up row, with the row that had focus there. */
    private class Step(val folder: Long?, val key: String?, val top: Int?)
    private val trail = ArrayList<Step>()
    private var headerFor: Boolean? = null

    private sealed interface Item { val key: String? get() = null }
    private class Header(val label: String) : Item
    private class UpRow(val label: String) : Item { override val key get() = UP }
    private class FolderRow(val folder: Folder, val detail: String) : Item { override val key get() = folderKey(folder.id) }
    private class Entry(val id: Long, val title: String, val url: String, val meta: String?, val delete: () -> Unit) : Item {
        override val key get() = "url:$url"
    }

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
        // One line however deep the folder: a long path keeps its end ("… › News › Tech").
        heading = Ui.title(this).apply {
            text = getString(if (history) R.string.history else R.string.bookmarks)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.START
        }
        header.addView(heading, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 32.px })
        headerButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(headerButtons)
        if (history) addHeaderButton(getString(R.string.delete_all)) { confirmDeleteAll() }
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
        // A row shows its buttons while it, or one of them, has focus. Checked once focus has settled, as a row being
        // left still counts as focused while its own views hear about it.
        list.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            for (i in 0 until list.childCount) (list.getChildAt(i) as? RowView)?.let { it.active(it.hasFocus()) }
        }
        val frame = FrameLayout(this)
        frame.addView(list)
        parking = View(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        frame.addView(parking, FrameLayout.LayoutParams(1, 1))
        empty = Ui.text(this, 32, Ui.p.textWeak).apply { gravity = Gravity.CENTER }
        frame.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (savedInstanceState != null) restore(savedInstanceState)
        else folder = intent.getLongExtra(EXTRA_FOLDER, -1L).takeIf { it >= 0 }
        load()
    }

    /**
     * Reads the rows again and puts focus on the row named [key] (kept [top] from the list's top when given), else on
     * the row now at [at] or the nearest one before it (the neighbour of a row that went), else on the first row that
     * isn't the Up row.
     */
    private fun load(key: String? = null, at: Int = -1, top: Int? = null) {
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
                items += Entry(h.id, h.title, h.url, time.format(Date(h.visitedAt))) { db.removeHistory(h.id) }
            }
        } else {
            tree = db.folderTree()
            counts = db.bookmarkCounts()
            // A folder deleted while away (a backup restored, say) leaves the top level on screen.
            if (folder != null && tree[folder] == null) folder = null
            folder?.let { items += UpRow(getString(R.string.folder_up, name(tree[it]?.parent))) }
            tree.children(folder).forEach { f ->
                items += FolderRow(f, FolderText.contents(this, tree.children(f.id).size, counts[f.id] ?: 0))
            }
            db.bookmarks(folder).forEach { b -> items += Entry(b.id, b.title, b.url, null) { db.removeBookmark(b.url) } }
            showFolder()
        }
        adapter.items = items
        park()
        adapter.notifyDataSetChanged()
        showEmpty()
        val named = key?.let { k -> items.indexOfFirst { it.key == k } }?.takeIf { it >= 0 }
        when {
            named != null -> focusRow(named, top)
            at >= 0 -> focusRow(at, top)
            else -> {
                val first = items.indexOfFirst { it !is UpRow && it !is Header }.takeIf { it >= 0 } ?: items.indexOfFirst { it is UpRow }
                // From the start of the list, so the Up row (or the day heading) above stays in sight, one Up away.
                focusRow(first, fromStart = true)
            }
        }
    }

    /** The heading, the list's name for a screen reader, and the header buttons, for the folder on screen. */
    private fun showFolder() {
        val names = listOf(getString(R.string.bookmarks)) + tree.pathNames(folder)
        heading.text = names.joinToString(" › ")
        title = names.last()
        A11y.row(list, names.last(), vertical = true) { adapter.items.count { it is FolderRow || it is Entry } }
        // Counted as the list is: without the Up row.
        list.itemIndex = { p -> if (adapter.items.getOrNull(p) is UpRow) null else p - (if (adapter.items.firstOrNull() is UpRow) 1 else 0) }
        val top = folder == null
        if (headerFor == top) return
        headerFor = top
        headerButtons.removeAllViews()
        addHeaderButton(getString(R.string.folder_new)) { newFolder() }
        if (top) {
            addHeaderButton(getString(R.string.bookmarks_transfer)) { transfer.showMenu() }
            addHeaderButton(getString(R.string.delete_all)) { confirmDeleteAll() }
        } else {
            addHeaderButton(getString(R.string.folder_delete_this)) { tree[folder]?.let { deleteFolder(it, here = true) } }
        }
    }

    private fun addHeaderButton(label: String, action: () -> Unit) {
        headerButtons.addView(Ui.button(this, label) { action() }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            if (headerButtons.childCount > 0) marginStart = 20.px
        })
    }

    private fun showEmpty() {
        val nothing = adapter.items.none { it is FolderRow || it is Entry }
        empty.text = getString(when {
            history -> R.string.history_empty
            folder != null -> R.string.folder_empty
            else -> R.string.bookmarks_empty
        })
        empty.visibility = if (nothing) View.VISIBLE else View.GONE
    }

    /** A folder's name, "Bookmarks" for the top level. */
    private fun name(id: Long?): String = tree[id]?.title ?: getString(R.string.bookmarks)

    // Moving between folders

    private fun open(f: Folder) {
        trail += Step(folder, folderKey(f.id), rowTop(folderKey(f.id)))
        folder = f.id
        load()
    }

    /** The Up row: the folder this one is in, focus on the folder just left. Back comes here again. */
    private fun up() {
        val from = folder ?: return
        val parent = tree[from]?.parent
        // Came down from there: the folder row goes back to where it was on screen, as with Back.
        val top = trail.lastOrNull()?.takeIf { it.folder == parent && it.key == folderKey(from) }?.top
        trail += Step(folder, UP, rowTop(UP))
        folder = parent
        load(key = folderKey(from), top = top)
    }

    /** Back: the folder visited before (one deleted since is passed over), else out of the screen. */
    private fun back() {
        while (trail.isNotEmpty()) {
            val step = trail.removeAt(trail.size - 1)
            if (step.folder == null || tree[step.folder] != null) {
                folder = step.folder
                load(key = step.key, top = step.top)
                return
            }
        }
        finish()
    }

    // Focus

    private fun park() {
        if (!list.hasFocus()) return
        parking.isFocusable = true
        parking.requestFocus()
    }

    /**
     * Focus on the row at [position] (or the nearest row before it), scrolled into sight; [top] keeps it that far from
     * the list's top, as a deleted row's neighbour stays where the deleted row was; [fromStart] shows the list from its
     * start, for a row near it.
     */
    // The screen reader's focus is only put back on the row that has the remote's focus, never moved elsewhere: after the
    // list changes it can stay on another row and read that one instead.
    @SuppressLint("AccessibilityFocus")
    private fun focusRow(position: Int, top: Int? = null, fromStart: Boolean = false) {
        val at = (position.coerceAtMost(adapter.count - 1) downTo 0).firstOrNull { adapter.isEnabled(it) }
        // No row to go to (an empty list): focus goes where Android puts it, a header button.
        if (at == null) { parking.isFocusable = false; return }
        // A screen reader lets go of the row it was on, which is about to show another item: it would read that.
        currentFocus?.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS, null)
        when {
            fromStart -> list.setSelection(0)
            top != null -> list.setSelectionFromTop(at, top)
            else -> list.setSelection(at)
        }
        // Once the list has laid out its new rows: the row then holds its new item, and a screen reader hears focus
        // arrive once the list has settled.
        list.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                list.viewTreeObserver.removeOnGlobalLayoutListener(this)
                // Not "selected": the list marks the row it scrolled to, which a screen reader would say.
                for (i in 0 until list.childCount) list.getChildAt(i).isSelected = false
                val body = list.getChildAt(at - list.firstVisiblePosition)?.findViewWithTag<View>(BODY)
                // Already there (the list kept focus on this very row): a screen reader still has to hear it.
                if (body?.isFocused == true) body.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED) else body?.requestFocus()
                // Shown from the start: the list still takes its first row as the current one, and its arrows would start
                // from there. This row becomes the current one where it already is.
                if (fromStart) list.getChildAt(at - list.firstVisiblePosition)?.let { list.setSelectionFromTop(at, it.top) }
                // Only now: a parked focus let go earlier would land on some other view first.
                parking.isFocusable = false
                // A screen reader picks its own row while the list changes, and doesn't always follow focus after: it is
                // sent to this one, once it has heard the change.
                if (body != null && A11y.screenReaderOn(this@ListActivity)) body.post {
                    if (body.isFocused) body.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                }
            }
        })
        list.requestLayout()
    }

    private fun rowTop(key: String): Int? {
        val at = adapter.items.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return null
        return list.getChildAt(at - list.firstVisiblePosition)?.top
    }

    private fun rowTopAt(at: Int): Int? = list.getChildAt(at - list.firstVisiblePosition)?.top

    /** The item of the row that has focus, if any. */
    private fun focusedItem(): Item? {
        var v = currentFocus ?: return null
        while (v !is RowView) v = v.parent as? View ?: return null
        return v.item
    }

    /** Channel up or down: one screen of rows. */
    private fun page(down: Boolean) {
        val focused = currentFocus?.let { runCatching { list.getPositionForView(it) }.getOrNull() } ?: return
        if (focused == AbsListView.INVALID_POSITION) return
        val screen = (list.lastVisiblePosition - list.firstVisiblePosition).coerceAtLeast(1)
        val to = (focused + if (down) screen else -screen).coerceIn(0, adapter.count - 1)
        val at = (if (down) (to downTo focused) else (to..focused)).firstOrNull { adapter.isEnabled(it) } ?: return
        focusRow(at, if (down) null else 0)
    }

    // Keys: Back retraces the folders, Menu opens a row's actions (as a long press of OK does), Channel pages.

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                val item = focusedItem() ?: return super.dispatchKeyEvent(event)
                if (down && event.repeatCount == 0) actions(item)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) page(event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // Back acts when it is let go, as Android's own Back does, so the screen under this one never gets half a press.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) { event.startTracking(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking && !event.isCanceled) { back(); return true }
        return super.onKeyUp(keyCode, event)
    }

    // Actions

    /** A row's actions as a list (long press of OK, or Menu): the same as its buttons. */
    private fun actions(item: Item) {
        val choices = when (item) {
            is FolderRow -> listOf(R.string.move to { move(item) }, R.string.folder_rename to { rename(item.folder) },
                R.string.delete to { deleteFolder(item.folder, here = false) })
            is Entry -> if (history) listOf(R.string.delete to { remove(item) })
                else listOf(R.string.move to { move(item) }, R.string.delete to { remove(item) })
            else -> return
        }
        val title = when (item) { is FolderRow -> item.folder.title; is Entry -> item.title.ifBlank { item.url } }
        Ui.dialog(this).setTitle(title)
            .setItems(choices.map { getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    private fun remove(entry: Entry) = guardHistory {
        val at = adapter.items.indexOf(entry).takeIf { it >= 0 } ?: return@guardHistory
        val top = rowTopAt(at)
        entry.delete()
        val items = ArrayList(adapter.items).apply { removeAt(at) }
        // A day heading with no visit left under it goes too, and what followed moves up one more place.
        var next = at
        if (items.getOrNull(at - 1) is Header && items.getOrNull(at) !is Entry) { items.removeAt(at - 1); next = at - 1 }
        adapter.items = items
        park()
        adapter.notifyDataSetChanged()
        showEmpty()
        focusRow(next, top)
    }

    private fun newFolder() {
        val parent = folder
        if (!tree.canHold(parent)) return tooDeep()
        askName(null, parent, null) { name -> load(key = folderKey(db.addFolder(parent, name))) }
    }

    private fun rename(f: Folder) {
        val top = rowTop(folderKey(f.id))
        askName(f.title, f.parent, f.id) { name ->
            if (name != f.title) db.renameFolder(f.id, name)
            load(key = folderKey(f.id), top = top)
        }
    }

    /**
     * Asks for a folder's name: [current] for a rename (cursor after it), else an empty field showing "New folder", so
     * typing replaces it. Empty keeps the old name or "New folder"; a name another folder in [parent] has is refused,
     * with the field kept open. [self] is the folder being renamed.
     */
    private fun askName(current: String?, parent: Long?, self: Long?, save: (String) -> Unit) {
        val fallback = current ?: getString(R.string.folder_new)
        val field = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_DONE
            // A new folder's name shows as the hint: typing starts afresh (a keyboard may undo a selection as it
            // connects), and Save with nothing typed keeps it. A rename starts from the name, cursor at its end.
            if (current == null) hint = fallback else { setText(current); setSelection(text.length) }
        }
        val problem = Ui.text(this, 24, Ui.p.text).apply {
            visibility = View.GONE
            setPadding(0, 16.px, 0, 0)
            A11y.announced(this)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(field)
            addView(problem)
        }
        val d = Ui.dialog(this).setTitle(if (current == null) R.string.folder_new else R.string.folder_rename_title)
            .setView(padded(this, box))
            .setPositiveButton(R.string.folder_save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            val ok = d.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val name = FolderTree.cleanName(field.text.toString()).ifEmpty { fallback }
                val other = tree.find(parent, name)
                if (other != null && other.id != self) {
                    problem.text = getString(R.string.folder_name_taken, other.title)
                    problem.visibility = View.VISIBLE
                    field.requestFocus()
                } else {
                    d.dismiss()
                    save(name)
                }
            }
            // The keyboard's Done saves, as Save does; so does Enter on a keyboard, heard as it goes down (and up).
            field.setOnEditorActionListener { _, action, event ->
                when {
                    action == EditorInfo.IME_ACTION_DONE -> { ok.performClick(); true }
                    event?.keyCode == KeyEvent.KEYCODE_ENTER -> { if (event.action == KeyEvent.ACTION_DOWN) ok.performClick(); true }
                    else -> false
                }
            }
        }
        d.show()
    }

    private fun tooDeep() {
        val max = FolderTree.MAX_DEPTH
        Toast.makeText(this, resources.getQuantityString(R.plurals.folder_too_deep, max, max), Toast.LENGTH_LONG).show()
    }

    /** Deletes [f] with what is in it, asking first when it holds anything; [here] when it is the folder on screen. */
    private fun deleteFolder(f: Folder, here: Boolean) {
        val inside = tree.subtree(f.id)
        val folders = inside.size - 1
        val bookmarks = inside.sumOf { counts[it] ?: 0 }
        val at = if (here) (if (f.parent != null) 1 else 0) + tree.children(f.parent).indexOfFirst { it.id == f.id }
            else adapter.items.indexOfFirst { it.key == folderKey(f.id) }
        val top = if (here) null else rowTopAt(at)
        val go = {
            db.deleteFolder(f.id)
            Toast.makeText(this, R.string.folder_deleted, Toast.LENGTH_SHORT).show()
            if (here) {
                // Out to the folder it was in; a Back that would have gone there already has nothing left to do.
                if (trail.lastOrNull()?.folder == f.parent) trail.removeAt(trail.size - 1)
                folder = f.parent
            }
            load(at = at, top = top)
        }
        if (folders == 0 && bookmarks == 0) return go()
        val d = Ui.dialog(this)
            .setMessage(getString(R.string.folder_delete_confirm, f.title, FolderText.deleted(this, folders, bookmarks)))
            .setPositiveButton(R.string.delete) { _, _ -> go() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        // Nothing is lost by a press of OK on the dialog as it opens.
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
        d.show()
    }

    /** A place [move] offers: a folder ([newFolder] for "New folder…"), how deep, and whether the item can go there. */
    private class Place(val folder: Long?, val label: String, val describe: String, val depth: Int,
        val enabled: Boolean, val here: Boolean, val newFolder: Boolean = false)

    /**
     * Moves a bookmark or a folder, picked from every folder, indented by depth. Focus starts on the folder it is in
     * now; a folder can't go into itself, one inside it, past the deepest level, or next to one of the same name.
     */
    private fun move(item: Item) {
        val moving = (item as? FolderRow)?.folder
        val title = moving?.title ?: (item as? Entry)?.let { it.title.ifBlank { it.url } } ?: return
        fun allowed(to: Long?) = to == folder || moving == null ||
            tree.canMove(moving.id, to) && tree.find(to, moving.title).let { it == null || it.id == moving.id }
        val hereNow = getString(R.string.folder_here_now)
        val targets = ArrayList<Place>()
        targets += Place(null, getString(R.string.folder_top), getString(R.string.folder_top), 0, allowed(null), folder == null)
        tree.outline().forEach { (f, depth) ->
            val describe = if (f.parent == null) f.title else getString(R.string.folder_in, f.title, name(f.parent))
            targets += Place(f.id, f.title, describe, depth, allowed(f.id), f.id == folder)
        }
        targets += Place(null, getString(R.string.folder_new_in), getString(R.string.folder_new_in), 0, tree.canHold(folder),
            here = false, newFolder = true)

        val builder = Ui.dialog(this).setTitle(getString(R.string.folder_move_title, title))
        val inflater = LayoutInflater.from(builder.context)
        val weak = builder.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).let { a ->
            a.getColor(0, Color.GRAY).also { a.recycle() }
        }
        val rows = object : BaseAdapter() {
            override fun getCount() = targets.size
            override fun getItem(position: Int) = targets[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = targets[position].enabled
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val t = targets[position]
                val v = convertView as? TextView ?: (inflater.inflate(android.R.layout.select_dialog_item, parent, false) as TextView)
                    .also { it.tag = it.paddingStart }
                v.setPaddingRelative(v.tag as Int + (t.depth - 1).coerceAtLeast(0) * 40.px, v.paddingTop, v.paddingEnd, v.paddingBottom)
                v.text = if (!t.here) t.label else SpannableStringBuilder(t.label).apply {
                    append("   ")
                    val from = length
                    append(hereNow)
                    setSpan(ForegroundColorSpan(weak), from, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                v.contentDescription = if (t.here) "${t.describe}, $hereNow" else t.describe
                v.isEnabled = t.enabled
                v.alpha = if (t.enabled) 1f else 0.38f
                return v
            }
        }
        val at = adapter.items.indexOf(item)
        val top = rowTopAt(at)
        val d = builder.setAdapter(rows) { _, which ->
            val t = targets[which]
            when {
                t.here -> Unit
                t.newFolder -> askName(null, folder, null) { name ->
                    val made = db.addFolder(folder, name)
                    tree = db.folderTree()
                    if (moving != null && !tree.canMove(moving.id, made)) { load(key = item.key); tooDeep() }
                    else moveTo(item, made, at, top)
                }
                else -> moveTo(item, t.folder, at, top)
            }
        }.create()
        d.setOnShowListener {
            val here = targets.indexOfFirst { it.here }.coerceAtLeast(0)
            d.listView.post { d.listView.setSelection(here) }
        }
        // Channel up or down: one screen of the folder list.
        d.setOnKeyListener { _, code, event ->
            val pageDown = code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_PAGE_DOWN
            if (!pageDown && code != KeyEvent.KEYCODE_CHANNEL_UP && code != KeyEvent.KEYCODE_PAGE_UP) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) {
                val lv = d.listView
                val from = lv.selectedItemPosition.coerceAtLeast(0)
                val screen = (lv.lastVisiblePosition - lv.firstVisiblePosition).coerceAtLeast(1)
                val to = (from + if (pageDown) screen else -screen).coerceIn(0, targets.size - 1)
                (if (pageDown) (to downTo from) else (to..from)).firstOrNull { targets[it].enabled }?.let(lv::setSelection)
            }
            true
        }
        d.show()
    }

    private fun moveTo(item: Item, to: Long?, at: Int, top: Int?) {
        when (item) {
            is Entry -> db.moveBookmark(item.id, to)
            is FolderRow -> db.moveFolder(item.folder.id, to)
            else -> return
        }
        Toast.makeText(this, getString(R.string.folder_moved_to, db.folderTree()[to]?.title ?: getString(R.string.bookmarks)),
            Toast.LENGTH_SHORT).show()
        load(at = at, top = top)
    }

    private fun dayLabel(t: Long): String = when (Dates.daysBetween(t, System.currentTimeMillis())) {
        0 -> getString(R.string.today)
        1 -> getString(R.string.yesterday)
        else -> Dates.day(t)
    }

    /** The list's rows: day headings (History) and rows, each kind made once per visible slot and reused. */
    private inner class Rows : BaseAdapter() {
        var items: List<Item> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position] is Header) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(position: Int) = items[position] !is Header

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            when (val item = items[position]) {
                is Header -> ((convertView as? TextView) ?: headerView()).apply { text = item.label }
                else -> ((convertView as? RowView) ?: RowView(this@ListActivity)).apply { bind(item) }
            }

        private fun headerView() = Ui.text(this@ListActivity, 30, Ui.p.textWeak).apply {
            setPadding(0, 20.px, 0, 4.px)
            layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, AbsListView.LayoutParams.WRAP_CONTENT)
        }
    }

    /**
     * A round button on a row, with its word under it while it has focus (as the start page's top buttons have), so
     * nobody has to read an icon from across the room. The word only shows and hides, never changes the button's size:
     * a row that changed its layout while focused would lose its focus inside the list.
     */
    private inner class RowButton(context: Context, icon: Int, word: Int, onClick: () -> Unit) : FrameLayout(context) {
        private val image = ImageView(context).apply { setImageResource(icon); setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        private val circle = FrameLayout(context).apply {
            background = Ui.fillSelector(Ui.p.fillFocused, Ui.p.focus, 40)
            isDuplicateParentStateEnabled = true
        }
        // Under the circle, inside the row; wider than the button, centred on it, over its neighbours' empty space.
        private val label = Ui.text(context, 22, Ui.p.text).apply {
            setText(word)
            maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = INVISIBLE
            translationY = 84.px.toFloat()
        }

        init {
            isFocusable = true
            isClickable = true
            clipChildren = false
            clipToPadding = false
            contentDescription = context.getString(word)
            A11y.role(this, A11y.BUTTON)
            A11y.skip(image)
            A11y.skip(label)
            circle.addView(image, LayoutParams(40.px, 40.px, Gravity.CENTER))
            addView(circle, LayoutParams(80.px, 80.px))
            addView(label, LayoutParams(LABEL_WIDTH.px, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has ->
                image.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textMid, PorterDuff.Mode.SRC_IN)
                label.visibility = if (has) VISIBLE else INVISIBLE
            }
        }
    }

    /**
     * One row: the body (OK opens the page, the folder, or the folder above) and, to its right, its buttons, shown
     * while the row has focus: Move, Rename (folders) and Delete, Delete always last.
     */
    private inner class RowView(context: Context) : LinearLayout(context) {
        var item: Item? = null
            private set
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
            isLongClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.fillFocused, 16)
            foreground = Ui.focusBorder(16)
            setPadding(40.px, 0, 40.px, 0)
            setOnClickListener {
                when (val i = item) {
                    is Entry -> {
                        setResult(RESULT_OK, Intent().putExtra(SearchActivity.EXTRA_URL, i.url))
                        finish()
                    }
                    is FolderRow -> open(i.folder)
                    is UpRow -> up()
                    else -> Unit
                }
            }
            setOnLongClickListener { item?.let(::actions); item is Entry || item is FolderRow }
        }
        private val moveButton = RowButton(context, R.drawable.ic_move, R.string.move) { item?.let(::move) }
        private val renameButton = RowButton(context, R.drawable.ic_edit, R.string.folder_rename) {
            (item as? FolderRow)?.let { rename(it.folder) }
        }
        private val deleteButton = RowButton(context, R.drawable.ic_delete, R.string.delete) {
            when (val i = item) {
                is Entry -> remove(i)
                is FolderRow -> deleteFolder(i.folder, here = false)
                else -> Unit
            }
        }
        private val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            visibility = View.INVISIBLE
            // As wide as a row's most buttons, so bodies line up whatever their row holds.
            clipChildren = false
            minimumWidth = if (history) 80.px else (3 * 80 + 2 * 16).px
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
            addView(body, LayoutParams(0, ROW_HEIGHT.px, 1f))
            listOf(moveButton, renameButton, deleteButton).forEachIndexed { i, b ->
                buttons.addView(b, LayoutParams(80.px, 80.px).apply { if (i > 0) marginStart = 16.px })
            }
            addView(buttons, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = 24.px })
            // The view's own animator: a new scale replaces one still running. A reused row can gain and lose focus
            // within one layout, and two animations at once would leave it at the larger size.
            body.setOnFocusChangeListener { _, has ->
                body.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(if (has) 400L else 250L)
                    .setInterpolator(if (has) OvershootInterpolator(1.6f) else DecelerateInterpolator(2f))
            }
        }

        fun bind(i: Item) {
            item = i
            val folderLike = i is FolderRow || i is UpRow
            when (i) {
                is Entry -> {
                    icon.setImageDrawable(Favicons.get(context).drawable(i.url, i.title, 128))
                    title.text = i.title.ifBlank { i.url }
                    meta.text = listOfNotNull(i.meta, UrlInput.display(i.url)).joinToString("  ·  ")
                }
                is FolderRow -> {
                    icon.setImageResource(R.drawable.ic_folder)
                    title.text = i.folder.title
                    meta.text = i.detail
                }
                is UpRow -> {
                    icon.setImageResource(R.drawable.ic_arrow_upward)
                    title.text = i.label
                }
                is Header -> Unit
            }
            if (folderLike) icon.setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN) else icon.clearColorFilter()
            meta.visibility = if (i is UpRow) View.GONE else View.VISIBLE
            (body.layoutParams as LayoutParams).height = (if (i is UpRow) UP_HEIGHT else ROW_HEIGHT).px
            // A screen reader hears a folder as one button with what it holds; a page reads its title and address.
            body.contentDescription = when (i) {
                is FolderRow -> "${i.folder.title}, ${getString(R.string.folder_word)}, ${i.detail}"
                is UpRow -> i.label
                else -> null
            }
            A11y.role(body, if (folderLike) A11y.BUTTON else null)
            moveButton.visibility = if (i is FolderRow || i is Entry && !history) View.VISIBLE else View.GONE
            renameButton.visibility = if (i is FolderRow) View.VISIBLE else View.GONE
            deleteButton.visibility = if (i is FolderRow || i is Entry) View.VISIBLE else View.GONE
            // A reused row starts as an unfocused one.
            if (!body.hasFocus()) { body.animate().cancel(); body.scaleX = 1f; body.scaleY = 1f }
            active(hasFocus())
        }

        // The list marks the row it scrolled to as selected; a screen reader would say "selected" on top of the row.
        override fun dispatchSetSelected(selected: Boolean) = Unit

        /** Shows the buttons while the row has focus. */
        fun active(has: Boolean) {
            buttons.visibility = if (has && item !is UpRow) View.VISIBLE else View.INVISIBLE
        }
    }

    /** A list that keeps the focused row [margin] away from its top and bottom edges, as [FocusScrollView] does. */
    private class FocusListView(context: Context, private val margin: Int = 120.px) : ListView(context) {
        // ListView keeps this much of the next row in sight when the arrows move focus (its "scroll preview").
        override fun getVerticalFadingEdgeLength() = margin

        // Focus moving from row to row already says each row. The list's own "selected", sent as it scrolls to a row,
        // would say that row again, its buttons and all.
        override fun sendAccessibilityEvent(eventType: Int) {
            if (eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) super.sendAccessibilityEvent(eventType)
        }

        /** A row's number for a screen reader ("2 of 8"), null for one that isn't an item (the Up row). */
        var itemIndex: (Int) -> Int? = { it }

        override fun onInitializeAccessibilityNodeInfoForItem(view: View, position: Int, info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfoForItem(view, position, info)
            val i = itemIndex(position)
            info.collectionItemInfo = when {
                i == null -> null
                Build.VERSION.SDK_INT >= 33 -> AccessibilityNodeInfo.CollectionItemInfo(i, 1, 0, 1, false)
                else -> @Suppress("DEPRECATION") AccessibilityNodeInfo.CollectionItemInfo.obtain(i, 1, 0, 1, false)
            }
        }
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

    private fun confirmDeleteAll() = guardHistory {
        val message = when {
            history -> R.string.confirm_clear_history
            tree.size > 0 -> R.string.confirm_clear_bookmarks_folders
            else -> R.string.confirm_clear_bookmarks
        }
        Ui.dialog(this)
            .setMessage(message)
            .setPositiveButton(R.string.delete_all) { _, _ ->
                if (history) db.clearHistory() else db.clearBookmarks()
                load()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // The folder on screen and the trail, kept if Android rebuilds the screen.

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_FOLDER, folder ?: -1L)
        outState.putLongArray(STATE_TRAIL, trail.map { it.folder ?: -1L }.toLongArray())
        outState.putStringArray(STATE_KEYS, trail.map { it.key.orEmpty() }.toTypedArray())
    }

    private fun restore(state: Bundle) {
        folder = state.getLong(STATE_FOLDER, -1L).takeIf { it >= 0 }
        val folders = state.getLongArray(STATE_TRAIL) ?: return
        val keys = state.getStringArray(STATE_KEYS) ?: return
        folders.zip(keys).forEach { (f, k) -> trail += Step(f.takeIf { it >= 0 }, k.ifEmpty { null }, null) }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        /** Bookmarks: the folder to open in (a start page folder card); Back from it leaves the screen. */
        const val EXTRA_FOLDER = "folder"
        const val MODE_HISTORY = "history"
        const val MODE_BOOKMARKS = "bookmarks"
        private const val BODY = "body"
        private const val UP = "up"
        private const val ROW_HEIGHT = 136
        private const val UP_HEIGHT = 104
        private const val LABEL_WIDTH = 240
        private const val STATE_FOLDER = "folder"
        private const val STATE_TRAIL = "trail"
        private const val STATE_KEYS = "trail-keys"

        private fun folderKey(id: Long) = "folder:$id"
    }
}
