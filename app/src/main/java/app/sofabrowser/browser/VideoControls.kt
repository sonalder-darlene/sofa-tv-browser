package app.sofabrowser.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sofabrowser.R
import app.sofabrowser.ui.Ui
import app.sofabrowser.ui.px
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Remote control for HTML5 video. A document-start script in every frame relays commands from the top frame
 * down to the frame holding the (fullscreen) video and reports its state back through a message listener, so
 * no Java object is exposed to pages.
 *
 * Nothing here has a fixed, guessable name: the listener object and the message key come from a per-session
 * random [PageKey], and the script keeps its state in a closure, so pages can't probe for "a Sofa user".
 */
object VideoRelay {
    private val keys = java.util.WeakHashMap<WebView, PageKey>()

    private fun script(key: PageKey) = """
(() => {
  const K = ${JSONObject.quote(key.id)}, L = ${JSONObject.quote(key.video)};
  const fsEl = () => document.fullscreenElement || document.webkitFullscreenElement;
  const pick = () => {
    const fe = fsEl();
    if (fe) {
      if (fe.tagName === 'VIDEO') return fe;
      if (fe.tagName === 'IFRAME') return null;
      const inner = fe.querySelector('video');
      if (inner) return inner;
    }
    let best = null, bestScore = 0;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect();
      const score = r.width * r.height * (v.paused ? 1 : 4);
      if (score > bestScore) { best = v; bestScore = score; }
    }
    return best;
  };
  let watched = null, last = 0, hideStyle = null;
  const report = (force) => {
    const v = watched, out = window[L];
    if (!v || !out) return;
    const now = Date.now();
    if (!force && now - last < 500) return;
    last = now;
    out.postMessage(JSON.stringify({ t: v.currentTime || 0, d: isFinite(v.duration) ? v.duration : 0, p: v.paused, r: v.playbackRate }));
  };
  const events = ['timeupdate', 'play', 'pause', 'playing', 'waiting', 'seeked', 'ratechange', 'durationchange', 'loadedmetadata'];
  const onEvent = (e) => report(e.type !== 'timeupdate');
  // While Sofa's bar drives a video, the browser's own controls (always shown for a fullscreen <video>) would
  // sit under it; hide them until done.
  const setNativeControls = (show) => {
    if (show) { if (hideStyle) hideStyle.remove(); hideStyle = null; return; }
    if (hideStyle) return;
    hideStyle = document.createElement('style');
    hideStyle.textContent = 'video::-webkit-media-controls{display:none!important}';
    (document.head || document.documentElement).appendChild(hideStyle);
  };
  const unwatch = () => {
    if (!watched) return;
    events.forEach((e) => watched.removeEventListener(e, onEvent));
    setNativeControls(true);
    watched = null;
  };
  const watch = (v) => {
    if (watched === v) return report(true);
    unwatch();
    watched = v;
    setNativeControls(false);
    events.forEach((e) => v.addEventListener(e, onEvent));
    report(true);
  };
  const forward = (msg) => {
    const fe = fsEl();
    if (fe && fe.tagName === 'IFRAME') { try { fe.contentWindow.postMessage(msg, '*'); } catch (e) {} return; }
    for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage(msg, '*'); } catch (e) {} }
  };
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (!m || m.k !== K) return;
    if (m.cmd === 'unwatch') { unwatch(); forward(m); return; }
    const v = pick();
    if (!v) { forward(m); return; }
    switch (m.cmd) {
      case 'watch': watch(v); break;
      case 'toggle': if (v.paused) v.play(); else v.pause(); break;
      case 'play': v.play(); break;
      case 'pause': v.pause(); break;
      case 'seekBy': v.currentTime = Math.max(0, Math.min(isFinite(v.duration) ? v.duration : 1e9, v.currentTime + m.v)); break;
      case 'seekTo': v.currentTime = m.v; break;
      case 'rate': v.playbackRate = m.v; break;
      case 'exit': if (document.exitFullscreen) document.exitFullscreen().catch(() => {}); break;
    }
  });
})();
""".trimIndent()

    fun install(web: WebView, key: PageKey, onState: (VideoState) -> Unit) {
        keys[web] = key
        WebViews.addDocumentStartScript(web, script(key))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, key.video, setOf("*")) { _, message, _, _, _ ->
                runCatching {
                    val o = JSONObject(message.data ?: return@runCatching)
                    onState(VideoState(o.optDouble("t"), o.optDouble("d"), o.optBoolean("p", true), o.optDouble("r", 1.0)))
                }
            }
        }
    }

    fun send(web: WebView?, cmd: String, value: Double? = null) {
        val key = keys[web ?: return] ?: return
        val v = value?.let { ",v:$it" } ?: ""
        web.evaluateJavascript("window.postMessage({k:${JSONObject.quote(key.id)},cmd:'$cmd'$v},'*')", null)
    }
}

/** Per-WebView random names for everything Sofa injects into pages. */
class PageKey {
    private fun random(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz"[rng.nextInt(26)] }.joinToString("")
    /** Marks Sofa's own postMessage commands. */
    val id = random(16)
    /** Name of the injected object that receives video state. */
    val video = "_" + random(12)
    /** Name of the injected object that receives page downloads. */
    val downloads = "_" + random(12)

    private companion object {
        val rng = java.security.SecureRandom()
    }
}

data class VideoState(val position: Double, val duration: Double, val paused: Boolean, val rate: Double)

/** The fullscreen control bar: -10 s, play/pause, +10 s, scrub bar, speed, pointer and exit. */
@SuppressLint("ViewConstructor")
class VideoBar(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun command(cmd: String, value: Double? = null)
        fun usePointer()
        fun exitFullscreen()
    }

    private val playPause: ImageView
    private val position: TextView
    private val durationText: TextView
    private val scrub: Scrubber
    private val speed: TextView
    private var state = VideoState(0.0, 0.0, true, 1.0)
    private var rateIndex = 1
    private val hide = Runnable { visibility = GONE }

    init {
        orientation = VERTICAL
        gravity = Gravity.BOTTOM
        visibility = GONE
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, 0xCC000000.toInt()))
        setPadding(96.px, 48.px, 96.px, 40.px)

        val timeline = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        position = Ui.text(context, 26, Color.WHITE).apply { text = time(0.0) }
        durationText = Ui.text(context, 26, Ui.DARK.textMid).apply { text = time(0.0) }
        scrub = Scrubber(context)
        timeline.addView(position)
        timeline.addView(scrub, LayoutParams(0, 48.px, 1f).apply { marginStart = 24.px; marginEnd = 24.px })
        timeline.addView(durationText)
        addView(timeline)

        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16.px, 0, 0) }
        buttons.addView(button(R.drawable.ic_fast_rewind, R.string.rewind_10) { actions.command("seekBy", -10.0) })
        playPause = button(R.drawable.ic_pause, R.string.play_pause) { actions.command("toggle") }
        buttons.addView(playPause)
        buttons.addView(button(R.drawable.ic_fast_forward, R.string.forward_10) { actions.command("seekBy", 10.0) })
        buttons.addView(View(context), LayoutParams(0, 1, 1f))
        speed = Ui.text(context, 26, Color.WHITE).apply {
            text = rateLabel(1.0)
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Color.TRANSPARENT, Ui.DARK.focus, 36)
            setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) Color.BLACK else Color.WHITE) }
            setOnClickListener {
                rateIndex = (rateIndex + 1) % RATES.size
                actions.command("rate", RATES[rateIndex])
                text = rateLabel(RATES[rateIndex])
            }
        }
        buttons.addView(speed, LayoutParams(120.px, 72.px).apply { marginStart = 16.px })
        buttons.addView(button(R.drawable.ic_mouse, R.string.pointer) { actions.usePointer() })
        buttons.addView(button(R.drawable.ic_close, R.string.exit_fullscreen) { actions.exitFullscreen() })
        addView(buttons)
    }

    private fun button(icon: Int, label: Int, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        contentDescription = context.getString(label)
        isFocusable = true
        isClickable = true
        setPadding(14.px, 14.px, 14.px, 14.px)
        background = Ui.fillSelector(Color.TRANSPARENT, Ui.DARK.focus, 36)
        setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
        setOnFocusChangeListener { _, has -> setColorFilter(if (has) Color.BLACK else Color.WHITE, PorterDuff.Mode.SRC_IN) }
        setOnClickListener { onClick(); show() }
        layoutParams = LayoutParams(72.px, 72.px).apply { marginStart = 16.px }
    }

    val showing get() = visibility == VISIBLE

    fun show(focusScrub: Boolean = false) {
        val wasHidden = !showing
        visibility = VISIBLE
        if (wasHidden || findFocus() == null) {
            // FromTouch: leaves touch mode (a touch or mouse may have entered it), where plain requestFocus
            // fails. Posted again because Chromium's fullscreen view grabs focus as it is attached.
            val target = if (focusScrub) scrub else playPause
            target.requestFocusFromTouch()
            post { if (!hasFocus()) target.requestFocusFromTouch() }
        }
        removeCallbacks(hide)
        postDelayed(hide, 5000)
    }

    fun hideNow() {
        removeCallbacks(hide)
        visibility = GONE
    }

    fun update(s: VideoState) {
        state = s
        playPause.setImageResource(if (s.paused) R.drawable.ic_play_arrow else R.drawable.ic_pause)
        if (!scrub.scrubbing) {
            position.text = time(s.position)
            scrub.progress = if (s.duration > 0) (s.position / s.duration).toFloat() else 0f
        }
        durationText.text = if (s.duration > 0) time(s.duration) else context.getString(R.string.live)
        val i = RATES.indexOfFirst { abs(it - s.rate) < 0.01 }
        if (i >= 0) { rateIndex = i; speed.text = rateLabel(RATES[i]) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (showing && event.action == KeyEvent.ACTION_DOWN) { removeCallbacks(hide); postDelayed(hide, 5000) }
        return super.dispatchKeyEvent(event)
    }

    /** Focusable timeline. Left/Right move a pending target in growing steps; it is committed 700 ms later. */
    private inner class Scrubber(context: Context) : View(context) {
        var progress = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        var scrubbing = false
        private var target = 0.0
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4DFFFFFF }
        private val done = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BRAND }
        private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val commit = Runnable {
            scrubbing = false
            actions.command("seekTo", target)
        }

        init {
            isFocusable = true
            setOnFocusChangeListener { _, _ -> invalidate() }
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            val dir = when (keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
            if (dir != 0 && state.duration > 0) {
                if (!scrubbing) { target = state.position; scrubbing = true }
                // 10 s steps, growing to 60 s while the key is held.
                val step = min(60.0, 10.0 * (1 + event.repeatCount / 8))
                target = (target + dir * max(step, state.duration / 200)).coerceIn(0.0, state.duration)
                progress = (target / state.duration).toFloat()
                position.text = time(target)
                removeCallbacks(commit)
                postDelayed(commit, 700)
                return true
            }
            if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && scrubbing) {
                removeCallbacks(commit)
                commit.run()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        override fun onDraw(canvas: Canvas) {
            val cy = height / 2f
            val hgt = (if (isFocused) 10 else 6).px.toFloat()
            canvas.drawRoundRect(0f, cy - hgt / 2, width.toFloat(), cy + hgt / 2, hgt, hgt, track)
            canvas.drawRoundRect(0f, cy - hgt / 2, width * progress, cy + hgt / 2, hgt, hgt, done)
            if (isFocused) canvas.drawCircle(width * progress, cy, 14.px.toFloat(), thumb)
        }
    }

    companion object {
        val RATES = doubleArrayOf(0.5, 1.0, 1.25, 1.5, 1.75, 2.0)

        fun rateLabel(r: Double) = (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) + "x"

        fun time(seconds: Double): String {
            val s = seconds.toLong().coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/** The fullscreen layer: the page's fullscreen view under a pointer layer, with the control bar on top. */
@SuppressLint("ViewConstructor")
class FullscreenLayer(context: Context, val bar: VideoBar) : FrameLayout(context) {
    val pointer = CursorLayout(context).apply { cursorEnabled = false }

    init {
        setBackgroundColor(Color.BLACK)
        addView(pointer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        visibility = GONE
    }

    fun attach(view: View) {
        // The page's fullscreen view would otherwise take focus from the bar; the pointer reaches it by touch.
        pointer.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        pointer.removeAllViews()
        pointer.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pointer.cursorEnabled = false
        visibility = VISIBLE
    }

    fun detach() {
        bar.hideNow()
        pointer.cursorEnabled = false
        pointer.removeAllViews()
        visibility = GONE
    }
}
