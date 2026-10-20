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
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * Network-level ad and tracker blocking. Requests are matched on-device against a domain list; nothing
 * about browsing leaves the device. The list ships in the APK and can optionally be refreshed from a URL the
 * user controls.
 */
class AdBlocker private constructor(private val context: Context) {

    /** What is blocked: the main list (bundled, or the user's download) and the bundled pop-up list together. */
    @Volatile var list: DomainBlocklist = DomainBlocklist.EMPTY
        private set

    /** The main list (bundled, or the user's download), and the bundled pop-up and redirect domains added to it. */
    @Volatile private var main: DomainBlocklist = DomainBlocklist.EMPTY
    @Volatile private var popups: DomainBlocklist = DomainBlocklist.EMPTY
    private val lock = Any()

    private fun publish() = synchronized(lock) { list = DomainBlocklist.merge(main, popups) }

    private val blocked = AtomicInteger()

    /** Requests, pop-ups and redirects blocked since the current page started. */
    val blockedCount: Int get() = blocked.get()

    private val compiled get() = File(context.filesDir, "blocklist.bin")

    init {
        thread(name = "blocklist-load", priority = Thread.MIN_PRIORITY) {
            // A broken list must never take the browser down; at worst nothing is blocked. The compiled main list
            // loads in moments, so it blocks first; the pop-up list (parsed from text) joins it after. A download
            // finished meanwhile wins over what is loaded here.
            val cached = runCatching { compiled.inputStream().use(DomainBlocklist::read) }.getOrNull()
                ?: runCatching { parseAsset(BUNDLED_ASSET) }.getOrNull()?.also { runCatching { writeCache(it) } }
            if (cached != null) synchronized(lock) { if (main === DomainBlocklist.EMPTY) main = cached }
            publish()
            popups = runCatching { parseAsset(POPUP_ASSET) }.getOrDefault(DomainBlocklist.EMPTY)
            publish()
        }
    }

    /** Returns a stand-in response for a blocked subresource request (see [BlockedResponse]), or null to let it through. */
    fun intercept(request: WebResourceRequest, pageHost: String?): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val host = request.url.host ?: return null
        if (pageHost != null && Allowlist.contains(context, pageHost)) return null
        if (!list.isBlocked(host)) return null
        blocked.incrementAndGet()
        val accept = request.requestHeaders.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
        return when (BlockedResponse.kind(request.url.toString(), accept)) {
            BlockedResponse.Kind.IMAGE -> ok("image/gif", BlockedResponse.PIXEL)
            BlockedResponse.Kind.CSS -> ok("text/css", ByteArray(0))
            BlockedResponse.Kind.ERROR ->
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun ok(mime: String, body: ByteArray) =
        WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body))

    fun resetPageCount() = blocked.set(0)

    /** Counts something blocked outside [intercept], such as a pop-up or a redirect. */
    fun countBlocked() {
        blocked.incrementAndGet()
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
                // What is blocked now, pop-up list included, as Settings counts it.
                list.size
            } finally {
                conn.disconnect()
            }
        })
    }

    /** Reverts to the list bundled with the app. */
    fun resetToBundled() = thread(name = "blocklist-reset") {
        compiled.delete()
        runCatching { save(parseAsset(BUNDLED_ASSET)) }
    }

    // The repository keeps the lists gzipped; AGP un-gzips ".gz" assets when packaging (the APK's zip compression
    // covers it), so accept either form.
    private fun parseAsset(name: String): DomainBlocklist {
        val stream = runCatching { context.assets.open(name.removeSuffix(".gz")) }.getOrNull()
            ?: GZIPInputStream(context.assets.open(name))
        return stream.bufferedReader().use(DomainBlocklist::parse)
    }

    /** The compiled cache holds the main list only; the pop-up list is always added from the APK. */
    private fun writeCache(fresh: DomainBlocklist) {
        val tmp = File(context.filesDir, "blocklist.bin.tmp")
        tmp.outputStream().use(fresh::writeTo)
        tmp.renameTo(compiled)
    }

    /** Makes [fresh] the main list, cached for next time, and blocks it with the pop-up list. */
    private fun save(fresh: DomainBlocklist) {
        writeCache(fresh)
        synchronized(lock) { main = fresh }
        publish()
    }

    companion object {
        const val BUNDLED_ASSET = "blocklist-hosts.txt.gz"
        const val POPUP_ASSET = "blocklist-popups.txt.gz"

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
