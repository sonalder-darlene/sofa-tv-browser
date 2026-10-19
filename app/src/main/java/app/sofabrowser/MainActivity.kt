package app.sofabrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.WebViewFeature
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.CursorLayout
import app.sofabrowser.browser.FullscreenLayer
import app.sofabrowser.browser.PageDownloads
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.browser.VideoBar
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.VideoState
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Site
import app.sofabrowser.downloads.Download
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.Http
import app.sofabrowser.downloads.Status as DownloadStatus
import app.sofabrowser.ui.BrowserMenu
import app.sofabrowser.ui.DownloadsActivity
import app.sofabrowser.ui.HomeView
import app.sofabrowser.ui.ListActivity
import app.sofabrowser.ui.Palette
import app.sofabrowser.ui.SearchActivity
import app.sofabrowser.ui.SettingsActivity
import app.sofabrowser.ui.SetupActivity
import app.sofabrowser.ui.Ui
import app.sofabrowser.ui.px
import app.sofabrowser.update.Updater
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.math.roundToInt

/** The single activity for the home screen and the browser; settings and lists are separate screens. */
class MainActivity : Activity(), HomeView.Listener, Browser.Host, BrowserMenu.Actions, VideoBar.Actions, CursorLayout.Listener {

    private lateinit var prefs: Prefs
    private val db by lazy { Db.get(this) }
    private lateinit var browser: Browser

    private lateinit var browserLayer: FrameLayout
    private lateinit var cursor: CursorLayout
    private lateinit var progress: View
    private lateinit var scrollPill: TextView
    private lateinit var fullscreen: FullscreenLayer
    private lateinit var home: HomeView
    private var menu: BrowserMenu? = null

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var loading = false
    private var zoomStep = 0
    private var lastBackOnHome = 0L
    /** The palette this screen was built with; Settings may switch the shared one. */
    private lateinit var palette: Palette

    private val web get() = browser.web
    private val homeShown get() = home.visibility == View.VISIBLE
    private val recordHistory get() = prefs.rememberHistory && !browser.privateMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        palette = Ui.p
        prefs = Prefs(this)
        // Private mode is a session: a cold start always begins in normal mode, with nothing left over.
        prefs.privateMode = false
        Browser.deletePrivateProfile()
        browser = Browser(this, prefs, this)
        db.pruneHistory()
        DownloadEngine.init(this)
        // Private downloads don't outlive a session.
        DownloadEngine.forgetPrivate()
        if (!prefs.setupDone || Updater.offered(this) && !prefs.updatesAsked) startActivity(Intent(this, SetupActivity::class.java))
        // Opt-in, and only in builds and copies that no app store updates; an offer waits for the home screen.
        else if (savedInstanceState == null) Updater.checkOnLaunch(this) { homeShown && !isFinishing }

        val root = FrameLayout(this)
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
        root.addView(browserLayer)

        fullscreen = FullscreenLayer(this, VideoBar(this, this))
        root.addView(fullscreen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        home = HomeView(this, this)
        root.addView(home, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)

        if (!handleIntent(intent)) showHome()
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
        openUrl(url)
        return true
    }

    // Screens

    private fun ensureWeb() {
        if (web != null) return
        val view = browser.create(prefs.privateMode)
        cursor.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        ensureWeb()
        showBrowser()
        // Older WebViews can't add Sec-GPC to every request; at least send it with navigations we start.
        if (prefs.sendGpc && !WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) {
            web?.loadUrl(url, mapOf("Sec-GPC" to "1"))
        } else {
            web?.loadUrl(url)
        }
    }

    private fun showBrowser() {
        home.visibility = View.GONE
        browserLayer.visibility = View.VISIBLE
        cursor.speedLevel = prefs.cursorSpeed
        web?.onResume()
        web?.requestFocus()
    }

    private fun showHome() {
        menu?.dismiss()
        // Leaving a page for the home screen shouldn't leave its video playing behind it.
        VideoRelay.send(web, "pause")
        web?.onPause()
        home.refresh(prefs.privateMode, prefs.adBlock)
        home.visibility = View.VISIBLE
        browserLayer.visibility = View.INVISIBLE
        if (home.findFocus() == null) home.searchPill.requestFocus()
        Updater.showPending(this)
    }

    // Keys

    // TV remotes deliver BACK as a key, and BACK drives the menu, scroll mode and fullscreen here, so the
    // manifest opts out of predictive back (enableOnBackInvokedCallback="false") to keep receiving it.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (fullscreen.visibility == View.VISIBLE) return fullscreenKey(event) || super.dispatchKeyEvent(event)
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
                    prefs.backGoesBack && web?.canGoBack() == true -> web?.goBack()
                    else -> showMenu()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { if (down) showMenu(); return true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> { if (down) cursor.scrollPages(-1f); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { if (down) cursor.scrollPages(1f); return true }
        }
        if (mediaKey(event)) return true
        return cursor.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    private fun mediaKey(event: KeyEvent): Boolean {
        val cmd = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "toggle" to null
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play" to null
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause" to null
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> "seekBy" to 10.0
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> "seekBy" to -10.0
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) VideoRelay.send(web, cmd.first, cmd.second)
        return true
    }

    @SuppressLint("GestureBackNavigation") // see dispatchKeyEvent
    private fun fullscreenKey(event: KeyEvent): Boolean {
        val bar = fullscreen.bar
        val down = event.action == KeyEvent.ACTION_DOWN
        if (fullscreen.pointer.cursorEnabled) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (down) { fullscreen.pointer.cursorEnabled = false; command("watch"); bar.show() }
                return true
            }
            return fullscreen.pointer.dispatchKeyEvent(event)
        }
        if (mediaKey(event)) { if (down) bar.show(); return true }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (down && event.repeatCount == 0) { if (bar.showing) bar.hideNow() else exitFullscreen() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!bar.showing) {
                if (down) {
                    command("seekBy", if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10.0 else 10.0)
                    bar.show(focusScrub = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!bar.showing) {
                if (down) bar.show()
                return true
            }
        }
        // Keys belong to the bar while it is up, even if the page's fullscreen view took focus.
        if (bar.showing && !bar.hasFocus()) {
            bar.show()
            return bar.dispatchKeyEvent(event)
        }
        return false
    }

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

    // Menu

    private fun menuState(): BrowserMenu.State {
        val url = web?.url.orEmpty()
        val host = Browser.hostOf(url)
        return BrowserMenu.State(
            address = addressText(url),
            canGoBack = web?.canGoBack() == true,
            canGoForward = web?.canGoForward() == true,
            loading = loading,
            bookmarked = url.isNotEmpty() && db.isBookmarked(url),
            adBlockOn = prefs.adBlock && (host == null || !Allowlist.contains(this, host)),
            desktop = prefs.userAgent != "mobile",
            pointer = cursor.cursorEnabled,
            zoomPercent = (100 * 1.25.pow(zoomStep)).roundToInt(),
            privateMode = browser.privateMode,
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

    private fun showMenu() {
        cursor.hide()
        val m = menu ?: BrowserMenu(this, this).also { menu = it }
        m.show(menuState())
    }

    override fun onAddress() = startSearch(voice = false, current = web?.url)

    override fun onAction(action: BrowserMenu.Action) {
        val w = web ?: return
        val url = w.url.orEmpty()
        val host = Browser.hostOf(url)
        when (action) {
            BrowserMenu.Action.HOME -> showHome()
            BrowserMenu.Action.BACK -> if (w.canGoBack()) w.goBack()
            BrowserMenu.Action.FORWARD -> if (w.canGoForward()) w.goForward()
            BrowserMenu.Action.RELOAD -> { if (loading) w.stopLoading() else w.reload(); menu?.dismiss() }
            BrowserMenu.Action.BOOKMARK -> if (Browser.isWebUrl(url)) {
                if (db.isBookmarked(url)) db.removeBookmark(url) else db.addBookmark(w.title.orEmpty(), url)
                toast(if (db.isBookmarked(url)) R.string.bookmark_added else R.string.bookmark_removed)
            }
            BrowserMenu.Action.PIN -> if (Browser.isWebUrl(url)) {
                db.addTile(w.title?.ifBlank { null } ?: UrlInput.display(url), url)
                toast(R.string.pinned)
            }
            BrowserMenu.Action.HISTORY -> openList(ListActivity.MODE_HISTORY)
            BrowserMenu.Action.BOOKMARKS -> openList(ListActivity.MODE_BOOKMARKS)
            BrowserMenu.Action.ADBLOCK -> if (host != null) {
                if (!prefs.adBlock) { toast(R.string.adblock_globally_off); return }
                val allowed = !Allowlist.contains(this, host)
                Allowlist.set(this, host, allowed)
                toast(if (allowed) R.string.adblock_site_disabled else R.string.adblock_site_enabled)
                menu?.dismiss()
                w.reload()
            }
            BrowserMenu.Action.USER_AGENT -> pickUserAgent()
            BrowserMenu.Action.SCROLL -> { menu?.dismiss(); cursor.setScrollMode(true) }
            BrowserMenu.Action.POINTER -> if (host != null) {
                val keys = !prefs.keysToPage(host)
                prefs.setKeysToPage(host, keys)
                cursor.cursorEnabled = !keys
                toast(if (keys) R.string.pointer_off_toast else R.string.pointer_on_toast)
                menu?.dismiss()
            }
            BrowserMenu.Action.ZOOM_IN -> if (zoomStep < 6) { w.zoomBy(1.25f); zoomStep++ }
            BrowserMenu.Action.ZOOM_OUT -> if (zoomStep > -4) { w.zoomBy(0.8f); zoomStep-- }
            BrowserMenu.Action.SETTINGS -> { menu?.dismiss(); startActivity(Intent(this, SettingsActivity::class.java)) }
            BrowserMenu.Action.DOWNLOADS -> { menu?.dismiss(); onDownloads() }
        }
        menu?.takeIf { it.isShowing }?.update(menuState())
    }

    private fun pickUserAgent() {
        val labels = resources.getStringArray(R.array.user_agents)
        val current = Prefs.UA_MODES.indexOf(prefs.userAgent).coerceAtLeast(0)
        dialog().setTitle(R.string.user_agent)
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val mode = Prefs.UA_MODES[which]
                if (mode == "custom") askCustomUserAgent() else setUserAgent(mode)
            }.show()
    }

    private fun askCustomUserAgent() {
        val field = EditText(this).apply { setText(prefs.customUserAgent); isSingleLine = true }
        dialog().setTitle(R.string.custom_user_agent).setView(padded(field))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.customUserAgent = field.text.toString().trim()
                setUserAgent("custom")
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun setUserAgent(mode: String) {
        prefs.userAgent = mode
        web?.let { WebViews.applyUserAgent(it, mode, prefs.customUserAgent); it.reload() }
        menu?.dismiss()
    }

    // Home actions

    override fun onOpen(url: String) = openUrl(url)
    override fun onSearch() = startSearch(voice = false, current = null)
    override fun onVoiceSearch() = startSearch(voice = true, current = null)
    override fun onBookmarks() = openList(ListActivity.MODE_BOOKMARKS)
    override fun onHistory() = openList(ListActivity.MODE_HISTORY)
    override fun onSettings() = startActivity(Intent(this, SettingsActivity::class.java))
    override fun onDownloads() = startActivity(Intent(this, DownloadsActivity::class.java))

    /**
     * Straight to the Android home screen, then closes Sofa. On a box plugged into a TV, the remote's Home key may
     * go to the TV's own home instead, so this is the reliable way back. Downloads keep running.
     */
    override fun onExit() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onToggleAdBlock() {
        prefs.adBlock = !prefs.adBlock
        toast(if (prefs.adBlock) R.string.adblock_on else R.string.adblock_off)
        home.refresh(prefs.privateMode, prefs.adBlock)
    }

    override fun onTogglePrivate() {
        val entering = !prefs.privateMode
        val wasIsolated = browser.isolated
        // Leaving or entering private mode throws away the current page and, for private mode, everything in it.
        browser.destroy()
        if (!entering && !wasIsolated) WebViews.clearBrowsingData(this, null)
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
        dialog().setTitle(if (site == null) R.string.add_site else R.string.edit).setView(padded(box))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val url = UrlInput.resolve(address.text.toString(), prefs.searchEngine)
                if (!Browser.isWebUrl(url)) return@setPositiveButton
                val name = title.text.toString().trim().ifEmpty { Db.prettyHost(Browser.hostOf(url).orEmpty()) }
                if (site == null) db.addTile(name, url) else db.updateTile(site.id, name, url)
                home.refresh(prefs.privateMode, prefs.adBlock)
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    // Other screens

    private fun startSearch(voice: Boolean, current: String?) {
        menu?.dismiss()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, SearchActivity::class.java)
            .putExtra(SearchActivity.EXTRA_VOICE, voice)
            .putExtra(SearchActivity.EXTRA_PRIVATE, prefs.privateMode)
            .putExtra(SearchActivity.EXTRA_CURRENT_URL, current), REQ_URL)
    }

    private fun openList(mode: String) {
        menu?.dismiss()
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
        loading = true
        zoomStep = 0
        progress.visibility = View.VISIBLE
        val host = Browser.hostOf(url)
        cursor.cursorEnabled = host == null || !prefs.keysToPage(host)
        if (cursor.scrollMode) cursor.setScrollMode(false)
        menu?.takeIf { it.isShowing }?.update(menuState())
    }

    override fun onPageFinished(url: String) {
        loading = false
        progress.animate().cancel()
        progress.visibility = View.GONE
        progress.scaleX = 0f
        menu?.takeIf { it.isShowing }?.update(menuState())
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

    override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        menu?.dismiss()
        fullscreenCallback = callback
        fullscreen.attach(view)
        browserLayer.visibility = View.INVISIBLE
        fullscreen.postDelayed({ VideoRelay.send(web, "watch") }, 300)
        fullscreen.bar.show()
    }

    override fun onExitFullscreen() {
        if (fullscreen.visibility != View.VISIBLE) return
        VideoRelay.send(web, "unwatch")
        fullscreen.detach()
        fullscreenCallback = null
        browserLayer.visibility = View.VISIBLE
        web?.requestFocus()
    }

    override fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        onExitFullscreen()
    }

    override fun command(cmd: String, value: Double?) = VideoRelay.send(web, cmd, value)

    override fun usePointer() {
        // Give the page its own controls back while the pointer is used on them.
        command("unwatch")
        fullscreen.bar.hideNow()
        fullscreen.pointer.cursorEnabled = true
        fullscreen.pointer.speedLevel = prefs.cursorSpeed
    }

    override fun onVideoState(state: VideoState) = fullscreen.bar.update(state)

    override fun onSslError(handler: SslErrorHandler, error: SslError) {
        val host = Browser.hostOf(error.url) ?: error.url
        dialog().setTitle(R.string.ssl_title)
            .setMessage(getString(R.string.ssl_message, host))
            .setPositiveButton(R.string.ssl_back) { _, _ -> handler.cancel(); if (web?.canGoBack() == true) web?.goBack() }
            .setNeutralButton(R.string.ssl_proceed) { _, _ -> handler.proceed() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    override fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit) {
        if (prefs.drmAllowed(origin)) { grant(true); return }
        var answered = false
        dialog().setTitle(R.string.drm_title)
            .setMessage(getString(R.string.drm_message, Browser.hostOf(origin) ?: origin))
            .setPositiveButton(R.string.allow) { _, _ -> answered = true; prefs.setDrmAllowed(origin, true); grant(true) }
            .setNegativeButton(R.string.block) { _, _ -> answered = true; grant(false) }
            .setOnDismissListener { if (!answered) grant(false) }
            .show()
    }

    override fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long) =
        startDownload(url, userAgent, contentDisposition, mimeType, length, waited = false)

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long, waited: Boolean) {
        val inPage = url.startsWith("data:") || url.startsWith("blob:")
        // The clicked link's download="name" can arrive a moment after the download itself; give it one beat.
        if (inPage && !waited && PageDownloads.suggestedName(url) == null) {
            cursor.postDelayed({ startDownload(url, userAgent, contentDisposition, mimeType, length, waited = true) }, 150)
            return
        }
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: if (url.startsWith("data:")) PageDownloads.dataUrlMime(url) else null
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: ""
        val name = Http.safeFileName(PageDownloads.suggestedName(url)
            ?: Http.filenameFromContentDisposition(contentDisposition)
            ?: if (inPage) "download$extension" else URLUtil.guessFileName(url, contentDisposition, mimeType))
        val private = browser.privateMode
        val page = web?.url
        when {
            url.startsWith("data:") -> confirmDownload(name, length, Browser.hostOf(page)) {
                thread(name = "data-url") {
                    val payload = runCatching { PageDownloads.decodeDataUrl(url) }.getOrNull()
                    runOnUiThread {
                        if (payload == null) toast(R.string.download_unsupported)
                        else DownloadEngine.saveBytes(name, payload.mime ?: mime, payload.bytes, url, private, ::savedFromPage)
                    }
                }
            }
            url.startsWith("blob:") -> confirmDownload(name, length, Browser.hostOf(page)) {
                val view = web ?: return@confirmDownload
                PageDownloads.fetchBlob(view, url, DownloadEngine.maxBlobBytes) { result ->
                    result.onSuccess { DownloadEngine.saveBytes(name, it.mime ?: mime, it.bytes, page ?: url, private, ::savedFromPage) }
                        .onFailure { toast(getString(R.string.download_failed, it.message)) }
                }
            }
            Browser.isWebUrl(url) -> confirmDownload(name, length, Browser.hostOf(url)) {
                val cookies = web?.let { WebViews.cookieManager(it).getCookie(url) }
                DownloadEngine.enqueue(url, name, mime, length, page, userAgent ?: web?.settings?.userAgentString, cookies, private)
                toast(getString(R.string.download_started, name))
                // Once, for the progress notification; downloads work just the same without it.
                if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications) {
                    prefs.askedNotifications = true
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                }
            }
            else -> toast(R.string.download_unsupported)
        }
    }

    private fun confirmDownload(name: String, length: Long, host: String?, start: () -> Unit) {
        val size = if (length > 0) " (" + Http.formatBytes(length) + ")" else ""
        dialog().setTitle(R.string.download_title)
            .setMessage(getString(R.string.download_message, name + size, host ?: ""))
            .setPositiveButton(R.string.download) { _, _ -> start() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun savedFromPage(result: Result<Download>) {
        result.onSuccess { toast(getString(R.string.download_done, it.name)) }
            .onFailure { toast(getString(R.string.download_failed, it.message)) }
    }

    /** Finished downloads announce themselves, and the Home screen shows when something is downloading. */
    private val downloadsListener: () -> Unit = {
        val all = DownloadEngine.all
        all.filter { it.status == DownloadStatus.DONE && it.id !in announced && it.id in watched }.forEach {
            toast(getString(R.string.download_done, it.name))
        }
        all.filter { it.status == DownloadStatus.DONE }.forEach { announced.add(it.id) }
        watched.addAll(all.filter { it.active }.map { it.id })
        home.setDownloadsActive(all.any { it.active })
    }
    private val announced = HashSet<Long>()
    private val watched = HashSet<Long>()

    override fun onBlockedLink(url: String) = toast(R.string.link_blocked)

    override fun onPopupBlocked() = toast(R.string.popup_blocked)

    override fun onRendererGone() {
        val url = web?.url
        browser.destroy()
        cursor.removeAllViews()
        if (url != null) openUrl(url) else showHome()
    }

    // Lifecycle

    override fun onStart() {
        super.onStart()
        DownloadEngine.addListener(downloadsListener)
        downloadsListener()
    }

    override fun onStop() {
        DownloadEngine.removeListener(downloadsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // A theme change in Settings rebuilds the screen (and the WebView, whose pages follow the theme).
        if (Ui.paletteFor(this, prefs.theme) !== palette) { recreate(); return }
        browser.applySettings()
        cursor.speedLevel = prefs.cursorSpeed
        if (!homeShown) web?.onResume()
        else home.refresh(prefs.privateMode, prefs.adBlock)
    }

    override fun onPause() {
        super.onPause()
        web?.onPause()
        web?.let { WebViews.cookieManager(it).flush() }
    }

    override fun onDestroy() {
        if (isFinishing) {
            val wasPrivate = browser.privateMode && !browser.isolated
            browser.destroy()
            if (prefs.clearOnExit || wasPrivate) {
                WebViews.clearBrowsingData(this, null)
                if (prefs.clearOnExit) db.clearHistory()
            }
            prefs.privateMode = false
        }
        super.onDestroy()
    }

    // Helpers

    private fun dialog() = Ui.dialog(this)

    private fun padded(v: View) = FrameLayout(this).apply {
        setPadding(48.px, 24.px, 48.px, 0)
        addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_URL = 10
        private const val REQ_NOTIFICATIONS = 11
    }
}
