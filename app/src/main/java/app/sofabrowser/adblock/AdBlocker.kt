package app.sofabrowser.adblock

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import app.sofabrowser.BuildConfig
import jp.hazuki.yuzubrowser.adblock.getContentType
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Ad and tracker blocking with filter lists in Adblock Plus / uBlock Origin syntax or hosts format: network rules
 * through the Yuzu Browser engine (as adapted in Fulguris), element hiding and trap links through [InPageBlocking]. Everything is
 * matched on the device; nothing about browsing leaves it. Two lists ship in the APK; more can be added by address,
 * and each list is downloaded only when the user asks (a plain GET without cookies or identifiers).
 *
 * Lists are compiled once into a binary cache, so starting Sofa never re-reads list text, and they load on a
 * background thread: until they have, nothing is blocked. A list that fails to load is left out; it never takes the
 * browser down.
 */
class AdBlocker private constructor(private val context: Context) {

    /** Every enabled list, merged. Replaced as a whole when lists change, so network threads never see half a set. */
    @Volatile var rules: RuleSet = RuleSet.EMPTY
        private set

    /** The lists in Settings, in order. */
    @Volatile var lists: List<FilterList> = emptyList()
        private set

    /** True once the lists have loaded after start. */
    @Volatile var ready = false
        private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "filter-lists").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val store = context.getSharedPreferences("filter_lists", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "filters")

    private val blocked = AtomicInteger()

    /** Requests, pop-ups and redirects blocked since the current page started. */
    val blockedCount: Int get() = blocked.get()

    init {
        worker.execute {
            lists = runCatching { loadOrMigrate() }.getOrDefault(FilterList.defaults())
            publish()
            ready = true
        }
    }

    /** Whether [host] is on a blocked-domain list; what video stream detection checks. */
    fun listed(host: String): Boolean = rules.domains.isBlocked(host)

    /** Whether lists block a window that a page at [openerHost] opens at [url] (blocked domains and $popup rules). */
    fun popupListed(url: String, openerHost: String?): Boolean = rules.blocksPopup(RequestUrl(url), openerHost)

    /** Whether lists block the page at [url] (blocked domains and $document rules), for pages a site sends Sofa to. */
    fun pageListed(url: String): Boolean = rules.blocksPage(RequestUrl(url))

    /** The page's address and whether a @@…$document exception lets everything on it through, for [intercept]. */
    @Volatile private var page: Pair<String, Boolean>? = null

    /**
     * Returns a stand-in response for a blocked subresource request (see [BlockedResponse]), or null to let it through.
     * [pageUrl] and [pageHost] are those of the page in the main frame.
     */
    fun intercept(request: WebResourceRequest, pageUrl: String?, pageHost: String?): WebResourceResponse? {
        if (request.isForMainFrame) return null
        val url = request.url.toString()
        if (!url.startsWith("http") && !url.startsWith("ws")) return null
        if (pageHost != null && Allowlist.contains(context, pageHost)) return null
        val set = rules
        if (pageUrl != null && !pageAllowed(set, pageUrl)) return null
        val target = RequestUrl(url)
        val headers = request.requestHeaders
        if (!set.blocks(target, getContentType(target, false, headers), pageHost)) return null
        blocked.incrementAndGet()
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }?.value
        return when (BlockedResponse.kind(url, accept)) {
            BlockedResponse.Kind.IMAGE -> ok("image/gif", BlockedResponse.PIXEL)
            BlockedResponse.Kind.CSS -> ok("text/css", ByteArray(0))
            BlockedResponse.Kind.ERROR ->
                WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }

    private fun pageAllowed(set: RuleSet, pageUrl: String): Boolean {
        page?.let { (u, allowed) -> if (u == pageUrl) return allowed }
        return set.pageAllowed(RequestUrl(pageUrl)).also { page = pageUrl to it }
    }

    private fun ok(mime: String, body: ByteArray) =
        WebResourceResponse(mime, "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(body))

    fun resetPageCount() = blocked.set(0)

    /** Counts something blocked outside [intercept], such as a pop-up or a redirect. */
    fun countBlocked() {
        blocked.incrementAndGet()
    }

    // Managing lists. Each change runs on the worker thread; [done] runs on the main thread.

    fun setEnabled(id: String, on: Boolean, done: () -> Unit = {}) = change(done) {
        edit(id) { it.copy(enabled = on) }
        // A list switched on for the first time may never have been downloaded.
        val list = lists.first { it.id == id }
        if (on && !list.bundled && !rawFile(list).isFile && compiledFile(list).isFile.not()) refresh(list)
        publish()
    }

    /** Whether EasyList and EasyPrivacy are both on ("Stronger ad blocking" in Settings). */
    val stronger: Boolean get() = lists.filter { it.stronger }.let { it.isNotEmpty() && it.all(FilterList::enabled) }

    /** Switches EasyList and EasyPrivacy on or off together, downloading them the first time. */
    fun setStronger(on: Boolean, done: () -> Unit = {}) = change(done) {
        lists.filter { it.stronger }.forEach { list ->
            edit(list.id) { it.copy(enabled = on) }
            if (on && !rawFile(list).isFile && !compiledFile(list).isFile) refresh(list)
        }
        publish()
    }

    /**
     * With automatic updates on, downloads each list that is on and due again: older than it asks to be (between a day
     * and a week; a bundled list counts from when Sofa was installed or updated). Checks at most every few hours, so
     * calling it whenever Sofa comes to the front costs nothing.
     */
    fun refreshIfDue(automatic: Boolean) {
        if (!automatic) return
        worker.execute {
            val now = System.currentTimeMillis()
            if (now - store.getLong(KEY_LAST_CHECK, 0) < CHECK_EVERY_MS) return@execute
            store.edit().putLong(KEY_LAST_CHECK, now).apply()
            val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
            val due = lists.filter { it.enabled && it.url != null && FilterList.isDue(it, installed, now) }
            if (due.isEmpty()) return@execute
            due.forEach { refresh(it) }
            publish()
        }
    }

    /** Adds the list at [url] and downloads it; the result carries the new list, or why it failed. */
    fun add(url: String, done: (Result<FilterList>) -> Unit) = worker.execute {
        val result = runCatching {
            require(lists.none { it.url == url }) { "already added" }
            val list = FilterList("u" + System.currentTimeMillis().toString(36), RequestUrl.hostOf(url) ?: url, url)
            lists = lists + list
            save()
            val fresh = refresh(list)
            publish()
            fresh.getOrElse { lists = lists - lists.first { it.id == list.id }; save(); delete(list); throw it }
        }
        main.post { done(result) }
    }

    fun remove(id: String, done: () -> Unit = {}) = change(done) {
        val list = lists.firstOrNull { it.id == id } ?: return@change
        if (list.bundled) return@change
        lists = lists - list
        save()
        delete(list)
        publish()
    }

    /** Downloads every enabled list that has an address; the result counts the lists that failed. */
    fun updateAll(done: (Int) -> Unit) = worker.execute {
        var failed = 0
        lists.filter { it.enabled && it.url != null }.forEach { if (refresh(it).isFailure) failed++ }
        publish()
        main.post { done(failed) }
    }

    /** Downloads one list again. */
    fun update(id: String, done: (Result<FilterList>) -> Unit) = worker.execute {
        val list = lists.firstOrNull { it.id == id }
        val result = list?.let { refresh(it) } ?: Result.failure(IllegalArgumentException("no such list"))
        publish()
        main.post { done(result) }
    }

    /** Goes back to the copy of a bundled list that came with the app. */
    fun resetToBundled(id: String, done: () -> Unit = {}) = change(done) {
        val list = lists.firstOrNull { it.id == id }?.takeIf { it.bundled } ?: return@change
        delete(list)
        edit(id) { it.copy(updated = 0, error = null) }
        publish()
    }

    private fun change(done: () -> Unit, block: () -> Unit) = worker.execute {
        runCatching(block)
        main.post(done)
    }

    // Worker thread only from here on.

    private fun loadOrMigrate(): List<FilterList> {
        store.getString(KEY_LISTS, null)?.let { json ->
            val stored = FilterList.listFromJson(json)
            // The lists every install has can't be removed; one added in a later version appears here.
            val (complete, renamed) = FilterList.complete(stored)
            renamed.forEach { (from, to) ->
                File(dir, "$from.txt.gz").renameTo(File(dir, "$to.txt.gz"))
                File(dir, "$from.bin").renameTo(File(dir, "$to.bin"))
            }
            if (stored.isNotEmpty()) {
                if (complete != stored) { lists = complete; save() }
                return complete
            }
        }
        // First start of this version: Sofa 1.1 had one main list, at an address in Settings.
        val old = File(context.filesDir, "blocklist.bin")
        val oldUrl = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("blocklist_url", null)
        val migrated = FilterList.migrate(oldUrl, old.isFile)
        val custom = migrated.firstOrNull { !it.bundled && it.url == oldUrl }
        val result = if (custom != null && old.isFile) {
            // Its full rules come with the next download; until then it keeps blocking the domains it blocked.
            runCatching {
                val domains = old.inputStream().use(DomainBlocklist::read)
                writeCompiled(custom, CompiledList.domainsOnly(domains), MIGRATED)
                migrated.map { if (it.id == custom.id) it.copy(rules = domains.size, updated = old.lastModified()) else it }
            }.getOrDefault(migrated)
        } else migrated
        lists = result
        save()
        old.delete()
        File(context.filesDir, "blocklist.bin.tmp").delete()
        return result
    }

    /**
     * Makes the enabled lists the active rules. A cache from an older Sofa or list format is used until its list is
     * compiled again, so after an app update blocking starts at once and catches up in the background.
     */
    private fun publish() {
        val started = System.nanoTime()
        val stale = ArrayList<FilterList>()
        build { list -> compiled(list, stale) }
        if (stale.isNotEmpty()) {
            val ids = stale.map { it.id }.toSet()
            build { list -> if (list.id in ids) recompile(list) else compiled(list, null) }
        }
        // Loading leaves several times the rules' size behind as garbage (about 35 MB with EasyList and EasyPrivacy);
        // collecting it now, on this background thread, gives it back before a small TV needs it elsewhere.
        Runtime.getRuntime().gc()
        if (BuildConfig.DEBUG) Log.d("SofaAdblock", "${rules.rules} rules from ${lists.count { it.enabled }} lists " +
            "(${stale.size} compiled again) in ${(System.nanoTime() - started) / 1_000_000} ms")
    }

    private fun build(load: (FilterList) -> CompiledList?) {
        val builder = RuleSet.Builder()
        lists.filter { it.enabled }.forEach { list ->
            val compiled = runCatching { load(list) }.getOrNull() ?: return@forEach
            builder.add(compiled)
            if (compiled.rules != list.rules) edit(list.id) { it.copy(rules = compiled.rules) }
        }
        rules = builder.build()
        page = null
    }

    /**
     * The list's compiled rules from its cache. A cache for another version of the list's text is still returned when
     * [stale] collects such lists; otherwise, or without a cache, the list is compiled again. Null when it has no rules yet.
     */
    private fun compiled(list: FilterList, stale: MutableList<FilterList>?): CompiledList? {
        val stamp = stamp(list)
        runCatching {
            DataInputStream(compiledFile(list).inputStream().buffered(1 shl 16)).use { input ->
                val found = CompiledList.readSource(input)
                if (found == stamp || found == MIGRATED && stamp == null) return CompiledList.readBody(input)
                if (found != null && stale != null) return CompiledList.readBody(input).also { stale += list }
            }
        }
        return recompile(list)
    }

    private fun recompile(list: FilterList): CompiledList? {
        val text = source(list) ?: return null
        val fresh = text.bufferedReader().use(CompiledList::parse)
        writeCompiled(list, fresh, stamp(list) ?: 0L)
        return fresh
    }

    /** What the cache must have been compiled from: the download (by its time), the APK (by version), or null for none. */
    private fun stamp(list: FilterList): Long? {
        val raw = rawFile(list)
        return when {
            raw.isFile -> raw.lastModified()
            list.asset != null -> BuildConfig.VERSION_CODE.toLong()
            else -> null
        }
    }

    private fun source(list: FilterList): InputStream? {
        val raw = rawFile(list)
        if (raw.isFile) return GZIPInputStream(raw.inputStream())
        val asset = list.asset ?: return null
        // The repository keeps the lists gzipped; AGP un-gzips ".gz" assets when packaging (the APK's zip compression
        // covers it), so accept either form.
        return runCatching { context.assets.open(asset.removeSuffix(".gz")) }.getOrNull() ?: GZIPInputStream(context.assets.open(asset))
    }

    /** Downloads [list] and compiles it; on failure the list keeps its previous rules and shows the error. */
    private fun refresh(list: FilterList): Result<FilterList> {
        val url = list.url ?: return Result.failure(IllegalStateException("no address"))
        val result = runCatching {
            dir.mkdirs()
            val tmp = File(dir, list.id + ".txt.gz.tmp")
            try {
                download(url, tmp)
                val fresh = GZIPInputStream(tmp.inputStream()).bufferedReader().use(CompiledList::parse)
                if (fresh.rules == 0) throw IOException("no rules found")
                val raw = rawFile(list)
                if (!tmp.renameTo(raw)) throw IOException("could not save")
                writeCompiled(list, fresh, raw.lastModified())
                fresh
            } finally {
                tmp.delete()
            }
        }
        result.onSuccess { fresh ->
            edit(list.id) {
                it.copy(name = if (it.stronger) it.name else fresh.title?.take(60) ?: it.name, rules = fresh.rules,
                    updated = System.currentTimeMillis(), error = null, expiresHours = fresh.expiresHours)
            }
        }.onFailure { e ->
            edit(list.id) { it.copy(error = e.message ?: e.javaClass.simpleName) }
        }
        return result.map { lists.first { l -> l.id == list.id } }
    }

    /** A plain GET without cookies, referrer or identifiers, saved gzipped; lists over [MAX_LIST_BYTES] are refused. */
    private fun download(url: String, to: File) {
        // Debug builds also take http://, so tests can serve a list from the development machine.
        require(url.startsWith("https://") || BuildConfig.DEBUG && url.startsWith("http://")) { "only https:// addresses" }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val body = if (url.endsWith(".gz")) GZIPInputStream(conn.inputStream) else conn.inputStream
            GZIPOutputStream(to.outputStream().buffered()).use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = body.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_LIST_BYTES) throw IOException("list too large")
                    out.write(buf, 0, n)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun writeCompiled(list: FilterList, compiled: CompiledList, stamp: Long) {
        dir.mkdirs()
        val tmp = File(dir, list.id + ".bin.tmp")
        DataOutputStream(tmp.outputStream().buffered(1 shl 16)).use { compiled.writeTo(it, stamp) }
        if (!tmp.renameTo(compiledFile(list))) tmp.delete()
    }

    private fun delete(list: FilterList) {
        rawFile(list).delete()
        compiledFile(list).delete()
    }

    private fun edit(id: String, change: (FilterList) -> FilterList) {
        lists = lists.map { if (it.id == id) change(it) else it }
        save()
    }

    private fun save() = store.edit().putString(KEY_LISTS, FilterList.listToJson(lists)).apply()

    private fun rawFile(list: FilterList) = File(dir, list.id + ".txt.gz")
    private fun compiledFile(list: FilterList) = File(dir, list.id + ".bin")

    companion object {
        const val BUNDLED_ASSET = "blocklist-hosts.txt.gz"
        const val POPUP_ASSET = "blocklist-popups.txt.gz"
        private const val KEY_LISTS = "lists"
        private const val KEY_LAST_CHECK = "last_auto_check"
        private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L
        /** Stamp of a cache carried over from Sofa 1.1: domains only, kept until the list is downloaded. */
        private const val MIGRATED = -1L
        /** Larger than any common list (EasyList is about 2 MB), small enough for any TV. */
        private const val MAX_LIST_BYTES = 30L shl 20

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
