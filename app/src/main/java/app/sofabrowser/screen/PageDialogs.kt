package app.sofabrowser.screen

import android.app.AlertDialog
import android.net.http.SslError
import android.webkit.SslErrorHandler
import app.sofabrowser.R
import app.sofabrowser.adblock.FilterList
import app.sofabrowser.adblock.SiteAllowlist
import app.sofabrowser.adblock.Sites
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.ProtectedMedia
import app.sofabrowser.browser.WebViewAge
import app.sofabrowser.browser.WebViews
import app.sofabrowser.ui.PinPad
import app.sofabrowser.ui.SettingsActivity

/** The questions a page raises: a bad certificate, protected media, a site that Site filters block; and an old WebView. */
class PageDialogs(private val s: Screen) {

    private val activity get() = s.activity

    fun sslError(handler: SslErrorHandler, error: SslError) {
        val host = Browser.hostOf(error.url) ?: error.url
        s.dialog().setTitle(R.string.ssl_title)
            .setMessage(activity.getString(R.string.ssl_message, host))
            .setPositiveButton(R.string.ssl_back) { _, _ -> handler.cancel(); if (s.web?.canGoBack() == true) s.browser.goBack() }
            .setNeutralButton(R.string.ssl_proceed) { _, _ -> handler.proceed() }
            .setOnCancelListener { handler.cancel() }
            .show()
    }

    fun protectedMedia(origin: String, grant: (Boolean) -> Unit) {
        val prefs = s.prefs
        when (ProtectedMedia.decide(prefs.drmMode, prefs.drmAnswer(origin))) {
            ProtectedMedia.Decision.GRANT -> { grant(true); return }
            ProtectedMedia.Decision.DENY -> { grant(false); return }
            ProtectedMedia.Decision.ASK -> {}
        }
        // Dismissing with Back blocks this time only; the buttons are remembered for the site.
        var answered = false
        s.dialog().setTitle(R.string.drm_title)
            .setMessage(activity.getString(R.string.drm_message, Browser.hostOf(origin) ?: origin))
            .setPositiveButton(R.string.allow) { _, _ -> answered = true; prefs.setDrmAnswer(origin, true); grant(true) }
            .setNegativeButton(R.string.block) { _, _ -> answered = true; prefs.setDrmAnswer(origin, false); grant(false) }
            .setOnDismissListener { if (!answered) grant(false) }
            .show()
    }

    private var siteBlock: AlertDialog? = null

    /**
     * The block screen: the site and the list that blocks it. "Go back" (focused) and Back leave it; "Allow this site"
     * lets the site through from now on (Settings > Site filters > Allowed sites takes it back),
     * or in private browsing until it ends.
     */
    fun siteBlocked(url: String, list: FilterList, replaced: Boolean) {
        if (activity.isFinishing) return
        siteBlock?.dismiss()
        val site = Sites.of(Browser.hostOf(url)?.lowercase() ?: url)
        fun leave() {
            // Nothing to go back to: the page was the first one opened from the home screen.
            if (replaced) { if (s.web?.canGoBack() == true) s.browser.goBack() else s.showHome() }
            else if (s.web?.url == null) s.showHome()
        }
        siteBlock = s.dialog().setTitle(R.string.site_blocked_title)
            .setMessage(activity.getString(R.string.site_blocked_text, site, SettingsActivity.siteListName(activity, list)))
            .setPositiveButton(R.string.go_back) { _, _ -> leave() }
            .setNegativeButton(R.string.allow_this_site) { _, _ ->
                // Not entered: back to the block screen, with Go back in focus.
                PinPad.guard(activity, R.string.pin_title_allow_site, cancelled = { siteBlocked(url, list, replaced) }) {
                    val private = s.browser.privateMode
                    SiteAllowlist.allow(activity, Browser.hostOf(url) ?: site, private)
                    s.toast(activity.getString(if (private) R.string.site_allowed_private else R.string.site_allowed, site))
                    if (replaced && s.web?.url == url) s.browser.reloadAllowed() else s.openUrl(url)
                }
            }
            .setOnCancelListener { leave() }
            .show()
            .also { it.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus() }
    }

    /** Once per WebView version, and again every few months: Sofa can't update the engine, only say so. */
    fun warnIfOldWebView() {
        val prefs = s.prefs
        val engine = WebViews.engine(activity)
        val now = System.currentTimeMillis()
        if (!WebViewAge.shouldWarn(engine.major, engine.version, prefs.webViewWarnedVersion, prefs.webViewWarnedAt, now)) return
        prefs.webViewWarnedVersion = engine.version
        prefs.webViewWarnedAt = now
        val d = s.dialog().setTitle(R.string.old_webview_title)
            .setMessage(R.string.old_webview_message)
            .setPositiveButton(R.string.keep_browsing, null)
            .setNeutralButton(R.string.how_to_update) { _, _ ->
                s.dialog().setTitle(R.string.how_to_update).setMessage(R.string.how_to_update_message)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
            .create()
        d.setOnShowListener { d.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() }
        d.show()
    }
}
