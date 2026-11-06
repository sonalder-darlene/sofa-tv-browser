package app.sofabrowser.ui

import android.content.Context
import app.sofabrowser.R
import app.sofabrowser.dns.LoopbackProxy
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A failed download (a filter list, a bookmarks file) in plain words. The HTTP stack's own messages ("Unexpected status
 * line: Custom DNS: no address for …", "Failed to connect to /127.0.0.1:1") mean nothing to someone on a sofa. Works on
 * the message alone too, because a list keeps only that from its last failure.
 */
object ErrorText {
    sealed class Reason {
        data class NoAddress(val host: String?) : Reason()
        data class Unreachable(val host: String?) : Reason()
        object TimedOut : Reason()
        data class Http(val code: Int) : Reason()
        object Secure : Reason()
        /** Sofa's own words ("no rules found") or something unforeseen: shown as it is. */
        data class Other(val text: String) : Reason()
    }

    fun reason(e: Throwable?, message: String? = e?.message): Reason {
        val m = message.orEmpty()
        val refused = m.substringAfter(LoopbackProxy.REFUSED, "").takeIf { LoopbackProxy.REFUSED in m }
        return when {
            refused != null && refused.startsWith(NO_ADDRESS) -> Reason.NoAddress(refused.removePrefix(NO_ADDRESS).trim().ifEmpty { null })
            refused != null -> Reason.Unreachable(refused.substringAfter(CANT_REACH, "").trim().ifEmpty { null })
            e is UnknownHostException || UNRESOLVED.containsMatchIn(m) -> Reason.NoAddress(UNRESOLVED.find(m)?.groupValues?.get(1))
            e is SocketTimeoutException || TIMEOUT.containsMatchIn(m) -> Reason.TimedOut
            e is ConnectException || e is NoRouteToHostException || CONNECT.containsMatchIn(m) ->
                Reason.Unreachable(CONNECT.find(m)?.groupValues?.get(1))
            e is SSLException || SECURE.containsMatchIn(m) -> Reason.Secure
            HTTP.matches(m.trim()) -> Reason.Http(HTTP.find(m.trim())!!.groupValues[1].toInt())
            else -> Reason.Other(m.ifBlank { e?.javaClass?.simpleName.orEmpty() })
        }
    }

    /** One sentence, capital first and full stop last, to follow "Couldn't add the list." and the like. */
    fun text(context: Context, e: Throwable?, message: String? = e?.message): String = when (val r = reason(e, message)) {
        is Reason.NoAddress -> r.host?.let { context.getString(R.string.error_no_address, it) } ?: context.getString(R.string.error_no_address_any)
        is Reason.Unreachable -> r.host?.let { context.getString(R.string.error_unreachable, it) } ?: context.getString(R.string.error_unreachable_any)
        Reason.TimedOut -> context.getString(R.string.error_timed_out)
        is Reason.Http -> if (r.code == 404) context.getString(R.string.error_not_found) else context.getString(R.string.error_http, r.code)
        Reason.Secure -> context.getString(R.string.error_secure)
        is Reason.Other -> sentence(r.text)
    }

    /** The stored message of a list's last failure, in plain words. */
    fun text(context: Context, message: String?): String = text(context, null, message)

    internal fun sentence(s: String): String {
        val t = s.trim()
        if (t.isEmpty()) return t
        val capital = t.replaceFirstChar { it.uppercaseChar() }
        return if (capital.last() in ".!?") capital else "$capital."
    }

    private const val NO_ADDRESS = "no address for"
    private const val CANT_REACH = "can't reach"
    /** Android's UnknownHostException text: `Unable to resolve host "example.com": No address associated with hostname`. */
    private val UNRESOLVED = Regex("""Unable to resolve host "([^"]+)"""")
    private val TIMEOUT = Regex("""timed? ?out""", RegexOption.IGNORE_CASE)
    /** `Failed to connect to /127.0.0.1:1` or `failed to connect to example.com/93.184.216.34 (port 443) …`. */
    private val CONNECT = Regex("""[Ff]ailed to connect to /?([^/:\s]+)""")
    private val SECURE = Regex("""SSL|CertPath|Trust anchor|certificate""")
    private val HTTP = Regex("""HTTP (\d{3})""")
}
