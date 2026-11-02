package app.sofabrowser.dns

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.concurrent.thread

/**
 * "Custom DNS": Sofa's host names are looked up over DNS over HTTPS with the main DNS, then the fallback, then the
 * device's own DNS if both fail. Off until the user picks a main DNS; then nothing changes for anyone else.
 *
 * WebView and HttpURLConnection can't be given a resolver, but both take a proxy: a [LoopbackProxy] on 127.0.0.1 does
 * the lookups, WebView is pointed at it with ProxyController, and every HttpURLConnection in the app (Sofa Player,
 * downloads, filter lists, search suggestions, the updater) through the default ProxySelector. A network with its
 * own HTTP proxy keeps it, and Custom DNS stays unused there (that proxy does the lookups).
 */
object SecureDns {

    private const val TAG = "SofaDns"
    private val main = Handler(Looper.getMainLooper())

    private var proxy: LoopbackProxy? = null
    private var servers: List<Server> = emptyList()
    @Volatile private var resolver: Resolver? = null
    private var webViewOverridden = false

    /** Why the chosen DNS is not in use, or null when it is (or none is chosen). */
    enum class Unused { NETWORK_PROXY, NO_WEBVIEW_SUPPORT }
    @Volatile var unused: Unused? = null
        private set

    /** Applies the saved choice; call when the app starts and after each change in Settings. Main thread. */
    fun apply(context: Context) {
        val prefs = Prefs(context)
        val chosen = listOfNotNull(server(prefs.dnsMain), server(prefs.dnsFallback)).distinctBy { it.id }
        servers.forEach { it.close() }
        servers = chosen
        unused = when {
            chosen.isEmpty() -> null
            !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE) -> Unused.NO_WEBVIEW_SUPPORT
            networkProxy() -> Unused.NETWORK_PROXY
            else -> null
        }
        if (chosen.isEmpty() || unused != null) return stop()

        val r = Resolver(chosen.map { s -> Resolver.Server { host, type -> s.query(host, type) } })
        resolver = r
        val p = proxy ?: try { LoopbackProxy(r::lookup).also { it.start(); proxy = it } } catch (e: IOException) {
            Log.w(TAG, "No loopback proxy: $e"); return stop()
        }
        p.resolve = r::lookup
        Selector.install(p.port)
        if (!webViewOverridden && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:${p.port}").build(), main::post) {}
            webViewOverridden = true
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "on: ${chosen.joinToString(" then ") { it.id }}, then the device DNS")
    }

    private fun stop() {
        resolver = null
        Selector.install(0)
        if (webViewOverridden && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().clearProxyOverride(main::post) {}
            webViewOverridden = false
        }
        proxy?.close()
        proxy = null
    }

    /**
     * Times one lookup of a well-known name through [choice] alone (a provider id or a DoH address), off the main
     * thread; [done] gets the milliseconds, or the failure, on the main thread.
     */
    fun test(choice: String, done: (Result<Long>) -> Unit) {
        val s = server(choice) ?: return done(Result.failure(IllegalArgumentException(choice)))
        thread(name = "sofa-dns-test", isDaemon = true) {
            val result = runCatching {
                val start = System.nanoTime()
                val answer = s.query(TEST_HOST, DnsMessage.A)
                if (answer.rcode != DnsMessage.NOERROR || answer.addresses.isEmpty()) throw IOException("DNS error ${answer.rcode}")
                (System.nanoTime() - start) / 1_000_000
            }
            s.close()
            main.post { done(result) }
        }
    }

    /** The name the Test button looks up: reserved for examples (RFC 2606) and answered by every resolver. */
    private const val TEST_HOST = "example.com"

    /** A provider's DoH servers, asked in order; [id] is the provider id or the custom address. */
    private class Server(val id: String, endpoints: List<DohEndpoint>) {
        private val clients = endpoints.map(::DohClient)
        fun query(host: String, type: Int): DnsMessage.Answer {
            var failure: IOException? = null
            for (c in clients) {
                try { return c.query(host, type) } catch (e: IOException) { failure = e }
            }
            throw failure ?: IOException("No server")
        }
        fun close() = clients.forEach { it.close() }
    }

    private fun server(choice: String): Server? = when {
        choice.isEmpty() -> null
        DnsProviders.isCustom(choice) -> runCatching { Server(choice, listOf(DohEndpoint(choice))) }.getOrNull()
        else -> DnsProviders.byId(choice)?.let { Server(it.id, it.endpoints) }
    }

    /** The device's HTTP proxy setting (Android publishes it as system properties to every app). */
    private fun networkProxy() = !System.getProperty("http.proxyHost").isNullOrEmpty()

    /** Sends HttpURLConnection's http and https requests to the loopback proxy while Custom DNS is on. */
    private object Selector : ProxySelector() {
        private var previous: ProxySelector? = null
        @Volatile private var port = 0

        fun install(p: Int) {
            port = p
            if (p != 0 && ProxySelector.getDefault() !== this) {
                previous = ProxySelector.getDefault()
                ProxySelector.setDefault(this)
            }
        }

        override fun select(uri: URI): List<Proxy> {
            val p = port
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()
            if (p == 0 || (scheme != "http" && scheme != "https") || host == null || host == "localhost" ||
                host.startsWith("127.") || host == "[::1]" || host == "::1") {
                return previous?.select(uri) ?: listOf(Proxy.NO_PROXY)
            }
            return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), p)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            previous?.connectFailed(uri, sa, ioe)
        }
    }
}
