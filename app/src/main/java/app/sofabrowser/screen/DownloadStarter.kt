package app.sofabrowser.screen

import android.Manifest
import android.os.Build
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import app.sofabrowser.R
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.PageDownloads
import app.sofabrowser.browser.WebViews
import app.sofabrowser.downloads.Download
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.Http
import app.sofabrowser.downloads.Status
import kotlin.concurrent.thread

/**
 * Downloads from the page, each after a "Download file?" question; finished ones are announced while the screen is in
 * front, and [onActive] says whether anything is downloading.
 */
class DownloadStarter(private val s: Screen, private val onActive: (Boolean) -> Unit) {

    private val activity get() = s.activity

    fun start(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?, length: Long, waited: Boolean) {
        val inPage = url.startsWith("data:") || url.startsWith("blob:")
        // The clicked link's download="name" can arrive a moment after the download itself; give it one beat.
        if (inPage && !waited && PageDownloads.suggestedName(url) == null) {
            s.cursor.postDelayed({ start(url, userAgent, contentDisposition, mimeType, length, waited = true) }, 150)
            return
        }
        val mime = mimeType?.takeIf { it.isNotBlank() } ?: if (url.startsWith("data:")) PageDownloads.dataUrlMime(url) else null
        val extension = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }?.let { ".$it" } ?: ""
        val name = Http.safeFileName(PageDownloads.suggestedName(url)
            ?: Http.filenameFromContentDisposition(contentDisposition)
            ?: if (inPage) "download$extension" else URLUtil.guessFileName(url, contentDisposition, mimeType))
        val private = s.browser.privateMode
        val page = s.web?.url
        when {
            url.startsWith("data:") -> confirm(name, length, Browser.hostOf(page)) {
                thread(name = "data-url") {
                    val payload = runCatching { PageDownloads.decodeDataUrl(url) }.getOrNull()
                    activity.runOnUiThread {
                        if (payload == null) s.toast(R.string.download_unsupported)
                        else DownloadEngine.saveBytes(name, payload.mime ?: mime, payload.bytes, url, private, ::saved)
                    }
                }
            }
            url.startsWith("blob:") -> confirm(name, length, Browser.hostOf(page)) {
                val view = s.web ?: return@confirm
                PageDownloads.fetchBlob(view, url, DownloadEngine.maxBlobBytes) { result ->
                    result.onSuccess { DownloadEngine.saveBytes(name, it.mime ?: mime, it.bytes, page ?: url, private, ::saved) }
                        .onFailure { s.toast(activity.getString(R.string.download_failed, it.message)) }
                }
            }
            Browser.isWebUrl(url) -> confirm(name, length, Browser.hostOf(url)) {
                val web = s.web
                val cookies = web?.let { WebViews.cookieManager(it).getCookie(url) }
                DownloadEngine.enqueue(url, name, mime, length, page, userAgent ?: web?.settings?.userAgentString, cookies, private)
                s.toast(activity.getString(R.string.download_started, name))
                // Once, for the progress notification; downloads work just the same without it.
                val prefs = s.prefs
                if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications) {
                    prefs.askedNotifications = true
                    activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
                }
            }
            else -> s.toast(R.string.download_unsupported)
        }
    }

    private fun confirm(name: String, length: Long, host: String?, start: () -> Unit) {
        val size = if (length > 0) " (" + Http.formatBytes(length) + ")" else ""
        s.dialog().setTitle(R.string.download_title)
            .setMessage(activity.getString(R.string.download_message, name + size, host ?: ""))
            .setPositiveButton(R.string.download) { _, _ -> start() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saved(result: Result<Download>) {
        result.onSuccess { s.toast(activity.getString(R.string.download_done, it.name)) }
            .onFailure { s.toast(activity.getString(R.string.download_failed, it.message)) }
    }

    /** Finished downloads announce themselves, and the Home screen shows when something is downloading. */
    private val listener: () -> Unit = {
        val all = DownloadEngine.all
        all.filter { it.status == Status.DONE && it.id !in announced && it.id in watched }.forEach {
            s.toast(activity.getString(R.string.download_done, it.name))
        }
        all.filter { it.status == Status.DONE }.forEach { announced.add(it.id) }
        watched.addAll(all.filter { it.active }.map { it.id })
        onActive(all.any { it.active })
    }
    private val announced = HashSet<Long>()
    private val watched = HashSet<Long>()

    /** The screen is in front: downloads are followed until [stopFollowing]. */
    fun follow() {
        DownloadEngine.addListener(listener)
        listener()
    }

    fun stopFollowing() = DownloadEngine.removeListener(listener)

    private companion object {
        const val REQ_NOTIFICATIONS = 11
    }
}
