package app.sofabrowser.screen

import android.net.Uri
import android.widget.EditText
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.browser.BrowseAs
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.SiteModes
import app.sofabrowser.browser.TabList
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.browser.WatchTarget
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
        s.tabs.current.zoomStep = 0
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
            desktop = browser.browseAs(url) != "mobile",
            browseAs = activity.resources.getStringArray(R.array.user_agents).getOrElse(Prefs.UA_MODES.indexOf(browser.browseAs(url))) { "" }.removeSuffix("…"),
            browseAsHere = browser.ownBrowseAs(url),
            pointer = s.cursor.cursorEnabled,
            zoomPercent = (100 * 1.25.pow(s.tabs.current.zoomStep)).roundToInt(),
            privateMode = browser.privateMode,
            allowPending = browser.pendingAllow() != null,
            autoWatchHere = WatchTarget.autoWatches(prefs.autoWatch, host, prefs.autoWatchSites),
            tabs = s.tabs.list.let { tabs ->
                TabList.names(tabs.map { it.title }, tabs.map { it.address }, activity.getString(R.string.new_tab), UrlInput::display)
                    .mapIndexed { i, name -> BrowserMenu.TabChip(name, tabs[i] === s.tabs.current, address = tabs[i].address != null && name != tabs[i].title) }
            },
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

    /** How the close dialog names [tab]: its page's title, else its address, else "New tab". */
    private fun label(tab: Tab): String =
        tab.title ?: tab.address?.let(UrlInput::display)?.takeIf { it.isNotEmpty() } ?: activity.getString(R.string.new_tab)

    /** OK on another tab brings it into sight; on the one in sight, it asks what to close. */
    override fun onTab(index: Int) {
        val tab = s.tabs.list.getOrNull(index) ?: return
        if (tab === s.tabs.current) onTabHeld(index) else s.selectTab(tab)
    }

    override fun onTabHeld(index: Int) {
        val tab = s.tabs.list.getOrNull(index) ?: return
        val others = s.tabs.list.filter { it !== tab }
        val choices = listOfNotNull(
            R.string.close_tab to { s.closeTabs(listOf(tab)) },
            (R.string.close_other_tabs to { s.closeTabs(others) }).takeIf { others.isNotEmpty() },
            R.string.close_all_tabs to { s.closeTabs(s.tabs.list.toList()) },
        )
        s.dialog().setTitle(label(tab))
            .setItems(choices.map { activity.getString(it.first) }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    override fun onNewTab() = s.newTab()

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
                if (db.isBookmarked(url)) db.removeBookmark(url) else db.addBookmark(s.browser.title.orEmpty(), url)
                s.toast(if (db.isBookmarked(url)) R.string.bookmark_added else R.string.bookmark_removed)
            }
            BrowserMenu.Action.PIN -> if (Browser.isWebUrl(url)) {
                db.addTile(s.browser.title?.ifBlank { null } ?: UrlInput.display(url), url)
                s.toast(R.string.pinned)
            }
            BrowserMenu.Action.HISTORY -> s.openList(ListActivity.MODE_HISTORY)
            BrowserMenu.Action.BOOKMARKS -> s.openList(ListActivity.MODE_BOOKMARKS)
            BrowserMenu.Action.ADBLOCK -> if (host != null) {
                if (!prefs.adBlock) { s.toast(R.string.adblock_globally_off); return }
                val allowed = !Allowlist.contains(activity, host)
                fun apply() {
                    Allowlist.set(activity, host, allowed, browser.privateMode)
                    s.toast(when {
                        !allowed -> R.string.adblock_site_enabled
                        browser.privateMode -> R.string.adblock_site_disabled_private
                        else -> R.string.adblock_site_disabled
                    })
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
            BrowserMenu.Action.ZOOM_IN -> s.tabs.current.let { t -> if (t.zoomStep < 6) { w.zoomBy(1.25f); t.zoomStep++ } }
            BrowserMenu.Action.ZOOM_OUT -> s.tabs.current.let { t -> if (t.zoomStep > -4) { w.zoomBy(0.8f); t.zoomStep-- } }
            BrowserMenu.Action.SETTINGS -> { dismiss(); s.openSettings() }
            BrowserMenu.Action.DOWNLOADS -> { dismiss(); s.openDownloads() }
        }
        refresh()
    }

    /** "Browse as" for the page in front: the mode, then (on a web page) whether for this site or all sites. */
    private fun pickUserAgent() {
        val labels = activity.resources.getStringArray(R.array.user_agents)
        val url = s.web?.url
        val current = Prefs.UA_MODES.indexOf(s.browser.browseAs(url)).coerceAtLeast(0)
        s.dialog().setTitle(R.string.user_agent)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val mode = Prefs.UA_MODES[which]
                if (mode == "custom") askCustomUserAgent(url) else pickScope(url, mode)
            }.show()
    }

    /** The custom text is one for every site that uses it, as in Settings. */
    private fun askCustomUserAgent(url: String?) {
        val field = EditText(activity).apply { setText(prefs.customUserAgent); isSingleLine = true }
        s.dialog().setTitle(R.string.custom_user_agent).setView(padded(activity, field))
            .setPositiveButton(R.string.ok) { _, _ ->
                val text = field.text.toString().trim()
                val changed = text != prefs.customUserAgent
                prefs.customUserAgent = text
                pickScope(url, "custom", changed)
            }.setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * Asks "This site" (first, so OK takes it) or "All sites" for [mode], when the page is a web page and the choice
     * changes something; [changed]: the custom text changed, which counts as a change for every site using it.
     */
    private fun pickScope(url: String?, mode: String, changed: Boolean = false) {
        val host = Browser.hostOf(url)?.takeIf { Browser.isWebUrl(url) }
        if (host == null) { setAllSites(null, mode); return }
        if (mode == s.browser.browseAs(url) && !changed) { dismiss(); return }
        val site = SiteModes.siteOf(host) ?: host
        val label = activity.resources.getStringArray(R.array.user_agents).getOrElse(Prefs.UA_MODES.indexOf(mode)) { mode }.removeSuffix("…")
        s.dialog().setTitle(activity.getString(R.string.browse_as_scope_title, label))
            .setItems(arrayOf(activity.getString(R.string.browse_as_this_site, site), activity.getString(R.string.browse_as_all_sites))) { _, which ->
                if (which == 0) setThisSite(host, mode) else setAllSites(host, mode)
            }.show()
    }

    /** This site only: stored, or in private browsing kept until it ends. */
    private fun setThisSite(host: String, mode: String) {
        val private = s.browser.privateMode
        BrowseAs.sites(activity).choose(host, mode, prefs.userAgent, private)
        if (private) s.toast(R.string.browse_as_site_private)
        applied()
    }

    /** The setting, for every site without a choice of its own; the site in front ([host]) drops its own too. */
    private fun setAllSites(host: String?, mode: String) {
        prefs.userAgent = mode
        host?.let { BrowseAs.sites(activity).follow(it) }
        applied()
    }

    /**
     * The page in front loads again as chosen. Every other tab presents its own site's choice from its next load on; a
     * page loading meanwhile starts over (WebView reloads a loading page whose user agent changes).
     */
    private fun applied() {
        s.tabs.list.forEach { it.browser.browseAsChanged() }
        s.browser.reload()
        dismiss()
    }
}
