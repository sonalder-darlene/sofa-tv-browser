package app.sofabrowser.browser

/**
 * A tab's way back from a site that a Site filters list blocks. The empty stand-in shown for a blocked site stays in
 * WebView's back list, and so may other blocked sites' stand-ins before it: going back one step can land on another
 * block screen, and the stand-in's address is no page worth remembering.
 */
object BlockedHistory {

    /**
     * Steps back from [current] (a negative number, for WebView.goBackOrForward) to the newest web page in [urls] that
     * [blocked] lets through; null when there is none.
     */
    fun back(urls: List<String?>, current: Int, blocked: (String) -> Boolean): Int? {
        for (i in minOf(current, urls.size) - 1 downTo 0) {
            val url = urls[i]
            if (Browser.isWebUrl(url) && !blocked(url!!)) return i - current
        }
        return null
    }

    /** The address to remember for the page at [current]: its own, or when that one is blocked, the one [back] finds. */
    fun kept(urls: List<String?>, current: Int, blocked: (String) -> Boolean): String? {
        val here = urls.getOrNull(current)
        if (here != null && !blocked(here)) return here
        return back(urls, current, blocked)?.let { urls[current + it] }
    }
}
