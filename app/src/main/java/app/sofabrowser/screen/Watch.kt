package app.sofabrowser.screen

import android.os.SystemClock
import android.view.View
import app.sofabrowser.R
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.WatchTarget

/**
 * Watch: locate the main video, scroll it into view if needed, tap it for real, then ask for fullscreen. Also Watch by
 * itself, and "Open videos in Sofa Player" when the user starts a video on the page.
 */
class Watch(private val s: Screen) {

    private val candidates = ArrayList<WatchTarget.Candidate>()
    private var watching = false
    private var revealedOnce = false
    /** When Watch last tapped a video: the page reports that start too, and Watch is already on it. */
    private var watchTapAt = 0L
    private val locateNext = Runnable { locate() }
    private val chooseNext = Runnable { chosen() }

    fun start() {
        stop()
        if (s.web == null || watching) return
        s.menu.dismiss()
        watching = true
        revealedOnce = false
        locate()
    }

    private fun locate() {
        candidates.clear()
        VideoRelay.send(s.web, "locate")
        s.cursor.postDelayed(chooseNext, 400)
    }

    fun onCandidate(candidate: WatchTarget.Candidate) {
        if (watching) candidates.add(candidate)
    }

    private fun chosen() {
        if (!watching) return
        val auto = autoWatching
        val best = if (auto) WatchTarget.chooseAlone(s.prefs.autoWatch, candidates) else WatchTarget.choose(candidates)
        streamLog("${if (auto) "Watch by itself" else "Watch"}: ${candidates.size} videos, chosen $best")
        if (best == null) { watching = false; if (auto) autoWatchAgain() else s.toast(R.string.no_video); return }
        val cursor = s.cursor
        if (!best.tappable(cursor.width, cursor.height)) {
            // Found, but a layer the page shows over it takes every tap (the ones nobody can see are let through).
            if (revealedOnce) { watching = false; if (auto) autoWatchAgain() else s.toast(R.string.video_covered); return }
            revealedOnce = true
            VideoRelay.send(s.web, "reveal", frame = best.frame)
            cursor.postDelayed(locateNext, 700)
            return
        }
        watching = false
        autoWatching = false
        watchTapAt = SystemClock.uptimeMillis()
        if (auto) s.browser.unattended { cursor.tapAt(best.tapX.toFloat(), best.tapY.toFloat()) }
        else cursor.tapAt(best.tapX.toFloat(), best.tapY.toFloat())
        cursor.postDelayed({ VideoRelay.send(s.web, "go", frame = best.frame) }, 150)
    }

    // Watch by itself: once a page the setting covers has loaded, Watch runs on it once, without a word, and looks
    // again a few times while the page builds its player. Any key the user presses leaves the page to them.

    /** The page Watch by itself ran (or runs) on: once per page, so Back from its video doesn't start it again. */
    private var autoWatchUrl: String? = null
    private var autoWatchTries = 0
    /** The Watch that runs is Watch by itself's: no notices, and another look when nothing is found. */
    private var autoWatching = false
    private val autoWatchNext = Runnable { autoWatchTry() }

    /** Another page started loading: Watch by itself may run on it once it has loaded. */
    fun pageStarted(url: String) {
        if (url != autoWatchUrl) { stop(); autoWatchUrl = null }
    }

    fun pageFinished(url: String) {
        if (url == autoWatchUrl || !WatchTarget.autoWatches(s.prefs.autoWatch, Browser.hostOf(url), s.prefs.autoWatchSites)) return
        autoWatchUrl = url
        autoWatchTries = 0
        s.cursor.postDelayed(autoWatchNext, AUTO_WATCH_SETTLE_MS)
    }

    private fun autoWatchTry() {
        if (!s.started || s.homeShown || s.inPip || watching || s.menu.showing || s.fullscreen.visibility == View.VISIBLE) return
        if (s.web?.url != autoWatchUrl) return
        autoWatching = true
        watching = true
        revealedOnce = false
        locate()
    }

    /** No video to watch yet: the player may still be on its way. */
    private fun autoWatchAgain() {
        autoWatching = false
        if (++autoWatchTries < AUTO_WATCH_TRIES) s.cursor.postDelayed(autoWatchNext, AUTO_WATCH_AGAIN_MS)
        else streamLog("Watch by itself: no video to watch, page left alone")
    }

    /** The user is doing something else (a key, Watch, another page, another app): Watch by itself stops here. */
    fun stop() {
        val cursor = s.cursor
        cursor.removeCallbacks(autoWatchNext)
        if (!autoWatching) return
        autoWatching = false
        watching = false
        cursor.removeCallbacks(locateNext)
        cursor.removeCallbacks(chooseNext)
    }

    /**
     * "Open videos in Sofa Player": the user started a video on the page (with sound, a minute or more, the size of a
     * player). It goes fullscreen at once, while their press still counts, and the usual takeover follows.
     */
    fun onVideoStarted(frame: String) {
        val prefs = s.prefs
        if (!prefs.openInPlayer || !(prefs.sofaPlayer || prefs.forceExternalPlayer) || !s.started || s.homeShown || s.inPip) return
        if (watching || s.fullscreen.visibility == View.VISIBLE || SystemClock.uptimeMillis() - watchTapAt < WATCH_TAP_MS) return
        streamLog("Open in Sofa Player: video started in $frame")
        VideoRelay.send(s.web, "start", frame = frame)
    }

    private companion object {
        /** How long after Watch's tap a video's start counts as Watch's own (ms). */
        const val WATCH_TAP_MS = 2000L
        /** How long Watch by itself waits after a page has loaded, for its player to settle (ms). */
        const val AUTO_WATCH_SETTLE_MS = 1500L
        /** How many looks Watch by itself takes for a video, [AUTO_WATCH_AGAIN_MS] apart: about 10 s in all. */
        const val AUTO_WATCH_TRIES = 6
        const val AUTO_WATCH_AGAIN_MS = 1500L
    }
}
