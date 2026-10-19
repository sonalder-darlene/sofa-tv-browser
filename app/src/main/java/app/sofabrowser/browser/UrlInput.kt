package app.sofabrowser.browser

import android.net.Uri
import android.util.Patterns
import app.sofabrowser.SearchEngine

/** Turns what the user typed into a URL to load: an address as-is, a bare domain over HTTPS, or a search. */
object UrlInput {

    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOST_PORT = Regex("^[^\\s/]+:\\d+(/.*)?$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")

    fun resolve(text: String, engine: SearchEngine): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (' ' !in t) {
            if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
            if (t.startsWith("about:", true) || t.startsWith("view-source:", true)) return t
            if (HOST_PORT.matches(t) || t.startsWith("localhost", true)) return "http://$t"
            if (!SCHEME.containsMatchIn(t) || t.substringBefore(':').contains('.')) {
                val host = t.substringBefore('/').substringBefore('?')
                if (IPV4.matches(host)) return "http://$t"
                if (Patterns.DOMAIN_NAME.matcher(host).matches() && '.' in host && !host.endsWith('.')) return "https://$t"
            }
        }
        return engine.searchUrl(t)
    }

    /** A short form of [url] for display: no scheme, no "www.", no trailing slash. */
    fun display(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val u = Uri.parse(url)
        if (u.scheme != "http" && u.scheme != "https") return url
        return url.substringAfter("://").removePrefix("www.").removeSuffix("/")
    }
}
