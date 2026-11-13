package app.sofabrowser.data

/**
 * The bookmarks file every desktop browser imports and exports (the "Netscape bookmark file": an HTML page of
 * `<A HREF>` links in nested `<DL>` lists, each folder named by the `<H3>` before its list). Reading keeps each link's
 * folder as a path of names; writing gives the same tree back, which browsers and Sofa import as it is.
 */
object BookmarkFile {

    /**
     * A bookmark as the file has it; [added] is milliseconds since 1970, or null when the file gives no date. [folder]
     * is the path of folder names it sits in, from the top ("News", "Tech"); empty at the top.
     */
    data class Entry(val title: String, val url: String, val added: Long? = null, val folder: List<String> = emptyList())

    /** A folder to write even when nothing is in it: its path from the top, and when it was made. */
    data class Folder(val path: List<String>, val added: Long? = null)

    /** What a file held: the web links in file order (first copy of each address only), and how many were skipped. */
    data class Contents(val entries: List<Entry>, val skipped: Int)

    /** Larger files are refused before they are read; a real bookmarks file is far smaller. */
    const val MAX_BYTES = 8 * 1024 * 1024

    /** At most this many bookmarks are taken from one file. */
    const val MAX_ENTRIES = 20_000

    /** At most this many folders are taken from one file; what is in any after them goes to the folder above. */
    const val MAX_FOLDERS = 2_000

    private const val MAX_TITLE = 500
    /**
     * The parts of the file that matter, in order: a link and its title, which ends at `</A>` or at the next link,
     * folder or list line when a file forgets to close it (groups 1–2); a folder name, ending the same way (3–4); a
     * list opening (5) or closing (6).
     */
    private val token = Regex(
        """<a\s([^>]*)>(.*?)(?:</a\s*>|(?=<a\s|<dt|<h3|</?dl))""" +
            """|<h3(\s[^>]*)?>(.*?)(?:</h3\s*>|(?=<a\s|<dt|<h3|</?dl))""" +
            """|(<dl\b[^>]*>)|(</dl\s*>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attributes = HashMap<String, Regex>()
    private val tag = Regex("<[^>]*>")
    private val space = Regex("\\s+")
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")

    /**
     * The bookmarks in [html], each with its folder. Links that aren't web addresses (javascript:, place:, file:, a
     * browser's own pages) are counted in [Contents.skipped] and so are copies of an address already taken; [now] caps
     * dates in the future.
     *
     * The browser's bookmarks bar (the top folder marked `PERSONAL_TOOLBAR_FOLDER`) is opened up: what is in it goes to
     * the top, as it is what the browser showed first. Other top folders ("Other bookmarks", "Mobile bookmarks") stay.
     * A folder without a name is opened up into the one above it. A folder exists here only through the bookmarks in
     * it, so one left empty, or holding only skipped links, is not made.
     */
    fun read(html: String, now: Long = System.currentTimeMillis()): Contents {
        val entries = ArrayList<Entry>()
        val seen = HashSet<String>()
        var skipped = 0
        // The folder path, and for each open list whether it added a name to the path (the file's outer list doesn't).
        val path = ArrayList<String>()
        val named = ArrayList<Boolean>()
        // A folder's name waits for the list that follows it.
        var pending: String? = null
        val folders = HashSet<List<String>>()
        for (m in token.findAll(html)) {
            val g = m.groups
            when {
                g[1] != null -> {
                    pending = null
                    val attrs = m.groupValues[1]
                    val url = attribute(attrs, "href")?.let(::decode)?.trim()
                    if (url == null || !isWeb(url) || !seen.add(url)) { skipped++; continue }
                    if (entries.size >= MAX_ENTRIES) { skipped++; continue }
                    val title = text(m.groupValues[2]).take(MAX_TITLE)
                    entries += Entry(title.ifEmpty { url }, url, attribute(attrs, "add_date")?.let { date(it, now) }, path.toList())
                }
                g[4] != null -> {
                    val toolbar = path.isEmpty() && attribute(m.groupValues[3], "personal_toolbar_folder").equals("true", ignoreCase = true)
                    pending = if (toolbar) "" else FolderTree.cleanName(text(m.groupValues[4]))
                }
                g[5] != null -> {
                    val name = pending
                    pending = null
                    val opens = !name.isNullOrEmpty() && path.size < FolderTree.MAX_DEPTH &&
                        (path + name in folders || folders.size < MAX_FOLDERS)
                    if (opens) { path += name; folders += path.toList() }
                    named += opens
                }
                g[6] != null -> {
                    pending = null
                    if (named.isNotEmpty() && named.removeAt(named.size - 1)) path.removeAt(path.size - 1)
                }
            }
        }
        return Contents(entries, skipped)
    }

    /**
     * [entries] as a bookmarks file that Chrome, Firefox, Edge, Safari and Sofa read: each folder an `<H3>` followed by
     * its list, folders first (A to Z, as Sofa shows them) and then the folder's bookmarks in the order given.
     * [folders] adds the dates folders were made, and the folders with nothing in them.
     */
    fun write(entries: List<Entry>, folders: List<Folder> = emptyList()): String {
        class Node(val name: String) {
            var added: Long? = null
            val children = LinkedHashMap<String, Node>()
            val entries = ArrayList<Entry>()
        }
        val root = Node("")
        fun node(path: List<String>) = path.fold(root) { n, name -> n.children.getOrPut(name) { Node(name) } }
        folders.forEach { f -> if (f.path.isNotEmpty()) node(f.path).added = f.added }
        entries.forEach { e -> node(e.folder).entries += e }
        return buildString {
            append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n")
            append("<!-- This is an automatically generated file.\n     It will be read and overwritten.\n     DO NOT EDIT! -->\n")
            append("<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n")
            append("<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n")
            fun list(n: Node, indent: String) {
                append(indent).append("<DL><p>\n")
                val inner = "$indent    "
                for (c in n.children.values.sortedWith(compareBy(FolderTree.byName) { it.name })) {
                    append(inner).append("<DT><H3")
                    c.added?.let { append(" ADD_DATE=\"").append(it / 1000).append('"') }
                    append('>').append(escape(c.name)).append("</H3>\n")
                    list(c, inner)
                }
                for (e in n.entries) {
                    append(inner).append("<DT><A HREF=\"").append(escape(e.url)).append('"')
                    e.added?.let { append(" ADD_DATE=\"").append(it / 1000).append('"') }
                    append('>').append(escape(e.title)).append("</A>\n")
                }
                append(indent).append("</DL><p>\n")
            }
            list(root, "")
        }
    }

    /** Text between tags as one line: inner tags dropped, entities decoded, runs of white space made one space. */
    private fun text(html: String) = decode(html.replace(tag, "")).replace(space, " ").trim()

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
