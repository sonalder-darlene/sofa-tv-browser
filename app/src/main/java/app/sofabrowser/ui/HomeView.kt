package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.TransitionDrawable
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
import app.sofabrowser.ui.HomeBackground.Cover

/**
 * The start screen: a fixed top bar (logo, search pill, mic, labelled icon buttons) above rows of site cards:
 * Quick access (user-editable), Most visited and Bookmarks. All of it is local data. The buttons and the rows come
 * in the order Settings > Start page gives them ([HomeLayout]).
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
    private val buttons: Map<HomeLayout.Button, LabeledIconButton>
    private val buttonBar = LinearLayout(context).apply { gravity = Gravity.TOP; clipChildren = false; clipToPadding = false }
    /** The buttons in [buttonBar], in its order; they are put in again only when the layout changes them. */
    private var barButtons: List<HomeLayout.Button>? = null
    private val privateButton get() = buttons.getValue(HomeLayout.Button.PRIVATE)
    private val adBlockButton get() = buttons.getValue(HomeLayout.Button.ADBLOCK)
    private val downloadsButton get() = buttons.getValue(HomeLayout.Button.DOWNLOADS)
    private val micButton: LabeledIconButton
    private var tilesRow: LinearLayout? = null

    /** When non-null, the tile being moved with Left/Right; OK or Back drops it. */
    private var moving: SiteCard? = null

    /**
     * The start page's own picture while it is on screen (only ever one), the layer under the rows that draws it, and
     * what it was decoded for.
     */
    private var picture: Bitmap? = null
    private var pictureLayer: View? = null
    private var pictureFor: String? = null
    /** Counts picture loads, so a slow one that is no longer wanted is dropped. */
    private var pictureLoads = 0

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
        buttons = HomeLayout.Button.entries.associateWith { b ->
            button(b.icon, b.label) {
                when (b) {
                    HomeLayout.Button.BOOKMARKS -> listener.onBookmarks()
                    HomeLayout.Button.HISTORY -> listener.onHistory()
                    HomeLayout.Button.DOWNLOADS -> listener.onDownloads()
                    HomeLayout.Button.PRIVATE -> listener.onTogglePrivate()
                    HomeLayout.Button.ADBLOCK -> listener.onToggleAdBlock()
                    HomeLayout.Button.SETTINGS -> listener.onSettings()
                    HomeLayout.Button.EXIT -> listener.onExit()
                }
            }
        }
        top.addView(buttonBar)

        column.addView(top)
        column.addView(scroller, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column)
    }

    /**
     * Settings > Start page background. A picture is decoded for this screen when the start page shows and faded in
     * over the theme's background; [releasePicture] lets go of it while something else is in front.
     */
    private fun showBackground(privateMode: Boolean) {
        val choice = HomeBackground.decode(prefs.homeBackground, HomePicture.exists(context))
        if (privateMode || choice !is HomeBackground.Choice.Picture) {
            releasePicture()
            background = HomeBackground.drawable(choice, privateMode)
            return
        }
        val dim = HomeBackground.dim(prefs.homeDim)
        val wanted = "${HomePicture.stamp(context)} $dim ${Ui.p.dark}"
        if (wanted == pictureFor) return
        releasePicture()
        pictureFor = wanted
        val load = pictureLoads
        val dm = resources.displayMetrics
        HomePicture.load(context, maxOf(dm.widthPixels, dm.heightPixels), minOf(dm.widthPixels, dm.heightPixels)) { bitmap ->
            if (load != pictureLoads) return@load
            if (bitmap == null) { pictureFor = null; return@load }
            picture = bitmap
            background = Ui.background(false)
            pictureLayer = View(context).apply {
                background = TransitionDrawable(arrayOf(Ui.background(false), Cover(bitmap, Ui.p.bgMid, HomeBackground.scrim(dim, Ui.p.dark)))).apply {
                    isCrossFadeEnabled = true
                    startTransition(180)
                }
            }.also { addView(it, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
        }
    }

    /**
     * Lets go of the start page's picture (a web page or a video is in front); the next [refresh] decodes it again.
     * The picture's layer is taken out rather than given a new background: this view is usually hidden by then, and a
     * hidden view is not drawn again, so its last drawing would keep the picture's graphics memory. Taking the layer
     * out drops that drawing, so the picture can go at once.
     */
    fun releasePicture() {
        pictureLoads++
        pictureFor = null
        pictureLayer?.let { removeView(it) }
        pictureLayer = null
        picture?.recycle()
        picture = null
    }

    /** Shows the accent dot on Downloads while something is downloading. */
    fun setDownloadsActive(active: Boolean) = downloadsButton.set(
        if (active) R.drawable.ic_downloading else R.drawable.ic_download,
        context.getString(if (active) R.string.downloads_active else R.string.downloads))

    /** The card that opened a page, as its row's place in [rows] and its address: focused again when the page is left. */
    private var opened: Pair<Int, String>? = null

    fun refresh(privateMode: Boolean, adBlock: Boolean) {
        showBackground(privateMode)
        val layout = prefs.homeLayout
        micButton.visibility = if (prefs.voiceSearch) VISIBLE else GONE
        // A hidden Private button still shows while private browsing is on: it is the way out of it.
        val shown = layout.buttons.entries.filter { it.shown || privateMode && it.item == HomeLayout.Button.PRIVATE }.map { it.item }
        if (shown != barButtons) {
            barButtons = shown
            buttonBar.removeAllViews()
            shown.forEach { b ->
                buttonBar.addView(buttons.getValue(b), LinearLayout.LayoutParams(LabeledIconButton.WIDTH.px, LayoutParams.WRAP_CONTENT).apply {
                    marginStart = 12.px
                    topMargin = 4.px
                })
            }
        }
        privateButton.set(if (privateMode) R.drawable.ic_private_filled else R.drawable.ic_private,
            context.getString(if (privateMode) R.string.private_mode_on else R.string.private_mode_off))
        adBlockButton.set(if (adBlock) R.drawable.ic_shield_filled else R.drawable.ic_shield,
            context.getString(if (adBlock) R.string.adblock_on_short else R.string.adblock_off_short))
        setDownloadsActive(app.sofabrowser.downloads.DownloadEngine.activeCount > 0)
        val focusedUrl = (findFocus() as? SiteCard)?.site?.url
        rows.removeAllViews()
        rows.setPadding(96.px, 24.px, 0, 64.px)

        tilesRow = null
        for (row in layout.rows.shown) when (row) {
            HomeLayout.Row.QUICK_ACCESS -> tilesRow = addRow(R.string.quick_access, db.tiles()) { card ->
                card.setOnLongClickListener { card.site?.let(listener::onTileMenu); true }
            }.also { line ->
                line.addView(SiteCard(context, null, context.getDrawable(R.drawable.ic_add), context.getString(R.string.add_site)).apply {
                    setOnClickListener { listener.onAddTile() }
                })
            }
            HomeLayout.Row.MOST_VISITED -> if (!privateMode) db.mostVisited(10).takeIf { it.isNotEmpty() }?.let { addRow(R.string.most_visited, it) }
            // One title over all the lines of bookmarks.
            HomeLayout.Row.BOOKMARKS -> layout.bookmarkLines(db.bookmarks(layout.bookmarkSlots)).forEachIndexed { i, line ->
                addRow(if (i == 0) R.string.bookmarks else 0, line)
            }
        }

        // Back on the card that had focus; else the first card, or the search pill when no row is shown.
        val restore = focusedUrl?.let { url -> findCard { it.site?.url == url } }
        (restore ?: if (findFocus() == null) firstFocus() else null)?.requestFocus()
    }

    /** Back from a page: focus on the card that opened it, in the same row if it's still there, else as [refresh] does. */
    fun focusBack() {
        firstFocus().requestFocus()
        opened = null
    }

    private fun firstFocus(): View {
        val back = opened?.let { (at, url) ->
            (rows.getChildAt(at) as? ViewGroup)?.let { row -> findCard(row) { it.site?.url == url } } ?: findCard { it.site?.url == url }
        }
        return back ?: findCard { true } ?: searchPill
    }

    /** A row of cards, under [title] (0: none, for the second and later lines of bookmarks). */
    private fun addRow(title: Int, sites: List<Site>, extra: ((SiteCard) -> Unit)? = null): LinearLayout {
        if (title != 0) rows.addView(Ui.text(context, 28, Ui.p.textMid).apply {
            text = context.getString(title)
            setPadding(0, 32.px, 0, 18.px)
        })
        val line = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(8.px, 12.px, 96.px, 12.px)
        }
        val at = rows.childCount
        sites.forEach { site ->
            line.addView(SiteCard(context, site).apply {
                setOnClickListener {
                    if (moving != null) return@setOnClickListener
                    opened = at to site.url
                    listener.onOpen(site.url)
                }
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

    private fun findCard(within: View = rows, match: (SiteCard) -> Boolean): SiteCard? {
        fun walk(v: View): SiteCard? = when {
            v is SiteCard && match(v) -> v
            v is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { walk(v.getChildAt(it)) }
            else -> null
        }
        return walk(within)
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
