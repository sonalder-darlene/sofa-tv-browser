package app.sofabrowser.browser

/**
 * What the menu of a held link or image offers, from WebView's hit test. Plain Kotlin, so the rules are unit-tested.
 *
 * Only addresses another app or Sofa can use make it in: no `javascript:` links, and pictures held in the page (data:
 * and blob: addresses) can be saved but not opened, copied or shared, since those addresses mean nothing elsewhere.
 */
object LinkMenu {

    enum class Action { OPEN_ELSEWHERE, COPY_LINK, SHARE_LINK, DOWNLOAD_LINK, OPEN_IMAGE, DOWNLOAD_IMAGE, COPY_IMAGE, SHARE_IMAGE }

    /** The menu for a hit test that found [link] (its address) and/or [image] (its source); empty when there is none. */
    fun actions(link: String?, image: String?): List<Action> = buildList {
        val l = link?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("javascript:", ignoreCase = true) }
        val i = image?.trim()?.takeIf { it.isNotEmpty() }
        if (l != null) {
            if (inPage(l)) add(Action.DOWNLOAD_LINK)
            else {
                addAll(listOf(Action.OPEN_ELSEWHERE, Action.COPY_LINK, Action.SHARE_LINK))
                if (web(l) && looksLikeFile(l)) add(Action.DOWNLOAD_LINK)
            }
        }
        if (i != null) {
            if (inPage(i)) add(Action.DOWNLOAD_IMAGE)
            else if (web(i)) addAll(listOf(Action.OPEN_IMAGE, Action.DOWNLOAD_IMAGE, Action.COPY_IMAGE, Action.SHARE_IMAGE))
        }
    }

    /** The menu's title: the link's address, else the image's, shortened for one line. */
    fun title(link: String?, image: String?, max: Int = 80): String {
        val a = link?.takeIf { it.isNotBlank() && !inPage(it) } ?: image?.takeIf { it.isNotBlank() && !inPage(it) } ?: return ""
        val shown = a.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        return if (shown.length <= max) shown else shown.take(max - 1) + "…"
    }

    /**
     * Whether [url] names a file (an extension that isn't a web page's), so "Download link" saves something useful
     * rather than a page as "page.bin". Links to files sites serve without a name still download when pressed.
     */
    fun looksLikeFile(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').substringAfter("://").substringAfter('/', "")
        val ext = path.substringAfterLast('/').substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() } && ext !in PAGES
    }

    private val PAGES = setOf("htm", "html", "xhtml", "shtml", "php", "asp", "aspx", "jsp", "cgi", "pl")

    private fun web(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private fun inPage(url: String) = url.startsWith("data:", ignoreCase = true) || url.startsWith("blob:", ignoreCase = true)
}
