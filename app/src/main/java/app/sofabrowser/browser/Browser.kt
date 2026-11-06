package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.CustomHeader
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sofabrowser.Prefs
import app.sofabrowser.adblock.AdBlocker
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.adblock.FilterList
import app.sofabrowser.adblock.InPageBlocking
import app.sofabrowser.adblock.NavigationGuard
import app.sofabrowser.adblock.Sites
import java.io.ByteArrayInputStream

/**
 * One tab's page: creates its WebView in the normal or private profile, applies settings and relays page events. The
 * WebView may be dropped and made again ([create], [destroy]) while the Browser, and the tab it belongs to, stays.
 */
class Browser(private val context: Context, private val prefs: Prefs, private val host: Host, val privateMode: Boolean) {

    interface Host {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String)
        fun onProgress(percent: Int)
        fun onTitle(url: String, title: String)
        fun onIcon(url: String, icon: Bitmap)
        fun onVisited(url: String)
        fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onExitFullscreen()
        fun onVideoState(state: VideoState)
        /** Whether a video plays anywhere on the page (any frame, fullscreen or not). */
        fun onPlaying(playing: Boolean)
        fun onVideoInfo(info: VideoInfo)
        fun onVideoCandidate(candidate: WatchTarget.Candidate)
        fun onVideoSource(source: VideoSource)
        /** The media times of the frames the watched video showed, for "Match frame rate". */
        fun onVideoFrameTimes(times: List<Double>)
        /** The user just started a video in [frame] that "Open videos in Sofa Player" may take ([VideoRelay]'s offerStart). */
        fun onVideoStarted(frame: String)
        fun onSslError(handler: SslErrorHandler, error: SslError)
        fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit)
        fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long)
        fun onBlockedLink(url: String)
        /** A pop-up was blocked; [url] is set when "Allow once" can open it. */
        fun onPopupBlocked(url: String?)
        /** The page tried to leave for a listed host on its own; "Allow once" can follow it. */
        fun onRedirectBlocked()
        /**
         * A Site filters [list] blocks the site at [url]. With [replaced], an empty page took its place in the history;
         * otherwise the navigation was stopped and the page before stays.
         */
        fun onSiteBlocked(url: String, list: FilterList, replaced: Boolean)
        fun onRendererGone()
    }

    var web: WebView? = null
        private set

    /** True when private browsing runs in its own WebView profile; false on WebViews too old for profiles. */
    val isolated get() = privateMode && profilesSupported

    /** Whether a video plays anywhere on the page. */
    val playing get() = frames.anyPlaying

    /** The private session this tab belongs to: its WebView, made again after being dropped, stays in that profile. */
    private val profile = privateProfile

    /** Host of the page in the main frame, readable from WebView's network threads. */
    @Volatile var pageHost: String? = null
        private set

    /** Address of the page in the main frame, for page-wide filter exceptions. */
    @Volatile var pageUrl: String? = null
        private set

    /**
     * Where the main frame is going: a link or a server redirect it follows. A redirect reaches neither
     * shouldInterceptRequest nor onPageStarted before its certificate is checked, so [pageUrl] still names the page before.
     */
    private var mainFrameTarget: String? = null

    /** The address whose page a Site filters list replaced with an empty one, while that one shows. */
    @Volatile private var blockedPage: String? = null

    /** A page reloaded by [reloadAllowed], until its visit is recorded. */
    private var allowedReload: String? = null

    val adBlocker = AdBlocker.get(context)
    private val guard = NavigationGuard()
    /** Set while Sofa taps the page on its own ([unattended]), so the guard doesn't take that tap for the user's. */
    private var tappingAlone = false
    private val frames = PlaybackFrames()

    /** Video streams the current page fetched, for Sofa Player and "Play in…". */
    val streams = StreamSniffer()

    /** Creates (or re-creates) the WebView. Returns the new view; the caller attaches it to its layout. */
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature", "ClickableViewAccessibility") // MULTI_PROFILE: see profilesSupported
    fun create(): WebView {
        destroy()
        if (app.sofabrowser.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val view = WebView(context)
        if (profilesSupported) {
            // Must happen before anything else touches the view.
            WebViewCompat.setProfile(view, if (isolated) profile else androidx.webkit.Profile.DEFAULT_PROFILE_NAME)
        }
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.setBackgroundColor(android.graphics.Color.WHITE)
        val density = context.resources.displayMetrics.density
        // Same trick as BrowsHere: a desktop-ish ~1130-1500 CSS px viewport that stays readable from the sofa.
        view.setInitialScale((85 * if (density >= 1) minOf(density, 2f) else density).toInt())
        WebViews.configure(view, prefs)
        applyGpc(view)
        // Fresh random names per view for everything injected into pages (see PageKey).
        val key = PageKey()
        VideoRelay.install(view, key, panelScale(), object : VideoRelay.Listener {
            override fun onState(state: VideoState) { view.post { host.onVideoState(state) } }
            override fun onPlaying(frame: String, playing: Boolean) {
                view.post { frames.report(frame, playing); host.onPlaying(frames.anyPlaying) }
            }
            override fun onInfo(info: VideoInfo) { view.post { host.onVideoInfo(info) } }
            override fun onCandidate(candidate: WatchTarget.Candidate) { view.post { host.onVideoCandidate(candidate) } }
            override fun onSource(source: VideoSource) { view.post { host.onVideoSource(source) } }
            override fun onFrameTimes(times: List<Double>) { view.post { host.onVideoFrameTimes(times) } }
            override fun onFetched(url: String, final: String?, body: String?, sender: String) {
                view.post { fetched(url, final, body, sender) }
            }
            override fun onStarted(frame: String) { view.post { host.onVideoStarted(frame) } }
        })
        // Every press that reaches the page (the pointer's taps, a real mouse, OK in keys-to-page mode) may open one
        // window. Both listeners only watch.
        view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { if (tappingAlone) guard.onUnattendedInput() else guard.onInput() }
            false
        }
        view.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && code in PRESS_KEYS) guard.onInput()
            false
        }
        PageDownloads.install(view, key)
        InPageBlocking.install(view, key, adBlocker, active = { blocking(pageHost) }) { url ->
            debug("trap link removed: $url")
            adBlocker.countBlocked()
            guard.blocked(url, SystemClock.uptimeMillis())
            host.onPopupBlocked(url)
        }
        view.webViewClient = client
        view.webChromeClient = chrome
        view.setDownloadListener { url, ua, disposition, mime, length -> host.onDownload(url, ua, disposition, mime, length) }
        web = view
        return view
    }

    fun destroy() {
        web?.let {
            it.stopLoading()
            (it.parent as? android.view.ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        frames.clear()
        host.onPlaying(false)
    }

    fun applySettings() {
        web?.let { WebViews.configure(it, prefs); applyGpc(it) }
    }

    @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
    private fun applyGpc(view: WebView) {
        if (prefs.sendGpc) WebViews.addDocumentStartScript(view, GPC_SCRIPT)
        if (!profilesSupported || !WebViewFeature.isFeatureSupported(WebViewFeature.CUSTOM_REQUEST_HEADERS)) return
        val profile = WebViewCompat.getProfile(view)
        if (prefs.sendGpc) profile.addCustomHeader(CustomHeader("Sec-GPC", "1", setOf("*")))
        else profile.clearCustomHeader("Sec-GPC")
    }

    // onRenderProcessGone is implemented below; lint flags it only because the callback starts at API 26.
    @SuppressLint("MissingOnRenderProcessGone")
    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            pageHost = AdBlocker.hostOf(url)
            pageUrl = url
            guard.pageStarted()
            frames.clear()
            host.onPlaying(false)
            host.onPageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String) = host.onPageFinished(url)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageHost = AdBlocker.hostOf(url)
            pageUrl = url
            // A blocked site's empty stand-in stays out of History; the page itself counts once the user allows it.
            val allowed = isReload && url == allowedReload
            if (allowed) allowedReload = null
            if ((!isReload || allowed) && url != blockedPage) host.onVisited(url)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            // Out of sight, a page that posts a form to go elsewhere slips past shouldOverrideUrlLoading, which never
            // sees a POST. Held here instead, before the page's state switches over: an empty 204 makes WebView drop
            // the navigation, so the page stays as it was.
            if (hidden && request.isForMainFrame) {
                hold(url)
                return WebResourceResponse("text/plain", "utf-8", 204, "No Content", mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(ByteArray(0)))
            }
            // Site filters apply whether ad blocking is on or not, and to every way of reaching a page: typed
            // addresses, bookmarks and home tiles never pass through shouldOverrideUrlLoading.
            val site = if (url.startsWith("http")) adBlocker.siteListed(url) else null
            if (request.isForMainFrame) {
                // A new document's subresources are requested before onPageStarted reaches the UI thread, so the
                // page host, count and streams switch over here, on the network thread, ahead of them.
                pageHost = request.url.host
                pageUrl = url
                adBlocker.resetPageCount()
                streams.clear()
                if (site != null) {
                    blockedPage = url
                    view.post { host.onSiteBlocked(url, site, replaced = true) }
                    return WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"),
                        ByteArrayInputStream(BLOCKED_PAGE))
                }
                blockedPage = null
            } else {
                if (site != null) {
                    adBlocker.countBlocked()
                    return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }
                if (url !in refetching) streams.request(url, listed(url), request.requestHeaders)
                if (app.sofabrowser.BuildConfig.DEBUG && extensionOf(url) in setOf("m3u8", "mpd")) android.util.Log.d("SofaStream", "manifest $url")
            }
            return if (prefs.adBlock) adBlocker.intercept(request, pageUrl, pageHost) else null
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            // Out of sight, the page doesn't go anywhere on its own: its timer or an ad would load a site, sound and
            // all, behind the start page. The address waits for the user (wake). Redirects of a navigation under way pass.
            if (hidden && request.isForMainFrame && !request.isRedirect) { hold(url.toString()); return true }
            if (request.isForMainFrame && url.scheme?.lowercase()?.startsWith("http") == true) {
                // Links and server redirects: stopped before anything loads, so the page before stays.
                adBlocker.siteListed(url.toString())?.let { host.onSiteBlocked(url.toString(), it, replaced = false); return true }
            }
            return when (url.scheme?.lowercase()) {
                "http", "https" -> (request.isForMainFrame && blockNavigation(view, request)).also { blocked ->
                    if (request.isForMainFrame && !blocked) mainFrameTarget = url.toString()
                }
                "about", "data", "blob", "javascript", null -> false
                "intent" -> {
                    // Never launch other apps from web content; follow the page's own web fallback if it has one.
                    val fallback = runCatching { Intent.parseUri(url.toString(), Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url") }.getOrNull()
                    if (fallback != null && (fallback.startsWith("https://") || fallback.startsWith("http://"))) view.loadUrl(fallback)
                    else host.onBlockedLink(url.toString())
                    true
                }
                else -> { host.onBlockedLink(url.toString()); true }
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Only the page itself, or the one the main frame is going to, may ask the user. Anything else with a broken
            // certificate (ad frames on such sites often have one) is refused quietly, as Chrome does, instead of
            // putting a dialog over the page.
            val errorHost = hostOf(error.url)
            val mine = error.url == pageUrl || error.url == mainFrameTarget || errorHost == null ||
                errorHost.equals(pageHost, ignoreCase = true) || errorHost.equals(mainFrameTarget?.let(::hostOf), ignoreCase = true)
            if (!mine) {
                debug("certificate error refused: ${error.url}")
                handler.cancel()
            } else {
                host.onSslError(handler, error)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            if (view === web) host.onRendererGone()
            return true
        }
    }

    private val chrome = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = host.onProgress(newProgress)

        override fun onReceivedTitle(view: WebView, title: String?) {
            val url = view.url ?: return
            if (!title.isNullOrBlank()) host.onTitle(url, title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap) {
            view.url?.let { host.onIcon(it, icon) }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = host.onEnterFullscreen(view, callback)

        override fun onHideCustomView() = host.onExitFullscreen()

        override fun onPermissionRequest(request: PermissionRequest) {
            // Only DRM playback (Widevine) is ever allowed, per site and after asking. Camera, microphone and
            // MIDI are refused without a prompt; the app doesn't hold those permissions anyway.
            if (request.resources.contentEquals(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))) {
                host.onProtectedMediaRequest(request.origin.toString()) { ok -> if (ok) request.grant(request.resources) else request.deny() }
            } else {
                request.deny()
            }
        }

        @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            guard.windowRequested(isUserGesture)
            // What the press was on, now, before the page can move things: windows from a link to them are the user's.
            val pressed = pressedLink(view)
            debug("window: gesture=$isUserGesture dialog=$isDialog link=$pressed")
            if (!isUserGesture) { adBlocker.countBlocked(); host.onPopupBlocked(null); return false }
            // Open the target in the same tab, so every window passes the pop-up rules below (only the user opens a new
            // tab): catch the first URL the throwaway view is sent to.
            val catcher = WebView(view.context)
            // A page can script into a window it opened, so the throwaway view must not reach the normal profile's
            // cookies while browsing privately.
            if (profilesSupported) runCatching { WebViewCompat.setProfile(catcher, WebViewCompat.getProfile(view).name) }
            val opener = pageHost
            var used = false
            @SuppressLint("MissingOnRenderProcessGone") // implemented; the callback starts at API 26
            val catcherClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (!used) openWindow(view, request.url.toString(), opener, pressed)
                    used = true
                    v.post { v.destroy() }
                    return true
                }

                override fun onRenderProcessGone(v: WebView, detail: RenderProcessGoneDetail): Boolean {
                    v.destroy()
                    return true
                }
            }
            catcher.webViewClient = catcherClient
            // A window that is opened but never navigated (e.g. filled with document.write) is dropped.
            view.postDelayed({ if (!used) { used = true; catcher.destroy() } }, 10_000)
            (resultMsg.obj as WebView.WebViewTransport).webView = catcher
            resultMsg.sendToTarget()
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
            if (app.sofabrowser.BuildConfig.DEBUG) {
                android.util.Log.d("SofaConsole", "${message.messageLevel()} ${message.sourceId()}:${message.lineNumber()} ${message.message()}")
            }
            return true
        }
    }

    // Manifests fetched again by the page itself (VideoRelay.fetch): the answers for one request at a time.

    /** [waiting] maps each address asked for to the origin of the frames asked to fetch it. */
    private class PageFetch(val waiting: MutableMap<String, String>, val done: (Map<String, Pair<String, String>>) -> Unit) {
        val got = HashMap<String, Pair<String, String>>()
    }
    private var pageFetch: PageFetch? = null
    /** What Sofa has the page fetch again: not the page's own fetches, so they don't count as such (StreamSniffer). */
    @Volatile private var refetching: Set<String> = emptySet()

    /**
     * Has the frames that fetched [streams] fetch them again, as their players did. [done] gets, within [timeoutMs],
     * the final address and text of each that came back, by the address asked for. On the main thread.
     */
    fun fetchInPage(streams: List<StreamSniffer.Stream>, timeoutMs: Long, done: (Map<String, Pair<String, String>>) -> Unit) {
        val view = web
        val asks = streams.mapNotNull { s -> StreamResolver.frameOf(s)?.let(PageHeaders::origin)?.let { s.url to it.serialized } }
        if (view == null || asks.isEmpty()) { done(emptyMap()); return }
        pageFetch?.let { finish(it) }
        val f = PageFetch(asks.toMap(HashMap()), done)
        refetching = f.waiting.keys.toSet()
        pageFetch = f
        asks.forEach { (url, origin) -> VideoRelay.fetch(view, url, origin) }
        view.postDelayed({ finish(f) }, timeoutMs)
    }

    /** An answer counts only from a frame of the origin asked ([sender] is WebView's word, not the frame's). */
    private fun fetched(url: String, final: String?, body: String?, sender: String) {
        val f = pageFetch ?: return
        if (!PageHeaders.sameOrigin(f.waiting[url], sender)) {
            if (url in f.waiting && app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", "ignored answer from $sender for $url")
            return
        }
        f.waiting.remove(url)
        if (body != null) f.got[url] = (final ?: url) to body
        if (f.waiting.isEmpty()) finish(f)
    }

    private fun finish(f: PageFetch) {
        if (pageFetch !== f) return
        pageFetch = null
        refetching = emptySet()
        f.done(f.got)
    }

    /** Panel pixels per app pixel: the TV's display mode (4K on a 4K panel) against the window Android gives apps. */
    private fun panelScale(): Double {
        val dm = context.resources.displayMetrics
        val mode = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.mode
            ?: return 1.0
        return PanelScale.of(mode.physicalWidth, mode.physicalHeight, dm.widthPixels, dm.heightPixels)
    }

    /** Loads a window's first address in [view], unless the pop-up rules block it. */
    private fun openWindow(view: WebView, url: String, opener: String?, pressed: String?) {
        if (hidden) { hold(url); return }
        val sameSite = opener != null && hostOf(url)?.let { Sites.same(it.lowercase(), opener.lowercase()) } == true
        val verdict = guard.newWindow(blocking(opener), isWebUrl(url) && adBlocker.popupListed(url, opener),
            fromLink = pressed.leadsTo(url), sameSite = sameSite)
        debug("window $verdict: $url (from $opener, link=$pressed)")
        if (verdict == NavigationGuard.Verdict.BLOCK) {
            adBlocker.countBlocked()
            guard.blocked(url, SystemClock.uptimeMillis())
            host.onPopupBlocked(url)
        } else {
            // The window came from a press, so its redirects are the user's too.
            guard.userNavigation()
            view.loadUrl(url)
        }
    }

    /** True when the pop-up and redirect rules (NavigationGuard) stop a main-frame navigation or redirect hop. */
    private fun blockNavigation(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // The per-site switch of the page the navigation left, not of a host the chain passes through.
        if (!request.isRedirect) chainHost = pageHost
        val target = hostOf(url)?.lowercase()
        val crossSite = target != null && chainHost?.let { !Sites.same(target, it.lowercase()) } == true
        val verdict = guard.navigation(request.hasGesture(), request.isRedirect, isWebUrl(url) && adBlocker.pageListed(url),
            blocking(chainHost), fromLink = pressedLink(view).leadsTo(url), crossSite = crossSite)
        debug("navigation $verdict: $url gesture=${request.hasGesture()} redirect=${request.isRedirect}")
        if (verdict == NavigationGuard.Verdict.ALLOW) return false
        adBlocker.countBlocked()
        guard.blocked(url, SystemClock.uptimeMillis())
        host.onRedirectBlocked()
        return true
    }

    private var chainHost: String? = null

    /**
     * What the last press landed on, from WebView's own hit test (no page script involved): a link's address, "" for an
     * image link (WebView doesn't say where it goes), or null when it wasn't a link.
     */
    private fun pressedLink(view: WebView): String? {
        val hit = view.hitTestResult
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> hit.extra.orEmpty()
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> ""
            else -> null
        }
    }

    /** Whether a press on the link [this] (see [pressedLink]) explains going to [url]. */
    private fun String?.leadsTo(url: String): Boolean = when {
        this == null -> false
        isEmpty() -> true
        else -> substringBefore('#') == url.substringBefore('#') || hostOf(this)?.equals(hostOf(url), ignoreCase = true) == true
    }

    /** Pop-up and redirect decisions in the log of test builds, for sites that misbehave. */
    private fun debug(message: String) {
        if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaPopup", message)
    }

    private fun listed(url: String) = isWebUrl(url) && hostOf(url)?.let(adBlocker::listed) == true

    /** Sofa is about to load an address itself; its redirects then count as the user's. */
    fun userNavigation() = guard.userNavigation()

    /** Runs [tap], a tap Sofa makes on the page with nobody pressing anything ("Watch by itself"). */
    fun unattended(tap: () -> Unit) {
        tappingAlone = true
        try { tap() } finally { tappingAlone = false }
    }

    // History moves and reloads are the user's own navigations too.
    fun goBack() { guard.userNavigation(); web?.goBack() }
    fun goForward() { guard.userNavigation(); web?.goForward() }
    fun reload() { guard.userNavigation(); web?.reload() }

    /** Reloads a page whose site the user just allowed from the block screen: unlike a plain reload, a visit. */
    fun reloadAllowed() { allowedReload = web?.url; reload() }

    /** The page has an entry in its back list: something loaded, even if only a blocked site's stand-in. */
    val hasPage: Boolean get() = (web?.copyBackForwardList()?.size ?: 0) > 0

    private fun backList(): Pair<List<String?>, Int>? = web?.copyBackForwardList()?.let { list ->
        (0 until list.size).map { list.getItemAtIndex(it)?.url } to list.currentIndex
    }

    private fun blockedSite(url: String) = adBlocker.siteListed(url) != null

    /** Goes back to the newest page before this one that no Site filters list blocks; false when there is none. */
    fun backPastBlocked(): Boolean {
        val (urls, at) = backList() ?: return false
        val steps = BlockedHistory.back(urls, at, ::blockedSite) ?: return false
        guard.userNavigation()
        web?.goBackOrForward(steps)
        return true
    }

    /**
     * [url] as Sofa remembers the page (saved tabs, "Where I left off"): when a Site filters list blocks it, the newest
     * page before it in this tab that none blocks, or null.
     */
    fun unblocked(url: String): String? {
        if (!blockedSite(url)) return url
        val (urls, at) = backList() ?: return null
        return BlockedHistory.kept(urls, at, ::blockedSite)
    }

    /** Whether ad blocking (and with it pop-up and redirect blocking) applies to pages from [pageHost]. */
    private fun blocking(pageHost: String?) = prefs.adBlock && (pageHost == null || !Allowlist.contains(context, pageHost))

    /**
     * Whether the page is out of sight: behind the start page, or Sofa is in the background. Set by [rest] and [wake];
     * meanwhile the page's own navigations and windows are held, not loaded. Read on WebView's network threads too.
     */
    @Volatile var hidden = false
        private set
    @Volatile private var held: String? = null

    private fun hold(url: String) {
        debug("navigation HELD (out of sight): $url")
        if (isWebUrl(url)) held = url
    }

    /** The page went out of sight. */
    fun rest() {
        hidden = true
    }

    /**
     * The page is in sight again. Returns the last web address it tried to open meanwhile, which "Allow once" can now
     * open, or null.
     */
    fun wake(): String? {
        hidden = false
        return held?.also { held = null; guard.blocked(it, SystemClock.uptimeMillis()) }
    }

    /** Forgets what the resting page tried to open: another address replaces it. */
    fun forgetHeld() {
        held = null
    }

    /** The address blocked last, if "Allow once" may still open it. */
    fun pendingAllow(): String? = guard.pending(SystemClock.uptimeMillis())

    /** Opens the address blocked last, once. */
    fun allowOnce(): String? = guard.takePending(SystemClock.uptimeMillis())

    companion object {
        private const val PRIVATE_PROFILE = "private"
        /** What a blocked site's page is replaced with, under the block screen: empty, dark, and titled with nothing. */
        private val BLOCKED_PAGE = "<!doctype html><meta name=color-scheme content=dark><title></title><body style=background:#121212>".toByteArray()
        private val PRESS_KEYS = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A)
        private const val GPC_SCRIPT =
            "try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:()=>true,configurable:true})}catch(e){}"

        val profilesSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        /**
         * The profile of the private session under way. Each session gets a new one: WebView keeps a profile "in use" for
         * a while after its last view is destroyed and refuses to delete it meanwhile, and a new session must never open
         * an old one's cookies or storage.
         */
        private var privateProfile = PRIVATE_PROFILE

        /** Private browsing starts: its tabs go into a profile of their own, made when the first one loads. */
        fun newPrivateSession() {
            privateProfile = PRIVATE_PROFILE + "-" + System.currentTimeMillis()
        }

        /**
         * Deletes the private profiles, with all their cookies, storage and cache, except the one of the session under
         * way when [keep]. WebView doesn't let go of a profile it loaded until Sofa's process ends, so the profile of a
         * session that ended just now can't be deleted yet: its cookies, site storage and cache are erased instead
         * ([context] makes the view that clears the cache), and the empty profile goes at the next start.
         */
        @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
        fun deletePrivateProfiles(context: Context, keep: Boolean = false) {
            if (!profilesSupported) return
            val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
            for (name in store.allProfileNames) {
                if (name != PRIVATE_PROFILE && !name.startsWith("$PRIVATE_PROFILE-") || keep && name == privateProfile) continue
                if (runCatching { store.deleteProfile(name) }.isSuccess) continue
                if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaPrivate", "profile $name in use: erasing it instead")
                runCatching {
                    val profile = store.getProfile(name) ?: return@runCatching
                    profile.cookieManager.removeAllCookies(null)
                    profile.cookieManager.flush()
                    profile.webStorage.deleteAllData()
                    WebView(context).apply { WebViewCompat.setProfile(this, name); clearCache(true); clearFormData(); destroy() }
                }
            }
        }

        fun isWebUrl(url: String?) = url != null && (url.startsWith("http://") || url.startsWith("https://"))

        fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
    }
}
