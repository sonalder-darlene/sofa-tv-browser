package app.sofabrowser

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.util.Rational
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import app.sofabrowser.browser.Decoders
import app.sofabrowser.browser.ExternalPlayer
import app.sofabrowser.browser.PageHeaders
import app.sofabrowser.browser.StreamCopyProvider
import app.sofabrowser.browser.StreamResolver
import app.sofabrowser.browser.FrameRate
import app.sofabrowser.browser.FullscreenLayer
import app.sofabrowser.browser.GamePad
import app.sofabrowser.browser.PageDownloads
import app.sofabrowser.browser.PlayerRequest
import app.sofabrowser.browser.Playlist
import app.sofabrowser.browser.ProtectedMedia
import app.sofabrowser.browser.RefreshRateMatch
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.browser.VideoBar
import app.sofabrowser.browser.VideoInfo
import app.sofabrowser.browser.VideoSource
import app.sofabrowser.browser.WatchTarget
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.VideoState
import app.sofabrowser.browser.WebViewAge
import app.sofabrowser.browser.WebViews
import app.sofabrowser.data.Db
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.HistoryRetention
import app.sofabrowser.data.Site
import app.sofabrowser.downloads.Download
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.Http
import app.sofabrowser.downloads.Status as DownloadStatus
import app.sofabrowser.player.SofaPlayer
import app.sofabrowser.player.Variants
import app.sofabrowser.ui.BrowserMenu
import app.sofabrowser.ui.DownloadsActivity
import app.sofabrowser.ui.FindBar
import app.sofabrowser.ui.VoiceInput
import app.sofabrowser.ui.HomeView
import app.sofabrowser.ui.PadHelp
import app.sofabrowser.ui.ListActivity
import app.sofabrowser.ui.Palette
import app.sofabrowser.ui.SearchActivity
import app.sofabrowser.ui.SettingsActivity
import app.sofabrowser.ui.SetupActivity
import app.sofabrowser.ui.Ui
import app.sofabrowser.ui.px
import app.sofabrowser.update.Updater
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.pow
import kotlin.math.roundToInt

/** The single activity for the home screen and the browser; settings and lists are separate screens. */
class MainActivity : Activity(), HomeView.Listener, Browser.Host, BrowserMenu.Actions, VideoBar.Actions, CursorLayout.Listener,
    FindBar.Actions {

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
    private lateinit var findBar: FindBar
    private val voiceInput by lazy { VoiceInput(this, findVoiceListener) }

    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var loading = false
    private var zoomStep = 0
    private var lastBackOnHome = 0L
    /** The palette this screen was built with; Settings may switch the shared one. */
    private lateinit var palette: Palette

    private val web get() = browser.web
    private val homeShown get() = home.visibility == View.VISIBLE
    private val recordHistory get() = HistoryRetention.records(prefs.historyDays) && !browser.privateMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        palette = Ui.p
        prefs = Prefs(this)
        // Private mode is a session: a cold start always begins in normal mode, with nothing left over.
        prefs.privateMode = false
        Browser.deletePrivateProfile()
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
            warnIfOldWebView()
        }

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
        findBar = FindBar(this, this)
        browserLayer.addView(findBar, FrameLayout.LayoutParams(1300.px, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 40.px })
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
        view.setFindListener { active, total, _ -> findBar.setMatches(active, total) }
        cursor.addView(view, 0, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        ensureWeb()
        showBrowser()
        browser.userNavigation()
        // Older WebViews can't add Sec-GPC to every request; at least send it with navigations we start.
        if (prefs.sendGpc && !WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) {
            web?.loadUrl(url, mapOf("Sec-GPC" to "1"))
        } else {
            web?.loadUrl(url)
        }
    }

    private fun showBrowser() {
        home.visibility = View.GONE
        applyKeepAwake()
        browserLayer.visibility = View.VISIBLE
        cursor.speedLevel = prefs.cursorSpeed
        web?.onResume()
        web?.requestFocus()
    }

    private fun showHome() {
        menu?.dismiss()
        if (findBar.showing) closeFind()
        // Leaving a page for the home screen shouldn't leave its video playing behind it.
        VideoRelay.send(web, "pause")
        web?.onPause()
        home.refresh(prefs.privateMode, prefs.adBlock)
        home.visibility = View.VISIBLE
        applyKeepAwake()
        browserLayer.visibility = View.INVISIBLE
        if (home.findFocus() == null) home.searchPill.requestFocus()
        Updater.showPending(this)
    }

    // Keys

    // TV remotes deliver BACK as a key, and BACK drives the menu, scroll mode and fullscreen here, so the
    // manifest opts out of predictive back (enableOnBackInvokedCallback="false") to keep receiving it.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (padKey(event)) return true
        if (fullscreen.visibility == View.VISIBLE) return fullscreenKey(event) || super.dispatchKeyEvent(event)
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
        // Play with nothing playing yet means "watch this page's video" (fullscreen has its own player already).
        if (fullscreen.visibility != View.VISIBLE && !videoPlaying && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)) {
            if (event.action == KeyEvent.ACTION_DOWN) watch()
            return true
        }
        val cmd = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "toggle" to null
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play" to null
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause" to null
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> "seekBy" to 10.0
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> "seekBy" to -10.0
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) command(cmd.first, cmd.second)
        return true
    }

    @SuppressLint("GestureBackNavigation") // see dispatchKeyEvent
    private fun fullscreenKey(event: KeyEvent): Boolean {
        val bar = fullscreen.bar
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            // The remote's volume keys always reach the system (often the TV itself, over HDMI-CEC).
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            KeyEvent.KEYCODE_CAPTIONS -> { if (down) { command("subs"); bar.show() }; return true }
        }
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

    // Game pads: each button stands in for a remote key or a browser action (GamePad has the layout), and the
    // sticks aim and scroll the pointer. Elsewhere (menus, lists, the bar) Android's own handling applies: A and B
    // act as OK and Back, and the stick moves the focus.

    private val triggers = GamePad.Triggers()

    private fun padKey(event: KeyEvent): Boolean {
        val action = GamePad.action(event.keyCode, fullscreen.visibility == View.VISIBLE) ?: return false
        padSeen(event.device)
        val trigger = when (event.keyCode) { KeyEvent.KEYCODE_BUTTON_L2 -> 0; KeyEvent.KEYCODE_BUTTON_R2 -> 1; else -> -1 }
        val stand = GamePad.remoteKey(action)
        when {
            trigger >= 0 -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && triggers.key(trigger, event.eventTime)) padPress(action)
            }
            // Down, repeats and up all go through, so holding A drags and long-presses as holding OK does.
            stand != null -> dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, stand, event.repeatCount,
                event.metaState, event.deviceId, event.scanCode, event.flags, event.source))
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> padPress(action)
        }
        return true
    }

    private fun padPress(action: GamePad.Action) {
        GamePad.remoteKey(action)?.let { code ->
            val now = SystemClock.uptimeMillis()
            dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
            dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
            return
        }
        val inFullscreen = fullscreen.visibility == View.VISIBLE
        when (action) {
            GamePad.Action.WATCH -> if (!homeShown) watch()
            GamePad.Action.SEARCH -> if (homeShown) onSearch() else onAddress()
            GamePad.Action.HOME -> if (!homeShown) showHome()
            GamePad.Action.HISTORY_BACK -> if (!homeShown && web?.canGoBack() == true) browser.goBack()
            GamePad.Action.HISTORY_FORWARD -> if (!homeShown && web?.canGoForward() == true) browser.goForward()
            GamePad.Action.SEEK_BACK_LONG, GamePad.Action.SEEK_FORWARD_LONG -> if (inFullscreen) {
                command("seekBy", if (action == GamePad.Action.SEEK_BACK_LONG) -30.0 else 30.0)
                fullscreen.bar.show(focusScrub = true)
            }
            GamePad.Action.VIDEO_INFO -> if (inFullscreen) toggleVideoInfo()
            else -> {}
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        padMotion(event) || super.dispatchGenericMotionEvent(event)

    /** The sticks drive the pointer that is in use, and the analog triggers page; false leaves the event to Android. */
    private fun padMotion(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) || event.actionMasked != MotionEvent.ACTION_MOVE) return false
        padSeen(event.device)
        val inFullscreen = fullscreen.visibility == View.VISIBLE
        for (side in 0..1) {
            val axes = if (side == 0) intArrayOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE)
            else intArrayOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS)
            val pull = axes.maxOf { event.getAxisValue(it) }
            val code = if (side == 0) KeyEvent.KEYCODE_BUTTON_L2 else KeyEvent.KEYCODE_BUTTON_R2
            if (triggers.axis(side, pull, event.eventTime)) GamePad.action(code, inFullscreen)?.let(::padPress)
        }
        val pointer = when {
            homeShown || menu?.isShowing == true -> null
            inFullscreen -> fullscreen.pointer.takeIf { it.cursorEnabled }
            findBar.hasFocus() -> null
            else -> cursor.takeIf { it.cursorEnabled }
        } ?: return false
        val device = event.device
        fun flat(axis: Int) = device?.getMotionRange(axis, event.source)?.flat ?: 0f
        // The right stick is Z and RZ on almost every pad Android knows; a few older layouts report RX and RY.
        val (rx, ry) = if (device == null || device.getMotionRange(MotionEvent.AXIS_Z) != null) MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        else MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        val (ax, ay) = GamePad.stick(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), flat(MotionEvent.AXIS_X))
        val (sx, sy) = GamePad.stick(event.getAxisValue(rx), event.getAxisValue(ry), flat(rx))
        return pointer.stick(ax, ay, sx, sy)
    }

    private val padsSeen = HashSet<Int>()

    /** The first time a pad is used in a session, the first few times ever, a card shows its buttons. */
    private fun padSeen(device: InputDevice?) {
        if (device == null || !padsSeen.add(device.id) || prefs.padHints >= PAD_HINTS) return
        prefs.padHints++
        PadHelp.showHint(this, browserLayer.parent as FrameLayout, GamePad.family(device.vendorId))
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
            blocked = browser.adBlocker.blockedCount,
            desktop = prefs.userAgent != "mobile",
            pointer = cursor.cursorEnabled,
            zoomPercent = (100 * 1.25.pow(zoomStep)).roundToInt(),
            privateMode = browser.privateMode,
            allowPending = browser.pendingAllow() != null,
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
            BrowserMenu.Action.ALLOW_ONCE -> { menu?.dismiss(); browser.allowOnce()?.let(::openUrl) }
            BrowserMenu.Action.HOME -> showHome()
            BrowserMenu.Action.BACK -> if (w.canGoBack()) browser.goBack()
            BrowserMenu.Action.FORWARD -> if (w.canGoForward()) browser.goForward()
            BrowserMenu.Action.RELOAD -> { if (loading) w.stopLoading() else browser.reload(); menu?.dismiss() }
            BrowserMenu.Action.WATCH -> watch()
            BrowserMenu.Action.FIND -> { menu?.dismiss(); findBar.open(voice = prefs.voiceSearch && voiceInput.available) }
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
                browser.reload()
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
        web?.let { WebViews.applyUserAgent(it, mode, prefs.customUserAgent); browser.reload() }
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
        // Stream copies hold what was watched (signed stream addresses); none outlives either side of the switch.
        StreamCopyProvider.clear(this)
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
        if (findBar.showing) closeFind()
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
        resumeOffered = false
        videoState = null
        videoFps = null
        frameTimesSeen = false
        playerRateMatched = false
        nativeTried = false
        nativeRetryAt = 0L
        hardwareRetried = false
        pausedForDecoder = false
        fullscreen.bar.pipShown = pipSupported
        fullscreen.attach(view)
        browserLayer.visibility = View.INVISIBLE
        fullscreen.postDelayed({ VideoRelay.send(web, "watch") }, 300)
        fullscreen.bar.show()
    }

    override fun onExitFullscreen() {
        if (fullscreen.visibility != View.VISIBLE) return
        // Leaving Sofa Player: the page's video carries on from where it was.
        if (sofaPlayer.active) backToPage(play = nativeOn && sofaPlayer.playing)
        VideoRelay.send(web, "unwatch")
        refreshMatch.reset()
        fullscreen.detach()
        fullscreenCallback = null
        browserLayer.visibility = View.VISIBLE
        web?.requestFocus()
    }

    override fun exitFullscreen() {
        fullscreenCallback?.onCustomViewHidden()
        onExitFullscreen()
    }

    override fun command(cmd: String, value: Double?) =
        if (nativeOn && cmd in PLAYER_COMMANDS) sofaPlayer.command(cmd, value) else VideoRelay.send(web, cmd, value)

    override fun usePointer() {
        // Give the page its own controls back while the pointer is used on them.
        command("unwatch")
        fullscreen.bar.hideNow()
        fullscreen.pointer.cursorEnabled = true
        fullscreen.pointer.speedLevel = prefs.cursorSpeed
    }

    override fun onVideoState(state: VideoState) {
        // The page's video is paused while Sofa Player plays; the bar follows the player.
        if (nativeOn) return
        maybeSofaPlayer(state)
        videoState = state
        pageStateAt = SystemClock.uptimeMillis()
        fullscreen.bar.update(state)
        rememberPosition(state)
    }

    // Resume: where long films were left, on this device only (ResumeStore has the rules).

    // Fresh each time: Settings may have cleared it meanwhile.
    private val resume get() = resumeStore(this)
    private var resumeSavedAt = 0L
    private var resumeOffered = false

    private fun rememberPosition(s: VideoState) {
        val url = web?.url ?: return
        if (s.duration <= 0) return
        val now = System.currentTimeMillis()
        if (!resumeOffered) {
            resumeOffered = true
            if (!browser.privateMode) resume.offer(url, now)?.takeIf { it > s.position + 30 }?.let(fullscreen.bar::offerResume)
        }
        if (s.paused || now - resumeSavedAt > 5000) {
            resumeSavedAt = now
            resume.save(url, s.position, s.duration, browser.privateMode, now)
        }
    }

    // Match frame rate: the page measures the watched video's frames once per fullscreen; FrameRate has the rules.

    private val refreshMatch by lazy { RefreshRateMatch(this) }
    private var videoState: VideoState? = null
    private var videoFps: Double? = null
    private var frameTimesSeen = false

    override fun onVideoFrameTimes(times: List<Double>) {
        if (fullscreen.visibility != View.VISIBLE || frameTimesSeen) return
        frameTimesSeen = true
        val fps = FrameRate.estimate(times) ?: return
        videoFps = fps
        val state = videoState
        // As Android's guide advises, no blackout for short videos; live streams (no duration) qualify.
        if (!prefs.matchFrameRate || inPip || (state != null && state.duration > 0 && state.duration < SHORT_VIDEO)) return
        val wasPlaying = state?.paused == false
        refreshMatch.match(fps, pause = { if (wasPlaying) command("pause") },
            resume = { if (wasPlaying && fullscreen.visibility == View.VISIBLE) command("play") })
    }

    override fun toggleVideoInfo() {
        val info = fullscreen.info
        info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        info.removeCallbacks(refreshInfo)
        if (info.visibility == View.VISIBLE) { info.text = getString(R.string.video_info_waiting); refreshInfo.run() }
    }

    private val refreshInfo: Runnable = Runnable {
        if (fullscreen.info.visibility != View.VISIBLE) return@Runnable
        if (nativeOn) showPlayerInfo() else command("info")
        fullscreen.info.postDelayed(refreshInfo, 1000)
    }

    override fun onVideoInfo(info: VideoInfo) {
        if (fullscreen.info.visibility != View.VISIBLE) return
        fun names(list: List<VideoInfo.Codec>) = list.joinToString(", ") { it.name }
        val hardware = info.codecs.filter { it.supported && it.efficient }
        val software = info.codecs.filter { it.supported && !it.efficient }
        // The panel's own mode can be 4K while apps are drawn at 1080p; the 4K investigation needs both (displayInfo).
        fullscreen.info.text = listOfNotNull(
            getString(R.string.video_info_size, info.width, info.height),
            if (info.total >= 0) getString(R.string.video_info_frames, info.total, info.dropped) else null,
            when {
                videoFps != null -> getString(R.string.video_info_rate, FrameRate.label(videoFps!!))
                frameTimesSeen -> getString(R.string.video_info_rate_unknown)
                else -> getString(R.string.video_info_rate_waiting)
            },
            if (info.codecs.isEmpty()) null
            else getString(R.string.video_info_hardware, names(hardware).ifEmpty { getString(R.string.video_info_none) }),
            if (software.isEmpty()) null else getString(R.string.video_info_software, names(software)),
            getString(R.string.video_info_note),
            "",
        ).plus(displayInfo()).plus(getString(R.string.video_info_page, info.screenWidth, info.screenHeight, info.pixelRatio.toString(),
                info.innerWidth, info.cssWidth)).joinToString("\n")
    }

    /**
     * The display's side of "Video info": window and panel sizes, and the TV's refresh rates. Sofa Player draws on a
     * video layer of the panel's own size, the page's player within the app's window: [player] says which.
     */
    private fun displayInfo(player: Boolean = false): List<String> {
        val dm = resources.displayMetrics
        @Suppress("DEPRECATION") val mode = windowManager.defaultDisplay.mode
        return listOfNotNull(
            getString(if (player) R.string.video_info_display_player else R.string.video_info_display, dm.widthPixels, dm.heightPixels,
                mode?.physicalWidth ?: dm.widthPixels, mode?.physicalHeight ?: dm.heightPixels),
            refreshMatch.otherRates().let { others ->
                val now = FrameRate.label(refreshMatch.refreshRate.toDouble())
                if (others.isEmpty()) getString(R.string.video_info_tv_rate_only, now)
                else getString(R.string.video_info_tv_rate, now, others.joinToString(", ") { FrameRate.label(it.toDouble()) })
            },
            if (refreshMatch.turnedOffInSystem) getString(R.string.video_info_tv_rate_off) else null,
        )
    }

    private fun showPlayerInfo() {
        val lines = sofaPlayer.info()
        val labels = listOf(R.string.video_info_native_video, R.string.video_info_decoder, R.string.video_info_native_frames, R.string.video_info_sound)
        val rate = when {
            playerFps != null -> getString(R.string.video_info_rate, FrameRate.label(playerFps!!))
            rateMeasured -> getString(R.string.video_info_rate_unknown)
            else -> getString(R.string.video_info_rate_waiting)
        }
        val text = (listOf(getString(R.string.sofa_player)) + lines.zip(labels).mapNotNull { (line, label) -> line?.let { getString(label, it) } } +
            rate + "" + displayInfo(player = true)).joinToString("\n")
        fullscreen.info.text = text
    }

    // Watch: locate the main video, scroll it into view if needed, tap it for real, then ask for fullscreen.

    private val candidates = ArrayList<WatchTarget.Candidate>()
    private var watching = false
    private var revealedOnce = false

    private fun watch() {
        if (web == null || watching) return
        menu?.dismiss()
        watching = true
        revealedOnce = false
        locate()
    }

    private fun locate() {
        candidates.clear()
        command("locate")
        cursor.postDelayed(::watchChosen, 400)
    }

    override fun onVideoCandidate(candidate: WatchTarget.Candidate) {
        if (watching) candidates.add(candidate)
    }

    private fun watchChosen() {
        val best = WatchTarget.choose(candidates)
        if (best == null) { watching = false; toast(R.string.no_video); return }
        if (!best.tappable(cursor.width, cursor.height)) {
            if (revealedOnce) { watching = false; toast(R.string.no_video); return }
            revealedOnce = true
            VideoRelay.send(web, "reveal", frame = best.frame)
            cursor.postDelayed(::locate, 700)
            return
        }
        watching = false
        cursor.tapAt(best.tapX.toFloat(), best.tapY.toFloat())
        cursor.postDelayed({ VideoRelay.send(web, "go", frame = best.frame) }, 150)
    }

    // Play in…: ask every frame for its main video, hand the largest to the chosen player, pause it here.

    private val sources = ArrayList<VideoSource>()

    private var choosePlayer = false

    /** [choose]: show Android's chooser even when a player was remembered (a long press on the button). */
    override fun playIn(choose: Boolean) {
        choosePlayer = choose
        collectSources(::playInChosen)
    }

    private var sourcesWanted: ((VideoSource?) -> Unit)? = null

    /** Asks every frame for its main video, then gives [then] the largest, or null. */
    private fun collectSources(then: (VideoSource?) -> Unit) {
        if (web == null || sourcesWanted != null) return
        sources.clear()
        sourcesWanted = then
        VideoRelay.send(web, "source")
        cursor.postDelayed({
            val done = sourcesWanted ?: return@postDelayed
            sourcesWanted = null
            done(sources.maxByOrNull { it.area })
        }, 300)
    }

    override fun onVideoSource(source: VideoSource) {
        if (sourcesWanted != null) sources.add(source)
    }

    private fun playInChosen(found: VideoSource?) {
        val w = web ?: return
        // From Sofa Player, the other player starts where Sofa Player was.
        val video = if (nativeOn) found?.copy(position = sofaPlayer.positionSeconds) else found
        val userAgent = w.settings.userAgentString.orEmpty()
        val cookies = WebViews.cookieManager(w)
        val direct = video?.src?.takeIf(Browser::isWebUrl)
        val candidates = if (direct == null && video != null && !video.protected) browser.streams.candidates() else emptyList()
        // The page fetches its manifests again first; reading the rest takes the network: off the main thread, then back.
        browser.fetchInPage(StreamResolver.manifestsToRead(candidates), PAGE_FETCH_MS) { fromPage ->
            thread(name = "play-in") {
                val resolved = if (candidates.isEmpty()) null
                else StreamResolver(userAgent, cookies::getCookie, ::streamLog).resolve(candidates, fromPage, checkOutside = true)
                runOnUiThread { if (!isDestroyed) handOver(video, resolved, userAgent, cookies::getCookie) }
            }
        }
    }

    /** Why "Play in…" opened nothing: over the video when it is fullscreen, where a toast goes unseen. */
    private fun playInMessage(res: Int) {
        streamLog("Play in: ${getString(res)}")
        if (fullscreen.visibility == View.VISIBLE) fullscreen.notice(getString(res)) else toast(res)
    }

    /** What Sofa read to find a page's stream, in the log of test builds. */
    private fun streamLog(message: String) {
        if (BuildConfig.DEBUG) android.util.Log.d("SofaStream", message)
    }

    private fun handOver(video: VideoSource?, resolved: StreamResolver.Resolved?, userAgent: String, cookie: (String) -> String?) {
        val stream = video?.src?.takeIf(Browser::isWebUrl) ?: resolved?.url
        // What the page's player sent for this stream (PageHeaders), for players that read headers. Only on this press.
        val headers = stream?.let {
            if (resolved == null) PageHeaders.forRequest(it, video?.page, userAgent, cookie, media = true)
            else PageHeaders.forRequest(it, resolved.frame, userAgent, cookie)
        }.orEmpty()
        val request = PlayerRequest.build(video?.src, web?.title, video?.position ?: 0.0, video?.protected == true, headers,
            resolved?.url, video?.subtitles.orEmpty(), Locale.getDefault().language)
        var launch = when (request) {
            PlayerRequest.Refusal.NO_VIDEO -> { playInMessage(R.string.no_video); return }
            PlayerRequest.Refusal.IN_PAGE -> { playInMessage(R.string.handover_in_page); return }
            PlayerRequest.Refusal.PROTECTED -> { playInMessage(R.string.handover_drm); return }
            is PlayerRequest.Launch -> request
        }
        // A master playlist the site gives only to its own page: hand over Sofa's copy, whose addresses point at the
        // site, so players that send their own headers (VLC, Just Player) can still open it. It is typed HLS for
        // players that go by the intent's type (PlayerRequest.types has the order), and leaves out the pictures the TV
        // can't decode in hardware (AV1 on many TVs), which a player might pick and play too slowly to show.
        if (resolved?.needsPage == true && resolved.playlist != null) {
            val copy = Playlist.decodableOnly(resolved.playlist, Decoders::hardware)
            if (BuildConfig.DEBUG) {
                fun codecs(p: String) = Regex("""#EXT-X-STREAM-INF:.*CODECS="([^"]*)"""").findAll(p).map { it.groupValues[1] }.toList()
                streamLog("copy for another player keeps ${codecs(copy)} of ${codecs(resolved.playlist)}")
            }
            launch = launch.copy(uri = StreamCopyProvider.put(this, copy).toString(), mime = PlayerRequest.HLS)
        }
        // The player picked last time, unless the user asked to choose (again, or every time) or it was uninstalled since.
        val remembered = prefs.videoPlayer.takeUnless { choosePlayer || it.isEmpty() || it == Prefs.ASK_EVERY_TIME }
        val player = remembered?.let { ExternalPlayer.installed(this, it) ?: run {
            prefs.videoPlayer = ""
            playInMessage(R.string.player_gone)
            null
        } }
        if (ExternalPlayer.open(this, launch, player, getString(R.string.play_in))) command("pause")
        else playInMessage(R.string.no_player)
    }

    // Sofa Player: once the page's video plays fullscreen and its stream is known, Sofa Player gets ready unseen under
    // it, then takes over at the same position. It falls back to the page's player if it can't (SofaPlayer has the
    // why). Never for DRM, for files the page didn't give its video (they may be ads), or for short videos.

    private val sofaPlayer: SofaPlayer by lazy { SofaPlayer(this, playerEvents) }
    /** Sofa Player shows and plays, instead of the page's video. */
    private var nativeOn = false
    /** Sofa Player was tried (or refused) for this fullscreen; a second try waits until [nativeRetryAt]. */
    private var nativeTried = false
    private var nativeRetryAt = 0L
    private var nativeTries = 0
    private var nativeFps = 0f
    /** What Sofa Player was last started on, for its one more try at a hardware decoder ([hardwareRetried]). */
    private var sofaStarted: Pair<SofaPlayer.Source, VideoSource>? = null
    private var hardwareRetried = false
    /** The page's video was paused by Sofa for that try, not by the user: Sofa Player takes over playing. */
    private var pausedForDecoder = false

    private fun maybeSofaPlayer(state: VideoState) {
        val external = prefs.forceExternalPlayer
        if (!(prefs.sofaPlayer || external) || nativeTried || sofaPlayer.active || fullscreen.visibility != View.VISIBLE || inPip) return
        if (state.paused || state.position < 1 || (state.duration > 0 && state.duration < SHORT_FOR_PLAYER)) return
        if (SystemClock.uptimeMillis() < nativeRetryAt) return
        nativeTried = true
        // "Always play in another app" (experimental): the "Play in…" app gets it, once per fullscreen; short videos
        // (often ads) and protected ones, which no other app can open, stay here without a word.
        if (external) {
            collectSources { video ->
                if (video?.protected == true) { streamLog("Always play in another app: protected, stays here"); return@collectSources }
                streamLog("Always play in another app: handing over")
                choosePlayer = false
                playInChosen(video)
            }
            return
        }
        collectSources(::sofaPlayerFor)
    }

    private fun sofaPlayerFor(video: VideoSource?) {
        val w = web ?: return
        if (video == null || video.protected || fullscreen.visibility != View.VISIBLE) {
            streamLog("Sofa Player not tried: " + if (video == null) "no video answered" else if (video.protected) "protected" else "left fullscreen")
            return
        }
        val userAgent = w.settings.userAgentString.orEmpty()
        val cookies = WebViews.cookieManager(w)
        val direct = video.src.takeIf(Browser::isWebUrl)
        if (direct != null) {
            startSofaPlayer(SofaPlayer.Source(direct, StreamResolver.Kind.FILE, video.page, null, userAgent, cookies::getCookie, media = true, video.subtitles), video)
            return
        }
        val candidates = browser.streams.candidates()
        if (candidates.none { it.manifest }) {
            // The page may fetch its stream a moment after it starts; look again a few times.
            if (++nativeTries < 4) { nativeTried = false; nativeRetryAt = SystemClock.uptimeMillis() + 2000 }
            streamLog("Sofa Player: no manifest caught yet (look $nativeTries)")
            return
        }
        browser.fetchInPage(StreamResolver.manifestsToRead(candidates), PAGE_FETCH_MS) { fromPage ->
            thread(name = "sofa-player") {
                val resolver = StreamResolver(userAgent, cookies::getCookie, ::streamLog)
                val resolved = resolver.resolve(candidates, fromPage, checkOutside = false)
                    // Which pictures are HDR, when the master doesn't say, so Auto can keep to what the TV shows.
                    ?.let { r -> r.copy(playlist = r.playlist?.let { resolver.withReadRanges(it, r.frame) }) }
                runOnUiThread {
                    streamLog("Sofa Player: ${resolved?.kind} ${resolved?.url}")
                    // A file only the page's requests mention (not its video's own address) may be an ad.
                    if (resolved == null || resolved.kind == StreamResolver.Kind.FILE || isDestroyed) return@runOnUiThread
                    startSofaPlayer(SofaPlayer.Source(resolved.url, resolved.kind, resolved.frame, resolved.playlist, userAgent,
                        cookies::getCookie, media = false, video.subtitles), video)
                }
            }
        }
    }

    private fun startSofaPlayer(source: SofaPlayer.Source, video: VideoSource) {
        if (fullscreen.visibility != View.VISIBLE) return
        nativeFps = 0f
        measuredFps = null
        measuredBelowTop = false
        rateMeasured = false
        catchUps = 0
        swapToken++
        sofaStarted = source to video
        fullscreen.addPlayer(sofaPlayer.view)
        // Ahead of the page's video, which plays on while Sofa Player gets ready unseen (see onReady).
        sofaPlayer.start(source, (pageNowMs() ?: (video.position * 1000).toLong()) + TAKEOVER_LEAD_MS, browser.pageHost?.let(qualityBySite::get))
    }

    /** Where the page's video is now: its last report, moved on by the time since when it plays. */
    private fun pageNowMs(): Long? {
        val s = videoState ?: return null
        val since = if (s.paused) 0L else ((SystemClock.uptimeMillis() - pageStateAt) * s.rate).toLong()
        return (s.position * 1000).toLong() + since
    }

    private var pageStateAt = 0L
    /** Qualities picked by hand in Sofa Player, by site, until Sofa closes. */
    private val qualityBySite = HashMap<String, Variants.Quality>()
    private var catchUps = 0

    /**
     * Sofa Player is ready, unseen, at a point ahead of the page's video: it shows when the page's video gets there,
     * so the picture carries straight on. If the page's video got past it first, Sofa Player moves further ahead,
     * still unseen; after a few tries, or when the page's video stopped, it takes over where that is.
     */
    private fun swapWhenPageArrives() {
        val page = pageNowMs()
        val ahead = page?.let { sofaPlayer.positionMs - it }
        streamLog("Sofa Player ready at ${sofaPlayer.positionMs} ms, page at $page ms")
        when {
            page == null || sofaPlayer.live -> swapIn(null)
            videoState?.paused == true -> swapIn(page)
            ahead!! < -SWAP_SLACK_MS -> if (++catchUps <= MAX_CATCH_UPS) sofaPlayer.catchUp(page + TAKEOVER_LEAD_MS * (catchUps + 1)) else swapIn(page)
            else -> {
                val token = swapToken
                fullscreen.postDelayed({ if (token == swapToken) swapIn(null) }, (ahead / (videoState?.rate ?: 1.0)).toLong().coerceAtLeast(0))
            }
        }
    }

    /** Bumped whenever Sofa Player stops or starts again, so a pending swap of an older one does nothing. */
    private var swapToken = 0

    private fun swapIn(seekMs: Long?) {
        if (fullscreen.visibility != View.VISIBLE || !sofaPlayer.active || nativeOn) return
        val state = videoState
        streamLog("Sofa Player takes over at ${seekMs ?: sofaPlayer.positionMs} ms, page at ${pageNowMs()} ms")
        VideoRelay.send(web, "pause")
        nativeOn = true
        fullscreen.showPlayer(true)
        sofaPlayer.takeOver(seekMs, play = state?.paused != true || pausedForDecoder, rate = state?.rate ?: 1.0)
        fullscreen.bar.show()
        matchPlayerRate()
    }

    private val playerEvents: SofaPlayer.Listener = object : SofaPlayer.Listener {
        override fun onReady() {
            if (fullscreen.visibility != View.VISIBLE) { sofaPlayer.release(); return }
            if (!nativeOn) swapWhenPageArrives()
        }

        override fun onState(state: VideoState) {
            if (!nativeOn) return
            videoState = state
            fullscreen.bar.update(state)
            rememberPosition(state)
        }

        override fun onPlaying(playing: Boolean) {
            nativePlaying = playing && nativeOn
            applyKeepAwake()
        }

        // Nothing more to show: Sofa Player's buffer and decoder are let go, and the page's video plays its own last
        // moment, so the site sees its video end (its next episode, its end screen) as it would without Sofa Player. A
        // seek to the very end of a paused video fires no "ended". Sofa Player was tried already, so it won't come back.
        override fun onEnded() {
            if (nativeOn) backToPage(play = true, before = END_FOR_PAGE_S)
        }

        override fun onError(reason: String, ready: Boolean) {
            streamLog("Sofa Player failed: $reason")
            if (nativeOn) { backToPage(play = true); toast(R.string.sofa_player_failed) }
            else {
                sofaPlayer.release(); fullscreen.removePlayer()
                // The page's video was paused only for the second try: it plays on.
                if (pausedForDecoder) { pausedForDecoder = false; VideoRelay.send(web, "play") }
            }
        }

        override fun onSoftwareDecoder() {
            // Still unseen: the page's video stops so Sofa Player can try for the hardware decoder once more. If it
            // gets software again, it plays on with it (the page's video is paused at the same point).
            val (source, video) = sofaStarted ?: return
            if (hardwareRetried || nativeOn || fullscreen.visibility != View.VISIBLE) return
            hardwareRetried = true
            pausedForDecoder = true
            streamLog("Sofa Player: software decoder, pausing the page's video and trying again")
            VideoRelay.send(web, "pause")
            startSofaPlayer(source, video)
        }

        override fun onFrameRate(fps: Float) {
            nativeFps = fps
            if (nativeOn) matchPlayerRate()
        }

        override fun onMeasuredFrameRate(fps: Double?, belowTop: Boolean) {
            measuredFps = fps
            measuredBelowTop = belowTop
            rateMeasured = true
            if (nativeOn) matchPlayerRate()
        }

        override fun onQualityPicked(quality: Variants.Quality?) {
            val site = browser.pageHost ?: return
            if (quality == null) qualityBySite.remove(site) else qualityBySite[site] = quality
        }
    }

    /** Sofa Player's frame rate: the stream's word, else what it measured from its own frames. */
    private val playerFps get() = nativeFps.takeIf { it > 0f }?.toDouble() ?: measuredFps
    private var measuredFps: Double? = null
    /** [measuredFps] came from a picture below the stream's largest: its rate may be half the stream's. */
    private var measuredBelowTop = false
    private var rateMeasured = false
    /** Sofa Player matched (or chose not to match) the TV's rate in this fullscreen; the page's measurement is apart. */
    private var playerRateMatched = false

    /** "Match frame rate" for Sofa Player, once per fullscreen, as for the page's video. */
    private fun matchPlayerRate() {
        val fps = playerFps ?: return
        if (playerRateMatched) return
        playerRateMatched = true
        val state = videoState
        val declared = nativeFps > 0f
        val source = if (declared) "declared" else if (measuredBelowTop) "measured below the top picture" else "measured"
        if (!prefs.matchFrameRate || inPip || (state != null && state.duration > 0 && state.duration < SHORT_VIDEO)) {
            streamLog("Sofa Player frame rate ${FrameRate.label(fps)} ($source): no switch (setting off, picture-in-picture, or video under ${SHORT_VIDEO.toInt()} s)")
            return
        }
        val switched = refreshMatch.match(fps, pause = { sofaPlayer.command("pause", null) },
            resume = { if (fullscreen.visibility == View.VISIBLE) sofaPlayer.command("play", null) }, orDouble = !declared && measuredBelowTop)
        streamLog("Sofa Player frame rate ${FrameRate.label(fps)} ($source): " +
            if (switched) "mode requested" else "no switch (none better, already asked, or no other mode)")
    }

    /** Hands the video back to the page's player, at Sofa Player's position, or [before] seconds earlier. */
    private fun backToPage(play: Boolean, before: Double = 0.0) {
        val at = (sofaPlayer.positionSeconds - before).coerceAtLeast(0.0)
        val wasOn = nativeOn
        nativeOn = false
        swapToken++
        nativePlaying = false
        sofaPlayer.release()
        fullscreen.removePlayer()
        applyKeepAwake()
        if (!wasOn) return
        VideoRelay.send(web, "seekTo", at)
        if (play) VideoRelay.send(web, "play")
    }

    override fun sitePlayer() {
        backToPage(play = true)
        fullscreen.bar.show()
    }

    override fun tracks() {
        val quality = sofaPlayer.qualities(getString(R.string.auto), getString(R.string.quality_beyond))
        val sound = sofaPlayer.audios()
        val subs = sofaPlayer.subtitles(getString(R.string.subtitles_off))
        val rows = listOfNotNull(
            Triple(R.string.quality, quality, quality.firstOrNull { it.selected }?.label),
            if (sound.size > 1) Triple(R.string.sound, sound, sound.firstOrNull { it.selected }?.label) else null,
            if (subs.size > 1) Triple(R.string.subtitles, subs, subs.firstOrNull { it.selected }?.label) else null,
        )
        val labels = rows.map { (title, _, now) -> getString(title) + (now?.let { ": $it" } ?: "") }.toTypedArray()
        dialog().setTitle(R.string.tracks).setItems(labels) { _, which ->
            val (title, choices, _) = rows[which]
            dialog().setTitle(title).setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.selected }) { d, i ->
                choices[i].apply()
                d.dismiss()
            }.show()
        }.show()
    }

    // Picture-in-picture: the fullscreen video shrinks into a corner, from the bar or on Home while it plays.

    private val pipSupported get() =
        Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private val inPip get() = isInPictureInPictureMode

    override fun pictureInPicture() {
        if (!pipSupported || fullscreen.visibility != View.VISIBLE || Build.VERSION.SDK_INT < 26) return
        fullscreen.bar.hideNow()
        fullscreen.info.visibility = View.GONE
        runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoPlaying && fullscreen.visibility == View.VISIBLE) pictureInPicture()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) { fullscreen.bar.hideNow(); menu?.dismiss() }
    }

    private var pagePlaying = false
    private var nativePlaying = false
    private val videoPlaying get() = pagePlaying || nativePlaying
    private var started = false

    override fun onPlaying(playing: Boolean) {
        pagePlaying = playing
        applyKeepAwake()
    }

    /**
     * Keeps the TV awake while a video plays in the page. WebView flags its own view for that, but Sofa hides the
     * page's layer during fullscreen, which cancels the flag (checked on WebView 113), so the window holds it.
     */
    private fun applyKeepAwake() {
        if (videoPlaying && started && !homeShown) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onSslError(handler: SslErrorHandler, error: SslError) {
        val host = Browser.hostOf(error.url) ?: error.url
        dialog().setTitle(R.string.ssl_title)
            .setMessage(getString(R.string.ssl_message, host))
            .setPositiveButton(R.string.ssl_back) { _, _ -> handler.cancel(); if (web?.canGoBack() == true) browser.goBack() }
            .setNeutralButton(R.string.ssl_proceed) { _, _ -> handler.proceed() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    override fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit) {
        when (ProtectedMedia.decide(prefs.drmMode, prefs.drmAnswer(origin))) {
            ProtectedMedia.Decision.GRANT -> { grant(true); return }
            ProtectedMedia.Decision.DENY -> { grant(false); return }
            ProtectedMedia.Decision.ASK -> {}
        }
        // Dismissing with Back blocks this time only; the buttons are remembered for the site.
        var answered = false
        dialog().setTitle(R.string.drm_title)
            .setMessage(getString(R.string.drm_message, Browser.hostOf(origin) ?: origin))
            .setPositiveButton(R.string.allow) { _, _ -> answered = true; prefs.setDrmAnswer(origin, true); grant(true) }
            .setNegativeButton(R.string.block) { _, _ -> answered = true; prefs.setDrmAnswer(origin, false); grant(false) }
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

    override fun onPopupBlocked(url: String?) = toast(if (url != null) R.string.popup_blocked_allow else R.string.popup_blocked)

    override fun onRedirectBlocked() = toast(R.string.redirect_blocked_allow)

    override fun onRendererGone() {
        val url = web?.url
        browser.destroy()
        cursor.removeAllViews()
        if (url != null) openUrl(url) else showHome()
    }

    // Lifecycle

    override fun onStart() {
        super.onStart()
        started = true
        applyKeepAwake()
        DownloadEngine.addListener(downloadsListener)
        downloadsListener()
    }

    override fun onStop() {
        // Stopped while in picture-in-picture means its window was closed: no sound may go on playing.
        if (inPip) { VideoRelay.send(web, "pause"); web?.onPause() }
        // Out of sight (picture-in-picture keeps the activity started), Sofa Player lets go of its buffer and decoder:
        // the page's video holds the place, and Sofa Player may take over again once it plays.
        if (sofaPlayer.active) {
            backToPage(play = false)
            nativeTried = false
            nativeTries = 0
        }
        started = false
        applyKeepAwake()
        DownloadEngine.removeListener(downloadsListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // A theme change in Settings rebuilds the screen (and the WebView, whose pages follow the theme).
        if (Ui.paletteFor(this, prefs.theme) !== palette) { recreate(); return }
        browser.applySettings()
        cursor.speedLevel = prefs.cursorSpeed
        // Cheap when nothing is due: it checks at most every few hours.
        browser.adBlocker.refreshIfDue(prefs.autoUpdateLists)
        if (!homeShown) web?.onResume()
        else home.refresh(prefs.privateMode, prefs.adBlock)
    }

    override fun onPause() {
        super.onPause()
        // In picture-in-picture the activity is paused but its video keeps playing.
        if (!inPip) web?.onPause()
        web?.let { WebViews.cookieManager(it).flush() }
    }

    // Find in page

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
        voiceInput.destroy()
        sofaPlayer.release()
        if (isFinishing) {
            val wasPrivate = browser.privateMode && !browser.isolated
            browser.destroy()
            StreamCopyProvider.clear(this)
            if (prefs.clearOnExit || wasPrivate) {
                WebViews.clearBrowsingData(this, null)
                if (prefs.clearOnExit) { db.clearHistory(); resume.clear() }
            }
            prefs.privateMode = false
        }
        super.onDestroy()
    }

    /** Once per WebView version, and again every few months: Sofa can't update the engine, only say so. */
    private fun warnIfOldWebView() {
        val engine = WebViews.engine(this)
        val now = System.currentTimeMillis()
        if (!WebViewAge.shouldWarn(engine.major, engine.version, prefs.webViewWarnedVersion, prefs.webViewWarnedAt, now)) return
        prefs.webViewWarnedVersion = engine.version
        prefs.webViewWarnedAt = now
        val d = dialog().setTitle(R.string.old_webview_title)
            .setMessage(R.string.old_webview_message)
            .setPositiveButton(R.string.keep_browsing, null)
            .setNeutralButton(R.string.how_to_update) { _, _ ->
                dialog().setTitle(R.string.how_to_update).setMessage(R.string.how_to_update_message)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        d.show()
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
        private const val REQ_MIC = 12
        /** The commands Sofa Player takes in place of the page's video. */
        private val PLAYER_COMMANDS = setOf("toggle", "play", "pause", "seekBy", "seekTo", "rate", "subs")
        /** Seconds: shorter videos (trailers, previews, ads) stay with the page's player. */
        private const val SHORT_FOR_PLAYER = 60.0
        /** How long the page may take to fetch its manifests again. */
        private const val PAGE_FETCH_MS = 2500L
        /** Seconds of the end the page's video plays itself after Sofa Player finished, so the site sees the end. */
        private const val END_FOR_PAGE_S = 0.5
        /** How far ahead of the page's video Sofa Player gets ready: about what it takes to start (ms). */
        private const val TAKEOVER_LEAD_MS = 4000L
        /** How far the page's video may have passed Sofa Player's first picture and it still swaps in there (ms). */
        private const val SWAP_SLACK_MS = 150L
        /** How many times Sofa Player moves further ahead, unseen, before it takes over with a seek. */
        private const val MAX_CATCH_UPS = 2
        /** How many times, ever, the game pad card is shown. */
        private const val PAD_HINTS = 3
        /** Seconds: "Match frame rate" leaves shorter videos alone. */
        private const val SHORT_VIDEO = 300.0
    }
}
