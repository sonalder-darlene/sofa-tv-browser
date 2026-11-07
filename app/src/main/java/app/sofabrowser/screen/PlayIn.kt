package app.sofabrowser.screen

import android.os.SystemClock
import android.view.View
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.Decoders
import app.sofabrowser.browser.ExternalPlayer
import app.sofabrowser.browser.PageHeaders
import app.sofabrowser.browser.PlayerRequest
import app.sofabrowser.browser.Playlist
import app.sofabrowser.browser.StreamCopyProvider
import app.sofabrowser.browser.StreamResolver
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.VideoSource
import app.sofabrowser.browser.WebViews
import app.sofabrowser.ui.SettingsActivity
import java.util.Locale
import kotlin.concurrent.thread

/** Asks every frame of the page for its main video, for "Play in…" and Sofa Player. */
class VideoSources(private val s: Screen) {
    private val sources = ArrayList<VideoSource>()
    private var wanted: ((VideoSource?) -> Unit)? = null

    /** Asks every frame for its main video, then gives [then] the largest, or null; false when it can't ask now. */
    fun collect(then: (VideoSource?) -> Unit): Boolean {
        if (s.web == null || wanted != null) return false
        sources.clear()
        wanted = then
        VideoRelay.send(s.web, "source")
        s.cursor.postDelayed({
            val done = wanted ?: return@postDelayed
            wanted = null
            done(sources.maxByOrNull { it.area })
        }, 300)
        return true
    }

    fun onSource(source: VideoSource) {
        if (wanted != null) sources.add(source)
    }
}

/** Play in…: ask every frame for its main video, hand the largest to the chosen player, pause it here. */
class PlayIn(private val s: Screen, private val video: VideoSession) {

    private var choosePlayer = false

    /**
     * When the press of "Play in…" still being worked on began, or 0. It lasts until Sofa is back in front, or a
     * refusal is shown: finding the stream can take seconds, and the player or chooser takes a moment to open.
     */
    private var since = 0L

    /** [choose]: show Android's chooser even when a player was remembered (a long press on the button). */
    fun press(choose: Boolean) {
        // A second press before the first one's player is up would open the player (or the chooser) twice.
        val now = SystemClock.uptimeMillis()
        if (since != 0L && now - since < PLAY_IN_BUSY_MS) return
        since = now
        choosePlayer = choose
        if (!video.sources.collect(::chosen)) since = 0L
    }

    /** Sofa is in front again: the press is over. */
    fun done() {
        since = 0L
    }

    /** Hands [found] to the player remembered for "Play in…" ("Always play in another app"), without a press. */
    fun handOver(found: VideoSource?) {
        choosePlayer = false
        chosen(found)
    }

    private fun chosen(found: VideoSource?) {
        val w = s.web ?: run { since = 0L; return }
        // From Sofa Player, the other player starts where Sofa Player was.
        val takeover = video.takeover
        val source = if (takeover.on) found?.copy(position = takeover.positionSeconds) else found
        val userAgent = w.settings.userAgentString.orEmpty()
        val cookies = WebViews.cookieManager(w)
        val direct = source?.src?.takeIf(Browser::isWebUrl)
        val browser = s.browser
        val candidates = if (direct == null && source != null && !source.protected) browser.streams.candidates() else emptyList()
        // The page fetches its manifests again first; reading the rest takes the network: off the main thread, then back.
        browser.fetchInPage(StreamResolver.manifestsToRead(candidates), PAGE_FETCH_MS) { fromPage ->
            thread(name = "play-in") {
                val blocker = browser.adBlocker
                val resolver = StreamResolver(userAgent, cookies::getCookie, ::streamLog) { blocker.siteListed(it) != null }
                val resolved = if (candidates.isEmpty()) null else resolver.resolve(candidates, fromPage, checkOutside = true)
                // Where the stream leads beyond the addresses Sofa holds (redirects, variant playlists): only with a
                // Site filters list on, which is the only reason to look, so other presses cost no extra request.
                val further = if (blocker.sitesOn && source?.protected != true) resolver.blockedAlong(direct, source?.page, resolved) else null
                s.activity.runOnUiThread { if (!s.activity.isDestroyed) open(source, resolved, userAgent, cookies::getCookie, further) }
            }
        }
    }

    /** Why "Play in…" opened nothing: over the video when it is fullscreen, where a toast goes unseen. */
    private fun message(res: Int) = message(s.activity.getString(res))

    private fun message(text: String) {
        since = 0L
        streamLog("Play in: $text")
        if (s.fullscreen.visibility == View.VISIBLE) s.fullscreen.notice(text) else s.toast(text)
    }

    /** [further]: an address beyond the stream's own that Site filters block ([StreamResolver.blockedAlong]), or null. */
    private fun open(video: VideoSource?, resolved: StreamResolver.Resolved?, userAgent: String, cookie: (String) -> String?, further: String?) {
        val activity = s.activity
        val stream = video?.src?.takeIf(Browser::isWebUrl) ?: resolved?.url
        // The other app fetches on its own, past Site filters: nothing that leads to a blocked site goes to it (the
        // stream, its master's addresses, its subtitles, and [further]: redirects, variant playlists' segments).
        val wanted = listOfNotNull(stream) + resolved?.let(::streamAddresses).orEmpty() + video?.subtitles.orEmpty().map { it.url }
        s.browser.adBlocker.siteListedIn(wanted + listOfNotNull(further))?.let { (url, list) ->
            message(activity.getString(R.string.video_site_blocked, Browser.hostOf(url) ?: url, SettingsActivity.siteListName(activity, list)))
            return
        }
        // What the page's player sent for this stream (PageHeaders), for players that read headers. Only on this press.
        val headers = stream?.let {
            if (resolved == null) PageHeaders.forRequest(it, video?.page, userAgent, cookie, media = true)
            else PageHeaders.forRequest(it, resolved.frame, userAgent, cookie)
        }.orEmpty()
        val request = PlayerRequest.build(video?.src, s.web?.title, video?.position ?: 0.0, video?.protected == true, headers,
            resolved?.url, video?.subtitles.orEmpty(), Locale.getDefault().language)
        var launch = when (request) {
            PlayerRequest.Refusal.NO_VIDEO -> { message(R.string.no_video); return }
            PlayerRequest.Refusal.IN_PAGE -> { message(R.string.handover_in_page); return }
            PlayerRequest.Refusal.PROTECTED -> { message(R.string.handover_drm); return }
            is PlayerRequest.Launch -> request
        }
        // A master playlist the site gives only to its own page: hand over Sofa's copy, whose addresses point at the
        // site, so players that send their own headers (VLC, Just Player) can still open it. It is typed HLS for
        // players that go by the intent's type (PlayerRequest.types has the order), and leaves out the pictures the TV
        // can't decode in hardware (AV1 on many TVs), which a player might pick and play too slowly to show.
        if (resolved?.needsPage == true && resolved.playlist != null) {
            val copy = Playlist.decodableOnly(resolved.playlist, Decoders::hardware)
            if (BuildConfig.DEBUG) {
                fun codecs(p: String) = Regex("""#EXT-X-STREAM-INF:.*CODECS="([^"]*)"""").findAll(p).map { it.groupValues[1] }.toList()
                streamLog("copy for another player keeps ${codecs(copy)} of ${codecs(resolved.playlist)}")
            }
            launch = launch.copy(uri = StreamCopyProvider.put(activity, copy).toString(), mime = PlayerRequest.HLS, siteUri = launch.uri)
        }
        // The player picked last time, unless the user asked to choose (again, or every time) or it was uninstalled since.
        val prefs = s.prefs
        val remembered = prefs.videoPlayer.takeUnless { choosePlayer || it.isEmpty() || it == Prefs.ASK_EVERY_TIME }
        val player = remembered?.let { ExternalPlayer.installed(activity, it) ?: run {
            prefs.videoPlayer = ""
            message(R.string.player_gone)
            null
        } }
        if (ExternalPlayer.open(activity, launch, player, activity.getString(R.string.play_in))) this.video.command("pause")
        else message(R.string.no_player)
    }

    companion object {
        /** How long the page may take to fetch its manifests again. */
        const val PAGE_FETCH_MS = 2500L
        /** Longest a press of "Play in…" keeps a second one out, should its stream search never answer (ms). */
        private const val PLAY_IN_BUSY_MS = 10_000L

        /** The stream's address and, for HLS, every address its playlist lists: what Site filters must let through. */
        fun streamAddresses(resolved: StreamResolver.Resolved): List<String> =
            listOf(resolved.url) + resolved.playlist?.let(Playlist::addresses).orEmpty()
    }
}
