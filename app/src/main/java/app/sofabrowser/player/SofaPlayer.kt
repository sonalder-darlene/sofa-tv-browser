package app.sofabrowser.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import app.sofabrowser.R
import app.sofabrowser.browser.Decoders
import app.sofabrowser.browser.FrameRate
import app.sofabrowser.browser.PageHeaders
import app.sofabrowser.browser.PlayerRequest
import app.sofabrowser.browser.StreamResolver
import app.sofabrowser.browser.VideoState
import kotlin.math.max

/**
 * Sofa Player: the stream a page's video comes from, played by Media3's ExoPlayer on a SurfaceView of its own
 * ([PlayerSurface]), which the TV composes as a video layer: the panel's own resolution (real 4K), HDR, hardware
 * decoding, and frames released on the display's beat, so 60 fps stays smooth. Each request goes out as the page's
 * player made it ([PageHeaders]); a master playlist comes from the page's own copy, since some sites refuse it to
 * any other client. It takes the same commands as the page's video (VideoRelay), so the bar drives either.
 */
@OptIn(UnstableApi::class)
class SofaPlayer(private val context: Context, private val listener: Listener) {

    interface Listener {
        /** Ready at the position asked ([start] or [catchUp]), still hidden: the picture can be shown. */
        fun onReady()
        fun onState(state: VideoState)
        fun onPlaying(playing: Boolean)
        /**
         * Playback failed: [reason] is Media3's error name, [detail] the picture and decoder when a decoder failed;
         * [ready] tells whether [onReady] had come.
         */
        fun onError(reason: String, detail: String?, ready: Boolean)
        /**
         * Sofa Player left a picture ([failed], as "AV1 3840 × 1632") and goes on with [now]: it didn't decode on this
         * TV, or would have played in software ([why]).
         */
        fun onSteppedDown(failed: String, now: String, why: Step)
        /**
         * The video got a software decoder though the TV has a hardware one for its format: often the page's video,
         * still playing, holds it. Once per [start]; after a second try, [keepToSoftware] says whether it plays on.
         */
        fun onSoftwareDecoder()
        /**
         * The video's frame rate, when the stream says it (0 otherwise): the highest its pictures declare, since
         * Auto may start on a lower one at half the rate.
         */
        fun onFrameRate(fps: Float)
        /**
         * The frame rate measured from the frames shown (FrameRate.estimate), once per start; null when unclear.
         * [belowTop]: measured on a picture smaller than the stream's largest, whose rate may be twice it.
         */
        fun onMeasuredFrameRate(fps: Double?, belowTop: Boolean)
        /** The video played to its end. */
        fun onEnded()
        /** A quality, subtitles or sound was picked in the menu: [choices] are all of them, for the next videos. */
        fun onChoicesChanged(choices: PlayerChoices)
    }

    /**
     * What to play: the page's stream, its frame (for the headers), the master playlist text when one was read, the
     * page's own subtitle files ([PageSubtitles]), and the size of the picture the site's player showed (0 unknown).
     */
    class Source(val url: String, val kind: StreamResolver.Kind, val frame: String?, val playlist: String?,
                 val userAgent: String, val cookie: (String) -> String?, val media: Boolean,
                 val subtitles: List<PlayerRequest.Subtitle> = emptyList(), val pageWidth: Int = 0, val pageHeight: Int = 0)

    val view = PlayerSurface(context)
    private var player: ExoPlayer? = null
    private var ready = false
    /** [Listener.onReady] is due at the next READY: after [start] or [catchUp]. */
    private var readyWanted = false
    private var decoder: String? = null
    private val main = Handler(Looper.getMainLooper())
    /** The master playlist's word on each picture. */
    private var variants: List<Variants.Variant> = emptyList()
    /** What was picked by hand on earlier videos, and what of it still waits for the stream's tracks. */
    private var choices = PlayerChoices()
    private var remembered: Variants.Quality? = null
    private var subtitlesWanted: String? = null

    val active get() = player != null

    /** [choices]: those picked by hand on earlier videos. */
    fun start(source: Source, startMs: Long, choices: PlayerChoices = PlayerChoices()) {
        release()
        variants = source.playlist?.let(Variants::parse).orEmpty()
        failed.clear()
        knownTracks = Tracks.EMPTY
        initRetried = false
        steppedFrom = null
        this.choices = choices
        remembered = choices.quality
        subtitlesWanted = choices.subtitles?.takeIf { it != PlayerChoices.OFF }
        val sampler = FrameRate.Sampler()
        ready = false
        readyWanted = true
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(source.userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(TIMEOUT_MS)
            .setReadTimeoutMs(TIMEOUT_MS)
        // The User-Agent is set above; the rest per request, since Referer, Origin and cookies depend on the address.
        val withHeaders = ResolvingDataSource.Factory(http) { spec ->
            spec.withAdditionalHeaders(PageHeaders.forRequest(spec.uri.toString(), source.frame, source.userAgent, source.cookie,
                media = source.media) - "User-Agent")
        }
        val master = source.playlist?.toByteArray()
        val dataSources = if (master == null) withHeaders else DataSource.Factory { PageCopyDataSource(Uri.parse(source.url), master, withHeaders.createDataSource()) }
        val selector = DefaultTrackSelector(context)
        val p = ExoPlayer.Builder(context, renderers().setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            .setBandwidthMeter(startMeter(Variants.startBandwidth(variants, source.pageWidth, source.pageHeight)))
            // Media3 sizes its buffer for video at about 130 MB; a 4K stream fills that on TVs with 2 or 3 GB of memory,
            // which then stop Sofa in the background. A cap in bytes keeps it to some 15 s at 4K, more at lower sizes.
            .setLoadControl(DefaultLoadControl.Builder().setTargetBufferBytes(BUFFER_BYTES).setPrioritizeTimeOverSizeThresholds(false).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .build()
        // Subtitles as the TV's caption setting says, the TV's language for sound when the stream has several; unless
        // picked by hand on an earlier video (a subtitle language is matched once the tracks are known, applySubtitles).
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredTextLanguageAndRoleFlagsToCaptioningManagerSettings(context)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, choices.subtitles == PlayerChoices.OFF)
            .setPreferredAudioLanguages(*listOfNotNull(choices.audio, java.util.Locale.getDefault().language).toTypedArray())
            .build()
        p.setVideoSurfaceView(view.surface)
        p.addListener(events)
        p.addAnalyticsListener(analytics)
        // The media time of each frame shown, for "Match frame rate" when the stream doesn't say its rate.
        p.setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
            sampler.add(presentationTimeUs / 1e6)?.let { times ->
                main.post {
                    if (player !== p) return@post
                    val top = topHeight()
                    listener.onMeasuredFrameRate(FrameRate.estimate(times), belowTop = top != null && (p.videoFormat?.height ?: 0) < top)
                }
            }
        }
        // Test builds log every load, format and error under the tag SofaPlayer, and the frame counters under SofaFrames.
        if (app.sofabrowser.BuildConfig.DEBUG) {
            p.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("SofaPlayer"))
            main.postDelayed(counters, COUNTERS_MS)
        }
        val mime = when (source.kind) {
            StreamResolver.Kind.HLS -> MimeTypes.APPLICATION_M3U8
            StreamResolver.Kind.DASH -> MimeTypes.APPLICATION_MPD
            StreamResolver.Kind.FILE -> null
        }
        // The page's subtitle files, when the stream has none of its own; one the page showed is shown here too.
        val subtitles = PageSubtitles.forPlayer(source.subtitles, source.playlist).mapIndexed { i, s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url)).setMimeType(PageSubtitles.mimeOf(s.url)).setId("page:$i")
                .setLabel(s.label.ifEmpty { null }).setLanguage(s.language.ifEmpty { null })
                .setSelectionFlags(if (s.showing) C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        p.setMediaItem(MediaItem.Builder().setUri(source.url).setMimeType(mime).setSubtitleConfigurations(subtitles).build(), startMs)
        p.volume = 0f
        p.playWhenReady = false
        p.prepare()
        player = p
    }

    /**
     * Auto opens at the site's own picture ([bandwidth], from [Variants.startBandwidth]): the site's player has measured
     * the network already, where Media3 would start from a guess, low, and climb only after some 15 s. Never below what
     * Media3 learnt on earlier videos; that alone without a [bandwidth].
     */
    private fun startMeter(bandwidth: Int?): BandwidthMeter {
        val shared = DefaultBandwidthMeter.getSingletonInstance(context)
        // Auto takes the largest picture within this share of the estimate.
        val wanted = (bandwidth ?: return shared) / AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION.toDouble()
        if (wanted <= shared.bitrateEstimate) return shared
        if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", "Sofa Player starts at the site's picture: $bandwidth b/s")
        return DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(wanted.toLong() + 1).build()
    }

    /**
     * Still hidden and silent, moves to [positionMs] (ahead of the page's player, which plays on meanwhile);
     * [Listener.onReady] comes again once the picture is ready there.
     */
    fun catchUp(positionMs: Long) {
        val p = player ?: return
        readyWanted = true
        p.seekTo(positionMs)
    }

    /**
     * From hidden and silent to the one playing, from where it is, or at [positionMs] when the page's player had
     * already passed it (a seek, so a short wait). [play] is false when the page's video was paused meanwhile.
     */
    fun takeOver(positionMs: Long?, play: Boolean, rate: Double) {
        val p = player ?: return
        positionMs?.let { if (!p.isCurrentMediaItemLive) p.seekTo(it) }
        if (rate != 1.0) p.playbackParameters = PlaybackParameters(rate.toFloat())
        p.volume = 1f
        p.playWhenReady = play
        report.run()
    }

    val positionSeconds get() = (player?.currentPosition ?: 0L) / 1000.0
    /** Where it is, in ms (0 when nothing plays). */
    val positionMs get() = player?.currentPosition ?: 0L
    /** A live stream: no position to catch up with. */
    val live get() = player?.isCurrentMediaItemLive == true
    val playing get() = player?.isPlaying == true

    fun release() {
        main.removeCallbacks(report)
        main.removeCallbacks(counters)
        player?.let {
            it.removeListener(events)
            it.release()
        }
        player = null
        ready = false
        readyWanted = false
        decoder = null
        videoMime = null
        decoderChecked = false
        panelLimit = null
        view.showText(null)
    }

    /** The commands of VideoRelay, so the bar and the remote's keys drive this player as they drive the page's. */
    fun command(cmd: String, value: Double?) {
        val p = player ?: return
        when (cmd) {
            "toggle" -> p.playWhenReady = !p.playWhenReady
            "play" -> p.playWhenReady = true
            "pause" -> p.playWhenReady = false
            "seekBy" -> value?.let { p.seekTo((p.currentPosition + (it * 1000).toLong()).coerceIn(0, max(0, p.duration))) }
            "seekTo" -> value?.let { p.seekTo((it * 1000).toLong()) }
            "rate" -> value?.let { p.playbackParameters = PlaybackParameters(it.toFloat()) }
            "subs" -> toggleSubtitles()
        }
        main.post(report)
    }

    // Tracks: quality, sound and subtitles, as the stream offers them.

    class Choice(val label: String, val selected: Boolean, val apply: () -> Unit)

    private fun groups(type: Int) = player?.currentTracks?.groups.orEmpty().filter { it.type == type }

    /** The height of the largest picture this TV can play from the stream, null before the tracks are known. */
    private fun topHeight() = groups(C.TRACK_TYPE_VIDEO).flatMap { g -> (0 until g.length).filter(g::isTrackSupported).map { g.getTrackFormat(it).height } }.maxOrNull()

    /**
     * "Auto", then each picture the stream offers, largest first, HDR and SDR versions apart. Sizes the TV's decoder
     * says are beyond it are offered too, marked [beyond]: some TVs undersell their decoders, and Auto never picks
     * those. A pick is kept ([remember]), so the next videos start with it.
     */
    fun qualities(auto: String, beyond: String): List<Choice> {
        val p = player ?: return emptyList()
        val overridden = pickedByHand(p)
        val current = p.videoFormat
        val out = mutableListOf(Choice(auto + (current?.let { " (" + quality(it) + ")" } ?: ""), !overridden) {
            remember(choices.copy(quality = null))
            // Auto, within what the panel shows (keepToPanel).
            set { clearOverridesOfType(C.TRACK_TYPE_VIDEO).apply { panelLimit?.let(::setOverrideForType) } }
        })
        for (g in groups(C.TRACK_TYPE_VIDEO)) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i, true) || g.mediaTrackGroup to i in failed) continue
            val f = g.getTrackFormat(i)
            val label = quality(f) + if (g.isTrackSupported(i)) "" else " ($beyond)"
            out += Choice(label, overridden && g.isTrackSelected(i)) {
                remember(choices.copy(quality = qualityOf(f)))
                set { setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
            }
        }
        // Variants alike in every shown respect (bitrates apart) are one entry, chosen if any of them is.
        val merged = out.groupBy { it.label }.map { (_, same) -> if (same.any { it.selected }) same.first { it.selected } else same.first() }
        return merged.sortedWith(compareBy<Choice> { it !== out[0] }.thenByDescending { it.label.substringBefore('p').toIntOrNull() ?: 0 })
    }

    /** What the menu says about [f]: size, rate, codec, and its range as the stream declares it. */
    private fun quality(f: Format) = Variants.label(f.height, f.frameRate, codecName(f), rangeOf(f))

    private fun qualityOf(f: Format) = Variants.Quality(f.height, codecName(f), rangeOf(f), f.width)

    /** The stream's own word on [format]'s range (its master playlist), else what Media3 learnt once it loaded it. */
    private fun rangeOf(format: Format): String? {
        val mixed = variants.any { it.range == Variants.Range.SDR } &&
            variants.any { it.range == Variants.Range.PQ || it.range == Variants.Range.HLG }
        return variantOf(format)?.let { Variants.rangeName(it.range, it.codecs, mixed) } ?: hdrName(format)
    }

    /** The master playlist's line for [format], matched by its bitrate. */
    private fun variantOf(format: Format): Variants.Variant? {
        // The format playing carries the variant's id but not always its bitrate: find the variant's own format.
        val f = groups(C.TRACK_TYPE_VIDEO).flatMap { g -> (0 until g.length).map(g::getTrackFormat) }
            .firstOrNull { format.id != null && it.id == format.id } ?: format
        return variants.firstOrNull { it.bandwidth == f.peakBitrate && (it.height == f.height || it.height == 0) }
            ?: variants.firstOrNull { it.bandwidth == f.bitrate && (it.height == f.height || it.height == 0) }
            ?: variants.singleOrNull { it.bandwidth == f.peakBitrate }
    }

    /** A quality picked in the menu (or remembered from an earlier video); not Sofa's own limit to what the panel shows. */
    private fun pickedByHand(p: ExoPlayer) = p.trackSelectionParameters.overrides.any { (group, o) -> group.type == C.TRACK_TYPE_VIDEO && o != panelLimit }

    /**
     * The quality picked by hand on an earlier video, once the stream's pictures are known: the same, or the closest
     * below (Variants.closest), or Auto. Only among those Auto may take, and within Sofa's limit to what the panel
     * shows ([keepToPanel], which runs first): an HDR film stays HDR.
     */
    private fun applyRemembered(tracks: Tracks) {
        val wanted = remembered ?: return
        val p = player ?: return
        val limit = panelLimit
        val offered = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { g ->
            (0 until g.length).filter { g.isTrackSupported(it) && (limit == null || limit.mediaTrackGroup != g.mediaTrackGroup || it in limit.trackIndices) }
                .map { g to it }
        }
        if (offered.isEmpty()) return
        remembered = null
        val i = Variants.closest(wanted, offered.map { (g, i) -> qualityOf(g.getTrackFormat(i)) }) ?: return
        val (g, track) = offered[i]
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, track)).build()
    }

    /**
     * The subtitle language picked by hand on an earlier video, once the stream's tracks are known (the page's own
     * files among them). Without a track in that language, the TV's caption settings decide, as before.
     */
    private fun applySubtitles(tracks: Tracks) {
        val language = subtitlesWanted ?: return
        if (tracks.isEmpty) return
        subtitlesWanted = null
        val offered = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it) }.map { g to it } }
        val i = PlayerChoices.match(language, offered.map { (g, i) ->
            val f = g.getTrackFormat(i)
            PlayerChoices.Track(f.language, forced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0, commentary = f.roleFlags and C.ROLE_FLAG_COMMENTARY != 0)
        }) ?: return
        val (g, track) = offered[i]
        set { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, track)) }
    }

    /** Keeps [picked] for the next videos; a track without a language leaves that choice to the TV again. */
    private fun remember(picked: PlayerChoices) {
        choices = picked
        listener.onChoicesChanged(picked)
    }

    fun audios(): List<Choice> = groups(C.TRACK_TYPE_AUDIO).flatMap { g ->
        (0 until g.length).filter(g::isTrackSupported).map { i ->
            val f = g.getTrackFormat(i)
            Choice(listOfNotNull(f.label, languageName(f.language)).distinct().joinToString(" · ").ifEmpty { "#${i + 1}" },
                g.isTrackSelected(i)) {
                remember(choices.copy(audio = PlayerChoices.tag(f.language)))
                set { setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
            }
        }
    }.distinctBy { it.label }

    /** "Off", then every text track. */
    fun subtitles(off: String): List<Choice> {
        val p = player ?: return emptyList()
        val disabled = C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes
        val tracks = groups(C.TRACK_TYPE_TEXT).flatMap { g ->
            (0 until g.length).filter(g::isTrackSupported).map { i ->
                val f = g.getTrackFormat(i)
                Choice(f.label ?: languageName(f.language) ?: "#${i + 1}", !disabled && g.isTrackSelected(i)) {
                    remember(choices.copy(subtitles = PlayerChoices.tag(f.language)))
                    set { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)) }
                }
            }
        }
        val anyOn = tracks.any { it.selected }
        return listOf(Choice(off, !anyOn) {
            remember(choices.copy(subtitles = PlayerChoices.OFF))
            set { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true) }
        }) + tracks
    }

    private fun toggleSubtitles() {
        val list = subtitles("")
        if (list.size < 2) return
        val mine = languageName(java.util.Locale.getDefault().language)
        if (list.drop(1).any { it.selected }) list[0].apply()
        else (list.drop(1).firstOrNull { mine != null && it.label.contains(mine) } ?: list[1]).apply()
    }

    private fun set(change: androidx.media3.common.TrackSelectionParameters.Builder.() -> androidx.media3.common.TrackSelectionParameters.Builder) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().change().build()
        main.post(report)
    }

    // "Video info": what this player knows, which is more than the page tells.

    /** Video, decoder, frames shown / dropped and sound; null where not known yet. */
    fun info(): List<String?> {
        val p = player ?: return emptyList()
        val v = p.videoFormat
        val a = p.audioFormat
        val counters = p.videoDecoderCounters
        return listOf(
            v?.let {
                listOfNotNull("${it.width} × ${it.height}", codecName(it),
                    it.bitrate.takeIf { b -> b > 0 }?.let { b -> String.format(java.util.Locale.ROOT, "%.1f Mbit/s", b / 1e6) }, rangeOf(it)).joinToString(" · ")
            },
            decoder?.let { context.getString(if (Decoders.isHardware(it)) R.string.decoder_hardware else R.string.decoder_software, it) },
            counters?.let { "${it.renderedOutputBufferCount} / ${it.droppedBufferCount}" },
            a?.let { listOfNotNull(codecName(it), it.channelCount.takeIf { c -> c > 0 }?.let { c -> "$c ch" }, languageName(it.language)).joinToString(" · ") },
        )
    }

    /** Test builds: frames shown and dropped, the picture and the TV's mode, for smoothness checks on a TV. */
    private val counters: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val c = p.videoDecoderCounters
            val v = p.videoFormat
            val d = context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            android.util.Log.d("SofaFrames", "pos=${p.currentPosition} playing=${p.isPlaying} rendered=${c?.renderedOutputBufferCount} " +
                "dropped=${c?.droppedBufferCount} maxConsecDrop=${c?.maxConsecutiveDroppedBufferCount} " +
                "fmt=${v?.width}x${v?.height}@${v?.frameRate} dec=$decoder " +
                "display=${d?.mode?.let { "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }}")
            main.postDelayed(this, COUNTERS_MS)
        }
    }

    private val report: Runnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            val duration = p.duration.takeIf { it != C.TIME_UNSET && !p.isCurrentMediaItemLive } ?: 0L
            val text = groups(C.TRACK_TYPE_TEXT)
            listener.onState(VideoState(p.currentPosition / 1000.0, duration / 1000.0, !p.playWhenReady, p.playbackParameters.speed.toDouble(),
                text.sumOf { g -> (0 until g.length).count(g::isTrackSupported) },
                C.TRACK_TYPE_TEXT !in p.trackSelectionParameters.disabledTrackTypes && text.any { it.isSelected }))
            main.removeCallbacks(this)
            if (ready) main.postDelayed(this, 500)
        }
    }

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && readyWanted) {
                ready = true
                readyWanted = false
                listener.onReady()
            }
            if (state == Player.STATE_ENDED) listener.onEnded()
            main.post(report)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener.onPlaying(isPlaying)
            main.post(report)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!stepDown(error)) listener.onError(error.errorCodeName, failureDetail(error), ready)
        }

        override fun onVideoSizeChanged(size: VideoSize) = view.setVideoSize(size.width, size.height, size.pixelWidthHeightRatio)

        override fun onCues(cues: CueGroup) {
            view.showText(cues.cues.mapNotNull { it.text }.joinTo(android.text.SpannableStringBuilder(), "\n").takeIf { it.isNotEmpty() })
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!tracks.isEmpty) knownTracks = tracks
            keepToPanel(tracks)
            applyRemembered(tracks)
            applySubtitles(tracks)
            main.post(report)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long,
                                               initializationDurationMs: Long) {
            decoder = decoderName
            checkDecoder()
        }

        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format,
                                               decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            videoMime = format.sampleMimeType
            steppedFrom?.let { (from, why) -> listener.onSteppedDown(from, describe(format), why) }
            steppedFrom = null
            checkDecoder()
            listener.onFrameRate(maxOf(format.frameRate, variants.maxOfOrNull { it.frameRate } ?: 0f).takeIf { it > 0 } ?: 0f)
        }
    }

    private var videoMime: String? = null
    private var decoderChecked = false

    /** Tells [Listener.onSoftwareDecoder] once both the decoder and the video's format are known. */
    private fun checkDecoder() {
        val name = decoder ?: return
        val mime = videoMime ?: return
        if (decoderChecked) return
        decoderChecked = true
        if (!Decoders.isHardware(name) && Decoders.hardwareFor(mime)) {
            if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", "Sofa Player decodes $mime in software ($name)")
            val p = player
            main.post { if (player === p) listener.onSoftwareDecoder() }
        }
    }

    // Decoders that fail: a TV may list a codec or size its decoder can't play (such as a stream's 4K AV1 picture),
    // or the last video's decoder may not be free yet. Sofa Player goes on with the stream's other pictures, and
    // hands back to the page only when none is left.

    /** The pictures that didn't decode in this video; not kept for the next one, which may play. */
    private val failed = HashSet<Pair<androidx.media3.common.TrackGroup, Int>>()
    private var initRetried = false
    /** The picture that didn't decode, said once the next one plays ([Listener.onSteppedDown]). */
    private var steppedFrom: Pair<String, Step>? = null
    /** The stream's tracks as last listed. */
    private var knownTracks = Tracks.EMPTY

    /** Whether [error] was a video decoder's and Sofa Player goes on, with the same picture again or another one. */
    private fun stepDown(error: PlaybackException): Boolean {
        val p = player ?: return false
        if (error.errorCode !in DECODER_ERRORS) return false
        val format = (error as? ExoPlaybackException)?.rendererFormat?.takeIf { MimeTypes.isVideo(it.sampleMimeType) } ?: return false
        // Right after a video, its decoder may still be busy: once, the same picture again a moment later.
        if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED && !initRetried) {
            initRetried = true
            log("Sofa Player: decoder init failed for ${describe(format)}, trying it again")
            main.postDelayed({ if (player === p) p.prepare() }, INIT_RETRY_MS)
            return true
        }
        val tracks = videoTracks()
        val formats = tracks.map { (g, i) -> g.getTrackFormat(i) }
        val at = trackOf(format, formats)
            ?: return false.also { log("Sofa Player: ${describe(format)} (${format.id}) isn't among the pictures ${formats.map { "${it.id} ${it.width}x${it.height} ${it.codecs}" }}") }
        // A decoder that can't do a codec at one size won't do it larger: those go too, so Auto doesn't stall on each.
        val area = formats[at].width * formats[at].height
        formats.indices.filter { it == at || codecName(formats[it]) == codecName(formats[at]) && formats[it].width * formats[it].height >= area }
            .forEach { failed += tracks[it].let { (g, i) -> g.mediaTrackGroup to i } }
        val gone = tracks.indices.filter { (tracks[it].first.mediaTrackGroup to tracks[it].second) in failed }.toSet()
        if (!goOnWithout(tracks, gone, describe(format), Step.FAILED)) return false
        log("Sofa Player: ${describe(format)} didn't decode (${error.errorCodeName}), going on without it")
        p.prepare()
        return true
    }

    /** Why a picture was left ([Listener.onSteppedDown]). */
    enum class Step { FAILED, SOFTWARE }

    /**
     * After the retry for a hardware decoder got software again: Sofa Player keeps to pictures a TV's processor can
     * decode in time (Variants.fitsSoftware), going down from the one playing if it is larger. False when the stream
     * has none: then software would stutter, and the page's player (which had the hardware decoder) is better.
     */
    fun keepToSoftware(): Boolean {
        val p = player ?: return false
        val tracks = videoTracks()
        val formats = tracks.map { (g, i) -> g.getTrackFormat(i) }
        val gone = formats.indices.filter { !Variants.fitsSoftware(qualityOf(formats[it]), formats[it].frameRate) }.toSet()
        if (gone.isEmpty()) return true
        val now = p.videoFormat?.takeIf { f -> !Variants.fitsSoftware(qualityOf(f), f.frameRate) }
        if (!goOnWithout(tracks, gone, now?.let(::describe), Step.SOFTWARE)) return false
        log("Sofa Player: software decoder, keeping to ${tracks.size - gone.size} of ${tracks.size} pictures")
        return true
    }

    /** The video pictures this TV may play, as last listed: after an error none are listed until prepared again. */
    private fun videoTracks() = knownTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        .flatMap { g -> (0 until g.length).filter { g.isTrackSupported(it, true) }.map { g to it } }

    /**
     * Sofa Player goes on with the pictures of [tracks] not at [gone], from now on (Sofa's own limit on Auto, and a
     * pick by hand moves to the closest below); [from], the picture left, is said once the next one plays. False
     * when none is left.
     */
    private fun goOnWithout(tracks: List<Pair<Tracks.Group, Int>>, gone: Set<Int>, from: String?, why: Step): Boolean {
        val p = player ?: return false
        val formats = tracks.map { (g, i) -> g.getTrackFormat(i) }
        // Auto keeps to what it may take anyway: no "may stutter" pictures, and within the panel's range while one is left.
        val beyond = tracks.indices.filter { !tracks[it].first.isTrackSupported(tracks[it].second) }
        val limit = panelLimit
        val outside = if (limit == null) emptyList() else tracks.indices.filter { tracks[it].let { (g, i) -> limit.mediaTrackGroup != g.mediaTrackGroup || i !in limit.trackIndices } }
        val qualities = formats.map(::qualityOf)
        fun next(picked: Int?) = Variants.afterFailure(qualities, gone + beyond + outside, picked) ?: Variants.afterFailure(qualities, gone + beyond, picked)
        val handPick = p.trackSelectionParameters.overrides.entries.firstOrNull { (g, o) -> g.type == C.TRACK_TYPE_VIDEO && o != limit }?.value
        val picked = handPick?.let { o -> tracks.indexOfFirst { (g, i) -> g.mediaTrackGroup == o.mediaTrackGroup && i == o.trackIndices.firstOrNull() } }?.takeIf { it >= 0 }
        val auto = next(null) ?: return false.also { log("Sofa Player: no picture left without ${gone.size} of ${tracks.size}") }
        val chosen = if (picked != null) next(picked) ?: return false else auto
        // One group per override: the one holding the largest picture left (DASH may give each codec its own group).
        fun override(keep: List<Int>): TrackSelectionOverride {
            val group = keep.maxBy { formats[it].height }.let { tracks[it].first }
            return TrackSelectionOverride(group.mediaTrackGroup, keep.filter { tracks[it].first === group }.map { tracks[it].second })
        }
        // Sofa's own limit from now on, so Auto (also chosen later in the menu) never comes back to a failed picture.
        panelLimit = override(auto.keep)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(if (chosen.byHand) override(chosen.keep) else panelLimit!!).build()
        steppedFrom = from?.let { it to why }
        return true
    }

    /**
     * Which of [formats] (the stream's) a decoder got as [format]: by id or bitrate, else by codec and width at the
     * nearest height, since a decoder may report the coded size (1280 × 544 for a 1280 × 536 picture).
     */
    private fun trackOf(format: Format, formats: List<Format>): Int? {
        formats.indexOfFirst { format.id != null && it.id == format.id }.takeIf { it >= 0 }?.let { return it }
        formats.indexOfFirst { format.bitrate > 0 && it.bitrate == format.bitrate }.takeIf { it >= 0 }?.let { return it }
        return formats.withIndex().filter { it.value.width == format.width && codecName(it.value) == codecName(format) }
            .minByOrNull { kotlin.math.abs(it.value.height - format.height) }?.index
    }

    /** For a failed decoder: its picture and its name, for the notice. */
    private fun failureDetail(error: PlaybackException): String? {
        if (error.errorCode !in DECODER_ERRORS) return null
        val format = (error as? ExoPlaybackException)?.rendererFormat
        val name = when (val cause = error.cause) {
            is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name
            is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException -> cause.codecInfo?.name
            else -> null
        }
        return listOfNotNull(format?.let(::describe), name).joinToString(", ").ifEmpty { null }
    }

    /** "H.264 1920 × 1080", with the rate above 30 fps ("… 60 fps"), where a step down may keep the size. */
    private fun describe(f: Format) = listOfNotNull(codecName(f) ?: f.sampleMimeType, if (f.width > 0) "${f.width} × ${f.height}" else null,
        f.frameRate.takeIf { it > 30.5f }?.let { "%.0f fps".format(java.util.Locale.ROOT, it) }).joinToString(" ")

    private fun log(message: String) {
        if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", message)
    }

    /**
     * Media3's renderers. Test builds can make tall pictures fail as a TV's decoder might: a height in the file
     * "fail-video" of the app's files (`echo 1600 > files/fail-video` through run-as), for the step down above. And
     * they can get software decoders first, as when the page's video holds the hardware one: "once" in the file
     * "soft-video" for the next start only (the second try then gets hardware), "always" for every start
     * ([keepToSoftware]).
     */
    private fun renderers(): DefaultRenderersFactory {
        val failAt = if (app.sofabrowser.BuildConfig.DEBUG) runCatching { java.io.File(context.filesDir, "fail-video").readText().trim().toInt() }.getOrNull() else null
        val factory = if (failAt == null) DefaultRenderersFactory(context) else object : DefaultRenderersFactory(context) {
            override fun getCodecAdapterFactory(): MediaCodecAdapter.Factory {
                val real = super.getCodecAdapterFactory()
                return MediaCodecAdapter.Factory { c ->
                    if (MimeTypes.isVideo(c.format.sampleMimeType) && c.format.height >= failAt) throw java.io.IOException("test build: fail-video $failAt")
                    real.createAdapter(c)
                }
            }
        }
        if (app.sofabrowser.BuildConfig.DEBUG) softwareFirst(factory)
        return factory
    }

    private fun softwareFirst(factory: DefaultRenderersFactory) {
        val file = java.io.File(context.filesDir, "soft-video")
        val mode = runCatching { file.readText().trim() }.getOrNull() ?: return
        if (mode == "once") file.delete()
        log("Sofa Player: test build, software decoders first ($mode)")
        factory.setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling).sortedBy { it.hardwareAccelerated }
        }
    }

    /** Sofa's own limit on Auto ([keepToPanel], and pictures that failed: [stepDown]), which the menu doesn't count as a pick. */
    private var panelLimit: TrackSelectionOverride? = null

    /**
     * When a stream mixes HDR and SDR versions, Auto keeps to the HDR ones the panel shows (HDR is the default on a
     * TV that has it), else to the SDR ones, so a film neither turns SDR halfway nor shows washed out
     * ([Variants.autoKeeps], from the master's word or its init segments'). Without that word, a panel without HDR
     * still avoids the HDR versions Media3 knows of. A quality picked by hand is left alone.
     */
    private fun keepToPanel(tracks: Tracks) {
        val p = player ?: return
        if (pickedByHand(p) || panelLimit != null) return
        val panel = panel()
        for (g in tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }) {
            val supported = (0 until g.length).filter { g.isTrackSupported(it) }
            val byTrack = supported.associateWith { variantOf(g.getTrackFormat(it)) }
            val keep = if (byTrack.values.all { it != null }) {
                Variants.autoKeeps(byTrack.values.filterNotNull(), panel)?.let { k -> supported.filter { byTrack[it] in k } }
            } else if (!panel.hdr10 && !panel.hlg && !panel.dolbyVision) {
                supported.filter { !ColorInfo.isTransferHdr(g.getTrackFormat(it).colorInfo) }
            } else null
            if (keep.isNullOrEmpty() || keep.size == supported.size) continue
            val limit = TrackSelectionOverride(g.mediaTrackGroup, keep)
            panelLimit = limit
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setOverrideForType(limit).build()
            if (app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", "Auto keeps to ${keep.size} of ${supported.size} pictures for $panel")
            return
        }
    }

    /** The HDR formats the TV's panel shows. */
    @Suppress("DEPRECATION")
    private fun panel(): Variants.Panel {
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val types = display?.hdrCapabilities?.supportedHdrTypes ?: intArrayOf()
        return Variants.Panel(hdr10 = Display.HdrCapabilities.HDR_TYPE_HDR10 in types, hlg = Display.HdrCapabilities.HDR_TYPE_HLG in types,
            dolbyVision = Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types)
    }

    companion object {
        private const val TIMEOUT_MS = 10_000
        private const val BUFFER_BYTES = 40 * 1024 * 1024
        private const val COUNTERS_MS = 5000L
        /** How long a decoder that failed to start gets to be free before Sofa Player asks again (ms). */
        private const val INIT_RETRY_MS = 500L
        private val DECODER_ERRORS = setOf(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)

        fun codecName(f: Format): String? {
            val c = (f.codecs ?: f.sampleMimeType ?: return null).lowercase()
            return when {
                c.startsWith("avc") || c.contains("avc") -> "H.264"
                c.startsWith("hvc") || c.startsWith("hev") || c.contains("hevc") -> "HEVC"
                c.startsWith("dvh") || c.startsWith("dva") || c.contains("dolby-vision") -> "Dolby Vision"
                c.startsWith("av01") || c.contains("av01") -> "AV1"
                c.startsWith("vp09") || c.startsWith("vp9") || c.contains("vp9") -> "VP9"
                c.startsWith("mp4a") || c.contains("aac") -> "AAC"
                c.startsWith("ac-3") || c.contains("ac3") -> "Dolby Digital"
                c.startsWith("ec-3") || c.contains("eac3") -> "Dolby Digital Plus"
                c.startsWith("opus") || c.contains("opus") -> "Opus"
                else -> null
            }
        }

        fun hdrName(f: Format): String? = when (f.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> if (codecName(f) == "Dolby Vision") null else "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }

        /** The language's own name ("Français"), or null when the stream doesn't say ("und"). */
        fun languageName(code: String?): String? = code?.takeUnless { it.isBlank() || it == "und" }?.let { c ->
            java.util.Locale.forLanguageTag(c).let { it.getDisplayLanguage(it) }.replaceFirstChar { it.uppercase() }.ifEmpty { c }
        }
    }
}

/** Serves the master playlist from the page's copy and everything else from the network. */
@OptIn(UnstableApi::class)
private class PageCopyDataSource(private val master: Uri, private val bytes: ByteArray, private val upstream: DataSource) : DataSource {
    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri == master) ByteArrayDataSource(bytes) else upstream
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        current?.close()
        current = null
    }
}
