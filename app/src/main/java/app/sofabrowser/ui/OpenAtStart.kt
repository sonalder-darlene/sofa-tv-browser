package app.sofabrowser.ui

/**
 * Settings > Open at start: what Sofa shows when it starts and no other app sent it a link. Home in the menu always
 * opens the start page, whatever is chosen here, so the start page can't be lost.
 */
object OpenAtStart {

    /** [id] is stored in Settings (`open_at_start`) and written by a settings export: it never changes. */
    enum class Choice(val id: String) { START_PAGE("home"), WEB_PAGE("page"), LAST_PAGE("last") }

    fun choice(id: String?): Choice = Choice.entries.firstOrNull { it.id == id } ?: Choice.START_PAGE

    /**
     * The address to open at start, or null for the start page. [restored]: Android rebuilt the screen (a theme
     * change, or Sofa brought back after the TV closed it in the background). Then only "Where I left off" applies,
     * since the chosen web page would replace the page that was in sight.
     */
    fun address(choice: Choice, webPage: String, lastPage: String, restored: Boolean): String? = when (choice) {
        Choice.START_PAGE -> null
        Choice.WEB_PAGE -> if (restored) null else webAddress(webPage)
        Choice.LAST_PAGE -> webAddress(lastPage)
    }

    /**
     * What "Where I left off" keeps when Sofa leaves the screen: the page in sight ([pageInSight], null while the
     * start page shows). Nothing when another choice is made, in private browsing, or with "Clear data on exit".
     */
    fun toRemember(choice: Choice, pageInSight: String?, private: Boolean, clearOnExit: Boolean): String =
        if (choice != Choice.LAST_PAGE || private || clearOnExit) "" else pageInSight?.let(::webAddress).orEmpty()

    /**
     * A typed address as Sofa keeps it: "example.com" and "example.com/tv" mean https, as does a full address typed
     * after the "https://" the field starts with. Anything that isn't a web page (file:, javascript:, data:, about:,
     * no site at all, spaces) gives null.
     */
    fun webAddress(typed: String): String? {
        var t = typed.trim()
        if (t.startsWith("https://", ignoreCase = true) && "://" in t.substring(8)) t = t.substring(8)
        if (t.isEmpty() || t.any(Char::isWhitespace)) return null
        if ("://" !in t) {
            // "javascript:…" or "about:blank" name a scheme; "localhost:8080" names a port.
            if (SCHEME_ONLY.containsMatchIn(t)) return null
            t = "https://$t"
        }
        val m = WEB.matchEntire(t) ?: return null
        if (!HOST_PORT.matches(m.groupValues[2].substringAfterLast('@'))) return null
        return m.groupValues[1].lowercase() + "://" + m.groupValues[2] + m.groupValues[3]
    }

    /** How Settings names the page: its host, with "http://" in front when the page isn't https. */
    fun label(url: String): String {
        val m = WEB.matchEntire(url) ?: return url
        val host = m.groupValues[2].substringAfterLast('@').removePrefix("www.")
        return if (m.groupValues[1].equals("http", ignoreCase = true)) "http://$host" else host
    }

    private val SCHEME_ONLY = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?![0-9])")
    private val WEB = Regex("^(?i)(https?)://([^/?#]+)(.*)$")
    /** A name (any script, for IDN) or an IPv4 or [IPv6] address, then an optional port in digits. */
    private val HOST_PORT = Regex("^(?:[^\\s.:@\\[\\]<>\"\\\\{}|^`%][^\\s:@\\[\\]<>\"\\\\{}|^`%]*|\\[[0-9a-fA-F:.]+])(?::[0-9]{1,5})?$")
}
