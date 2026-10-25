package app.sofabrowser.browser

import java.net.URI

/**
 * The headers the page's own player sends when it fetches a stream (hls.js, dash.js and the like use fetch or XHR,
 * without credentials), so Sofa Player and other players ask for it the same way and nothing more. Some sites check
 * them: Dailymotion refuses a manifest request without the browser's Sec-Fetch headers, and one with the embedding
 * site as Referer. Plain Kotlin, so the rules are unit-tested.
 */
object PageHeaders {

    /**
     * Headers for a request to [url] made by the frame showing [frameUrl], with the browser's [userAgent]. [cookie]
     * gives the cookies for an address; they go only to the frame's own origin, as a request without credentials
     * does. [media] is for a video element's own src: the browser sends no Origin then, and cookies within the site.
     */
    fun forRequest(url: String, frameUrl: String?, userAgent: String, cookie: (String) -> String? = { null },
                   media: Boolean = false): Map<String, String> {
        val target = origin(url)
        val frame = frameUrl?.let(::origin)
        val out = LinkedHashMap<String, String>()
        if (userAgent.isNotBlank()) out["User-Agent"] = userAgent
        out["Accept"] = "*/*"
        if (frame != null) {
            val sameOrigin = frame == target
            // The default referrer policy: the full address within the origin, the origin alone across origins,
            // nothing from https to http.
            when {
                sameOrigin -> out["Referer"] = frameUrl.substringBefore('#')
                !(frame.scheme == "https" && target?.scheme == "http") -> out["Referer"] = frame.serialized + "/"
            }
            if (!sameOrigin && !media) out["Origin"] = frame.serialized
            out["Sec-Fetch-Site"] = when {
                sameOrigin -> "same-origin"
                target != null && frame.scheme == target.scheme && sameSite(frame.host, target.host) -> "same-site"
                else -> "cross-site"
            }
            val withCookies = if (media) target != null && sameSite(frame.host, target.host) else sameOrigin
            if (withCookies) cookie(url)?.takeIf { it.isNotBlank() }?.let { out["Cookie"] = it }
        } else {
            out["Sec-Fetch-Site"] = "none"
        }
        out["Sec-Fetch-Mode"] = if (media) "no-cors" else "cors"
        out["Sec-Fetch-Dest"] = if (media) "video" else "empty"
        return out
    }

    data class Origin(val scheme: String, val host: String, val port: Int) {
        val serialized get() = "$scheme://$host" + if (port == -1) "" else ":$port"
    }

    fun origin(url: String): Origin? = runCatching {
        val u = URI(url)
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = u.port.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) } ?: -1
        Origin(scheme, host, port)
    }.getOrNull()

    /**
     * The frame a message came from, for the rules above: the address its script [claimed] when that is on the
     * [sender]'s origin (which WebView reports and pages can't forge), else the sender's origin alone. Null for an
     * opaque sender ("null", a sandboxed or data: frame). Keeps a hostile frame from posing as another site.
     */
    fun frameFor(claimed: String?, sender: String): String? {
        val own = origin(sender) ?: return null
        return claimed?.takeIf { origin(it) == own } ?: (own.serialized + "/")
    }

    /** Whether [a] and [b] are addresses on one origin; never for opaque ones. */
    fun sameOrigin(a: String?, b: String?): Boolean {
        val oa = a?.let(::origin) ?: return false
        return oa == b?.let(::origin)
    }

    /** Same registrable domain, by the same rule the pop-up blocker uses. */
    private fun sameSite(a: String, b: String) = app.sofabrowser.adblock.Sites.same(a, b)
}
