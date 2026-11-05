package app.sofabrowser.screen

import android.net.Uri
import android.widget.EditText
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.browser.WatchTarget
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.ui.BrowserMenu
import app.sofabrowser.ui.ListActivity
import app.sofabrowser.ui.PinPad
import kotlin.math.pow
import kotlin.math.roundToInt

/** The browser's menu over the page, and what its buttons do. */
class PageMenu(private val s: Screen, private val watch: Watch) : BrowserMenu.Actions {

    private val activity get() = s.activity
    private val prefs get() = s.prefs
    private val db by lazy { Db.get(activity) }
    private var menu: BrowserMenu? = null
    /** The page's zoom, in steps of 25 % from where it loaded. */
    private var zoomStep = 0

    val showing get() = menu?.isShowing == true

    fun show() {
        s.cursor.hide()
        // Built again after the buttons were rearranged in Settings.
        val layout = prefs.menuLayout
        val m = menu?.takeIf { it.layout == layout } ?: BrowserMenu(activity, this, layout).also { menu = it }
        m.show(state())
    }

    fun dismiss() {
        menu?.dismiss()
    }

    /** The menu, if it shows, follows the page. */
    fun refresh() {
        menu?.takeIf { it.isShowing }?.update(state())
    }

    /** Another page started loading: its zoom starts over. */
    fun pageStarted() {
        zoomStep = 0
    }

    private fun state(): BrowserMenu.State {
        val browser = s.browser
        val web = browser.web
        val url = web?.url.orEmpty()
        val host = Browser.hostOf(url)
        return BrowserMenu.State(
            address = addressText(url),
            canGoBack = web?.canGoBack() == true,
            canGoForward = web?.canGoForward() == true,
            loading = s.loading,
            bookmarked = url.isNotEmpty() && db.isBookmarked(url),
            adBlockOn = prefs.adBlock && (host == null || !Allowlist.contains(activity, host)),
            blocked = browser.adBlocker.blockedCount,
            desktop = prefs.userAgent != "mobile",
            pointer = s.cursor.cursorEnabled,
            zoomPercent = (100 * 1.25.pow(zoomStep)).roundToInt(),
            privateMode = browser.privateMode,
            allowPending = browser.pendingAllow() != null,
            autoWatchHere = WatchTarget.autoWatches(prefs.autoWatch, host, prefs.autoWatchSites),
        )
    }

    /** The query when on a results page of the current engine, otherwise the short address. */
    private fun addressText(url: String): String {
        val template = Uri.parse(prefs.searchEngine.template.replace("%s", "QUERY"))
        val param = template.queryParameterNames.firstOrNull { template.getQueryParameter(it) == "QUERY" }
        val uri = Uri.parse(url)
        if (param != null && uri.host == template.host) uri.getQueryParameter(param)?.let { return it }
        return UrlInput.display(url)
    }

    override fun onAddress() = s.startSearch(voice = false, current = s.web?.url)

    override fun onAction(action: BrowserMenu.Action) {
        val browser = s.browser
        val w = browser.web ?: return
        val url = w.url.orEmpty()
        val host = Browser.hostOf(url)
        val cursor = s.cursor
        when (action) {
            BrowserMenu.Action.ALLOW_ONCE -> { dismiss(); browser.allowOnce()?.let(s::openUrl) }
            BrowserMenu.Action.HOME -> s.showHome()
            BrowserMenu.Action.BACK -> if (w.canGoBack()) browser.goBack()
            BrowserMenu.Action.FORWARD -> if (w.canGoForward()) browser.goForward()
            BrowserMenu.Action.RELOAD -> { if (s.loading) w.stopLoading() else browser.reload(); dismiss() }
            BrowserMenu.Action.WATCH -> watch.start()
            BrowserMenu.Action.AUTO_WATCH -> if (host != null && !browser.privateMode) {
                if (prefs.autoWatch == WatchTarget.Auto.ALWAYS) { s.toast(R.string.auto_watch_every_site); return }
                // Chosen while off: the setting turns to "Chosen sites only", with this one.
                val on = prefs.autoWatch == WatchTarget.Auto.OFF || host !in prefs.autoWatchSites
                prefs.setAutoWatchSite(host, on)
                if (on) prefs.autoWatch = WatchTarget.Auto.SITES
                s.toast(if (on) R.string.auto_watch_site_added else R.string.auto_watch_site_removed)
                dismiss()
            }
            BrowserMenu.Action.FIND -> { dismiss(); s.openFind() }
            BrowserMenu.Action.BOOKMARK -> if (Browser.isWebUrl(url)) {
                if (db.isBookmarked(url)) db.removeBookmark(url) else db.addBookmark(w.title.orEmpty(), url)
                s.toast(if (db.isBookmarked(url)) R.string.bookmark_added else R.string.bookmark_removed)
            }
            BrowserMenu.Action.PIN -> if (Browser.isWebUrl(url)) {
                db.addTile(w.title?.ifBlank { null } ?: UrlInput.display(url), url)
                s.toast(R.string.pinned)
            }
            BrowserMenu.Action.HISTORY -> s.openList(ListActivity.MODE_HISTORY)
            BrowserMenu.Action.BOOKMARKS -> s.openList(ListActivity.MODE_BOOKMARKS)
            BrowserMenu.Action.ADBLOCK -> if (host != null) {
                if (!prefs.adBlock) { s.toast(R.string.adblock_globally_off); return }
                val allowed = !Allowlist.contains(activity, host)
                fun apply() {
                    Allowlist.set(activity, host, allowed)
                    s.toast(if (allowed) R.string.adblock_site_disabled else R.string.adblock_site_enabled)
                    dismiss()
                    browser.reload()
                }
                // Turning blocking back on never needs the PIN.
                if (allowed) PinPad.guard(activity, R.string.pin_title_site_adblock) { apply() } else apply()
            }
            BrowserMenu.Action.USER_AGENT -> pickUserAgent()
            BrowserMenu.Action.SCROLL -> { dismiss(); cursor.setScrollMode(true) }
            BrowserMenu.Action.POINTER -> if (host != null) {
                val keys = !prefs.keysToPage(host)
                prefs.setKeysToPage(host, keys)
                cursor.cursorEnabled = !keys
                s.toast(if (keys) R.string.pointer_off_toast else R.string.pointer_on_toast)
                dismiss()
            }
            BrowserMenu.Action.ZOOM_IN -> if (zoomStep < 6) { w.zoomBy(1.25f); zoomStep++ }
            BrowserMenu.Action.ZOOM_OUT -> if (zoomStep > -4) { w.zoomBy(0.8f); zoomStep-- }
            BrowserMenu.Action.SETTINGS -> { dismiss(); s.openSettings() }
            BrowserMenu.Action.DOWNLOADS -> { dismiss(); s.openDownloads() }
        }
        refresh()
    }

    private fun pickUserAgent() {
        val labels = activity.resources.getStringArray(R.array.user_agents)
        val current = Prefs.UA_MODES.indexOf(prefs.userAgent).coerceAtLeast(0)
        s.dialog().setTitle(R.string.user_agent)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val mode = Prefs.UA_MODES[which]
                if (mode == "custom") askCustomUserAgent() else setUserAgent(mode)
            }.show()
    }

    private fun askCustomUserAgent() {
        val field = EditText(activity).apply { setText(prefs.customUserAgent); isSingleLine = true }
        s.dialog().setTitle(R.string.custom_user_agent).setView(padded(activity, field))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.customUserAgent = field.text.toString().trim()
                setUserAgent("custom")
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun setUserAgent(mode: String) {
        prefs.userAgent = mode
        s.web?.let { WebViews.applyUserAgent(it, mode, prefs.customUserAgent); s.browser.reload() }
        dismiss()
    }
}
