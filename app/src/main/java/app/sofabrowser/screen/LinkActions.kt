package app.sofabrowser.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.sofabrowser.BuildConfig
import app.sofabrowser.R
import app.sofabrowser.adblock.RequestUrl
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.LinkMenu
import app.sofabrowser.browser.PageDownloads
import app.sofabrowser.ui.HomeBackground
import app.sofabrowser.ui.HomePicture
import app.sofabrowser.ui.PictureSize
import app.sofabrowser.ui.PinPad
import jp.hazuki.yuzubrowser.adblock.core.ContentRequest

/**
 * Link menu: OK held still on a link or image. What is under the pointer comes from WebView's own hit test, which
 * reaches into frames and needs no page script.
 */
class LinkActions(private val s: Screen, private val downloads: DownloadStarter) {

    private val activity get() = s.activity

    /** OK is held on the page; false when there is no link or image under the pointer. */
    fun hold(): Boolean {
        val w = s.web ?: return false
        val hit = w.hitTestResult
        if (BuildConfig.DEBUG) android.util.Log.d("SofaLink", "hold: hit type ${hit.type} ${hit.extra?.take(120)}")
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> showMenu(hit.extra, null)
            WebView.HitTestResult.IMAGE_TYPE -> showMenu(null, hit.extra)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                // The hit test names the picture; the link around it is a second question, answered a moment later.
                val image = hit.extra
                val reply = Handler(Looper.getMainLooper()) { m -> showMenu(m.data.getString("url"), image); true }
                w.requestFocusNodeHref(reply.obtainMessage())
                true
            }
            else -> false
        }
    }

    private fun showMenu(link: String?, image: String?): Boolean {
        val actions = LinkMenu.actions(link, image)
        if (actions.isEmpty()) return false
        val labels = actions.map { activity.getString(when (it) {
            LinkMenu.Action.OPEN_ELSEWHERE -> R.string.link_open_elsewhere
            LinkMenu.Action.COPY_LINK -> R.string.link_copy
            LinkMenu.Action.SHARE_LINK -> R.string.link_share
            LinkMenu.Action.DOWNLOAD_LINK -> R.string.link_download
            LinkMenu.Action.OPEN_IMAGE -> R.string.image_open
            LinkMenu.Action.DOWNLOAD_IMAGE -> R.string.image_download
            LinkMenu.Action.COPY_IMAGE -> R.string.image_copy
            LinkMenu.Action.SHARE_IMAGE -> R.string.image_share
            LinkMenu.Action.SET_BACKGROUND -> R.string.image_set_background
        }) }
        s.dialog().setTitle(LinkMenu.title(link, image).ifEmpty { activity.getString(R.string.image) })
            .setItems(labels.toTypedArray()) { _, which -> act(actions[which], link.orEmpty(), image.orEmpty()) }
            .show()
        return true
    }

    private fun act(action: LinkMenu.Action, link: String, image: String) {
        when (action) {
            LinkMenu.Action.OPEN_ELSEWHERE -> openElsewhere(link)
            LinkMenu.Action.COPY_LINK -> copy(link, R.string.link_copied)
            LinkMenu.Action.SHARE_LINK -> share(link)
            LinkMenu.Action.DOWNLOAD_LINK -> downloads.start(link, null, null, null, -1, waited = true)
            LinkMenu.Action.OPEN_IMAGE -> s.openUrl(image)
            LinkMenu.Action.DOWNLOAD_IMAGE -> downloads.start(image, null, null, null, -1, waited = true)
            LinkMenu.Action.COPY_IMAGE -> copy(image, R.string.image_copied)
            LinkMenu.Action.SHARE_IMAGE -> share(image)
            LinkMenu.Action.SET_BACKGROUND -> PinPad.guard(activity, R.string.pin_title_background) { setStartPagePicture(image) }
        }
    }

    /**
     * The held picture as the start page's background: one picture of the page, read from it or fetched once like the
     * page fetched it, never from a site the blockers stop.
     */
    private fun setStartPagePicture(image: String) {
        val web = s.web
        val page = web?.url
        val done = { outcome: HomePicture.Outcome ->
            when (outcome) {
                HomePicture.Outcome.SET -> { s.prefs.homeBackground = HomeBackground.PICTURE; s.toast(R.string.background_set) }
                HomePicture.Outcome.TOO_LARGE -> s.toast(R.string.background_too_large)
                HomePicture.Outcome.UNREADABLE -> s.toast(R.string.background_unreadable)
            }
        }
        when {
            image.startsWith("data:") -> HomePicture.set(activity, { PageDownloads.decodeDataUrl(image)?.bytes?.inputStream() ?: throw java.io.IOException() }, done)
            image.startsWith("blob:") -> {
                val view = web ?: return
                PageDownloads.fetchBlob(view, image, PictureSize.MAX_SOURCE_BYTES) { result ->
                    result.onSuccess { HomePicture.set(activity, { it.bytes.inputStream() }, done) }.onFailure { done(HomePicture.Outcome.UNREADABLE) }
                }
            }
            Browser.isWebUrl(image) -> {
                val blocker = s.browser.adBlocker
                if (blocker.siteListed(image) != null || s.prefs.adBlock && blocker.rules.blocks(RequestUrl(image), ContentRequest.TYPE_IMAGE, Browser.hostOf(page))) {
                    s.toast(R.string.background_blocked)
                    return
                }
                val agent = web?.settings?.userAgentString
                HomePicture.set(activity, { HomePicture.fetch(image, agent, page) }, done)
            }
            else -> return
        }
        s.toast(R.string.background_setting)
    }

    /** Android's chooser for [url], without Sofa in it. */
    private fun openElsewhere(url: String) {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        startOutside(view, R.string.link_open_elsewhere)
    }

    private fun share(text: String) =
        startOutside(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), R.string.share)

    private fun startOutside(intent: Intent, title: Int) {
        val chooser = Intent.createChooser(intent, activity.getString(title))
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(activity, activity.javaClass)))
        runCatching { activity.startActivity(chooser) }.onFailure { s.toast(R.string.no_other_app) }
    }

    private fun copy(text: String, done: Int) {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.link), text))
        s.toast(done)
    }
}
