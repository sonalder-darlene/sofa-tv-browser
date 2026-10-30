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
import app.sofabrowser.player.PlayerSurface
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

    private fun script(key: PageKey, panelScale: Double) = """
(() => {
  const K = ${JSONObject.quote(key.id)}, L = ${JSONObject.quote(key.video)}, PANEL = $panelScale;
  const fsEl = () => document.fullscreenElement || document.webkitFullscreenElement;
  // Players size their quality to devicePixelRatio × their box, and Android draws apps at 1080p even on a 4K
  // panel, so "auto" stops at 1080p. In fullscreen only, report the panel's pixels instead; pages' layout,
  // images and canvases outside fullscreen see the real value.
  // The getter wraps the native one in a Proxy, so it still reads as native code to a page that looks.
  const ownRatio = Object.getOwnPropertyDescriptor(window, 'devicePixelRatio');
  const realRatio = () => (ownRatio && ownRatio.get ? ownRatio.get.call(window) : devicePixelRatio);
  if (PANEL > 1 && ownRatio && ownRatio.get) {
    try {
      Object.defineProperty(window, 'devicePixelRatio', { configurable: true, enumerable: ownRatio.enumerable,
        get: new Proxy(ownRatio.get, { apply: (f, self, args) => {
          const r = Reflect.apply(f, self, args);
          return fsEl() ? r * PANEL : r;
        } }) });
    } catch (e) {}
  }
  // The video Watch chose: once its player box is fullscreen, the bar drives it, not another one in the box.
  let chosen = null;
  const pick = () => {
    const fe = fsEl();
    if (fe) {
      if (fe.tagName === 'VIDEO') return fe;
      if (fe.tagName === 'IFRAME') return null;
      if (chosen && fe.contains(chosen)) return chosen;
      let inner = null, innerArea = 0;
      for (const v of fe.querySelectorAll('video')) {
        const r = v.getBoundingClientRect();
        if (r.width * r.height > innerArea) { inner = v; innerArea = r.width * r.height; }
      }
      if (inner) return inner;
    }
    // Fullscreen just ended (Back from Sofa Player): its video carries on, not a muted one playing elsewhere on the page.
    if (watched && watched.isConnected) return watched;
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
    const tracks = textTracks(v);
    out.postMessage(JSON.stringify({ t: v.currentTime || 0, d: isFinite(v.duration) ? v.duration : 0, p: v.paused, r: v.playbackRate,
      tt: tracks.length, ts: tracks.some((t) => t.mode === 'showing'), w: F }));
  };
  // Subtitles Sofa can switch: the video's own text tracks. Players that draw subtitles themselves aren't reachable.
  const textTracks = (v) => [...(v.textTracks || [])].filter((t) => t.kind === 'subtitles' || t.kind === 'captions');
  const toggleSubtitles = (v) => {
    const tracks = textTracks(v);
    if (!tracks.length) return;
    if (tracks.some((t) => t.mode === 'showing')) { tracks.forEach((t) => { t.mode = 'disabled'; }); return; }
    const lang = (navigator.language || '').slice(0, 2);
    (tracks.find((t) => (t.language || '').slice(0, 2) === lang) || tracks[0]).mode = 'showing';
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
  // A site's player that sizes its video in fixed pixels leaves it small in a fullscreen box, since only its own
  // button knows to resize it: then the video alone fills the screen, until fullscreen ends.
  const fill = (v, box) => {
    if (fsEl() !== box) return;
    const r = v.getBoundingClientRect();
    if (r.width >= innerWidth * 0.6 || r.height >= innerHeight * 0.6) return;
    const old = v.getAttribute('style');
    for (const [p, val] of [['position', 'fixed'], ['inset', '0'], ['width', '100%'], ['height', '100%'],
      ['object-fit', 'contain'], ['background', '#000'], ['z-index', '2147483647']]) v.style.setProperty(p, val, 'important');
    const undo = () => {
      if (fsEl()) return;
      if (old === null) v.removeAttribute('style'); else v.setAttribute('style', old);
      document.removeEventListener('fullscreenchange', undo);
    };
    document.addEventListener('fullscreenchange', undo);
  };
  // "Match frame rate": the media times of the frames shown, about 5 s of them, for the app to work out the rate
  // (FrameRate.estimate). Once per watch; a time going backwards or jumping (seek, ad, new source) starts over.
  let sampler = null;
  const sample = (v) => {
    if (!v.requestVideoFrameCallback) return;
    const s = { stop: false, times: [] };
    sampler = s;
    const onFrame = (now, meta) => {
      if (s.stop) return;
      const t = meta.mediaTime, last = s.times[s.times.length - 1];
      if (last !== undefined && (t <= last || t - last > 1)) s.times = [];
      s.times.push(t);
      const out = window[L];
      if (out && s.times.length >= 100 && t - s.times[0] >= 5.5) { out.postMessage(JSON.stringify({ fr: s.times })); return; }
      v.requestVideoFrameCallback(onFrame);
    };
    v.requestVideoFrameCallback(onFrame);
  };
  const unwatch = () => {
    if (!watched) return;
    if (sampler) { sampler.stop = true; sampler = null; }
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
    sample(v);
    report(true);
  };
  const forward = (msg) => {
    const fe = fsEl();
    if (fe && fe.tagName === 'IFRAME') { try { fe.contentWindow.postMessage(msg, '*'); } catch (e) {} return; }
    for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage(msg, '*'); } catch (e) {} }
  };
  // Playback anywhere in this frame, watched or not, so the app can keep the screen on. Media events don't bubble,
  // hence the capture phase; the frame reports under a random id since messages don't say which frame sent them.
  const F = Math.random().toString(36).slice(2);
  const playing = new Set();
  let wasPlaying = false;
  const sendPlaying = () => {
    const now = playing.size > 0, out = window[L];
    if (now === wasPlaying || !out) return;
    wasPlaying = now;
    out.postMessage(JSON.stringify({ f: F, on: now }));
  };
  // "Open videos in Sofa Player": a video the user just started (their press gave this frame its activation), with
  // sound, a minute or more (or live) and about the size of the page's player: not a muted preview, a hero video
  // playing on load, or a short ad. Once per video, so going back to the page doesn't start it over; a site that
  // plays the next video in the same element gives it a new source, and that one is offered again.
  const offered = new WeakMap();
  let started = null;
  const offerStart = (v) => {
    const out = window[L], src = v.currentSrc || v.src || '';
    if (!out || offered.get(v) === src || fsEl() || !(navigator.userActivation && navigator.userActivation.isActive)) return;
    if (v.muted || v.volume === 0 || v.mediaKeys || !(v.duration >= 60)) return;
    const r = v.getBoundingClientRect();
    if (r.width * r.height < innerWidth * innerHeight * 0.2) return;
    offered.set(v, src);
    started = v;
    out.postMessage(JSON.stringify({ st: F }));
  };
  const onMedia = (e) => {
    if (!(e.target instanceof HTMLVideoElement)) return;
    if (e.type === 'play' || e.type === 'playing') playing.add(e.target); else playing.delete(e.target);
    sendPlaying();
    if (e.type === 'playing') offerStart(e.target);
  };
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach((t) => document.addEventListener(t, onMedia, true));
  window.addEventListener('pagehide', () => { playing.clear(); sendPlaying(); });
  // A video removed from the page pauses, but its event no longer reaches the document: check now and then.
  setInterval(() => {
    if (!playing.size) return;
    for (const v of [...playing]) if (!v.isConnected || v.paused || v.ended) playing.delete(v);
    sendPlaying();
  }, 15000);
  // "Video info": what is decoded, frames, and whether the TV reports hardware decoding for the common codecs at
  // this size. The page doesn't expose the stream's codec, so each is asked for; results are kept per size.
  const codecs = (h) => h > 1080
    ? [['H.264', 'video/mp4; codecs="avc1.640033"'], ['VP9', 'video/webm; codecs="vp09.00.51.08"'],
       ['AV1', 'video/mp4; codecs="av01.0.12M.08"'], ['HEVC', 'video/mp4; codecs="hvc1.1.6.L153.90"']]
    : [['H.264', 'video/mp4; codecs="avc1.640028"'], ['VP9', 'video/webm; codecs="vp09.00.40.08"'],
       ['AV1', 'video/mp4; codecs="av01.0.08M.08"'], ['HEVC', 'video/mp4; codecs="hvc1.1.6.L120.90"']];
  const support = new Map();
  const decoders = async (w, h) => {
    const k = w + 'x' + h;
    if (support.has(k)) return support.get(k);
    const list = [];
    for (const [n, type] of codecs(h)) {
      try {
        const r = await navigator.mediaCapabilities.decodingInfo({ type: 'media-source',
          video: { contentType: type, width: w, height: h, bitrate: 8000000, framerate: 30 } });
        list.push({ n, s: r.supported, e: r.powerEfficient });
      } catch (e) {}
    }
    support.set(k, list);
    return list;
  };
  const info = async (v) => {
    const out = window[L];
    if (!out) return;
    const w = v.videoWidth, h = v.videoHeight, q = v.getVideoPlaybackQuality ? v.getVideoPlaybackQuality() : null;
    const c = w && h && navigator.mediaCapabilities ? await decoders(w, h) : [];
    out.postMessage(JSON.stringify({ i: { w, h, dropped: q ? q.droppedVideoFrames : -1, total: q ? q.totalVideoFrames : -1,
      cw: Math.round(v.getBoundingClientRect().width), dpr: devicePixelRatio, sw: screen.width, sh: screen.height,
      iw: innerWidth, c } }));
  };
  // "Watch": find the page's main video across frames. Each frame offers its largest video to its parent, which
  // moves it into its own coordinates, up to the top frame, which hands the offers to the app in view pixels.
  const largest = () => {
    let best = null, bestArea = 0;
    for (const v of document.querySelectorAll('video')) {
      const r = v.getBoundingClientRect(), a = r.width * r.height;
      if (a > bestArea) { best = v; bestArea = a; }
    }
    return best;
  };
  // The player box: the outermost ancestor about the video's size, which holds the site's own controls.
  const playerBox = (v) => {
    const r = v.getBoundingClientRect();
    let box = v;
    for (let p = v.parentElement; p && p !== document.body; p = p.parentElement) {
      const pr = p.getBoundingClientRect();
      if (pr.width > r.width * 1.25 || pr.height > r.height * 1.25) break;
      box = p;
    }
    return box;
  };
  // A layer nobody can see (nearly transparent, with its parents), such as an ad link laid over the whole page to
  // catch the first tap. Watch lets taps through it for a few seconds after its press, long enough for its own tap
  // to reach the video; then the layer takes taps again, as the site made it. A layer one can see is left alone.
  const unseen = (el) => {
    let o = 1;
    for (let e = el; e && e.nodeType === 1; e = e.parentElement) { o *= +getComputedStyle(e).opacity; if (o < 0.1) return true; }
    return false;
  };
  const passed = [];
  let passedTimer = 0;
  const restorePassed = () => {
    for (const [el, value, priority] of passed.splice(0)) {
      if (value) el.style.setProperty('pointer-events', value, priority); else el.style.removeProperty('pointer-events');
    }
  };
  const hitThrough = (x, y, wanted) => {
    let el = document.elementFromPoint(x, y);
    for (let i = 0; i < 8 && el && !wanted(el) && unseen(el); i++) {
      passed.push([el, el.style.getPropertyValue('pointer-events'), el.style.getPropertyPriority('pointer-events')]);
      el.style.setProperty('pointer-events', 'none', 'important');
      el = document.elementFromPoint(x, y);
    }
    if (passed.length) { clearTimeout(passedTimer); passedTimer = setTimeout(restorePassed, 3000); }
    return el;
  };
  // A point where a tap reaches the video or its player box, not something laid over it, such as an ad.
  const tapPoint = (v) => {
    const r = v.getBoundingClientRect(), box = playerBox(v);
    for (const [fx, fy] of [[0.5, 0.5], [0.3, 0.3], [0.7, 0.3], [0.3, 0.7], [0.7, 0.7]]) {
      const x = r.left + r.width * fx, y = r.top + r.height * fy;
      if (x < 0 || y < 0 || x >= innerWidth || y >= innerHeight) continue;
      const el = hitThrough(x, y, (e) => box.contains(e));
      if (el && box.contains(el)) return [x, y];
    }
    return null;
  };
  const offer = (c) => {
    if (window !== window.top) { window.parent.postMessage({ k: K, cmd: 'found', c }, '*'); return; }
    const out = window[L], vv = window.visualViewport;
    if (!out) return;
    const s = realRatio() * (vv ? vv.scale : 1), ox = vv ? vv.offsetLeft : 0, oy = vv ? vv.offsetTop : 0;
    out.postMessage(JSON.stringify({ loc: { fid: c.fid, w: c.w, h: c.h,
      tx: c.tx >= 0 ? (c.tx - ox) * s : -1, ty: c.ty >= 0 ? (c.ty - oy) * s : -1, m: c.m, p: c.p, l: c.l, d: c.d } }));
  };
  const toChildren = (msg) => {
    for (let i = 0; i < window.frames.length; i++) { try { window.frames[i].postMessage(msg, '*'); } catch (e) {} }
  };
  const fetching = new Set();
  const childFrame = (source) => [...document.querySelectorAll('iframe,frame')].find((f) => f.contentWindow === source);
  const locate = () => {
    const v = largest();
    if (v) {
      const r = v.getBoundingClientRect(), p = tapPoint(v);
      offer({ fid: F, x: r.left, y: r.top, w: r.width, h: r.height, tx: p ? p[0] : -1, ty: p ? p[1] : -1,
        m: v.muted || v.volume === 0, p: !v.paused, l: v.loop, d: isFinite(v.duration) ? v.duration : v.duration === Infinity ? -1 : 0 });
    }
    toChildren({ k: K, cmd: 'locate' });
  };
  // Frames learn the key from forwarded commands, so a child's offer is trusted only as far as its own frame: its
  // size is clamped to the frame's, and its tap point must land on the frame, uncovered.
  const fromChild = (source, c) => {
    const f = childFrame(source);
    if (!f) return;
    const r = f.getBoundingClientRect(), dx = r.left + f.clientLeft, dy = r.top + f.clientTop;
    const moved = { fid: String(c.fid), x: c.x + dx, y: c.y + dy, w: Math.min(+c.w || 0, r.width), h: Math.min(+c.h || 0, r.height), tx: -1, ty: -1,
      m: !!c.m, p: !!c.p, l: !!c.l, d: +c.d || 0 };
    if (c.tx >= 0) {
      const tx = c.tx + dx, ty = c.ty + dy;
      if (tx >= r.left && tx < r.right && ty >= r.top && ty < r.bottom && hitThrough(tx, ty, (e) => e === f) === f) {
        moved.tx = tx; moved.ty = ty;
      }
    }
    offer(moved);
  };
  // Scrolls the chosen video into view, then each frame holding it, outward.
  const revealed = () => { if (window !== window.top) window.parent.postMessage({ k: K, cmd: 'revealed' }, '*'); };
  window.addEventListener('message', (e) => {
    const m = e.data;
    if (!m || m.k !== K) return;
    switch (m.cmd) {
      // "Play in…" and Sofa Player: every frame reports its main video's address, where it is, how big, whether it
      // uses DRM, the frame's own address, the subtitle files the page gives the video, and the picture it shows.
      case 'source': {
        const v = watched || largest(), out = window[L];
        if (v && out) {
          const r = v.getBoundingClientRect();
          const tr = [...v.querySelectorAll('track')].filter((t) => t.src && (!t.kind || t.kind === 'subtitles' || t.kind === 'captions'))
            .map((t) => ({ u: t.src, l: t.label || '', g: t.srclang || '', o: !!t.track && t.track.mode === 'showing' }));
          out.postMessage(JSON.stringify({ src: { s: v.currentSrc || v.src || '', t: v.currentTime || 0,
            a: r.width * r.height, drm: !!v.mediaKeys, pg: location.href, tr, w: v.videoWidth || 0, h: v.videoHeight || 0 } }));
        }
        toChildren(m);
        return;
      }
      case 'locate': locate(); return;
      // A stream manifest, fetched again by the frame whose player fetched it first (same origin), so it goes out
      // exactly as the player's request did: some sites turn away any other client. Usually from the cache.
      case 'fetch': {
        const out = window[L];
        if (out && location.origin === m.o && !fetching.has(m.u)) {
          fetching.add(m.u);
          fetch(m.u, { credentials: 'same-origin' })
            .then((r) => r.ok ? r.text().then((b) => ({ u: m.u, f: r.url, b: b.slice(0, 2097152) })) : { u: m.u, s: r.status })
            .catch(() => ({ u: m.u, s: 0 }))
            .then((mf) => { fetching.delete(m.u); out.postMessage(JSON.stringify({ mf })); });
        }
        toChildren(m);
        return;
      }
      case 'found': fromChild(e.source, m.c || {}); return;
      case 'reveal': {
        const v = m.fid === F && largest();
        if (v) { v.scrollIntoView({ block: 'center', inline: 'center' }); revealed(); } else toChildren(m);
        return;
      }
      case 'revealed': { const f = childFrame(e.source); if (f) f.scrollIntoView({ block: 'center', inline: 'center' }); revealed(); return; }
      // Runs right after the app's tap, while this frame still has the tap's user activation; "start" for the video
      // the user just started here (offerStart), whose press still counts.
      case 'go': case 'start': {
        const v = m.fid === F && (m.cmd === 'go' ? largest() : started);
        if (!v) { if (m.fid !== F) toChildren(m); return; }
        if (m.cmd === 'start' && fsEl()) return;
        v.play().catch(() => {});
        chosen = v;
        // The player box, as the site's own fullscreen button does: many players choose their quality from their
        // box's size, and with only the bare video in fullscreen the box stays small (seen as a drop to 480p).
        const box = playerBox(v), fs = box.requestFullscreen || box.webkitRequestFullscreen;
        if (fs) Promise.resolve(fs.call(box)).then(() => setTimeout(() => fill(v, box), 600)).catch(() => {});
        return;
      }
    }
    if (m.cmd === 'unwatch') { unwatch(); forward(m); return; }
    // Addressed to the frame of the watched video (Back from Sofa Player): another frame's own video doesn't take it.
    if (m.fid && m.fid !== F) { forward(m); return; }
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
      case 'info': info(v); break;
      case 'subs': toggleSubtitles(v); report(true); break;
    }
  });
})();
""".trimIndent()

    /**
     * [panelScale] is PanelScale for this TV. [onState] gets the watched video's state; [onPlaying] gets a frame's id
     * and whether any video plays in it; [onInfo] answers the "info" command, and [onCandidate] and [onSource] the
     * "locate" and "source" ones, once per frame with a video. [onFrameTimes] gets the media times of the watched
     * video's frames, once per watch. [onFetched] answers [fetch]: the address asked for, and the final address and
     * text, or nulls when the frame couldn't fetch it, then the origin of the frame that answered. [onStarted] gets the
     * id of a frame where the user just started a video worth opening in Sofa Player; "start" to that frame puts it in
     * fullscreen. All are called on WebView's message thread.
     */
    fun install(web: WebView, key: PageKey, panelScale: Double, onState: (VideoState) -> Unit,
                onPlaying: (String, Boolean) -> Unit, onInfo: (VideoInfo) -> Unit, onCandidate: (WatchTarget.Candidate) -> Unit,
                onSource: (VideoSource) -> Unit, onFrameTimes: (List<Double>) -> Unit,
                onFetched: (String, String?, String?, String) -> Unit, onStarted: (String) -> Unit) {
        keys[web] = key
        WebViews.addDocumentStartScript(web, script(key, panelScale))
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, key.video, setOf("*")) { _, message, sourceOrigin, isMainFrame, _ ->
                // What a frame says about where it is counts only as far as WebView's own word on who sent it.
                val sender = sourceOrigin.toString()
                runCatching {
                    val o = JSONObject(message.data ?: return@runCatching)
                    when {
                        // Watch's candidates come from the top frame only: a child's go through its parent, which clamps
                        // them to the frame (fromChild). One sent straight from a frame could put Watch's tap anywhere.
                        o.has("loc") && !isMainFrame -> Unit
                        o.has("f") -> onPlaying(o.getString("f"), o.optBoolean("on"))
                        o.has("st") -> onStarted(o.getString("st"))
                        o.has("i") -> onInfo(VideoInfo.from(o.getJSONObject("i")))
                        o.has("fr") -> o.getJSONArray("fr").let { a -> onFrameTimes((0 until a.length()).map(a::getDouble)) }
                        o.has("mf") -> o.getJSONObject("mf").let {
                            onFetched(it.optString("u"), it.optString("f").takeIf(Browser::isWebUrl), if (it.has("b")) it.optString("b") else null, sender)
                        }
                        o.has("src") -> o.getJSONObject("src").let {
                            val tr = it.optJSONArray("tr")
                            val tracks = (0 until (tr?.length() ?: 0)).mapNotNull { i ->
                                tr!!.optJSONObject(i)?.let { t -> PlayerRequest.Subtitle(t.optString("u"), t.optString("l"), t.optString("g"), t.optBoolean("o")) }
                            }.filter { t -> Browser.isWebUrl(t.url) }
                            val claimed = it.optString("pg").takeIf(Browser::isWebUrl)
                            val page = PageHeaders.frameFor(claimed, sender)
                            if (page != claimed && app.sofabrowser.BuildConfig.DEBUG) android.util.Log.d("SofaStream", "frame of $sender claimed $claimed")
                            onSource(VideoSource(it.optString("s"), it.optDouble("t", 0.0), it.optDouble("a", 0.0), it.optBoolean("drm"),
                                page, tracks, it.optInt("w"), it.optInt("h")))
                        }
                        o.has("loc") -> o.getJSONObject("loc").let {
                            onCandidate(WatchTarget.Candidate(it.getString("fid"), it.optDouble("w", 0.0), it.optDouble("h", 0.0),
                                it.optDouble("tx", -1.0), it.optDouble("ty", -1.0),
                                it.optBoolean("m"), it.optBoolean("p"), it.optBoolean("l"), it.optDouble("d", 0.0)))
                        }
                        else -> onState(VideoState(o.optDouble("t"), o.optDouble("d"), o.optBoolean("p", true), o.optDouble("r", 1.0),
                            o.optInt("tt"), o.optBoolean("ts"), o.optString("w").takeIf { it.isNotEmpty() }))
                    }
                }
            }
        }
    }

    /** Sends [cmd] to the page; [frame] addresses one frame's script by the id it reported. */
    fun send(web: WebView?, cmd: String, value: Double? = null, frame: String? = null) {
        val key = keys[web ?: return] ?: return
        val v = value?.let { ",v:$it" } ?: ""
        val f = frame?.let { ",fid:${JSONObject.quote(it)}" } ?: ""
        web.evaluateJavascript("window.postMessage({k:${JSONObject.quote(key.id)},cmd:'$cmd'$v$f},'*')", null)
    }

    /** Asks the frames from [origin] to fetch [url] again as their player did; [install]'s onFetched gets the answer. */
    fun fetch(web: WebView?, url: String, origin: String) {
        val key = keys[web ?: return] ?: return
        web.evaluateJavascript("window.postMessage({k:${JSONObject.quote(key.id)},cmd:'fetch',u:${JSONObject.quote(url)}," +
            "o:${JSONObject.quote(origin)}},'*')", null)
    }
}

/** Per-WebView random names for everything Sofa injects into pages. */
class PageKey {
    private fun random(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz"[rng.nextInt(26)] }.joinToString("")
    /** Marks Sofa's own postMessage commands. */
    val id = random(16)
    /** Name of the injected object that receives video state. */
    val video = "_" + random(12)
    /** Name of the injected object that answers in-page ad blocking (InPageBlocking). */
    val cosmetic = "_" + random(12)
    /** Name of the injected object that receives page downloads. */
    val downloads = "_" + random(12)

    private companion object {
        val rng = java.security.SecureRandom()
    }
}

data class VideoState(
    val position: Double, val duration: Double, val paused: Boolean, val rate: Double,
    /** Subtitle tracks Sofa can switch, and whether one is showing. */
    val subtitleTracks: Int = 0, val subtitlesOn: Boolean = false,
    /** The id of the page frame whose video this is; null for Sofa Player's own state. */
    val frame: String? = null,
)

/**
 * A frame's main video for "Play in…" and Sofa Player: its address, position in seconds, on-screen area, DRM use,
 * the frame's own address (or only its origin when it named another; [PageHeaders.frameFor]), the page's subtitle
 * files for it, and the size of the picture it shows (0 before the first one).
 */
data class VideoSource(val src: String, val position: Double, val area: Double, val protected: Boolean,
                       val page: String? = null, val subtitles: List<PlayerRequest.Subtitle> = emptyList(),
                       val width: Int = 0, val height: Int = 0)

/** What "Video info" shows, as the page reports it: the decoded size, frames, codec support and page geometry. */
data class VideoInfo(
    val width: Int, val height: Int,
    /** -1 when the browser doesn't report them. */
    val dropped: Int, val total: Int,
    /** The video element's width in CSS pixels, the pixel ratio, the page's screen and viewport width. */
    val cssWidth: Int, val pixelRatio: Double, val screenWidth: Int, val screenHeight: Int, val innerWidth: Int,
    val codecs: List<Codec>,
) {
    /** [efficient] is media capabilities' "power efficient": a hardware decoder exists for it on Android. */
    data class Codec(val name: String, val supported: Boolean, val efficient: Boolean)

    companion object {
        fun from(o: JSONObject): VideoInfo {
            val c = o.optJSONArray("c")
            val codecs = (0 until (c?.length() ?: 0)).map { i ->
                c!!.getJSONObject(i).let { Codec(it.optString("n"), it.optBoolean("s"), it.optBoolean("e")) }
            }
            return VideoInfo(o.optInt("w"), o.optInt("h"), o.optInt("dropped", -1), o.optInt("total", -1),
                o.optInt("cw"), o.optDouble("dpr", 1.0), o.optInt("sw"), o.optInt("sh"), o.optInt("iw"), codecs)
        }
    }
}

/**
 * The fullscreen control bar: -10 s, play/pause, +10 s, scrub bar, subtitles, speed, info, pointer and exit; with Sofa
 * Player, also its tracks and the way back to the site's player.
 */
@SuppressLint("ViewConstructor")
class VideoBar(context: Context, private val actions: Actions) : LinearLayout(context) {

    interface Actions {
        fun command(cmd: String, value: Double? = null)
        fun usePointer()
        fun exitFullscreen()
        fun toggleVideoInfo()
        fun playIn(choose: Boolean)
        fun pictureInPicture()
        /** Sofa Player's quality, sound and subtitle choices. */
        fun tracks()
        /** From Sofa Player back to the site's own player. */
        fun sitePlayer()
    }

    private val playPause: ImageView
    private val position: TextView
    private val durationText: TextView
    private val scrub: Scrubber
    private val speed: TextView
    private val subtitles: ImageView
    private var state = VideoState(0.0, 0.0, true, 1.0)

    private val pip: ImageView
    private val tracks: ImageView
    private val site: ImageView

    /** Sofa Player plays: its own buttons show, and the pointer (for the page's controls) doesn't. */
    var native = false
        set(v) {
            field = v
            tracks.visibility = if (v) VISIBLE else GONE
            site.visibility = if (v) VISIBLE else GONE
            pointer.visibility = if (v) GONE else VISIBLE
        }
    private val pointer: ImageView

    /** "Continue from 12:30", above the timeline, for a film left part-way; it goes away after a while. */
    private val resume: TextView
    private var resumeAt = 0.0
    /** The offer belongs to one video: it goes after a while, even when focused (focus moves to play/pause). */
    private val hideResume = Runnable { clearResume() }

    /** Takes the "Continue from" offer away: its time is up, or fullscreen ended. */
    fun clearResume() {
        resume.removeCallbacks(hideResume)
        if (resume.hasFocus()) playPause.requestFocus()
        resume.visibility = GONE
    }

    fun offerResume(seconds: Double) {
        resumeAt = seconds
        resume.text = context.getString(R.string.continue_from, time(seconds))
        resume.visibility = VISIBLE
        show()
        resume.requestFocusFromTouch()
        resume.removeCallbacks(hideResume)
        resume.postDelayed(hideResume, RESUME_OFFER_MS)
    }

    /** Picture-in-picture shows only on TVs that offer it to apps. */
    var pipShown: Boolean
        get() = pip.visibility == VISIBLE
        set(v) { pip.visibility = if (v) VISIBLE else GONE }
    private var rateIndex = RATES.indexOfFirst { it == 1.0 }
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

        resume = Ui.text(context, 26, Color.WHITE).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            visibility = GONE
            setPadding(32.px, 0, 32.px, 0)
            background = Ui.fillSelector(0x33FFFFFF, Ui.DARK.focus, 36)
            setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) Color.BLACK else Color.WHITE) }
            setOnClickListener {
                actions.command("seekTo", resumeAt)
                visibility = GONE
                playPause.requestFocus()
            }
        }
        addView(resume, LayoutParams(LayoutParams.WRAP_CONTENT, 72.px).apply { bottomMargin = 16.px; gravity = Gravity.START })

        val buttons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 16.px, 0, 0) }
        buttons.addView(button(R.drawable.ic_fast_rewind, R.string.rewind_10) { actions.command("seekBy", -10.0) })
        playPause = button(R.drawable.ic_pause, R.string.play_pause) { actions.command("toggle") }
        buttons.addView(playPause)
        buttons.addView(button(R.drawable.ic_fast_forward, R.string.forward_10) { actions.command("seekBy", 10.0) })
        buttons.addView(View(context), LayoutParams(0, 1, 1f))
        subtitles = button(R.drawable.ic_subtitles, R.string.subtitles) { actions.command("subs") }.apply { visibility = GONE }
        buttons.addView(subtitles)
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
        tracks = button(R.drawable.ic_settings, R.string.tracks) { actions.tracks() }.apply { visibility = GONE }
        buttons.addView(tracks)
        // "Play in…": the player picked the first time, from then on; a long press (hold OK) picks again.
        buttons.addView(button(R.drawable.ic_output, R.string.play_in) { actions.playIn(choose = false) }.apply {
            // A held key gets Android's long-press flag on its repeat; the release then mustn't also click.
            var held = false
            setOnKeyListener { _, code, e ->
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return@setOnKeyListener false
                when {
                    e.action == KeyEvent.ACTION_DOWN && e.isLongPress -> { held = true; actions.playIn(choose = true); true }
                    e.action == KeyEvent.ACTION_UP && held -> { held = false; isPressed = false; true }
                    else -> false
                }
            }
        })
        pip = button(R.drawable.ic_picture_in_picture_alt, R.string.picture_in_picture) { actions.pictureInPicture() }
            .apply { visibility = GONE }
        buttons.addView(pip)
        buttons.addView(button(R.drawable.ic_info, R.string.video_info) { actions.toggleVideoInfo() })
        site = button(R.drawable.ic_desktop_windows, R.string.site_player) { actions.sitePlayer() }.apply { visibility = GONE }
        buttons.addView(site)
        pointer = button(R.drawable.ic_mouse, R.string.pointer) { actions.usePointer() }
        buttons.addView(pointer)
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

    /** Told when the bar shows or hides, so Sofa Player's subtitles can move out of its way. */
    var onShowing: ((Boolean) -> Unit)? = null

    override fun setVisibility(visibility: Int) {
        val was = this.visibility
        super.setVisibility(visibility)
        if (was != visibility) onShowing?.invoke(visibility == VISIBLE)
    }

    fun show(focusScrub: Boolean = false) {
        val wasHidden = !showing
        visibility = VISIBLE
        if (wasHidden || findFocus() == null) {
            // FromTouch: leaves touch mode (a touch or mouse may have entered it), where plain requestFocus
            // fails. Posted again because Chromium's fullscreen view grabs focus as it is attached.
            val target = when {
                focusScrub -> scrub
                resume.visibility == VISIBLE -> resume
                else -> playPause
            }
            target.requestFocusFromTouch()
            post { if (!hasFocus()) target.requestFocusFromTouch() }
        }
        removeCallbacks(hide)
        // A "Continue from" offer keeps the bar up as long as the offer stands.
        postDelayed(hide, if (resume.visibility == VISIBLE) RESUME_OFFER_MS else 5000)
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
        // Hidden without text tracks; kept if it has focus, so focus never falls out of the bar.
        if (s.subtitleTracks > 0 || subtitles.hasFocus()) subtitles.visibility = VISIBLE else subtitles.visibility = GONE
        subtitles.alpha = if (s.subtitlesOn) 1f else 0.6f
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
        val RATES = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)
        private const val RESUME_OFFER_MS = 15_000L

        fun rateLabel(r: Double) = (if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()) + "x"

        fun time(seconds: Double): String {
            val s = seconds.toLong().coerceAtLeast(0)
            return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
        }
    }
}

/**
 * The fullscreen layer: the page's fullscreen view under a pointer layer, with the control bar on top. Sofa Player's
 * picture lies under the page's view, hidden by it while it gets ready, and shows once the page's view is hidden.
 */
@SuppressLint("ViewConstructor")
class FullscreenLayer(context: Context, val bar: VideoBar) : FrameLayout(context) {
    val pointer = CursorLayout(context).apply { cursorEnabled = false }

    /** The "Video info" readout, top left, until toggled off or fullscreen ends. */
    val info: TextView = Ui.text(context, 24, Color.WHITE).apply {
        background = Ui.rounded(0xCC000000.toInt(), 16)
        setPadding(32.px, 24.px, 32.px, 24.px)
        setLineSpacing(8.px.toFloat(), 1f)
        visibility = GONE
    }

    /** A short message about the video, top centre, for a few seconds: a toast is easy to miss over a video. */
    private val notice: TextView = Ui.text(context, 26, Color.WHITE).apply {
        background = Ui.rounded(0xE6000000.toInt(), 16)
        setPadding(40.px, 24.px, 40.px, 24.px)
        gravity = Gravity.CENTER
        visibility = GONE
    }
    private val hideNotice = Runnable { notice.visibility = GONE }

    /** Shows [text] over the video for a few seconds. */
    fun notice(text: String) {
        notice.text = text
        notice.visibility = VISIBLE
        notice.removeCallbacks(hideNotice)
        notice.postDelayed(hideNotice, NOTICE_MS)
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(pointer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(info, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(48.px, 48.px, 48.px, 0)
        })
        addView(notice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(96.px, 96.px, 96.px, 0)
        })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        visibility = GONE
        bar.onShowing = { liftSubtitles() }
    }

    fun attach(view: View) {
        // The page's fullscreen view would otherwise take focus from the bar; the pointer reaches it by touch.
        pointer.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        pointer.removeAllViews()
        pointer.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        pointer.cursorEnabled = false
        visibility = VISIBLE
    }

    private var player: PlayerSurface? = null

    /** Puts [view] (Sofa Player's) under the page's view, unseen until [showPlayer]. */
    fun addPlayer(view: PlayerSurface) {
        removePlayer()
        player = view
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        liftSubtitles()
    }

    /** While the bar is up, Sofa Player's subtitles sit above it (measured once it is laid out). */
    private fun liftSubtitles() {
        val lift = { player?.liftSubtitles(if (bar.showing) bar.height else 0) }
        lift()
        if (bar.showing) bar.post { lift() }
    }

    /** Sofa Player's picture, or the page's again. */
    fun showPlayer(on: Boolean) {
        pointer.visibility = if (on) INVISIBLE else VISIBLE
        bar.native = on
    }

    fun removePlayer() {
        player?.let(::removeView)
        player = null
        showPlayer(false)
    }

    fun detach() {
        removePlayer()
        bar.hideNow()
        bar.clearResume()
        info.visibility = GONE
        notice.removeCallbacks(hideNotice)
        notice.visibility = GONE
        pointer.cursorEnabled = false
        pointer.removeAllViews()
        visibility = GONE
    }

    private companion object {
        const val NOTICE_MS = 5000L
    }
}
