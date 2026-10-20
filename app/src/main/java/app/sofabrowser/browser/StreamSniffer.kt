package app.sofabrowser.browser

/**
 * Remembers the video streams a page fetched, so "Play in…" has an address when the video element only shows a
 * blob: one (players assemble HLS and DASH in the page). Called from WebView's network threads. Plain Kotlin, so
 * the rules are unit-tested.
 */
class StreamSniffer {
    private var manifest: String? = null
    private var file: String? = null

    /**
     * Every subresource request the page makes; [listed] is true for blocklisted hosts, whose videos are ads.
     * Manifests win over single files (a later file is more often a preview or an ad), and the newest of each wins.
     */
    @Synchronized
    fun request(url: String, listed: Boolean) {
        if (listed || !(url.startsWith("https://") || url.startsWith("http://"))) return
        when (extensionOf(url)) {
            "m3u8", "mpd" -> manifest = url
            "mp4", "m4v", "webm", "mkv", "mov" -> file = url
        }
    }

    /** The address to hand over, or null when the page fetched no stream. */
    @Synchronized
    fun best(): String? = manifest ?: file

    /** A new page: what the old one fetched no longer applies. */
    @Synchronized
    fun clear() {
        manifest = null
        file = null
    }
}
