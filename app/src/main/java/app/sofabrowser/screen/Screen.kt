package app.sofabrowser.screen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.CursorLayout
import app.sofabrowser.browser.FullscreenLayer
import app.sofabrowser.ui.FindBar
import app.sofabrowser.ui.Ui
import app.sofabrowser.ui.px

/**
 * What the parts of the browser screen (the menu, Watch, the fullscreen video, the link menu, downloads, game pads)
 * share: the activity, its views, and the page in sight. Parts read [browser] each time they act and never keep it,
 * so the page in sight can change under them.
 */
interface Screen {
    val activity: Activity
    val prefs: Prefs
    /** The tabs of the side in sight (normal or private browsing). */
    val tabs: Tabs
    /** The page in sight, or resting behind the start page: the one of [Tabs.current]. */
    val browser: Browser
    val web: WebView? get() = browser.web
    val cursor: CursorLayout
    val fullscreen: FullscreenLayer
    val findBar: FindBar
    val menu: PageMenu
    /** The screen's root, for cards laid over everything. */
    val root: FrameLayout
    val homeShown: Boolean
    /** Between onStart and onStop. */
    val started: Boolean
    val inPip: Boolean get() = activity.isInPictureInPictureMode
    /** The page in sight is loading. */
    val loading: Boolean

    /** Opens [url] in the tab in sight. */
    fun openUrl(url: String)
    /** Opens [url] in a new tab next to the one in sight, and brings it into sight. */
    fun openInNewTab(url: String)
    /** A new empty tab, with the start page. */
    fun newTab()
    fun selectTab(tab: Tab)
    fun closeTabs(which: List<Tab>)
    fun showHome()
    /**
     * Go back from a blocked site's stand-in: to the newest page in the tab that no list blocks; with none, a tab made
     * for the link closes and what was in sight comes back, and any other tab keeps only the start page.
     */
    fun leaveBlocked()
    fun startSearch(voice: Boolean, current: String?)
    fun openList(mode: String)
    fun openSettings()
    fun openDownloads()
    fun openFind()
    fun applyKeepAwake()
    fun toast(res: Int)
    fun toast(text: String)
    fun dialog(): AlertDialog.Builder = Ui.dialog(activity)
}

/** [v] with the margins of a dialog's own text, for a field in a dialog. */
fun padded(context: Context, v: View) = FrameLayout(context).apply {
    setPadding(48.px, 24.px, 48.px, 0)
    addView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** What Sofa read to find a page's stream, in the log of test builds. */
fun streamLog(message: String) {
    if (BuildConfig.DEBUG) android.util.Log.d("SofaStream", message)
}
