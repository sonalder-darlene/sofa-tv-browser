package app.sofabrowser.adblock

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Network-level ad and tracker blocking. Requests are matched on-device against a domain list; nothing
 * about browsing leaves the device. The list ships in the APK and can optionally be refreshed from a URL the
 * user controls.
 */
class AdBlocker private constructor(private val context: Context) {

    @Volatile var list: DomainBlocklist = DomainBlocklist.EMPTY
        private set

    @Volatile var blockedCount = 0
        private set

    private val compiled get() = File(context.filesDir, "blocklist.bin")

    init {
        thread(name = "blocklist-load", priority = Thread.MIN_PRIORITY) {
            // A broken list must never take the browser down; at worst nothing is blocked.
            list = runCatching { compiled.inputStream().use(DomainBlocklist::read) }.getOrNull()
                ?: runCatching { compileBundled() }.getOrDefault(DomainBlocklist.EMPTY)
        }
    }

    /** Returns an empty response to cancel a blocked subresource request, or null to let it through. */
    fun intercept(request: WebResourceRequest, pageHost: String?): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val host = request.url.host ?: return null
        if (pageHost != null && Allowlist.contains(context, pageHost)) return null
        if (!list.isBlocked(host)) return null
        blockedCount++
        return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    fun resetPageCount() {
        blockedCount = 0
    }

    /**
     * Downloads a hosts or Adblock Plus list from [url] and replaces the active list. The request carries no
     * cookies or identifiers; it is a plain GET.
     */
    fun update(url: String, done: (Result<Int>) -> Unit) = thread(name = "blocklist-update") {
        done(runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                val stream = if (url.endsWith(".gz")) GZIPInputStream(conn.inputStream) else conn.inputStream
                val fresh = stream.bufferedReader().use(DomainBlocklist::parse)
                check(fresh.size > 0) { "no domain rules found" }
                save(fresh)
                fresh.size
            } finally {
                conn.disconnect()
            }
        })
    }

    /** Reverts to the list bundled with the app. */
    fun resetToBundled() = thread(name = "blocklist-reset") {
        compiled.delete()
        list = runCatching { compileBundled() }.getOrDefault(DomainBlocklist.EMPTY)
    }

    private fun compileBundled(): DomainBlocklist {
        // The repository keeps the list gzipped; AGP un-gzips ".gz" assets when packaging (the APK's zip
        // compression covers it), so accept either form.
        val stream = runCatching { context.assets.open(BUNDLED_ASSET.removeSuffix(".gz")) }.getOrNull()
            ?: GZIPInputStream(context.assets.open(BUNDLED_ASSET))
        val parsed = stream.bufferedReader().use(DomainBlocklist::parse)
        save(parsed)
        return parsed
    }

    private fun save(fresh: DomainBlocklist) {
        val tmp = File(context.filesDir, "blocklist.bin.tmp")
        tmp.outputStream().use(fresh::writeTo)
        tmp.renameTo(compiled)
        list = fresh
    }

    companion object {
        const val BUNDLED_ASSET = "blocklist-hosts.txt.gz"

        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: AdBlocker? = null

        fun get(context: Context): AdBlocker = instance ?: synchronized(this) {
            instance ?: AdBlocker(context.applicationContext).also { instance = it }
        }

        fun hostOf(url: String?): String? = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
    }
}

/** Sites where the user switched ad blocking off. */
object Allowlist {
    private const val PREFS = "adblock_allowlist"

    fun contains(context: Context, host: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(normalize(host))

    fun set(context: Context, host: String, allowed: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (allowed) putBoolean(normalize(host), true) else remove(normalize(host))
        }.apply()
    }

    private fun normalize(host: String) = host.lowercase().removePrefix("www.")
}
