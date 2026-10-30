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
    private var inputAskingWindow = -1L
    private var inputWithNavigation = -1L
    private var inputUnattended = -1L

    /** The user pressed something: a tap, a click or a key sent to the page. */
    fun onInput() {
        input++
    }

    /**
     * Sofa tapped the page on its own, with nobody pressing anything ("Watch by itself" starting a video). The page
     * sees a press, but it opens no window and the navigation it starts is not the user's.
     */
    fun onUnattendedInput() {
        input++
        inputUnattended = input
    }

    /** The page asked for a window (before its address is known), on the current press if [gesture]. */
    fun windowRequested(gesture: Boolean) {
        if (gesture) inputAskingWindow = input
    }

    /**
     * A page opened a window; [listed] says whether its first address is on a filter list, [blocking] whether ad
     * blocking is on for the page that opened it. [fromLink] is true when the press was on a link to that address, and
     * [sameSite] when the window stays on the opener's site. One press gets at most one window, blocked or not, and a
     * script may open another site's window only from a link to it: pop-unders open ad sites from a press anywhere
     * on the page, and with one view the ad would replace the page. A press that already sent the page somewhere (the
     * link under it) gets no window as well: pop-under scripts ride on presses on real links.
     */
    fun newWindow(blocking: Boolean, listed: Boolean, fromLink: Boolean = true, sameSite: Boolean = true): Verdict {
        if (!blocking) return Verdict.ALLOW
        val extra = inputWithWindow == input
        inputWithWindow = input
        val alsoNavigated = inputWithNavigation == input
        val unattended = inputUnattended == input
        return if (listed || extra || alsoNavigated || unattended || !fromLink && !sameSite) Verdict.BLOCK else Verdict.ALLOW
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
     *
     * A press counts as the user's unless it wasn't on a link ([fromLink]) and either leads to a listed host, or
     * leaves for another site ([crossSite]) on the same press that asked for a window: the "tab-under", where the
     * page opens itself in a window and sends the current view to an ad.
     */
    fun navigation(gesture: Boolean, redirect: Boolean, listed: Boolean, blocking: Boolean,
                   fromLink: Boolean = true, crossSite: Boolean = false): Verdict {
        if (!redirect) {
            inputWithNavigation = input
            val tabUnder = gesture && !fromLink && crossSite && inputAskingWindow == input
            userChain = gesture && inputUnattended != input && (fromLink || !listed) && !tabUnder
            if (blocking && tabUnder) return Verdict.BLOCK
        }
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
