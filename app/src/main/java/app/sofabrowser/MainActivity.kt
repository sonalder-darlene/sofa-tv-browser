package app.sofabrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewFeature
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.adblock.FilterList
import app.sofabrowser.adblock.SiteAllowlist
import app.sofabrowser.adblock.Sites
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.CursorLayout
import app.sofabrowser.browser.FullscreenLayer
import app.sofabrowser.browser.StreamCopyProvider
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.browser.VideoBar
import app.sofabrowser.browser.VideoInfo
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.VideoSource
import app.sofabrowser.browser.VideoState
import app.sofabrowser.browser.WatchTarget
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.HistoryRetention
import app.sofabrowser.data.Site
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.screen.DownloadStarter
import app.sofabrowser.screen.LinkActions
import app.sofabrowser.screen.PadInput
import app.sofabrowser.screen.PageDialogs
import app.sofabrowser.screen.PageMenu
import app.sofabrowser.screen.Screen
import app.sofabrowser.screen.Tab
import app.sofabrowser.screen.Tabs
import app.sofabrowser.screen.VideoSession
import app.sofabrowser.screen.Watch
import app.sofabrowser.screen.padded
import app.sofabrowser.ui.DownloadsActivity
import app.sofabrowser.ui.FindBar
import app.sofabrowser.ui.VoiceInput
import app.sofabrowser.ui.HomeView
import app.sofabrowser.ui.NoEngineActivity
import app.sofabrowser.ui.OpenAtStart
import app.sofabrowser.ui.ListActivity
import app.sofabrowser.ui.Palette
import app.sofabrowser.ui.SearchActivity
import app.sofabrowser.ui.PinGate
import app.sofabrowser.ui.PinPad
import app.sofabrowser.ui.SettingsActivity
import app.sofabrowser.ui.SetupActivity
import app.sofabrowser.ui.Ui
import app.sofabrowser.ui.px
import app.sofabrowser.update.Updater

/**
 * The single activity for the home screen and the browser; settings and lists are separate screens. The parts of the
 * browser screen are in [app.sofabrowser.screen]; this class holds the views, the keys, the start page, the tabs and
 * their switching, and the page events of the tab in sight, and passes each part's events on to it.
 */
class MainActivity : Activity(), HomeView.Listener, Browser.Host, CursorLayout.Listener, FindBar.Actions, Screen {

    override val activity: Activity get() = this
    override lateinit var prefs: Prefs
        private set
    private val db by lazy { Db.get(this) }
    override lateinit var tabs: Tabs
        private set
    override val browser: Browser get() = tabs.current.browser

    override lateinit var root: FrameLayout
        private set
    private lateinit var browserLayer: FrameLayout
    override lateinit var cursor: CursorLayout
        private set
    private lateinit var progress: View
    private lateinit var scrollPill: TextView
    override lateinit var fullscreen: FullscreenLayer
        private set
    private lateinit var home: HomeView
    override lateinit var findBar: FindBar
        private set
    private val voiceInput by lazy { VoiceInput(this, findVoiceListener) }

    private val watch = Watch(this)
    override val menu = PageMenu(this, watch)
    private val video by lazy { VideoSession(this, watch, browserLayer) }
    private val downloads = DownloadStarter(this) { home.setDownloadsActive(it) }
    private val links = LinkActions(this, downloads)
    private val dialogs = PageDialogs(this)
    private val pad by lazy { PadInput(this, video, watch) }

    override val loading get() = tabs.current.loading
    private var lastBackOnHome = 0L
    /** The palette this screen was built with; Settings may switch the shared one. */
    private lateinit var palette: Palette

    override val homeShown get() = home.visibility == View.VISIBLE
    override var started = false
        private set
    private fun recordsHistory(tab: Tab) = HistoryRetention.records(prefs.historyDays) && !tab.private

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing here works without a WebView: say so rather than close at once.
        if (!WebViews.usable(this)) {
            startActivity(Intent(this, NoEngineActivity::class.java))
            finish()
            return
        }
        Ui.init(this)
        palette = Ui.p
        prefs = Prefs(this)
        // Private mode is a session: a cold start always begins in normal mode, with nothing left over.
        prefs.privateMode = false
        Browser.deletePrivateProfiles(this)
        forgetPrivateAllows()
        // Copies for other players are needed only while one starts; a kill or crash may have left some.
        StreamCopyProvider.clear(this)
        tabs = Tabs(this, prefs) { tab, private -> Browser(this, prefs, TabHost(tab), private) }
        // The normal tabs come back unloaded: each page loads when it's chosen. Nothing is kept with "Clear data on exit".
        if (!prefs.clearOnExit) tabs.restore(prefs.tabs)
        // Only a period prunes at start: "Never" removes saved pages when chosen in Settings, so updating from
        // 1.0.0 with history off (now "Never") keeps them, as 1.0.0 did.
        if (prefs.historyDays > 0) db.pruneHistory(prefs.historyDays)
        DownloadEngine.init(this)
        // Private downloads don't outlive a session.
        DownloadEngine.forgetPrivate()
        if (SetupActivity.needed(this, prefs)) startActivity(Intent(this, SetupActivity::class.java))
        // Opt-in, and only in builds and copies that no app store updates; an offer waits for the home screen.
        else if (savedInstanceState == null) {
            Updater.checkOnLaunch(this) { homeShown && !isFinishing }
            dialogs.warnIfOldWebView()
        }

        root = FrameLayout(this)
        browserLayer = FrameLayout(this).apply { visibility = View.GONE }
        cursor = CursorLayout(this).apply { listener = this@MainActivity }
        browserLayer.addView(cursor, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        progress = View(this).apply { setBackgroundColor(Ui.BRAND); pivotX = 0f; scaleX = 0f }
        browserLayer.addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 6.px, Gravity.TOP))
        scrollPill = Ui.text(this, 26, Ui.DARK.text).apply {
            text = getString(R.string.scroll_mode_hint)
            background = Ui.rounded(0xB3000000.toInt(), 48)
            setPadding(36.px, 18.px, 36.px, 18.px)
            visibility = View.GONE
        }
        browserLayer.addView(scrollPill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 120.px, 120.px) })
        findBar = FindBar(this, this)
        browserLayer.addView(findBar, FrameLayout.LayoutParams(1300.px, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 40.px })
        root.addView(browserLayer)

        fullscreen = FullscreenLayer(this, VideoBar(this, video))
        root.addView(fullscreen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        home = HomeView(this, this)
        root.addView(home, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (!handleIntent(intent)) {
            // Settings > Open at start; a link from another app always wins. A screen Android rebuilt shows what it showed.
            val at = OpenAtStart.address(prefs.openAtStart, prefs.startPage, prefs.lastPage, restored = savedInstanceState != null)
            when {
                savedInstanceState?.getBoolean(STATE_PAGE_SHOWN) == true && tabs.current.address != null -> showPage()
                at == null -> showHome()
                tabs.current.address == at -> showPage()
                // The chosen page gets a tab of its own, the same one each time when it's still on that site.
                prefs.openAtStart == OpenAtStart.Choice.WEB_PAGE -> openInTab(tabs.list.firstOrNull { sameSite(it.address, at) }, at)
                else -> openUrl(at)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Not a private page: the next screen starts in normal browsing.
        outState.putBoolean(STATE_PAGE_SHOWN, !homeShown && !tabs.privateShown)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf(Browser::isWebUrl)
            Intent.ACTION_WEB_SEARCH, Intent.ACTION_SEARCH -> intent.getStringExtra("query")?.let { UrlInput.resolve(it, prefs.searchEngine) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { UrlInput.resolve(it, prefs.searchEngine) }
            else -> null
        } ?: return false
        // A link from another app (or a phone remote) may come while the menu is open: the page it opens gets the keys.
        menu.dismiss()
        if (findBar.showing) closeFind()
        // It gets a tab of its own, so the page that was open stays; in the tab in sight when that one is empty or no
        // other tab fits.
        if (tabs.current.address == null) openUrl(url) else openInTab(null, url)
        return true
    }

    // Screens

    /** The tab in sight has its WebView, and it's the one in the pointer's layout. */
    private fun ensureWeb() {
        val view = web ?: browser.create().also { v -> v.setFindListener { active, total, _ -> findBar.setMatches(active, total) } }
        if (view.parent === cursor) return
        (0 until cursor.childCount).map(cursor::getChildAt).filterIsInstance<WebView>().forEach(cursor::removeView)
        cursor.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun openUrl(url: String) {
        if (url.isBlank()) return
        // An unloaded page this tab kept is replaced too.
        tabs.current.forget()
        ensureWeb()
        // What the resting page tried to open, and its paused sound, go with it: this address replaces it.
        browser.forgetHeld()
        showBrowser(wakeSound = false)
        load(url)
    }

    private fun load(url: String) {
        browser.userNavigation()
        // Older WebViews can't add Sec-GPC to every request; at least send it with navigations we start.
        if (prefs.sendGpc && !WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) {
            web?.loadUrl(url, mapOf("Sec-GPC" to "1"))
        } else {
            web?.loadUrl(url)
        }
    }

    /** Shows the tab in sight's page, loading it again when it was unloaded, or the start page when it has none. */
    private fun showPage() {
        val tab = tabs.current
        if (tab.live) { ensureWeb(); showBrowser(); return }
        val url = tab.address ?: return showHome()
        ensureWeb()
        // Whatever the unloaded page held is gone with it; it starts over now, in sight.
        browser.forgetHeld()
        showBrowser(wakeSound = false)
        browser.userNavigation()
        if (!tab.bringBack(web ?: return)) load(url)
    }

    // Tabs

    /**
     * Brings [tab] into sight. The tab before rests like a page behind the start page (its media, Web Audio, timers
     * and navigations held) and its WebView is paused; its page stays loaded while the TV has room for it.
     */
    override fun selectTab(tab: Tab) {
        if (tab !in tabs.list) return
        if (tab !== tabs.current) {
            leaveTab()
            tabs.select(tab)
        }
        enterTab()
    }

    private fun leaveTab() {
        menu.dismiss()
        if (findBar.showing) closeFind()
        watch.stop()
        pageOutOfSight()
        web?.onPause()
        web?.let(cursor::removeView)
    }

    /** The tab now in sight takes the screen: its page, or the start page when it has none. */
    private fun enterTab() {
        val tab = tabs.current
        progress.animate().cancel()
        progress.visibility = if (tab.loading) View.VISIBLE else View.GONE
        if (!tab.loading) progress.scaleX = 0f
        if (cursor.scrollMode) cursor.setScrollMode(false)
        cursor.cursorEnabled = Browser.hostOf(tab.address)?.let { !prefs.keysToPage(it) } ?: true
        video.onPlaying(browser.playing)
        if (tab.address == null) showHome() else showPage()
        tabs.trim()
    }

    override fun newTab() {
        val tab = tabs.add() ?: return toast(resources.getQuantityString(R.plurals.tabs_full, Tabs.MAX, Tabs.MAX))
        selectTab(tab)
    }

    override fun openInNewTab(url: String) {
        val tab = tabs.add(after = tabs.current) ?: return toast(resources.getQuantityString(R.plurals.tabs_full, Tabs.MAX, Tabs.MAX))
        openInTab(tab, url)
    }

    /** Opens [url] in [tab]: a new one when null, or the tab in sight when the side is full. */
    private fun openInTab(tab: Tab?, url: String) {
        val target = tab ?: tabs.add(after = tabs.current) ?: tabs.current
        if (target !== tabs.current) { leaveTab(); tabs.select(target) }
        openUrl(url)
        tabs.trim()
    }

    /** Closes [which]; when the tab in sight is among them, the one seen before it comes into sight. */
    override fun closeTabs(which: List<Tab>) {
        val inSight = tabs.current in which
        if (inSight) leaveTab()
        which.forEach(tabs::close)
        if (inSight) enterTab() else menu.refresh()
    }

    private fun sameSite(a: String?, b: String): Boolean {
        val x = Browser.hostOf(a)?.lowercase() ?: return false
        val y = Browser.hostOf(b)?.lowercase() ?: return false
        return Sites.same(x, y)
    }

    /**
     * Each tab's page events. The tab in sight passes them on to the screen; one out of sight keeps its loading state
     * and History, and refuses whatever would need the screen (a certificate question, DRM, fullscreen, a download).
     */
    private inner class TabHost(private val tab: Tab) : Browser.Host {
        private val inSight get() = tabs.isCurrent(tab)

        override fun onPageStarted(url: String) {
            if (inSight) this@MainActivity.onPageStarted(url) else { tab.loading = true; tab.zoomStep = 0 }
        }
        override fun onPageFinished(url: String) {
            if (inSight) this@MainActivity.onPageFinished(url) else tab.loading = false
        }
        override fun onProgress(percent: Int) {
            if (inSight) this@MainActivity.onProgress(percent) else if (percent >= 100) tab.loading = false
        }
        override fun onVisited(url: String) = visited(tab, url)
        override fun onTitle(url: String, title: String) = titled(tab, url, title)
        override fun onIcon(url: String, icon: Bitmap) = iconed(tab, url, icon)
        override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
            if (inSight) this@MainActivity.onEnterFullscreen(view, callback) else callback.onCustomViewHidden()
        }
        override fun onExitFullscreen() { if (inSight) this@MainActivity.onExitFullscreen() }
        override fun onVideoState(state: VideoState) { if (inSight) this@MainActivity.onVideoState(state) }
        override fun onPlaying(playing: Boolean) { if (inSight) this@MainActivity.onPlaying(playing) }
        override fun onVideoInfo(info: VideoInfo) { if (inSight) this@MainActivity.onVideoInfo(info) }
        override fun onVideoCandidate(candidate: WatchTarget.Candidate) { if (inSight) this@MainActivity.onVideoCandidate(candidate) }
        override fun onVideoSource(source: VideoSource) { if (inSight) this@MainActivity.onVideoSource(source) }
        override fun onVideoFrameTimes(times: List<Double>) { if (inSight) this@MainActivity.onVideoFrameTimes(times) }
        override fun onVideoStarted(frame: String) { if (inSight) this@MainActivity.onVideoStarted(frame) }
        override fun onSslError(handler: SslErrorHandler, error: SslError) {
            if (inSight) this@MainActivity.onSslError(handler, error) else handler.cancel()
        }
        override fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit) {
            if (inSight) this@MainActivity.onProtectedMediaRequest(origin, grant) else grant(false)
        }
        override fun onSiteBlocked(url: String, list: FilterList, replaced: Boolean) {
            if (inSight) this@MainActivity.onSiteBlocked(url, list, replaced)
        }
        override fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long) {
            if (inSight) this@MainActivity.onDownload(url, userAgent, contentDisposition, mimeType, length)
        }
        override fun onBlockedLink(url: String) { if (inSight) this@MainActivity.onBlockedLink(url) }
        override fun onPopupBlocked(url: String?) { if (inSight) this@MainActivity.onPopupBlocked(url) }
        override fun onRedirectBlocked() { if (inSight) this@MainActivity.onRedirectBlocked() }
        // The page renderer is shared, so every loaded tab hears of it: those out of sight keep only their address.
        override fun onRendererGone() { if (inSight) this@MainActivity.onRendererGone() else tab.drop(history = false) }
    }

    private fun showBrowser(wakeSound: Boolean = true) {
        home.visibility = View.GONE
        home.releasePicture()
        applyKeepAwake()
        browserLayer.visibility = View.VISIBLE
        cursor.speedLevel = prefs.cursorSpeed
        pageInSight(wakeSound)
        web?.onResume()
        web?.requestFocus()
    }

    override fun showHome() {
        menu.dismiss()
        if (findBar.showing) closeFind()
        // Leaving a page for the home screen shouldn't leave it playing, or going anywhere, behind it.
        pageOutOfSight()
        web?.onPause()
        home.refresh(prefs.privateMode, prefs.adBlock)
        home.visibility = View.VISIBLE
        applyKeepAwake()
        browserLayer.visibility = View.INVISIBLE
        if (home.findFocus() == null) home.searchPill.requestFocus()
        Updater.showPending(this)
    }

    /**
     * The page went out of sight (the start page, or Sofa left): its media elements and Web Audio pause in every frame,
     * its timers stop, and what it tries to open waits ([Browser.hidden]). WebView's own onPause pauses media elements
     * only, so a page could go on sounding through Web Audio, or send itself to another site that plays.
     */
    private fun pageOutOfSight() {
        if (browser.hidden) return
        browser.rest()
        VideoRelay.send(web, "rest")
        // Paused timers hold back the page's messages too, so "rest" goes through every frame first.
        browserLayer.removeCallbacks(pauseTimers)
        browserLayer.postDelayed(pauseTimers, TIMERS_PAUSE_MS)
    }

    /**
     * For every WebView of the app, which is fine: the start page and the rest of Sofa are native. Any loaded tab's view
     * can ask, for a new tab on the start page has none of its own.
     */
    private val pauseTimers = Runnable { if (browser.hidden) (web ?: tabs.anyWeb)?.pauseTimers() }

    /** The page is in sight again; [sound] resumes the Web Audio that [pageOutOfSight] paused. */
    private fun pageInSight(sound: Boolean = true) {
        browserLayer.removeCallbacks(pauseTimers)
        // Timers stay paused across a rebuilt screen (a theme change), so they're resumed whatever this one did.
        web?.resumeTimers()
        if (!browser.hidden) return
        val held = browser.wake()
        if (sound) VideoRelay.send(web, "wake")
        if (held != null) toast(getString(R.string.page_held_allow, Sites.of(Browser.hostOf(held)?.lowercase() ?: held)))
    }

    // Keys

    // TV remotes deliver BACK as a key, and BACK drives the menu, scroll mode and fullscreen here, so the
    // manifest opts out of predictive back (enableOnBackInvokedCallback="false") to keep receiving it.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) watch.stop()
        if (pad.key(event)) return true
        if (fullscreen.visibility == View.VISIBLE) return video.key(event) || super.dispatchKeyEvent(event)
        if (findBar.showing && !homeShown) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (voiceInput.listening) voiceInput.cancel() else closeFind()
                }
                return true
            }
            // While the bar has focus, the D-pad moves between its buttons instead of the pointer.
            if (findBar.hasFocus()) return super.dispatchKeyEvent(event)
        }
        if (homeShown) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) backOnHome()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (down && event.repeatCount == 0) when {
                    cursor.scrollMode -> cursor.setScrollMode(false)
                    prefs.backGoesBack && web?.canGoBack() == true -> browser.goBack()
                    else -> menu.show()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { if (down) menu.show(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) cursor.scrollPages(-1f); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) cursor.scrollPages(1f); return true }
        }
        if (video.mediaKey(event)) return true
        return cursor.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        pad.motion(event) || super.dispatchGenericMotionEvent(event)

    private fun backOnHome() {
        if (tabs.current.address != null) { showPage(); return }
        // An empty tab next to others was a New tab nobody used: Back closes it again.
        if (tabs.list.size > 1) { closeTabs(listOf(tabs.current)); return }
        val now = SystemClock.uptimeMillis()
        if (now - lastBackOnHome < 2500) finish()
        else Toast.makeText(this, R.string.back_again_to_exit, Toast.LENGTH_SHORT).show()
        lastBackOnHome = now
    }

    override fun onScrollModeChanged(active: Boolean) {
        scrollPill.visibility = if (active) View.VISIBLE else View.GONE
    }

    override fun onHold(): Boolean = links.hold()

    // Home actions

    override fun onOpen(url: String) = openUrl(url)
    override fun onSearch() = startSearch(voice = false, current = null)
    override fun onVoiceSearch() = startSearch(voice = true, current = null)
    override fun onBookmarks() = openList(ListActivity.MODE_BOOKMARKS)
    override fun onHistory() = openList(ListActivity.MODE_HISTORY)
    override fun onSettings() = openSettings()
    override fun onDownloads() = openDownloads()

    /** Settings may offer the open page (in sight or resting) as the one to open at start; never a private one. */
    override fun openSettings() = startActivity(Intent(this, SettingsActivity::class.java).apply {
        tabs.current.address?.takeIf { !tabs.current.private && Browser.isWebUrl(it) }?.let { putExtra(SettingsActivity.EXTRA_OPEN_PAGE, it) }
    })

    override fun openDownloads() = startActivity(Intent(this, DownloadsActivity::class.java))

    /**
     * Straight to the Android home screen, then closes Sofa. On a box plugged into a TV, the remote's Home key may
     * go to the TV's own home instead, so this is the reliable way back. Downloads keep running.
     */
    override fun onExit() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onToggleAdBlock() {
        if (prefs.adBlock) PinPad.guard(this, R.string.pin_title_adblock) { toggleAdBlock() } else toggleAdBlock()
    }

    private fun toggleAdBlock() {
        prefs.adBlock = !prefs.adBlock
        toast(if (prefs.adBlock) R.string.adblock_on else R.string.adblock_off)
        home.refresh(prefs.privateMode, prefs.adBlock)
    }

    override fun onTogglePrivate() {
        // Leaving private browsing never needs the PIN.
        if (!prefs.privateMode) PinPad.guard(this, R.string.pin_title_private) { togglePrivate() } else togglePrivate()
    }

    private fun togglePrivate() {
        val entering = !prefs.privateMode
        // The tabs of the side left rest out of sight: normal ones wait unloaded, private ones close with all they held.
        leaveTab()
        if (entering) { Browser.newPrivateSession(); tabs.enterPrivate() }
        else {
            tabs.leavePrivate()
            if (Browser.profilesSupported) Browser.deletePrivateProfiles(this) else WebViews.clearBrowsingData(this, null)
        }
        // Stream copies hold what was watched (signed stream addresses); none outlives either side of the switch.
        StreamCopyProvider.clear(this)
        video.takeover.forgetPrivateChoices()
        forgetPrivateAllows()
        prefs.privateMode = entering
        if (entering) {
            dialog().setTitle(R.string.private_mode)
                .setMessage(if (Browser.profilesSupported) R.string.private_mode_info else R.string.private_mode_info_fallback)
                .setPositiveButton(android.R.string.ok, null).show()
        } else {
            DownloadEngine.forgetPrivate()
            toast(R.string.private_mode_left)
        }
        home.refresh(prefs.privateMode, prefs.adBlock)
    }

    override fun onTileMenu(site: Site) {
        val items = arrayOf(getString(R.string.open), getString(R.string.move), getString(R.string.edit), getString(R.string.remove))
        dialog().setTitle(site.title).setItems(items) { _, which ->
            when (which) {
                0 -> openUrl(site.url)
                1 -> home.startMoving(site)
                2 -> editTile(site)
                3 -> { db.removeTile(site.id); home.refresh(prefs.privateMode, prefs.adBlock) }
            }
        }.show()
    }

    override fun onAddTile() = editTile(null)

    private fun editTile(site: Site?) {
        val title = EditText(this).apply { hint = getString(R.string.tile_name); setText(site?.title); isSingleLine = true }
        val address = EditText(this).apply {
            hint = getString(R.string.tile_address)
            setText(site?.url)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(title); addView(address) }
        dialog().setTitle(if (site == null) R.string.add_site else R.string.edit).setView(padded(this, box))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = UrlInput.resolve(address.text.toString(), prefs.searchEngine)
                if (!Browser.isWebUrl(url)) return@setPositiveButton
                val name = title.text.toString().trim().ifEmpty { Db.prettyHost(Browser.hostOf(url).orEmpty()) }
                if (site == null) db.addTile(name, url) else db.updateTile(site.id, name, url)
                home.refresh(prefs.privateMode, prefs.adBlock)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // Other screens

    override fun startSearch(voice: Boolean, current: String?) {
        menu.dismiss()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SearchActivity::class.java)
            .putExtra(SearchActivity.EXTRA_VOICE, voice)
            .putExtra(SearchActivity.EXTRA_PRIVATE, prefs.privateMode)
            .putExtra(SearchActivity.EXTRA_CURRENT_URL, current), REQ_URL)
    }

    override fun openList(mode: String) {
        menu.dismiss()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, ListActivity::class.java).putExtra(ListActivity.EXTRA_MODE, mode), REQ_URL)
    }

    @Deprecated("Framework activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_URL && resultCode == RESULT_OK) data?.getStringExtra(SearchActivity.EXTRA_URL)?.let(::openUrl)
    }

    // Browser events

    override fun onPageStarted(url: String) {
        if (findBar.showing) closeFind()
        tabs.current.loading = true
        menu.pageStarted()
        progress.visibility = View.VISIBLE
        val host = Browser.hostOf(url)
        cursor.cursorEnabled = host == null || !prefs.keysToPage(host)
        if (cursor.scrollMode) cursor.setScrollMode(false)
        watch.pageStarted(url)
        menu.refresh()
    }

    override fun onPageFinished(url: String) {
        tabs.current.loading = false
        progress.animate().cancel()
        progress.visibility = View.GONE
        progress.scaleX = 0f
        menu.refresh()
        if (Browser.isWebUrl(url)) watch.pageFinished(url)
    }

    override fun onProgress(percent: Int) {
        progress.animate().scaleX(percent / 100f).setDuration(150).start()
        if (percent >= 100) onPageFinished(web?.url.orEmpty())
    }

    override fun onVisited(url: String) = visited(tabs.current, url)
    override fun onTitle(url: String, title: String) = titled(tabs.current, url, title)
    override fun onIcon(url: String, icon: Bitmap) = iconed(tabs.current, url, icon)

    private fun visited(tab: Tab, url: String) {
        val w = tab.browser.web
        if (recordsHistory(tab) && Browser.isWebUrl(url)) db.recordVisit(w?.title?.takeIf { w.url == url }.orEmpty(), url)
    }

    private fun titled(tab: Tab, url: String, title: String) {
        if (recordsHistory(tab)) db.updateHistoryTitle(url, title)
    }

    private fun iconed(tab: Tab, url: String, icon: Bitmap) {
        if (!tab.private) Favicons.get(this).save(url, icon)
    }

    override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) = video.enter(view, callback)
    override fun onExitFullscreen() = video.exit()
    override fun onVideoState(state: VideoState) = video.onVideoState(state)
    override fun onPlaying(playing: Boolean) = video.onPlaying(playing)
    override fun onVideoInfo(info: VideoInfo) = video.onVideoInfo(info)
    override fun onVideoCandidate(candidate: WatchTarget.Candidate) = watch.onCandidate(candidate)
    override fun onVideoSource(source: VideoSource) = video.sources.onSource(source)
    override fun onVideoFrameTimes(times: List<Double>) = video.onVideoFrameTimes(times)
    override fun onVideoStarted(frame: String) = watch.onVideoStarted(frame)
    override fun onSslError(handler: SslErrorHandler, error: SslError) = dialogs.sslError(handler, error)
    override fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit) = dialogs.protectedMedia(origin, grant)
    override fun onSiteBlocked(url: String, list: FilterList, replaced: Boolean) = dialogs.siteBlocked(url, list, replaced)

    override fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long) =
        downloads.start(url, userAgent, contentDisposition, mimeType, length, waited = false)

    override fun onBlockedLink(url: String) = toast(R.string.link_blocked)

    override fun onPopupBlocked(url: String?) = toast(if (url != null) R.string.popup_blocked_allow else R.string.popup_blocked)

    override fun onRedirectBlocked() = toast(R.string.redirect_blocked_allow)

    override fun onRendererGone() {
        if (BuildConfig.DEBUG) android.util.Log.d("SofaTabs", "renderer gone")
        val url = web?.url
        browser.destroy()
        cursor.removeAllViews()
        if (url != null) openUrl(url) else showHome()
    }

    /** Android is short of memory: the pages out of sight are unloaded first (each comes back when chosen). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (BuildConfig.DEBUG) android.util.Log.d("SofaTabs", "trim memory $level")
        @Suppress("DEPRECATION") // the running levels still come on Android 13 and older
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_RUNNING_CRITICAL) tabs.trim(all = true)
    }

    // Picture-in-picture and keeping the TV awake

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Also called when onCreate hands over to NoEngineActivity, with nothing set up.
        if (::prefs.isInitialized) video.onUserLeaveHint()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        video.onPictureInPicture(isInPictureInPictureMode)
    }

    /**
     * Keeps the TV awake while a video plays in the page. WebView flags its own view for that, but Sofa hides the
     * page's layer during fullscreen, which cancels the flag (checked on WebView 113), so the window holds it.
     */
    override fun applyKeepAwake() {
        if (video.playing && started && !homeShown) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // Lifecycle

    override fun onStart() {
        super.onStart()
        started = true
        applyKeepAwake()
        downloads.follow()
    }

    override fun onStop() {
        // Stopped while in picture-in-picture means its window was closed: the page rests now (onPause left it awake).
        pageOutOfSight()
        if (inPip) web?.onPause()
        video.takeover.stop()
        started = false
        watch.stop()
        applyKeepAwake()
        downloads.stopFollowing()
        // For "Where I left off": the TV may close Sofa in the background without another word.
        prefs.lastPage = OpenAtStart.toRemember(prefs.openAtStart, if (homeShown) null else web?.url, tabs.current.private, prefs.clearOnExit)
        // The normal tabs too, for the same reason; private ones never.
        prefs.tabs = if (prefs.clearOnExit) "" else tabs.saved()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back to browsing: a PIN entered for Settings or History doesn't carry over.
        PinGate.close()
        video.playIn.done()
        // A theme change in Settings rebuilds the screen (and the WebView, whose pages follow the theme).
        if (Ui.paletteFor(this, prefs.theme) !== palette) { recreate(); return }
        // Every loaded tab: one out of sight comes back with the settings of now.
        tabs.list.forEach { it.browser.applySettings() }
        cursor.speedLevel = prefs.cursorSpeed
        // Cheap when nothing is due: it checks at most every few hours.
        browser.adBlocker.refreshIfDue(prefs.autoUpdateLists)
        // While Sofa Player plays, the page stays at rest (swapIn), past a chooser or another screen.
        if (homeShown) home.refresh(prefs.privateMode, prefs.adBlock)
        else { pageInSight(); if (!video.takeover.holdsPage) web?.onResume() }
    }

    override fun onPause() {
        super.onPause()
        // In picture-in-picture the activity is paused but its video keeps playing. Otherwise the page rests before
        // WebView tells it it's hidden: a page that leaves on visibilitychange must find its navigations held.
        if (!inPip) { pageOutOfSight(); web?.onPause() }
        web?.let { WebViews.cookieManager(it).flush() }
    }

    // Find in page

    override fun openFind() = findBar.open(voice = prefs.voiceSearch && voiceInput.available)

    override fun find(query: String) {
        if (query.isEmpty()) web?.clearMatches() else web?.findAllAsync(query)
    }

    override fun findNext(forward: Boolean) {
        web?.findNext(forward)
    }

    override fun findByVoice() = when {
        voiceInput.listening -> voiceInput.cancel()
        !voiceInput.hasPermission -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        else -> voiceInput.start()
    }

    override fun closeFind() {
        voiceInput.cancel()
        findBar.close()
        web?.clearMatches()
        web?.requestFocus()
    }

    private val findVoiceListener = object : VoiceInput.Listener {
        override fun onListening(active: Boolean) = findBar.setListening(active)
        override fun onLevel(level: Float) = Unit
        override fun onPartial(text: String) = findBar.field.setText(text)
        override fun onResult(text: String) = findBar.setQuery(text)
        override fun onFailed(message: Int) = toast(message)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) voiceInput.start()
    }

    override fun onDestroy() {
        // Closed in onCreate because there was no WebView: nothing was set up.
        if (!::prefs.isInitialized) return super.onDestroy()
        // A screen rebuilt for a theme change must not have this one pause its timers afterwards.
        browserLayer.removeCallbacks(pauseTimers)
        voiceInput.destroy()
        video.takeover.release()
        // Private browsing without its own profile shared the normal one: it goes too, also when the screen is rebuilt
        // (the next screen starts in normal browsing).
        val wasPrivate = tabs.privateShown && !Browser.profilesSupported
        // Every tab's WebView goes, for a rebuilt screen too; the next one brings the normal tabs back from Settings.
        tabs.destroyAll()
        if (isFinishing) {
            StreamCopyProvider.clear(this)
            if (prefs.clearOnExit) { prefs.tabs = ""; db.clearHistory(); resumeStore(this).clear() }
            forgetPrivateAllows()
            prefs.privateMode = false
        }
        if (isFinishing && prefs.clearOnExit || wasPrivate) WebViews.clearBrowsingData(this, null)
        super.onDestroy()
    }

    // Helpers

    /** What private browsing let through (sites in Site filters, ads on a site) lasts only as long as it. */
    private fun forgetPrivateAllows() {
        SiteAllowlist.forgetPrivate()
        Allowlist.forgetPrivate()
    }

    override fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    override fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private companion object {
        const val REQ_URL = 10
        const val REQ_MIC = 12
        /** Saved with the screen: the page, not the start page, was in sight. */
        const val STATE_PAGE_SHOWN = "page_shown"
        /** How long the page's frames get to rest ([VideoRelay]'s "rest") before its timers pause. */
        const val TIMERS_PAUSE_MS = 1000L
    }
}
