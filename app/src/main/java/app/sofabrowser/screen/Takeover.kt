package app.sofabrowser.screen

import android.os.SystemClock
import android.view.View
import app.sofabrowser.R
import app.sofabrowser.browser.Browser
import app.sofabrowser.browser.FrameRate
import app.sofabrowser.browser.StreamResolver
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.VideoSource
import app.sofabrowser.browser.VideoState
import app.sofabrowser.browser.WebViews
import app.sofabrowser.player.PlayerChoices
import app.sofabrowser.player.SofaPlayer
import kotlin.concurrent.thread

/**
 * Sofa Player: once the page's video plays fullscreen and its stream is known, Sofa Player gets ready unseen under it,
 * then takes over at the same position. It falls back to the page's player if it can't (SofaPlayer has the why).
 * Never for DRM, for files the page didn't give its video (they may be ads), or for short videos.
 */
class Takeover(private val s: Screen, private val video: VideoSession) {

    private val activity get() = s.activity
    private val fullscreen get() = s.fullscreen
    private val inFullscreen get() = fullscreen.visibility == View.VISIBLE

    val player: SofaPlayer by lazy { SofaPlayer(activity, events) }
    /** Sofa Player shows and plays, instead of the page's video. */
    var on = false
        private set
    /** Sofa Player shows and its video plays. */
    var playing = false
        private set
    val positionSeconds get() = player.positionSeconds

    /** Sofa Player was tried (or refused) for this fullscreen; a second try waits until [retryAt]. */
    private var tried = false
    private var retryAt = 0L
    /** Looks for a manifest in this fullscreen, 2 s apart; they start over with every fullscreen. */
    private var tries = 0
    private var declaredFps = 0f
    /** What Sofa Player was last started on, for its one more try at a hardware decoder ([hardwareRetried]). */
    private var started: Pair<SofaPlayer.Source, VideoSource>? = null
    private var hardwareRetried = false
    /** The page's video was paused by Sofa for that try, not by the user: Sofa Player takes over playing. */
    private var pausedForDecoder = false
    /** The page rests ([restPage]) so its video lets go of its decoder; [backToPage] wakes it. */
    private var pageRested = false
    /** Which picture didn't decode on this TV and what plays instead, for Video info; null when none failed. */
    private var steppedDown: String? = null
    /** Why Sofa Player doesn't play this fullscreen's video, for Video info; null while it does, or still may. */
    var notForPlayerWhy: String? = null
        private set
    /** Sites whose reason was shown over the video while Sofa runs: once each, then only in Video info. */
    private val notForPlayerShown = HashSet<String>()

    /** Sofa Player shows, or the page rests for it: the page is not to be woken. */
    val holdsPage get() = on || pageRested

    /** A video went fullscreen: Sofa Player may be tried for it. */
    fun newFullscreen() {
        playerRateMatched = false
        tried = false
        tries = 0
        notForPlayerWhy = null
        retryAt = 0L
        hardwareRetried = false
        pausedForDecoder = false
    }

    /** Fullscreen ends: the page's video carries on from where it was, also when Sofa paused it for a second try. */
    fun leaveFullscreen() {
        if (player.active || pausedForDecoder) backToPage(play = if (on) player.playing else pausedForDecoder)
    }

    /**
     * Out of sight (picture-in-picture keeps the activity started), Sofa Player lets go of its buffer and decoder: the
     * page's video holds the place, and Sofa Player may take over again once it plays.
     */
    fun stop() {
        if (!player.active && !pausedForDecoder) return
        backToPage(play = false)
        // backToPage woke the page, which onPause, or a closed picture-in-picture, had just put to rest.
        s.web?.onPause()
        tried = false
        tries = 0
    }

    fun release() = player.release()

    /** Private browsing ended or began: its picks in Sofa Player are forgotten. */
    fun forgetPrivateChoices() {
        privateChoices = null
    }

    /**
     * Watch stays with the site's player: says [why] over the video ([once] per site, so YouTube doesn't say it at every
     * video; a failure every time), and keeps it for Video info.
     */
    private fun notForPlayer(why: String, once: Boolean = true) {
        if (why == notForPlayerWhy) return
        notForPlayerWhy = why
        streamLog("Sofa Player not used: $why")
        val site = Browser.hostOf(s.web?.url).orEmpty()
        if (inFullscreen && (!once || notForPlayerShown.add(site))) fullscreen.notice(why)
    }

    /** Too short for Sofa Player: often an ad, over before it would be ready. */
    fun short(state: VideoState) = state.duration > 0 && state.duration < SHORT_FOR_PLAYER

    /** The page's video reports [state] while it plays fullscreen. */
    fun maybeStart(state: VideoState) {
        val prefs = s.prefs
        val external = prefs.forceExternalPlayer
        if (!(prefs.sofaPlayer || external) || tried || player.active || !inFullscreen || s.inPip) return
        if (state.paused || state.position < 1 || short(state)) return
        if (SystemClock.uptimeMillis() < retryAt) return
        tried = true
        // "Always play in another app" (experimental): the "Play in…" app gets it, once per fullscreen; short videos
        // (often ads) and protected ones, which no other app can open, stay here without a word.
        if (external) {
            video.sources.collect { source ->
                if (source?.protected == true) { streamLog("Always play in another app: protected, stays here"); return@collect }
                streamLog("Always play in another app: handing over")
                video.playIn.handOver(source)
            }
            return
        }
        video.sources.collect(::startFor)
    }

    private fun startFor(source: VideoSource?) {
        val w = s.web ?: return
        if (!inFullscreen) { streamLog("Sofa Player not tried: left fullscreen"); return }
        if (source == null || source.protected) {
            notForPlayer(activity.getString(if (source == null) R.string.sofa_player_why_no_video else R.string.sofa_player_why_protected))
            return
        }
        val userAgent = w.settings.userAgentString.orEmpty()
        val cookies = WebViews.cookieManager(w)
        val direct = source.src.takeIf(Browser::isWebUrl)
        if (direct != null) {
            start(SofaPlayer.Source(direct, StreamResolver.Kind.FILE, source.page, null, userAgent, cookies::getCookie, media = true, source.subtitles), source)
            return
        }
        val browser = s.browser
        val candidates = browser.streams.candidates()
        if (candidates.none { it.manifest }) {
            // The page may fetch its stream a moment after it starts; look again a few times.
            if (++tries < 4) { tried = false; retryAt = SystemClock.uptimeMillis() + 2000 }
            streamLog("Sofa Player: no manifest caught yet (look $tries)")
            if (tried) notForPlayer(activity.getString(R.string.sofa_player_why_no_stream)) // that was the last look
            return
        }
        browser.fetchInPage(StreamResolver.manifestsToRead(candidates), PlayIn.PAGE_FETCH_MS) { fromPage ->
            thread(name = "sofa-player") {
                val resolver = StreamResolver(userAgent, cookies::getCookie, ::streamLog)
                val resolved = resolver.resolve(candidates, fromPage, checkOutside = false)
                    // Which pictures are HDR, when the master doesn't say, so Auto can keep to what the TV shows.
                    ?.let { r -> r.copy(playlist = r.playlist?.let { resolver.withReadRanges(it, r.frame) }) }
                activity.runOnUiThread {
                    streamLog("Sofa Player: ${resolved?.kind} ${resolved?.url}")
                    // A file only the page's requests mention (not its video's own address) may be an ad.
                    if (activity.isDestroyed) return@runOnUiThread
                    if (resolved == null || resolved.kind == StreamResolver.Kind.FILE) {
                        notForPlayer(activity.getString(if (resolved == null) R.string.sofa_player_why_unreadable else R.string.sofa_player_why_no_stream))
                        return@runOnUiThread
                    }
                    start(SofaPlayer.Source(resolved.url, resolved.kind, resolved.frame, resolved.playlist, userAgent,
                        cookies::getCookie, media = false, source.subtitles, source.width, source.height), source)
                }
            }
        }
    }

    private fun start(source: SofaPlayer.Source, page: VideoSource) {
        if (!inFullscreen) return
        declaredFps = 0f
        measuredFps = null
        measuredBelowTop = false
        steppedDown = null
        rateMeasured = false
        catchUps = 0
        swapToken++
        started = source to page
        fullscreen.addPlayer(player.view)
        // Ahead of the page's video, which plays on while Sofa Player gets ready unseen (see onReady).
        player.start(source, (pageNowMs() ?: (page.position * 1000).toLong()) + TAKEOVER_LEAD_MS, playerChoices)
    }

    /** Where the page's video is now: its last report, moved on by the time since when it plays. */
    private fun pageNowMs(): Long? {
        val st = video.state ?: return null
        val since = if (st.paused) 0L else ((SystemClock.uptimeMillis() - video.stateAt) * st.rate).toLong()
        return (st.position * 1000).toLong() + since
    }

    /**
     * What was picked by hand in Sofa Player, for every later video. Private browsing reads the saved choices but keeps
     * its own picks in memory only, until it ends.
     */
    private val playerChoices get() = privateChoices?.takeIf { s.browser.privateMode } ?: s.prefs.playerChoices
    private var privateChoices: PlayerChoices? = null
    private var catchUps = 0

    /**
     * Sofa Player is ready, unseen, at a point ahead of the page's video: it shows when the page's video gets there,
     * so the picture carries straight on. If the page's video got past it first, Sofa Player moves further ahead,
     * still unseen; after a few tries, or when the page's video stopped, it takes over where that is.
     */
    private fun swapWhenPageArrives() {
        val page = pageNowMs()
        val ahead = page?.let { player.positionMs - it }
        streamLog("Sofa Player ready at ${player.positionMs} ms, page at $page ms")
        val state = video.state
        when {
            page == null || player.live -> swapIn(null)
            state?.paused == true -> swapIn(page)
            ahead!! < -SWAP_SLACK_MS -> if (++catchUps <= MAX_CATCH_UPS) player.catchUp(page + TAKEOVER_LEAD_MS * (catchUps + 1)) else swapIn(page)
            else -> {
                val token = swapToken
                fullscreen.postDelayed({ if (token == swapToken) swapIn(null) }, (ahead / (state?.rate ?: 1.0)).toLong().coerceAtLeast(0))
            }
        }
    }

    /** Bumped whenever Sofa Player stops or starts again, so a pending swap of an older one does nothing. */
    private var swapToken = 0

    private fun swapIn(seekMs: Long?) {
        if (!inFullscreen || !player.active || on) return
        val state = video.state
        streamLog("Sofa Player takes over at ${seekMs ?: player.positionMs} ms, page at ${pageNowMs()} ms")
        VideoRelay.send(s.web, "pause")
        on = true
        fullscreen.showPlayer(true)
        player.takeOver(seekMs, play = state?.paused != true || pausedForDecoder, rate = state?.rate ?: 1.0)
        // The page's paused video still holds a hardware decoder (measured on a TV: two while Sofa Player plays), and
        // TVs have few: the page rests meanwhile, which lets it go, once its pause is through. backToPage wakes it.
        val token = swapToken
        fullscreen.postDelayed({ if (on && token == swapToken) restPage() }, PAGE_REST_MS)
        fullscreen.bar.show()
        matchRate()
    }

    private fun restPage() {
        pageRested = true
        s.web?.onPause()
    }

    private val events: SofaPlayer.Listener = object : SofaPlayer.Listener {
        override fun onReady() {
            if (!inFullscreen) { player.release(); return }
            if (!on) swapWhenPageArrives()
        }

        override fun onState(state: VideoState) {
            if (on) video.playerState(state)
        }

        override fun onPlaying(playing: Boolean) {
            this@Takeover.playing = playing && on
            s.applyKeepAwake()
        }

        // Nothing more to show: Sofa Player's buffer and decoder are let go, and the page's video plays its own last
        // moment, so the site sees its video end (its next episode, its end screen) as it would without Sofa Player. A
        // seek to the very end of a paused video fires no "ended". Sofa Player was tried already, so it won't come back.
        override fun onEnded() {
            if (on) backToPage(play = true, before = END_FOR_PAGE_S)
        }

        override fun onError(reason: String, detail: String?, ready: Boolean) {
            streamLog("Sofa Player failed: $reason ${detail.orEmpty()}")
            // "ERROR_CODE_DECODER_INIT_FAILED" as "decoder init failed", with the picture and decoder when one failed
            // ("AV1 3840 × 2160" and the decoder's name): enough for a report, over the video every time.
            val name = reason.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
            val why = activity.getString(R.string.sofa_player_failed, detail?.let { "$name: $it" } ?: name)
                .let { if (reason.startsWith(DECODER_ERROR)) withPlayInHint(it) else it }
            val shown = on
            if (shown) backToPage(play = true)
            notForPlayer(why, once = false)
            // Unseen: the page wakes, and its video plays on if Sofa paused it for the second try.
            if (!shown) backToPage(play = pausedForDecoder)
        }

        override fun onSteppedDown(failed: String, now: String, why: SofaPlayer.Step) {
            // Over the video once per video (the next step down replaces it), and kept in Video info.
            val text = activity.getString(when (why) {
                SofaPlayer.Step.FAILED -> R.string.sofa_player_stepped_down
                SofaPlayer.Step.SOFTWARE -> R.string.sofa_player_stepped_down_software
                SofaPlayer.Step.STUTTER -> R.string.sofa_player_stepped_down_stutter
            }, failed, now).let { if (why == SofaPlayer.Step.FAILED) withPlayInHint(it) else it }
            steppedDown = text
            streamLog(text)
            if (inFullscreen) fullscreen.notice(text)
        }

        override fun onSoftwareDecoder() {
            // Still unseen: the page's video stops and the page rests, which is when its video lets go of the hardware
            // decoder (a paused one keeps it), then Sofa Player tries for it once more. If it gets software again, it
            // plays on with it up to 1080p 30 fps, which a TV's processor keeps up with (the page's video is paused
            // at the same point); for a video with nothing that small, the page's player is better.
            val (source, page) = started ?: return
            if (!inFullscreen) return
            if (hardwareRetried) {
                if (!player.keepToSoftware()) {
                    streamLog("Sofa Player: software decoder again, nothing small enough, back to the page")
                    backToPage(play = on || pausedForDecoder)
                    notForPlayer(activity.getString(R.string.sofa_player_why_software), once = false)
                }
                return
            }
            if (on) return
            hardwareRetried = true
            pausedForDecoder = true
            streamLog("Sofa Player: software decoder, pausing the page's video and trying again")
            VideoRelay.send(s.web, "pause")
            player.release()
            val token = ++swapToken
            fullscreen.postDelayed({
                if (token != swapToken) return@postDelayed
                restPage()
                fullscreen.postDelayed({ if (token == swapToken && inFullscreen) start(source, page) }, PAGE_REST_MS)
            }, PAGE_REST_MS)
        }

        override fun onFrameRate(fps: Float) {
            declaredFps = fps
            if (on) matchRate()
        }

        override fun onMeasuredFrameRate(fps: Double?, belowTop: Boolean) {
            measuredFps = fps
            measuredBelowTop = belowTop
            rateMeasured = true
            if (on) matchRate()
        }

        override fun onChoicesChanged(choices: PlayerChoices) {
            if (s.browser.privateMode) privateChoices = choices else s.prefs.playerChoices = choices
        }
    }

    /** Sofa Player's frame rate: the stream's word, else what it measured from its own frames. */
    private val fps get() = declaredFps.takeIf { it > 0f }?.toDouble() ?: measuredFps
    private var measuredFps: Double? = null
    /** [measuredFps] came from a picture below the stream's largest: its rate may be half the stream's. */
    private var measuredBelowTop = false
    private var rateMeasured = false
    /** Sofa Player matched (or chose not to match) the TV's rate in this fullscreen; the page's measurement is apart. */
    private var playerRateMatched = false

    /** "Match frame rate" for Sofa Player, once per fullscreen, as for the page's video. */
    private fun matchRate() {
        val fps = this.fps ?: return
        if (playerRateMatched) return
        playerRateMatched = true
        val state = video.state
        val declared = declaredFps > 0f
        val source = if (declared) "declared" else if (measuredBelowTop) "measured below the top picture" else "measured"
        if (!s.prefs.matchFrameRate || s.inPip || (state != null && state.duration > 0 && state.duration < VideoSession.SHORT_VIDEO)) {
            streamLog("Sofa Player frame rate ${FrameRate.label(fps)} ($source): no switch (setting off, picture-in-picture, or video under ${VideoSession.SHORT_VIDEO.toInt()} s)")
            return
        }
        val switched = video.refreshMatch.match(fps, pause = { player.command("pause", null) },
            resume = { if (inFullscreen) player.command("play", null) }, orDouble = !declared && measuredBelowTop)
        streamLog("Sofa Player frame rate ${FrameRate.label(fps)} ($source): " +
            if (switched) "mode requested" else "no switch (none better, already asked, or no other mode)")
    }

    /** Hands the video back to the page's player, at Sofa Player's position, or [before] seconds earlier. */
    private fun backToPage(play: Boolean, before: Double = 0.0) {
        val at = (player.positionSeconds - before).coerceAtLeast(0.0)
        val wasOn = on
        on = false
        swapToken++
        playing = false
        player.release()
        fullscreen.removePlayer()
        s.applyKeepAwake()
        val rested = pageRested
        pageRested = false
        val paused = pausedForDecoder
        pausedForDecoder = false
        val web = s.web
        val frame = video.watchedFrame
        if (!wasOn) {
            // Sofa Player was still unseen: the page may rest for its second try (onSoftwareDecoder).
            if (rested) web?.onResume()
            if (play && paused) VideoRelay.send(web, "play", frame = frame)
            return
        }
        // Awake again (it rested while Sofa Player played, see swapIn), so its video can play on.
        web?.onResume()
        VideoRelay.send(web, "seekTo", at, frame = frame)
        if (play) VideoRelay.send(web, "play", frame = frame)
    }

    /** From Sofa Player back to the site's own player. */
    fun sitePlayer() {
        backToPage(play = true)
        fullscreen.bar.show()
    }

    /** Sofa Player's quality, sound and subtitle choices. */
    fun tracks() {
        val quality = player.qualities(activity.getString(R.string.auto), activity.getString(R.string.quality_beyond))
        val sound = player.audios()
        val subs = player.subtitles(activity.getString(R.string.subtitles_off))
        val rows = listOfNotNull(
            Triple(R.string.quality, quality, quality.firstOrNull { it.selected }?.label),
            if (sound.size > 1) Triple(R.string.sound, sound, sound.firstOrNull { it.selected }?.label) else null,
            if (subs.size > 1) Triple(R.string.subtitles, subs, subs.firstOrNull { it.selected }?.label) else null,
        )
        val labels = rows.map { (title, _, now) -> activity.getString(title) + (now?.let { ": $it" } ?: "") }.toTypedArray()
        s.dialog().setTitle(R.string.tracks).setItems(labels) { _, which ->
            val (title, choices, _) = rows[which]
            s.dialog().setTitle(title).setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.selected }) { d, i ->
                choices[i].apply()
                d.dismiss()
            }.show()
        }.show()
    }

    /**
     * A notice about a picture the TV's own decoder couldn't play, with the hint that another app may: players such
     * as VLC bring decoders of their own.
     */
    private fun withPlayInHint(text: String) = "$text ${activity.getString(R.string.play_in_hint)}"

    /** Video info while Sofa Player plays. */
    fun info(): String {
        val lines = player.info()
        val labels = listOf(R.string.video_info_native_video, R.string.video_info_decoder, R.string.video_info_native_frames, R.string.video_info_sound)
        val rate = when {
            fps != null -> activity.getString(R.string.video_info_rate, FrameRate.label(fps!!))
            rateMeasured -> activity.getString(R.string.video_info_rate_unknown)
            else -> activity.getString(R.string.video_info_rate_waiting)
        }
        return (listOfNotNull(activity.getString(R.string.sofa_player), steppedDown) +
            lines.zip(labels).mapNotNull { (line, label) -> line?.let { activity.getString(label, it) } } +
            rate + "" + video.displayInfo(player = true) + video.decoderInfo()).joinToString("\n")
    }

    private companion object {
        /** Media3's decoder errors by name: ERROR_CODE_DECODER_INIT_FAILED, ERROR_CODE_DECODING_FAILED and the like. */
        const val DECODER_ERROR = "ERROR_CODE_DECOD"
        /** Seconds: shorter videos (trailers, previews, ads) stay with the page's player. */
        const val SHORT_FOR_PLAYER = 60.0
        /** Seconds of the end the page's video plays itself after Sofa Player finished, so the site sees the end. */
        const val END_FOR_PAGE_S = 0.5
        /** How far ahead of the page's video Sofa Player gets ready: about what it takes to start (ms). */
        const val TAKEOVER_LEAD_MS = 4000L
        /** How long after Sofa Player takes over the page goes to rest; its video's pause is through by then (ms). */
        const val PAGE_REST_MS = 500L
        /** How far the page's video may have passed Sofa Player's first picture and it still swaps in there (ms). */
        const val SWAP_SLACK_MS = 150L
        /** How many times Sofa Player moves further ahead, unseen, before it takes over with a seek. */
        const val MAX_CATCH_UPS = 2
    }
}
