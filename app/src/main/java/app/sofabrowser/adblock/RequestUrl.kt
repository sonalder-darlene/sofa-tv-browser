package app.sofabrowser.adblock

/**
 * The parts of an address that filter rules look at, read without android.net.Uri so the filter engine runs in
 * plain unit tests. [full] is the address as WebView reports it, still percent-encoded, as filter lists expect.
 */
class RequestUrl(val full: String) {

    /** Everything after "scheme:", such as "//ads.example.com/a.js?x=1". */
    val ssp: String = full.substring(full.indexOf(':') + 1)

    /** The host in lower case, without user, port or IPv6 brackets; null for addresses without one (data:, about:). */
    val host: String? = hostOf(full)

    /** The same address in lower case, for rules that ignore case (most of them). */
    val lowercase: RequestUrl by lazy(LazyThreadSafetyMode.NONE) {
        val l = full.lowercase()
        if (l == full) this else RequestUrl(l)
    }

    override fun toString() = full

    companion object {
        fun hostOf(url: String): String? {
            val colon = url.indexOf(':')
            if (colon <= 0 || !url.startsWith("//", colon + 1)) return null
            val start = colon + 3
            var end = url.length
            for (i in start until url.length) {
                val c = url[i]
                if (c == '/' || c == '?' || c == '#' || c == '\\') { end = i; break }
            }
            var authority = url.substring(start, end)
            authority = authority.substring(authority.lastIndexOf('@') + 1)
            val host = if (authority.startsWith('[')) {
                authority.substring(1, authority.indexOf(']').takeIf { it > 0 } ?: authority.length)
            } else {
                authority.substringBefore(':')
            }
            return host.lowercase().trimEnd('.').ifEmpty { null }
        }
    }
}
