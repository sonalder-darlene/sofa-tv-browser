package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
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

/**
 * Owns the single WebView: creates it in the normal or private profile, applies settings and relays page events.
 * BrowsHere has no tabs either; one view keeps memory use low on TV hardware.
 */
class Browser(private val context: Context, private val prefs: Prefs, private val host: Host) {

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
        fun onSslError(handler: SslErrorHandler, error: SslError)
        fun onProtectedMediaRequest(origin: String, grant: (Boolean) -> Unit)
        fun onDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long)
        fun onBlockedLink(url: String)
        fun onPopupBlocked()
        fun onRendererGone()
    }

    var web: WebView? = null
        private set
    var privateMode = false
        private set

    /** True when private browsing runs in its own WebView profile; false on WebViews too old for profiles. */
    var isolated = false
        private set

    /** Host of the page in the main frame, readable from WebView's network threads. */
    @Volatile var pageHost: String? = null
        private set

    val adBlocker = AdBlocker.get(context)

    /** Creates (or re-creates) the WebView. Returns the new view; the caller attaches it to its layout. */
    @SuppressLint("SetJavaScriptEnabled", "RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
    fun create(private: Boolean): WebView {
        destroy()
        privateMode = private
        isolated = private && profilesSupported
        if (app.sofabrowser.BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        val view = WebView(context)
        if (profilesSupported) {
            // Must happen before anything else touches the view.
            WebViewCompat.setProfile(view, if (isolated) PRIVATE_PROFILE else androidx.webkit.Profile.DEFAULT_PROFILE_NAME)
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
        VideoRelay.install(view, key) { state -> view.post { host.onVideoState(state) } }
        PageDownloads.install(view, key)
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
        if (isolated) deletePrivateProfile()
        privateMode = false
        isolated = false
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
            adBlocker.resetPageCount()
            host.onPageStarted(url)
        }

        override fun onPageFinished(view: WebView, url: String) = host.onPageFinished(url)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            pageHost = AdBlocker.hostOf(url)
            if (!isReload) host.onVisited(url)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (prefs.adBlock) adBlocker.intercept(request, pageHost) else null

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            return when (url.scheme?.lowercase()) {
                "http", "https", "about", "data", "blob", "javascript", null -> false
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
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = host.onSslError(handler, error)

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

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            if (!isUserGesture) { host.onPopupBlocked(); return false }
            // Open the target in the same view (no tabs): catch the first URL the throwaway view is sent to.
            val catcher = WebView(view.context)
            var used = false
            @SuppressLint("MissingOnRenderProcessGone") // implemented; the callback starts at API 26
            val catcherClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    if (!used) view.loadUrl(request.url.toString())
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

    companion object {
        private const val PRIVATE_PROFILE = "private"
        private const val GPC_SCRIPT =
            "try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:()=>true,configurable:true})}catch(e){}"

        val profilesSupported get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

        /** Deletes the private profile with all its cookies, storage and cache. Call only with no view using it. */
        @SuppressLint("RequiresFeature") // MULTI_PROFILE is checked via profilesSupported
        fun deletePrivateProfile() {
            if (!profilesSupported) return
            runCatching {
                val store = ProfileStore.getInstance()
                if (PRIVATE_PROFILE in store.allProfileNames) store.deleteProfile(PRIVATE_PROFILE)
            }
        }

        fun isWebUrl(url: String?) = url != null && (url.startsWith("http://") || url.startsWith("https://"))

        fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
    }
}
