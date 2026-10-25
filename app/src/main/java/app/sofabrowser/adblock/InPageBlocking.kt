package app.sofabrowser.adblock

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.sofabrowser.browser.PageKey
import app.sofabrowser.browser.WebViews
import org.json.JSONArray
import org.json.JSONObject

/**
 * The part of ad blocking that runs in pages, one small script in every frame, used only where ad blocking is on.
 *
 * **Element hiding** ("##" rules), done the way Brave and uBlock do it so it stays light on small TVs. The script asks
 * Sofa for the page's own selectors and the few generic ones that apply everywhere, and hides them with one style
 * sheet, which also covers ads the page inserts later. Then it watches the page for class and id names it hasn't seen
 * (on load and as elements are added) and asks only for the generic selectors keyed to those names, out of tens of
 * thousands. Selectors go in as CSS rules one by one, so a broken or hostile selector is dropped on its own and can't
 * run script; CosmeticRule refuses anything that could leave its rule.
 *
 * **Trap links.** Pop-under networks lay an invisible link to an ad over the whole page, so the first press anywhere
 * opens the ad, and with one view the ad replaces the page. WebView sees an ordinary press on a link. The script
 * looks at the press first: a link to another site that is nearly invisible, or laid over most of the screen, is
 * removed before the press lands, and Sofa says a pop-up was blocked. The press then usually reaches what was under it.
 *
 * The channel has a random name per WebView ([PageKey]); a page that finds it can only ask which of its own elements
 * would be hidden, or claim it blocked a pop-up.
 */
object InPageBlocking {

    private fun script(key: PageKey) = """
(() => {
  const ch = window[${JSONObject.quote(key.cosmetic)}];
  if (!ch) return;
  let sheet = null, generic = false, guard = false, timer = 0, reported = 0;
  const seen = new Set(), queue = [], pending = [];
  const rule = (s) => s + '{display:none!important}';
  const hide = (list) => {
    if (!list || !list.length) return;
    try {
      if (!sheet) sheet = new CSSStyleSheet();
      const all = document.adoptedStyleSheets;
      if (!all.includes(sheet)) document.adoptedStyleSheets = [...all, sheet];
      for (const s of list) { try { sheet.insertRule(rule(s), sheet.cssRules.length); } catch (e) {} }
    } catch (e) {
      const st = document.createElement('style');
      st.textContent = list.map(rule).join('\n');
      (document.head || document.documentElement).appendChild(st);
    }
  };
  const add = (t) => { if (seen.size < 20000 && !seen.has(t)) { seen.add(t); pending.push(t); } };
  const visit = (el) => {
    if (el.nodeType !== 1) return;
    const id = el.getAttribute('id');
    if (id) add('#' + id);
    for (const c of el.classList) add('.' + c);
  };
  const flush = () => {
    timer = 0;
    for (const n of queue.splice(0)) {
      visit(n);
      if (n.firstElementChild) for (const el of n.querySelectorAll('[id],[class]')) visit(el);
    }
    if (pending.length) ch.postMessage(JSON.stringify({ u: location.href, k: pending.splice(0) }));
    // A page that replaced the document's style sheets gets this one back.
    try { if (sheet && !document.adoptedStyleSheets.includes(sheet)) document.adoptedStyleSheets = [...document.adoptedStyleSheets, sheet]; } catch (e) {}
  };
  const later = () => { if (!timer) timer = setTimeout(flush, 80); };
  const start = () => {
    new MutationObserver((records) => {
      for (const r of records) {
        if (r.type === 'attributes') queue.push(r.target);
        else for (const n of r.addedNodes) if (n.nodeType === 1) queue.push(n);
      }
      later();
    }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['id', 'class'] });
    if (document.documentElement) queue.push(document.documentElement);
    later();
  };
  // Trap links: nearly invisible, or laid over most of the screen, and leading to another site.
  const site = (h) => h.split('.').slice(-2).join('.');
  const trap = (target) => {
    const a = target && target.closest ? target.closest('a[href]') : null;
    if (!a) return null;
    let u;
    try { u = new URL(a.href, location.href); } catch (x) { return null; }
    if (!/^https?:$/.test(u.protocol) || site(u.hostname) === site(location.hostname)) return null;
    const screen = innerWidth * innerHeight;
    let opacity = 1, layer = null;
    for (let el = target; el && el.nodeType === 1; el = el.parentElement) {
      const s = getComputedStyle(el);
      opacity *= parseFloat(s.opacity);
      if (s.position === 'fixed' || s.position === 'absolute') {
        const r = el.getBoundingClientRect();
        if (r.width * r.height >= screen * 0.3) layer = el;
      }
    }
    const r = a.getBoundingClientRect();
    if (opacity < 0.1) return { el: layer || a, href: u.href };
    if (layer && r.width * r.height >= screen * 0.5) return { el: layer, href: u.href };
    return null;
  };
  const press = (e) => {
    if (!guard) return;
    const t = trap(e.target);
    if (!t) return;
    e.preventDefault();
    e.stopImmediatePropagation();
    if (t.el.style.getPropertyValue('display') === 'none') return;
    t.el.style.setProperty('display', 'none', 'important');
    t.el.style.setProperty('pointer-events', 'none', 'important');
    // One press sends several events; Sofa hears about the trap once.
    if (Date.now() - reported > 1000) ch.postMessage(JSON.stringify({ o: t.href }));
    reported = Date.now();
  };
  for (const type of ['pointerdown', 'mousedown', 'touchstart', 'pointerup', 'mouseup', 'touchend', 'click', 'auxclick']) {
    window.addEventListener(type, press, { capture: true, passive: false });
  }
  ch.addEventListener('message', (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch (x) { return; }
    if (m.p) guard = true;
    hide(m.s);
    if (m.g && !generic) { generic = true; start(); }
  });
  ch.postMessage(JSON.stringify({ u: location.href }));
})();
""".trimIndent()

    /**
     * Installs in-page blocking in [web]; [active] says, for the page in the main frame, whether ad blocking is on there.
     * [onTrapBlocked] gets the address of a trap link the script removed. Needs WebView's document-start scripts and
     * message listeners; without them pages keep their elements and trap links.
     */
    fun install(web: WebView, key: PageKey, blocker: AdBlocker, active: () -> Boolean, onTrapBlocked: (String) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        if (!WebViews.addDocumentStartScript(web, script(key))) return
        val pages = Pages()
        WebViewCompat.addWebMessageListener(web, key.cosmetic, setOf("*")) { _, message, origin, _, reply ->
            runCatching {
                if (!active()) return@runCatching
                val o = JSONObject(message.data ?: return@runCatching)
                o.optString("o").takeIf { it.startsWith("http") }?.let { onTrapBlocked(it); return@runCatching }
                val page = pages.get(blocker.rules, pageUrl(o.optString("u"), origin))
                val keys = o.optJSONArray("k")
                val out = JSONObject()
                if (keys == null) {
                    out.put("p", true)
                    if (page != null) out.put("s", JSONArray(page.selectors)).put("g", page.generic)
                } else {
                    if (page == null) return@runCatching
                    val found = blocker.rules.cosmetics.forKeys(page, List(minOf(keys.length(), MAX_KEYS)) { keys.optString(it) })
                    if (found.isEmpty()) return@runCatching
                    out.put("s", JSONArray(found))
                }
                reply.postMessage(out.toString())
            }
        }
    }

    /** The frame's own address when it belongs to the origin WebView vouches for, else just that origin. */
    private fun pageUrl(claimed: String, origin: Uri): String {
        val o = origin.toString().trimEnd('/')
        return if (claimed.startsWith("$o/") || claimed == o) claimed else "$o/"
    }

    /** The last few pages' element hiding, so each batch of names doesn't look the page up again. */
    private class Pages {
        private var rules: RuleSet? = null
        private val cache = object : LinkedHashMap<String, CosmeticIndex.Page?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CosmeticIndex.Page?>?) = size > 16
        }

        fun get(current: RuleSet, url: String): CosmeticIndex.Page? {
            if (current !== rules) { cache.clear(); rules = current }
            if (cache.containsKey(url)) return cache[url]
            return current.cosmeticsFor(RequestUrl(url)).also { cache[url] = it }
        }
    }

    private const val MAX_KEYS = 5000
}
