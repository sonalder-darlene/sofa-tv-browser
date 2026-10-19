package app.sofabrowser.browser

import android.util.Base64
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID

/**
 * Downloads whose bytes live in the page: data: URLs (decoded here) and blob: URLs (read by a one-off script in
 * the page and posted back). Only replies carrying a token Sofa just issued are accepted, so a page can't push
 * files on its own.
 *
 * A capture-phase click listener also reports the `download="name"` of clicked links, which WebView doesn't
 * pass on for data: and blob: URLs. It uses a listener rather than patching page prototypes, which pages could
 * detect, and talks over the per-session randomly named channel from [PageKey].
 */
object PageDownloads {

    class Payload(val bytes: ByteArray, val mime: String?)

    private val pending = HashMap<String, (Result<Payload>) -> Unit>()
    private val keys = java.util.WeakHashMap<WebView, PageKey>()

    /** Recently clicked download links: href to suggested name. */
    private val names = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 20
    }

    private fun clickScript(key: PageKey) = """
(() => {
  const L = ${JSONObject.quote(key.downloads)};
  document.addEventListener('click', (e) => {
    const a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
    const out = window[L];
    if (!a || !a.href || !out) return;
    try { out.postMessage(JSON.stringify({ href: a.href, name: a.getAttribute('download') || '' })); } catch (x) {}
  }, true);
})();
""".trimIndent()

    fun install(web: WebView, key: PageKey) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        keys[web] = key
        WebViews.addDocumentStartScript(web, clickScript(key))
        WebViewCompat.addWebMessageListener(web, key.downloads, setOf("*")) { view, message, _, _, _ ->
            val o = runCatching { JSONObject(message.data ?: return@addWebMessageListener) }.getOrNull() ?: return@addWebMessageListener
            if (o.has("href")) {
                val name = o.optString("name")
                if (name.isNotBlank()) names[o.optString("href")] = name
                return@addWebMessageListener
            }
            val done = pending.remove(o.optString("token")) ?: return@addWebMessageListener
            view.post {
                val err = o.optString("error")
                if (err.isNotEmpty()) done(Result.failure(IllegalStateException(err)))
                else done(runCatching { decodeDataUrl(o.getString("data")) ?: error("unreadable data") })
            }
        }
    }

    /** The `download` attribute of the link that started [url], if the user just clicked one. */
    fun suggestedName(url: String): String? = names[url]

    /** Asks the page for the contents of [blobUrl]; [done] runs on the main thread. */
    fun fetchBlob(web: WebView, blobUrl: String, maxBytes: Long, done: (Result<Payload>) -> Unit) {
        val key = keys[web]
        if (key == null) {
            done(Result.failure(UnsupportedOperationException("WebView too old")))
            return
        }
        val token = UUID.randomUUID().toString()
        pending[token] = done
        val js = """
            (async () => {
              const out = window[${JSONObject.quote(key.downloads)}];
              const reply = (o) => out.postMessage(JSON.stringify(Object.assign({ token: ${JSONObject.quote(token)} }, o)));
              try {
                const blob = await (await fetch(${JSONObject.quote(blobUrl)})).blob();
                if (blob.size > $maxBytes) return reply({ error: 'too large' });
                const reader = new FileReader();
                reader.onload = () => reply({ data: reader.result });
                reader.onerror = () => reply({ error: 'read failed' });
                reader.readAsDataURL(blob);
              } catch (e) { reply({ error: String(e) }); }
            })();
        """.trimIndent()
        web.evaluateJavascript(js, null)
        web.postDelayed({ pending.remove(token)?.invoke(Result.failure(IllegalStateException("timed out"))) }, 60_000)
    }

    /** Decodes "data:[mime][;base64],payload". */
    fun decodeDataUrl(url: String): Payload? {
        if (!url.startsWith("data:")) return null
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val body = url.substring(comma + 1)
        val base64 = meta.endsWith(";base64", ignoreCase = true)
        val mime = meta.removeSuffix(";base64").substringBefore(';').ifBlank { null }
        val bytes = if (base64) Base64.decode(body, Base64.DEFAULT)
        else URLDecoder.decode(body.replace("+", "%2B"), "UTF-8").toByteArray()
        return Payload(bytes, mime)
    }

    /** The MIME type declared in a data: URL, without decoding it. */
    fun dataUrlMime(url: String): String? =
        url.removePrefix("data:").substringBefore(',').removeSuffix(";base64").substringBefore(';').ifBlank { null }
}
