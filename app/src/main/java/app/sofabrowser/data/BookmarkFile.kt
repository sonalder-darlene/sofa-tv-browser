package app.sofabrowser.data

/**
 * The bookmarks file every desktop browser imports and exports (the "Netscape bookmark file": an HTML page of
 * `<A HREF>` links in nested `<DL>` folders). Sofa keeps bookmarks in one flat list, so reading takes every web link
 * from every folder in file order, and writing gives one folder-less list that browsers import as it is.
 */
object BookmarkFile {

    /** A bookmark as the file has it; [added] is milliseconds since 1970, or null when the file gives no date. */
    data class Entry(val title: String, val url: String, val added: Long? = null)

    /** What a file held: the web links in file order (first copy of each address only), and how many were skipped. */
    data class Contents(val entries: List<Entry>, val skipped: Int)

    /** Larger files are refused before they are read; a real bookmarks file is far smaller. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** At most this many bookmarks are taken from one file. */
    const val MAX_ENTRIES = 20_000

    private const val MAX_TITLE = 500
    /** A link and its title, which ends at `</A>`, or at the next link or list line when a file forgets to close it. */
    private val anchor = Regex("""<a\s([^>]*)>(.*?)(?:</a\s*>|(?=<a\s|<dt|</?dl))""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attributes = HashMap<String, Regex>()
    private val tag = Regex("<[^>]*>")
    private val space = Regex("\\s+")
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    /**
     * The bookmarks in [html]. Links that aren't web addresses (javascript:, place:, file:, a browser's own pages) are
     * counted in [Contents.skipped] and so are copies of an address already taken; [now] caps dates in the future.
     */
    fun read(html: String, now: Long = System.currentTimeMillis()): Contents {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        var skipped = 0
        for (m in anchor.findAll(html)) {
            val attrs = m.groupValues[1]
            val url = attribute(attrs, "href")?.let(::decode)?.trim()
            if (url == null || !isWeb(url) || !seen.add(url)) { skipped++; continue }
            if (entries.size >= MAX_ENTRIES) { skipped++; continue }
            val title = decode(m.groupValues[2].replace(tag, "")).replace(space, " ").trim().take(MAX_TITLE)
            entries += Entry(title.ifEmpty { url }, url, attribute(attrs, "add_date")?.let { date(it, now) })
        }
        return Contents(entries, skipped)
    }

    /** [entries] as a bookmarks file that Chrome, Firefox, Edge, Safari and Sofa read. */
    fun write(entries: List<Entry>): String = buildString {
        append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
        append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
        append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
        append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n")
        for (e in entries) {
            append("    <DT><A HREF=\"").append(escape(e.url)).append('"')
            e.added?.let { append(" ADD_DATE=\"").append(it / 1000).append('"') }
            append('>').append(escape(e.title)).append("</A>\n")
        }
        append("</DL><p>\n")
    }

    fun isWeb(url: String) = url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** The value of attribute [name] in a tag's attribute text: double, single or no quotes. */
    internal fun attribute(attrs: String, name: String): String? {
        val pattern = synchronized(attributes) {
            attributes.getOrPut(name) { Regex("""(?:^|\s)$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""", RegexOption.IGNORE_CASE) }
        }
        val m = pattern.find(attrs) ?: return null
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
    }

    /** ADD_DATE is in seconds; a few tools write milliseconds or microseconds, told apart by size. */
    internal fun date(value: String, now: Long): Long? {
        val n = value.trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
        val ms = when {
            n >= 100_000_000_000_000L -> n / 1000 // microseconds
            n >= 100_000_000_000L -> n            // milliseconds
            else -> n * 1000                       // seconds
        }
        return ms.coerceAtMost(now)
    }

    internal fun decode(text: String): String = if ('&' !in text) text else entity.replace(text) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let(::codePoint)
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let(::codePoint)
            else -> NAMED[e.lowercase()]
        } ?: m.value
    }

    private fun codePoint(c: Int): String? = if (Character.isValidCodePoint(c) && c != 0) String(Character.toChars(c)) else null

    /** Browsers write only the first five; the rest turn up in files made by hand. */
    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "\u2013",
        "mdash" to "\u2014", "hellip" to "\u2026", "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201c",
        "rdquo" to "\u201d", "laquo" to "\u00ab", "raquo" to "\u00bb", "copy" to "\u00a9", "reg" to "\u00ae", "trade" to "\u2122",
    )

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
