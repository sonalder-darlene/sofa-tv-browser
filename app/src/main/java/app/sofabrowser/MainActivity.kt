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
 * browser screen are in [app.sofabrowser.screen]; this class holds the views, the keys, the start page and the page's
 * events, and passes each part's events on to it.
 */
class MainActivity : Activity(), HomeView.Listener, Browser.Host, CursorLayout.Listener, FindBar.Actions, Screen {

    override val activity: Activity get() = this
    override lateinit var prefs: Prefs
        private set
    private val db by lazy { Db.get(this) }
    override lateinit var browser: Browser
        private set

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

    override var loading = false
        private set
    private var lastBackOnHome = 0L
    /** The palette this screen was built with; Settings may switch the shared one. */
    private lateinit var palette: Palette

    override val homeShown get() = home.visibility == View.VISIBLE
    override var started = false
        private set
    private val recordHistory get() = HistoryRetention.records(prefs.historyDays) && !browser.privateMode

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
        Browser.deletePrivateProfile()
        forgetPrivateAllows()
        // Copies for other players are needed only while one starts; a kill or crash may have left some.
        StreamCopyProvider.clear(this)
        browser = Browser(this, prefs, this)
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
            // Settings > Open at start; a link from another app always wins.
            val at = OpenAtStart.address(prefs.openAtStart, prefs.startPage, prefs.lastPage, restored = savedInstanceState != null)
            if (at != null) openUrl(at) else showHome()
        }
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
        openUrl(url)
        return true
    }

    // Screens

    private fun ensureWeb() {
        if (web != null) return
        val view = browser.create(prefs.privateMode)
        view.setFindListener { active, total, _ -> findBar.setMatches(active, total) }
        cursor.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun openUrl(url: String) {
        if (url.isBlank()) return
        ensureWeb()
        // What the resting page tried to open, and its paused sound, go with it: this address replaces it.
        browser.forgetHeld()
        showBrowser(wakeSound = false)
        browser.userNavigation()
        // Older WebViews can't add Sec-GPC to every request; at least send it with navigations we start.
        if (prefs.sendGpc && !WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) {
            web?.loadUrl(url, mapOf("Sec-GPC" to "1"))
        } else {
            web?.loadUrl(url)
        }
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

    /** For every WebView of the app, which is fine: the start page and the rest of Sofa are native. */
    private val pauseTimers = Runnable { if (browser.hidden) web?.pauseTimers() }

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
        if (web?.url != null) { showBrowser(); return }
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
        web?.url?.takeIf { !browser.privateMode && Browser.isWebUrl(it) }?.let { putExtra(SettingsActivity.EXTRA_OPEN_PAGE, it) }
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
        val wasIsolated = browser.isolated
        // Leaving or entering private mode throws away the current page and, for private mode, everything in it.
        browser.destroy()
        if (!entering && !wasIsolated) WebViews.clearBrowsingData(this, null)
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
        loading = true
        menu.pageStarted()
        progress.visibility = View.VISIBLE
        val host = Browser.hostOf(url)
        cursor.cursorEnabled = host == null || !prefs.keysToPage(host)
        if (cursor.scrollMode) cursor.setScrollMode(false)
        watch.pageStarted(url)
        menu.refresh()
    }

    override fun onPageFinished(url: String) {
        loading = false
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

    override fun onVisited(url: String) {
        if (recordHistory && Browser.isWebUrl(url)) db.recordVisit(web?.title?.takeIf { web?.url == url }.orEmpty(), url)
    }

    override fun onTitle(url: String, title: String) {
        if (recordHistory) db.updateHistoryTitle(url, title)
    }

    override fun onIcon(url: String, icon: Bitmap) {
        if (!browser.privateMode) Favicons.get(this).save(url, icon)
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
        val url = web?.url
        browser.destroy()
        cursor.removeAllViews()
        if (url != null) openUrl(url) else showHome()
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
        prefs.lastPage = OpenAtStart.toRemember(prefs.openAtStart, if (homeShown) null else web?.url, browser.privateMode, prefs.clearOnExit)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // Back to browsing: a PIN entered for Settings or History doesn't carry over.
        PinGate.close()
        video.playIn.done()
        // A theme change in Settings rebuilds the screen (and the WebView, whose pages follow the theme).
        if (Ui.paletteFor(this, prefs.theme) !== palette) { recreate(); return }
        browser.applySettings()
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
        if (isFinishing) {
            val wasPrivate = browser.privateMode && !browser.isolated
            browser.destroy()
            StreamCopyProvider.clear(this)
            if (prefs.clearOnExit || wasPrivate) {
                WebViews.clearBrowsingData(this, null)
                if (prefs.clearOnExit) { db.clearHistory(); resumeStore(this).clear() }
            }
            forgetPrivateAllows()
            prefs.privateMode = false
        }
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
        /** How long the page's frames get to rest ([VideoRelay]'s "rest") before its timers pause. */
        const val TIMERS_PAUSE_MS = 1000L
    }
}
