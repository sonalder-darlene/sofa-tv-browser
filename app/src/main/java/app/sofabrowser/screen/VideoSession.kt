package app.sofabrowser.screen

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.webkit.WebChromeClient
import app.sofabrowser.R
import app.sofabrowser.browser.Decoders
import app.sofabrowser.browser.FrameRate
import app.sofabrowser.browser.RefreshRateMatch
import app.sofabrowser.browser.VideoBar
import app.sofabrowser.browser.VideoInfo
import app.sofabrowser.browser.VideoRelay
import app.sofabrowser.browser.VideoState
import app.sofabrowser.resumeStore

/**
 * A page's video in fullscreen: the bar's buttons, the keys, where long films were left, "Match frame rate", Video
 * info and picture-in-picture. Sofa Player's takeover ([Takeover]) and "Play in…" ([PlayIn]) are its parts.
 */
class VideoSession(private val s: Screen, private val watch: Watch, private val page: View) : VideoBar.Actions {

    private val activity get() = s.activity
    private val fullscreen get() = s.fullscreen
    val shown get() = fullscreen.visibility == View.VISIBLE

    val sources = VideoSources(s)
    val playIn = PlayIn(s, this)
    val takeover = Takeover(s, this)

    private var callback: WebChromeClient.CustomViewCallback? = null

    /** The watched video's last state: the page's, or Sofa Player's while it shows. */
    var state: VideoState? = null
        private set
    /** When the page's video last reported [state]. */
    var stateAt = 0L
        private set
    /** The page frame of the watched video, so Back from Sofa Player reaches it and not another frame's video. */
    var watchedFrame: String? = null
        private set

    /** A video plays on the page, in any frame. */
    private var pagePlaying = false
    /** A video plays, on the page or in Sofa Player. */
    val playing get() = pagePlaying || takeover.playing

    fun onPlaying(playing: Boolean) {
        pagePlaying = playing
        s.applyKeepAwake()
    }

    /** The page put [view] in fullscreen; it takes the page's place until [callback] or [exit] ends it. */
    fun enter(view: View, callback: WebChromeClient.CustomViewCallback) {
        s.menu.dismiss()
        this.callback = callback
        resumeOffered = false
        state = null
        watchedFrame = null
        videoFps = null
        frameTimesSeen = false
        takeover.newFullscreen()
        fullscreen.bar.pipShown = pipSupported
        fullscreen.attach(view)
        page.visibility = View.INVISIBLE
        fullscreen.postDelayed({ VideoRelay.send(s.web, "watch") }, 300)
        fullscreen.bar.show()
    }

    /** Fullscreen ended: the page shows again. */
    fun exit() {
        if (!shown) return
        takeover.leaveFullscreen()
        VideoRelay.send(s.web, "unwatch")
        refreshMatch.reset()
        fullscreen.detach()
        callback = null
        page.visibility = View.VISIBLE
        s.web?.requestFocus()
    }

    override fun exitFullscreen() {
        callback?.onCustomViewHidden()
        exit()
    }

    override fun command(cmd: String, value: Double?) =
        if (takeover.on && cmd in PLAYER_COMMANDS) takeover.player.command(cmd, value) else VideoRelay.send(s.web, cmd, value)

    override fun usePointer() {
        // Give the page its own controls back while the pointer is used on them.
        command("unwatch")
        fullscreen.bar.hideNow()
        fullscreen.pointer.cursorEnabled = true
        fullscreen.pointer.speedLevel = s.prefs.cursorSpeed
    }

    override fun playIn(choose: Boolean) = playIn.press(choose)
    override fun tracks() = takeover.tracks()
    override fun sitePlayer() = takeover.sitePlayer()

    fun onVideoState(state: VideoState) {
        state.frame?.let { watchedFrame = it }
        // The page's video is paused while Sofa Player plays; the bar follows the player.
        if (takeover.on) return
        takeover.maybeStart(state)
        this.state = state
        stateAt = SystemClock.uptimeMillis()
        fullscreen.bar.update(state)
        rememberPosition(state)
    }

    /** Sofa Player, while it shows, reports [state] in place of the page's video. */
    fun playerState(state: VideoState) {
        this.state = state
        fullscreen.bar.update(state)
        rememberPosition(state)
    }

    // Keys

    @SuppressLint("GestureBackNavigation") // see MainActivity.dispatchKeyEvent
    fun key(event: KeyEvent): Boolean {
        val bar = fullscreen.bar
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            // The remote's volume keys always reach the system (often the TV itself, over HDMI-CEC).
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
            KeyEvent.KEYCODE_CAPTIONS -> { if (down) { command("subs"); bar.show() }; return true }
        }
        if (fullscreen.pointer.cursorEnabled) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (down) { fullscreen.pointer.cursorEnabled = false; command("watch"); bar.show() }
                return true
            }
            return fullscreen.pointer.dispatchKeyEvent(event)
        }
        if (mediaKey(event)) { if (down) bar.show(); return true }
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (down && event.repeatCount == 0) { if (bar.showing) bar.hideNow() else exitFullscreen() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> if (!bar.showing) {
                if (down) {
                    command("seekBy", if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -10.0 else 10.0)
                    bar.show(focusScrub = true)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (!bar.showing) {
                if (down) bar.show()
                return true
            }
        }
        // Keys belong to the bar while it is up, even if the page's fullscreen view took focus.
        if (bar.showing && !bar.hasFocus()) {
            bar.show()
            return bar.dispatchKeyEvent(event)
        }
        return false
    }

    /** The remote's media keys, on the page or in fullscreen. */
    fun mediaKey(event: KeyEvent): Boolean {
        // Play with nothing playing yet means "watch this page's video" (fullscreen has its own player already).
        if (!shown && !playing && event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY)) {
            if (event.action == KeyEvent.ACTION_DOWN) watch.start()
            return true
        }
        val cmd = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> "toggle" to null
            KeyEvent.KEYCODE_MEDIA_PLAY -> "play" to null
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "pause" to null
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> "seekBy" to 10.0
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> "seekBy" to -10.0
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN) command(cmd.first, cmd.second)
        return true
    }

    // Resume: where long films were left, on this device only (ResumeStore has the rules).

    // Fresh each time: Settings may have cleared it meanwhile.
    private val resume get() = resumeStore(activity)
    private var resumeSavedAt = 0L
    private var resumeOffered = false

    private fun rememberPosition(st: VideoState) {
        val url = s.web?.url ?: return
        if (st.duration <= 0) return
        val now = System.currentTimeMillis()
        val private = s.browser.privateMode
        if (!resumeOffered) {
            resumeOffered = true
            if (!private) resume.offer(url, now)?.takeIf { it > st.position + 30 }?.let(fullscreen.bar::offerResume)
        }
        if (st.paused || now - resumeSavedAt > 5000) {
            resumeSavedAt = now
            resume.save(url, st.position, st.duration, private, now)
        }
    }

    // Match frame rate: the page measures the watched video's frames once per fullscreen; FrameRate has the rules.

    val refreshMatch by lazy { RefreshRateMatch(activity) }
    private var videoFps: Double? = null
    private var frameTimesSeen = false

    fun onVideoFrameTimes(times: List<Double>) {
        if (!shown || frameTimesSeen) return
        frameTimesSeen = true
        val fps = FrameRate.estimate(times) ?: return
        videoFps = fps
        val st = state
        // As Android's guide advises, no blackout for short videos; live streams (no duration) qualify.
        if (!s.prefs.matchFrameRate || s.inPip || (st != null && st.duration > 0 && st.duration < SHORT_VIDEO)) return
        val wasPlaying = st?.paused == false
        refreshMatch.match(fps, pause = { if (wasPlaying) command("pause") },
            resume = { if (wasPlaying && shown) command("play") })
    }

    // Video info

    override fun toggleVideoInfo() {
        val info = fullscreen.info
        info.visibility = if (info.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        info.removeCallbacks(refreshInfo)
        if (info.visibility == View.VISIBLE) { info.text = activity.getString(R.string.video_info_waiting); refreshInfo.run() }
    }

    private val refreshInfo: Runnable = Runnable {
        val info = fullscreen.info
        if (info.visibility != View.VISIBLE) return@Runnable
        if (takeover.on) info.text = takeover.info() else command("info")
        info.postDelayed(refreshInfo, 1000)
    }

    fun onVideoInfo(info: VideoInfo) {
        if (fullscreen.info.visibility != View.VISIBLE) return
        fun names(list: List<VideoInfo.Codec>) = list.joinToString(", ") { it.name }
        val hardware = info.codecs.filter { it.supported && it.efficient }
        val software = info.codecs.filter { it.supported && !it.efficient }
        val prefs = s.prefs
        // The panel's own mode can be 4K while apps are drawn at 1080p; the 4K investigation needs both (displayInfo).
        fullscreen.info.text = listOfNotNull(
            // Why this is the site's player and not Sofa Player, for a report.
            (takeover.notForPlayerWhy ?: state?.takeIf(takeover::short)?.let { activity.getString(R.string.sofa_player_why_short) })
                ?.takeIf { prefs.sofaPlayer && !prefs.forceExternalPlayer },
            activity.getString(R.string.video_info_size, info.width, info.height),
            if (info.total >= 0) activity.getString(R.string.video_info_frames, info.total, info.dropped) else null,
            when {
                videoFps != null -> activity.getString(R.string.video_info_rate, FrameRate.label(videoFps!!))
                frameTimesSeen -> activity.getString(R.string.video_info_rate_unknown)
                else -> activity.getString(R.string.video_info_rate_waiting)
            },
            if (info.codecs.isEmpty()) null
            else activity.getString(R.string.video_info_hardware, names(hardware).ifEmpty { activity.getString(R.string.video_info_none) }),
            if (software.isEmpty()) null else activity.getString(R.string.video_info_software, names(software)),
            activity.getString(R.string.video_info_note),
            "",
        ).plus(displayInfo()).plus(activity.getString(R.string.video_info_page, info.screenWidth, info.screenHeight,
            info.pixelRatio.toString(), info.innerWidth, info.cssWidth)).plus(decoderInfo()).joinToString("\n")
    }

    /**
     * Video info's last lines, the same in both players: per codec, the largest picture the TV says its hardware and
     * software decoders take ([Decoders.tv]). Last, so a small screen cuts these first.
     */
    fun decoderInfo(): List<String> {
        fun take(d: Decoders.Listed): String = "${d.width} × ${d.height}".let { size ->
            d.fps?.let { activity.getString(R.string.tv_decoder_rate, size, it) } ?: size
        }
        return listOf("") + Decoders.tv.map { s ->
            val found = listOfNotNull(s.hardware?.let { activity.getString(R.string.tv_decoder_hardware, take(it)) },
                s.software?.let { activity.getString(R.string.tv_decoder_software, take(it)) })
            activity.getString(R.string.video_info_tv_decoder, s.codec, found.joinToString(", ").ifEmpty { activity.getString(R.string.tv_decoder_none) })
        }
    }

    /**
     * The display's side of "Video info": window and panel sizes, and the TV's refresh rates. Sofa Player draws on a
     * video layer of the panel's own size, the page's player within the app's window: [player] says which.
     */
    fun displayInfo(player: Boolean = false): List<String> {
        val dm = activity.resources.displayMetrics
        @Suppress("DEPRECATION") val mode = activity.windowManager.defaultDisplay.mode
        return listOfNotNull(
            activity.getString(if (player) R.string.video_info_display_player else R.string.video_info_display, dm.widthPixels, dm.heightPixels,
                mode?.physicalWidth ?: dm.widthPixels, mode?.physicalHeight ?: dm.heightPixels),
            refreshMatch.otherRates().let { others ->
                val now = FrameRate.label(refreshMatch.refreshRate.toDouble())
                if (others.isEmpty()) activity.getString(R.string.video_info_tv_rate_only, now)
                else activity.getString(R.string.video_info_tv_rate, now, others.joinToString(", ") { FrameRate.label(it.toDouble()) })
            },
            if (refreshMatch.turnedOffInSystem) activity.getString(R.string.video_info_tv_rate_off) else null,
        )
    }

    // Picture-in-picture: the fullscreen video shrinks into a corner, from the bar or on Home while it plays.

    private val pipSupported get() =
        Build.VERSION.SDK_INT >= 26 && activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun pictureInPicture() {
        if (!pipSupported || !shown || Build.VERSION.SDK_INT < 26) return
        fullscreen.bar.hideNow()
        fullscreen.info.visibility = View.GONE
        runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
    }

    /** Home was pressed: a video playing fullscreen goes on in picture-in-picture. */
    fun onUserLeaveHint() {
        if (playing && shown) pictureInPicture()
    }

    fun onPictureInPicture(active: Boolean) {
        if (active) { fullscreen.bar.hideNow(); s.menu.dismiss() }
    }

    companion object {
        /** The commands Sofa Player takes in place of the page's video. */
        private val PLAYER_COMMANDS = setOf("toggle", "play", "pause", "seekBy", "seekTo", "rate", "subs")
        /** Seconds: "Match frame rate" leaves shorter videos alone. */
        const val SHORT_VIDEO = 300.0
    }
}
