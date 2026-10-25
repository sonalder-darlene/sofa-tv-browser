package app.sofabrowser.browser

/**
 * Remembers the video streams a page fetched, so Sofa Player and "Play in…" have an address when the video element
 * only shows a blob: one (players assemble HLS and DASH in the page). Called from WebView's network threads. Plain
 * Kotlin, so the rules are unit-tested.
 *
 * A page's player fetches the master playlist first, then the renditions it picked: video without sound, sound
 * without video, subtitles. So every manifest is kept, newest first, and [StreamResolver] reads them to find the
 * master; the newest alone is usually a rendition (seen on Dailymotion: the audio playlist came last). Players that
 * switch quality often (hls.js) fetch a variant playlist at each switch, so the page's first manifests are kept too.
 */
class StreamSniffer {

    /** A fetched stream, with the frame that fetched it as the request said: its Referer and Origin. */
    data class Stream(val url: String, val manifest: Boolean, val referer: String?, val origin: String?)

    private val manifests = ArrayDeque<Stream>()
    /** The page's first manifests, kept however many come after: usually the master. */
    private val earliest = ArrayList<Stream>()
    private var file: Stream? = null

    /**
     * Every subresource request the page makes; [listed] is true for blocklisted hosts, whose videos are ads.
     * [headers] are the request's own, as WebView reports them.
     */
    @Synchronized
    fun request(url: String, listed: Boolean, headers: Map<String, String> = emptyMap()) {
        if (listed || !(url.startsWith("https://") || url.startsWith("http://"))) return
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }
        val manifest = when (extensionOf(url)) {
            "m3u8", "mpd" -> true
            "mp4", "m4v", "webm", "mkv", "mov" -> false
            else -> return
        }
        val stream = Stream(url, manifest, header("Referer"), header("Origin"))
        if (!manifest) { file = stream; return }
        if (earliest.size < EARLIEST && earliest.none { it.url == url }) earliest += stream
        // A live playlist is fetched again and again; it moves up instead of filling the list.
        manifests.removeAll { it.url == url }
        manifests.addLast(stream)
        while (manifests.size > MAX_MANIFESTS) manifests.removeFirst()
    }

    /**
     * Manifests, newest first, then the page's first ones, then the newest single file: what to try, in order. The
     * first ones come last however often they were fetched again, so [StreamResolver] always reaches them. Empty when
     * the page fetched none.
     */
    @Synchronized
    fun candidates(): List<Stream> {
        val newest = manifests.reversed()
        val first = earliest.map { it.url }.toSet()
        val (firstKept, others) = newest.partition { it.url in first }
        val pushedOut = earliest.filter { e -> firstKept.none { it.url == e.url } }.reversed()
        return others + firstKept + pushedOut + listOfNotNull(file)
    }

    /** A new page: what the old one fetched no longer applies. */
    @Synchronized
    fun clear() {
        manifests.clear()
        earliest.clear()
        file = null
    }

    private companion object {
        const val MAX_MANIFESTS = 12
        const val EARLIEST = 3
    }
}
