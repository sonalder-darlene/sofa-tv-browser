package app.sofabrowser.adblock

import app.sofabrowser.adblock.NavigationGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationGuardTest {

    private val guard = NavigationGuard()

    @Test
    fun popUpToListedHostIsBlocked() {
        guard.onInput()
        assertEquals(Verdict.BLOCK, guard.newWindow(blocking = true, listed = true))
    }

    @Test
    fun onePressOpensAtMostOneWindow() {
        guard.onInput()
        assertEquals(Verdict.ALLOW, guard.newWindow(blocking = true, listed = false))
        assertEquals(Verdict.BLOCK, guard.newWindow(blocking = true, listed = false))
        guard.onInput()
        assertEquals(Verdict.ALLOW, guard.newWindow(blocking = true, listed = false))
    }

    @Test
    fun aBlockedWindowStillUsesUpThePress() {
        guard.onInput()
        assertEquals(Verdict.BLOCK, guard.newWindow(blocking = true, listed = true))
        assertEquals(Verdict.BLOCK, guard.newWindow(blocking = true, listed = false))
    }

    // Navigation flags below are what WebView 113 reports for each case:
    // a server redirect never carries a gesture, even when the user pressed the link that started it.

    @Test
    fun pageNavigationToListedHostWithoutPressIsBlocked() {
        assertEquals(Verdict.BLOCK, guard.navigation(gesture = false, redirect = false, listed = true, blocking = true))
    }

    @Test
    fun pressedLinkPassesThroughListedRedirectHops() {
        assertEquals(Verdict.ALLOW, guard.navigation(gesture = true, redirect = false, listed = false, blocking = true))
        assertEquals(Verdict.ALLOW, guard.navigation(gesture = false, redirect = true, listed = true, blocking = true))
    }

    @Test
    fun pageStartedChainIsBlockedAtItsFirstListedHop() {
        assertEquals(Verdict.ALLOW, guard.navigation(gesture = false, redirect = false, listed = false, blocking = true))
        assertEquals(Verdict.BLOCK, guard.navigation(gesture = false, redirect = true, listed = true, blocking = true))
    }

    @Test
    fun sofasOwnLoadsStartAUserChain() {
        guard.navigation(gesture = false, redirect = false, listed = false, blocking = true)
        guard.userNavigation()
        assertEquals(Verdict.ALLOW, guard.navigation(gesture = false, redirect = true, listed = true, blocking = true))
    }

    @Test
    fun aNewPageNavigationEndsTheUserChain() {
        guard.userNavigation()
        assertEquals(Verdict.BLOCK, guard.navigation(gesture = false, redirect = false, listed = true, blocking = true))
    }

    @Test
    fun siteSwitchOffAllowsRedirects() {
        assertEquals(Verdict.ALLOW, guard.navigation(gesture = false, redirect = false, listed = true, blocking = false))
    }

    @Test
    fun aBlockedAddressCanBeAllowedOnceWithinThirtySeconds() {
        guard.blocked("https://ads.test/x", now = 1_000)
        assertEquals("https://ads.test/x", guard.pending(now = 30_999))
        assertEquals("https://ads.test/x", guard.takePending(now = 31_000))
        assertNull(guard.pending(now = 31_000))
    }

    @Test
    fun allowOnceExpires() {
        guard.blocked("https://ads.test/x", now = 1_000)
        assertNull(guard.pending(now = 31_001))
        assertNull(guard.takePending(now = 31_001))
    }

    @Test
    fun allowOnceOffersTheLatestBlockAndEndsWithTheNextPage() {
        guard.blocked("https://ads.test/1", now = 0)
        guard.blocked("https://ads.test/2", now = 10)
        assertEquals("https://ads.test/2", guard.pending(now = 20))
        guard.pageStarted()
        assertNull(guard.pending(now = 20))
    }

    @Test
    fun siteSwitchOffAllowsEveryWindow() {
        guard.onInput()
        assertEquals(Verdict.ALLOW, guard.newWindow(blocking = false, listed = true))
        assertEquals(Verdict.ALLOW, guard.newWindow(blocking = false, listed = true))
    }
}
