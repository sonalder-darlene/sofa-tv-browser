package app.sofabrowser.browser

import app.sofabrowser.adblock.SiteBlockedException
import app.sofabrowser.adblock.SiteFilteredHttp
import app.sofabrowser.player.Variants
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Finds the stream to play among those the page fetched ([StreamSniffer.candidates]): it reads the manifests,
 * newest first, and keeps the first master playlist. Each read is a request the page's player made already. The
 * page's own copies come first ([Browser.fetchInPage]): some sites turn away any client but the browser (Dailymotion
 * refuses Android's own HTTP stack even with the same headers); others are fetched here as the page's player asked
 * ([PageHeaders]). Blocking: call it off the main thread.
 */
/** [blocked]: addresses Site filters block, never requested, a redirect's included ([SiteFilteredHttp]). */
class StreamResolver(private val userAgent: String, private val cookie: (String) -> String?, private val log: (String) -> Unit = {},
                     private val blocked: (String) -> Boolean = { false }) {

    enum class Kind { HLS, DASH, FILE }

    /**
     * [frame] is the address of the frame that played it, as far as its requests tell; [playlist] the HLS master
     * with absolute addresses, when one was read. [needsPage] is true when the stream opens only with the page's
     * headers (checked on request), so players that send their own can't open it directly.
     */
    data class Resolved(val url: String, val kind: Kind, val frame: String?, val playlist: String? = null, val needsPage: Boolean = false)

    /** [fromPage] holds the page's own copies (final address and text) by address. */
    fun resolve(candidates: List<StreamSniffer.Stream>, fromPage: Map<String, Pair<String, String>>, checkOutside: Boolean): Resolved? {
        val read = ArrayList<Pair<StreamSniffer.Stream, Playlist.Kind>>()
        val bodies = HashMap<String, Pair<String, String>>()
        for (s in manifestsToRead(candidates)) {
            val (finalUrl, body) = fromPage[s.url] ?: fetch(s.url) { PageHeaders.forRequest(it, frameOf(s), userAgent, cookie) } ?: continue
            val kind = Playlist.kind(body)
            log("read $kind ${s.url}")
            read += s to kind
            bodies[s.url] = finalUrl to body
            if (kind == Playlist.Kind.MASTER) break
        }
        val chosen = choose(read) ?: return candidates.firstOrNull { !it.manifest }?.let { Resolved(it.url, Kind.FILE, frameOf(it)) }
        val (finalUrl, body) = bodies.getValue(chosen.url)
        val dash = body.trimStart().let { it.startsWith("<") }
        val needsPage = checkOutside && fetch(chosen.url) { mapOf("User-Agent" to userAgent, "Accept" to "*/*") } == null
        return Resolved(chosen.url, if (dash) Kind.DASH else Kind.HLS, frameOf(chosen),
            if (dash) null else Playlist.absolute(body, finalUrl), needsPage)
    }

    /**
     * [master] (absolute addresses, as [resolve] gives it) with VIDEO-RANGE filled in from each variant's init
     * segment when it declares none but offers twins ([Variants.twinsWithoutRange]); else unchanged. Reads each
     * variant's playlist and the start of its init segment, a few at once, with the requests' own headers.
     */
    fun withReadRanges(master: String, frame: String?): String {
        if (!Variants.twinsWithoutRange(master)) return master
        val pool = Executors.newFixedThreadPool(PROBES_AT_ONCE)
        try {
            val reads = Variants.addresses(master).distinct().associateWith { url -> pool.submit<Variants.Range> { rangeOf(url, frame) } }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_DEADLINE_MS)
            val ranges = reads.mapValues { (_, read) ->
                runCatching { read.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }.getOrDefault(Variants.Range.UNKNOWN)
            }
            log("ranges read from init segments: ${ranges.values.groupingBy { it }.eachCount()}")
            return Variants.withRanges(master, ranges)
        } finally {
            pool.shutdownNow()
        }
    }

    private fun rangeOf(url: String, frame: String?): Variants.Range {
        val (finalUrl, body) = fetch(url) { PageHeaders.forRequest(it, frame, userAgent, cookie) } ?: return Variants.Range.UNKNOWN
        val init = Playlist.initSegment(body, finalUrl) ?: return Variants.Range.UNKNOWN
        val length = minOf(init.length ?: INIT_BYTES.toLong(), INIT_BYTES.toLong())
        val range = "Range" to "bytes=${init.offset}-${init.offset + length - 1}"
        return fetchBytes(init.url, INIT_BYTES) { PageHeaders.forRequest(it, frame, userAgent, cookie) + range }?.let(Variants::rangeOfInit)
            ?: Variants.Range.UNKNOWN
    }

    /** At most [max] bytes from the start of the answer, or null when the server refused. */
    private fun fetchBytes(url: String, max: Int, headers: (String) -> Map<String, String>): ByteArray? = runCatching {
        val c = connect(url, headers)
        try {
            if (c.responseCode !in 200..299) { log("refused ${c.responseCode} $url"); return@runCatching null }
            c.inputStream.use { it.readNBytesCompat(max) }
        } finally {
            c.disconnect()
        }
    }.onFailure { failed(it, url) }.getOrNull()

    /** The final address and the text, or null when the server refused or it isn't a short text. */
    private fun fetch(url: String, headers: (String) -> Map<String, String>): Pair<String, String>? = runCatching {
        val c = connect(url, headers)
        try {
            if (c.responseCode !in 200..299) { log("refused ${c.responseCode} ${c.getHeaderField("X-Error-Code").orEmpty()} $url"); return@runCatching null }
            val bytes = c.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
            c.url.toString() to String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }.onFailure { failed(it, url) }.getOrNull()

    /** [url] through its redirects, each hop checked by Site filters first and sent [headers] for its own address. */
    private fun connect(url: String, headers: (String) -> Map<String, String>) = SiteFilteredHttp.open(url, blocked) { c ->
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        headers(c.url.toString()).forEach { (k, v) -> c.setRequestProperty(k, v) }
    }

    private fun failed(e: Throwable, url: String) = log(if (e is SiteBlockedException) "site filters ${e.url}" else "failed $e $url")

    /**
     * For "Play in…", whose app fetches everything itself, past Site filters: the first address that [blocked] names
     * along the way, or null. For a file, where its redirects lead; for an HLS master, the playlists it lists and the
     * segments, keys and init segments those list (the master's own addresses are the caller's to check). What can't
     * be read in time counts as not blocked. DASH segments aren't followed (only Sofa Player checks those).
     */
    fun blockedAlong(direct: String?, page: String?, resolved: Resolved?): String? {
        if (direct != null) return blockedHop(direct, page, media = true)
        val master = resolved?.playlist?.takeIf { resolved.kind == Kind.HLS && Playlist.kind(it) == Playlist.Kind.MASTER } ?: return null
        val lists = Playlist.addresses(master).filter { it.startsWith("http") }.distinct().take(MAX_LISTS_CHECKED)
        if (lists.isEmpty()) return null
        val pool = Executors.newFixedThreadPool(PROBES_AT_ONCE)
        try {
            val reads = lists.map { url ->
                pool.submit<String?> {
                    val reached = reach(url, resolved.frame, media = false, wantBody = true) ?: return@submit null
                    reached.blockedAt ?: reached.body?.let { Playlist.addresses(Playlist.absolute(it, reached.url)).firstOrNull(blocked) }
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROBE_DEADLINE_MS)
            return reads.firstNotNullOfOrNull { read ->
                runCatching { read.get(maxOf(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }.getOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** The first of [url]'s redirects that [blocked] names, found without connecting to it; null when none or unread. */
    fun blockedHop(url: String, frame: String?, media: Boolean): String? = reach(url, frame, media, wantBody = false)?.blockedAt

    /**
     * Where [url]'s redirects end, each hop checked before it is requested and one byte asked of the last, so Sofa
     * Player can open that address itself: its HTTP source follows redirects inside one call, past any check.
     * Throws [SiteBlockedException] for the first hop [blocked] names, an IOException when the walk fails.
     */
    fun land(url: String, frame: String?, media: Boolean): String {
        val c = connect(url) { hopHeaders(it, frame, media, wantBody = false) }
        try {
            // A one-byte answer read to its end leaves the connection open for the player's own request.
            if (c.responseCode == HttpURLConnection.HTTP_PARTIAL && c.contentLengthLong in 0..1) c.inputStream.use { it.readNBytesCompat(1) }
            return c.url.toString()
        } finally {
            c.disconnect()
        }
    }

    /** [blockedAt]: the first hop Site filters block (nothing more is fetched); else [url] is the last hop. */
    private class Reached(val blockedAt: String?, val url: String, val body: String?)

    /**
     * Follows [url]'s redirects one by one, as players do (http and https both ways), checking each hop before it is
     * requested. [wantBody]: read the text (a playlist), else ask for one byte (a video file). Null when it failed.
     */
    private fun reach(url: String, frame: String?, media: Boolean, wantBody: Boolean): Reached? = try {
        val c = connect(url) { hopHeaders(it, frame, media, wantBody) }
        try {
            if (c.responseCode !in 200..299) null
            else Reached(null, c.url.toString(), if (wantBody) String(c.inputStream.use { it.readNBytesCompat(MAX_BYTES) }, Charsets.UTF_8) else null)
        } finally {
            c.disconnect()
        }
    } catch (e: SiteBlockedException) {
        log("site filters ${e.url}")
        Reached(e.url, e.url, null)
    } catch (e: Exception) {
        log("failed $e $url")
        null
    }

    private fun hopHeaders(at: String, frame: String?, media: Boolean, wantBody: Boolean) =
        PageHeaders.forRequest(at, frame, userAgent, cookie, media = media).let { if (wantBody) it else it + ("Range" to "bytes=0-0") }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024
        private const val TIMEOUT_MS = 5000
        /** An init segment's colr box sits in its first kilobyte or so (Vimeo's whole init is under 1 KB). */
        private const val INIT_BYTES = 4096
        private const val PROBES_AT_ONCE = 4
        private const val PROBE_DEADLINE_MS = 4000L
        /** Variant and rendition playlists read by [blockedAlong]: a master rarely lists more. */
        private const val MAX_LISTS_CHECKED = 24

        /**
         * The manifests worth reading, in order; ask the page for these. All that [StreamSniffer] keeps (15 at most):
         * newest first, so a new video's master wins, and the page's first ones last, where an old master pushed back
         * by many renditions still is. Reading stops at the first master.
         */
        fun manifestsToRead(candidates: List<StreamSniffer.Stream>) = candidates.filter { it.manifest }

        /** The frame a request came from: its Referer, else its Origin (a strict referrer policy sends none). */
        fun frameOf(s: StreamSniffer.Stream): String? = s.referer ?: s.origin?.let { "$it/" }

        /**
         * Of the manifests read (newest first), the newest master; without one, the newest rendition that isn't
         * subtitles only. Null when none was readable.
         */
        fun choose(read: List<Pair<StreamSniffer.Stream, Playlist.Kind>>): StreamSniffer.Stream? =
            read.firstOrNull { it.second == Playlist.Kind.MASTER }?.first
                ?: read.firstOrNull { it.second == Playlist.Kind.MEDIA }?.first
    }
}
