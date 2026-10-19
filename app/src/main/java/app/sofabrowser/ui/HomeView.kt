package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.R
import app.sofabrowser.data.Db
import app.sofabrowser.data.Site

/**
 * The start screen: a fixed top bar (logo, search pill, mic, labelled icon buttons) above rows of site cards:
 * Quick access (user-editable), Most visited and Bookmarks. All of it is local data.
 */
@SuppressLint("ViewConstructor")
class HomeView(context: Context, private val listener: Listener) : FrameLayout(context) {

    interface Listener {
        fun onOpen(url: String)
        fun onSearch()
        fun onVoiceSearch()
        fun onBookmarks()
        fun onHistory()
        fun onTogglePrivate()
        fun onToggleAdBlock()
        fun onSettings()
        fun onTileMenu(site: Site)
        fun onAddTile()
        fun onDownloads()
        fun onExit()
    }

    private val db = Db.get(context)
    private val prefs = app.sofabrowser.Prefs(context)
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val scroller = FocusScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        clipChildren = false
        addView(rows)
    }
    val searchPill: View
    private val privateButton: LabeledIconButton
    private val adBlockButton: LabeledIconButton
    private val downloadsButton: LabeledIconButton
    private val micButton: LabeledIconButton
    private var tilesRow: LinearLayout? = null

    /** When non-null, the tile being moved with Left/Right; OK or Back drops it. */
    private var moving: SiteCard? = null

    init {
        clipChildren = false
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }

        // Top-aligned so the pill (88 px) and the icon circles (80 px, labels hang below) share a centre line.
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            setPadding(64.px, 36.px, 64.px, 0)
            clipChildren = false
            clipToPadding = false
        }
        top.addView(ImageView(context).apply {
            setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(64.px, 64.px).apply { marginEnd = 32.px; topMargin = 12.px })

        searchPill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 18)
            setPadding(24.px, 0, 24.px, 0)
            val icon = ImageView(context).apply { setImageResource(R.drawable.ic_search) }
            val hint = Ui.text(context, 32, Ui.p.textHint).apply { text = context.getString(R.string.search_hint) }
            addView(icon, LinearLayout.LayoutParams(40.px, 40.px).apply { marginEnd = 20.px })
            addView(hint)
            setOnFocusChangeListener { _, has ->
                icon.setColorFilter(if (has) Ui.p.onFocus else Ui.p.textWeak, PorterDuff.Mode.SRC_IN)
                hint.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textHint)
            }
            icon.setColorFilter(Ui.p.textWeak, PorterDuff.Mode.SRC_IN)
            setOnClickListener { listener.onSearch() }
        }
        top.addView(searchPill, LinearLayout.LayoutParams(760.px, 88.px))
        fun button(icon: Int, label: Int, onClick: () -> Unit) = LabeledIconButton(context, icon, context.getString(label), onClick)
        fun params(start: Int) = LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LayoutParams.WRAP_CONTENT).apply {
            marginStart = start.px
            topMargin = 4.px
        }
        micButton = button(R.drawable.ic_mic, R.string.voice_search) { listener.onVoiceSearch() }
        top.addView(micButton, params(8))
        top.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        privateButton = button(R.drawable.ic_private, R.string.private_mode) { listener.onTogglePrivate() }
        adBlockButton = button(R.drawable.ic_shield, R.string.ad_blocker) { listener.onToggleAdBlock() }
        downloadsButton = button(R.drawable.ic_download, R.string.downloads) { listener.onDownloads() }
        listOf(
            button(R.drawable.ic_bookmarks, R.string.bookmarks) { listener.onBookmarks() },
            button(R.drawable.ic_history, R.string.history) { listener.onHistory() },
            downloadsButton,
            privateButton,
            adBlockButton,
            button(R.drawable.ic_settings, R.string.settings) { listener.onSettings() },
            button(R.drawable.ic_exit, R.string.exit_sofa) { listener.onExit() },
        ).forEach { top.addView(it, params(12)) }

        column.addView(top)
        column.addView(scroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column)
    }

    /** Shows the accent dot on Downloads while something is downloading. */
    fun setDownloadsActive(active: Boolean) = downloadsButton.set(
        if (active) R.drawable.ic_downloading else R.drawable.ic_download,
        context.getString(if (active) R.string.downloads_active else R.string.downloads))

    fun refresh(privateMode: Boolean, adBlock: Boolean) {
        background = Ui.background(privateMode)
        micButton.visibility = if (prefs.voiceSearch) VISIBLE else GONE
        privateButton.set(if (privateMode) R.drawable.ic_private_filled else R.drawable.ic_private,
            context.getString(if (privateMode) R.string.private_mode_on else R.string.private_mode_off))
        adBlockButton.set(if (adBlock) R.drawable.ic_shield_filled else R.drawable.ic_shield,
            context.getString(if (adBlock) R.string.adblock_on_short else R.string.adblock_off_short))
        setDownloadsActive(app.sofabrowser.downloads.DownloadEngine.activeCount > 0)
        val focusedUrl = (findFocus() as? SiteCard)?.site?.url
        rows.removeAllViews()
        rows.setPadding(96.px, 24.px, 0, 64.px)

        val tiles = db.tiles()
        tilesRow = addRow(R.string.quick_access, tiles) { card ->
            card.setOnLongClickListener { card.site?.let(listener::onTileMenu); true }
        }.also { row ->
            row.addView(SiteCard(context, null, context.getDrawable(R.drawable.ic_add), context.getString(R.string.add_site)).apply {
                setOnClickListener { listener.onAddTile() }
            })
        }
        if (!privateMode && prefs.showMostVisited) {
            db.mostVisited(10).takeIf { it.isNotEmpty() }?.let { addRow(R.string.most_visited, it) }
        }
        db.bookmarks().take(12).takeIf { it.isNotEmpty() }?.let { addRow(R.string.bookmarks, it) }

        val restore = focusedUrl?.let { url -> findCard { it.site?.url == url } }
        (restore ?: if (findFocus() == null) tilesRow?.getChildAt(0) else null)?.requestFocus()
    }

    private fun addRow(title: Int, sites: List<Site>, extra: ((SiteCard) -> Unit)? = null): LinearLayout {
        rows.addView(Ui.text(context, 28, Ui.p.textMid).apply {
            text = context.getString(title)
            setPadding(0, 32.px, 0, 18.px)
        })
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(8.px, 12.px, 96.px, 12.px)
        }
        sites.forEach { site ->
            line.addView(SiteCard(context, site).apply {
                setOnClickListener { if (moving == null) listener.onOpen(site.url) }
                extra?.invoke(this)
            })
        }
        rows.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(line)
        })
        return line
    }

    private fun findCard(match: (SiteCard) -> Boolean): SiteCard? {
        fun walk(v: View): SiteCard? = when {
            v is SiteCard && match(v) -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(rows)
    }

    /** Enters move mode for the tile showing [site]. */
    fun startMoving(site: Site) {
        val card = findCard { it.site?.id == site.id && it.parent === tilesRow } ?: return
        moving = card
        card.requestFocus()
        card.alpha = 0.75f
        Ui.animateScale(card, 1.12f, true)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Menu on a Quick access tile opens its options, like a long press of OK.
        if (moving == null && event.keyCode == KeyEvent.KEYCODE_MENU) {
            val focused = findFocus() as? SiteCard
            if (focused != null && focused.parent === tilesRow && focused.site != null) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) listener.onTileMenu(focused.site)
                return true
            }
        }
        val card = moving ?: return super.dispatchKeyEvent(event)
        val row = tilesRow ?: return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val i = row.indexOfChild(card)
                val j = i + if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                // The last child is the "Add site" card, which stays last.
                if (j in 0 until row.childCount - 1) {
                    row.removeViewAt(i)
                    row.addView(card, j)
                    card.requestFocus()
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> {
                moving = null
                card.alpha = 1f
                Ui.animateScale(card, 1.04f, false)
                db.reorderTiles((0 until row.childCount).mapNotNull { (row.getChildAt(it) as SiteCard).site?.id })
            }
        }
        return true
    }
}
