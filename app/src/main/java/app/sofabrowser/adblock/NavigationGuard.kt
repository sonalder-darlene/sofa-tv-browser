package app.sofabrowser.adblock

/**
 * Pop-up and redirect blocking decisions. Plain Kotlin with no Android types, so the rules are unit-tested; the
 * browser feeds it what WebView reports and acts on the verdicts.
 */
class NavigationGuard {

    enum class Verdict { ALLOW, BLOCK }

    /** Counts the user's presses; WebView gives no identity to the gesture behind a window. */
    private var input = 0L
    private var inputWithWindow = -1L

    /** The user pressed something: a tap, a click or a key sent to the page. */
    fun onInput() {
        input++
    }

    /**
     * A page opened a window; [listed] says whether its first address is on the blocklist, [blocking] whether ad
     * blocking is on for the page that opened it. One press gets at most one window, blocked or not.
     */
    fun newWindow(blocking: Boolean, listed: Boolean): Verdict {
        if (!blocking) return Verdict.ALLOW
        val extra = inputWithWindow == input
        inputWithWindow = input
        return if (listed || extra) Verdict.BLOCK else Verdict.ALLOW
    }

    /** Whether the user started the main-frame navigation now under way, redirects included. */
    private var userChain = false

    /** Sofa itself is loading an address: typed, a bookmark, a tile, history or "Allow once". */
    fun userNavigation() {
        userChain = true
    }

    /**
     * A main-frame navigation or one of its redirect hops. WebView reports no gesture on server redirects, even for
     * a pressed link, so a hop belongs to the navigation that started it: one the user started passes every hop
     * (click-tracking redirects go through listed hosts); one the page started is blocked at a listed host.
     */
    fun navigation(gesture: Boolean, redirect: Boolean, listed: Boolean, blocking: Boolean): Verdict {
        if (!redirect) userChain = gesture
        return if (blocking && listed && !userChain) Verdict.BLOCK else Verdict.ALLOW
    }

    private var pendingUrl: String? = null
    private var pendingAt = 0L

    /** Remembers the latest blocked address, so the menu can offer to open it once. Times are in milliseconds. */
    fun blocked(url: String, now: Long) {
        pendingUrl = url
        pendingAt = now
    }

    /** The address "Allow once" would open, for [ALLOW_ONCE_MS] after it was blocked and until the next page. */
    fun pending(now: Long): String? = pendingUrl?.takeIf { now - pendingAt <= ALLOW_ONCE_MS }

    /** Like [pending], and forgets it: an address is allowed once. */
    fun takePending(now: Long): String? = pending(now).also { pendingUrl = null }

    fun pageStarted() {
        pendingUrl = null
    }

    companion object {
        const val ALLOW_ONCE_MS = 30_000L
    }
}
