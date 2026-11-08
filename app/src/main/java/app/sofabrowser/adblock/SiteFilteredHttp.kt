package app.sofabrowser.adblock

import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Thrown for an address Site filters block. A FileNotFoundException, so Media3 doesn't retry it (its
 * DefaultLoadErrorHandlingPolicy gives up at once on one) and playback stops with it as the cause.
 */
class SiteBlockedException(val url: String) : FileNotFoundException("Blocked by Site filters: $url")

/** A redirect [SiteFilteredHttp] won't follow: too many, to an address that isn't http(s), or https to http. */
class RedirectRefusedException(message: String) : IOException(message)

/**
 * What Sofa fetches itself (Sofa Player, "Play in…", downloads, a start page picture, a file imported from a web
 * address) goes through HttpURLConnection, which WebView's Site filters never see. [open] makes those requests with
 * redirects followed one by one, each address checked before it is requested, so a site on a list is never
 * contacted, not even through a redirect from an allowed one. Plain java.net, so it is tested on the JVM.
 */
object SiteFilteredHttp {

    const val MAX_REDIRECTS = 8

    /**
     * The connection to the last of [url]'s redirects, its status read (anything but a redirect with a Location:
     * the caller checks it), its body not. [setup] prepares each request: timeouts and headers, which may depend on
     * the connection's address. Throws [SiteBlockedException] for the first address [blocked] names;
     * [RedirectRefusedException] for too many redirects, one to an address that isn't http(s), or one from https to
     * http unless [downgrade]; any other IOException as the request does.
     */
    fun open(url: String, blocked: (String) -> Boolean, downgrade: Boolean = true, setup: (HttpURLConnection) -> Unit): HttpURLConnection {
        var at = URL(url)
        repeat(MAX_REDIRECTS + 1) {
            if (at.protocol != "http" && at.protocol != "https") throw RedirectRefusedException("Unsupported address")
            if (blocked(at.toString())) throw SiteBlockedException(at.toString())
            val c = at.openConnection() as HttpURLConnection
            val location = try {
                c.instanceFollowRedirects = false
                setup(c)
                val code = c.responseCode
                c.getHeaderField("Location")?.takeIf { code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED }
            } catch (e: Exception) {
                c.disconnect()
                throw e
            }
            if (location == null) return c
            c.disconnect()
            val next = try { URL(at, location) } catch (_: java.net.MalformedURLException) { throw RedirectRefusedException("Unsupported address") }
            if (!downgrade && at.protocol == "https" && next.protocol == "http") throw RedirectRefusedException("Redirect from https to http")
            at = next
        }
        throw RedirectRefusedException("Too many redirects")
    }
}
