package app.sofabrowser.browser

import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds the stream to play among those the page fetched ([StreamSniffer.candidates]): it reads the manifests,
 * newest first, and keeps the first master playlist. Each read is a request the page's player made already. The
 * page's own copies come first ([Browser.fetchInPage]): some sites turn away any client but the browser (Dailymotion
 * refuses Android's own HTTP stack even with the same headers); others are fetched here as the page's player asked
 * ([PageHeaders]). Blocking: call it off the main thread.
 */
class StreamResolver(private val userAgent: String, private val cookie: (String) -> String?, private val log: (String) -> Unit = {}) {

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
            val (finalUrl, body) = fromPage[s.url] ?: fetch(s.url, PageHeaders.forRequest(s.url, frameOf(s), userAgent, cookie)) ?: continue
            val kind = Playlist.kind(body)
            log("read $kind ${s.url}")
            read += s to kind
            bodies[s.url] = finalUrl to body
            if (kind == Playlist.Kind.MASTER) break
        }
        val chosen = choose(read) ?: return candidates.firstOrNull { !it.manifest }?.let { Resolved(it.url, Kind.FILE, frameOf(it)) }
        val (finalUrl, body) = bodies.getValue(chosen.url)
        val dash = body.trimStart().let { it.startsWith("<") }
        val needsPage = checkOutside && fetch(chosen.url, mapOf("User-Agent" to userAgent, "Accept" to "*/*")) == null
        return Resolved(chosen.url, if (dash) Kind.DASH else Kind.HLS, frameOf(chosen),
            if (dash) null else Playlist.absolute(body, finalUrl), needsPage)
    }

    /** The final address and the text, or null when the server refused or it isn't a short text. */
    private fun fetch(url: String, headers: Map<String, String>): Pair<String, String>? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.instanceFollowRedirects = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (c.responseCode !in 200..299) { log("refused ${c.responseCode} ${c.getHeaderField("X-Error-Code").orEmpty()} $url"); return@runCatching null }
            val bytes = c.inputStream.use { it.readNBytesCompat(MAX_BYTES) }
            c.url.toString() to String(bytes, Charsets.UTF_8)
        } finally {
            c.disconnect()
        }
    }.onFailure { log("failed $it $url") }.getOrNull()

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
