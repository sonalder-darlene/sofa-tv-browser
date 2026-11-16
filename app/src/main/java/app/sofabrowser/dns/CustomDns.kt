package app.sofabrowser.dns

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/**
 * A DNS server typed in by hand, in one of three forms, stored in the settings as its [text]:
 * - `https://dns.example/dns-query`: DNS over HTTPS, encrypted;
 * - `tls://dns.example`, port 853 unless one is given: DNS over TLS, encrypted;
 * - an IP address such as `192.0.2.53` or `2001:db8::53`, port 53 unless given (`[2001:db8::53]:5353`): plain DNS,
 *   not encrypted.
 * A DNS over TLS server is reached by its name, which the device's DNS looks up, as for a DNS over HTTPS address.
 */
sealed class CustomDns(val text: String) {

    /** What the server's lookups are sent over. */
    enum class Kind { HTTPS, TLS, PLAIN }

    abstract val kind: Kind

    /** The short name Settings shows: the host name or the IP address. */
    abstract val name: String

    abstract fun client(): DnsClient

    class Https(val endpoint: DohEndpoint) : CustomDns(endpoint.url) {
        override val kind get() = Kind.HTTPS
        override val name get() = endpoint.host
        override fun client() = DohClient(endpoint)
    }

    class Tls(val host: String, val port: Int) : CustomDns("tls://$host" + if (port == DotClient.PORT) "" else ":$port") {
        override val kind get() = Kind.TLS
        override val name get() = host
        override fun client() = DotClient(host, port)
    }

    class Plain(val address: InetAddress, val port: Int, private val literal: String) : CustomDns(plainText(address, port, literal)) {
        override val kind get() = Kind.PLAIN
        override val name get() = literal
        override fun client() = PlainDnsClient(InetSocketAddress(address, port))
    }

    override fun toString() = text

    companion object {

        /** A stored value or a typed address in its exact form, or null when it isn't one (a provider id isn't). */
        fun parse(text: String): CustomDns? {
            if (text.isEmpty() || text.any(Char::isWhitespace)) return null
            return when {
                text.startsWith("https://") -> runCatching { Https(DohEndpoint(text)) }.getOrNull()
                text.startsWith("tls://") -> tls(text)
                else -> plain(text)
            }
        }

        /**
         * What someone typed, made into an address: spaces around it trimmed, the scheme's case ignored, and a bare
         * name (`dns.example/dns-query`) taken as DNS over HTTPS, as before there were three forms.
         */
        fun fromTyped(typed: String): CustomDns? {
            val t = typed.trim()
            val scheme = Regex("^[A-Za-z]+://").find(t)?.value
            val fixed = if (scheme != null) scheme.lowercase() + t.substring(scheme.length) else t
            return parse(fixed) ?: if (scheme == null && !looksLikeIp(fixed)) parse("https://$fixed") else null
        }

        private fun tls(text: String): Tls? {
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return null
            val port = if (uri.port == -1) DotClient.PORT else uri.port
            if (port !in 1..65535) return null
            return Tls(host, port)
        }

        private fun plain(text: String): Plain? {
            val (literal, port) = when {
                text.startsWith("[") -> {
                    val end = text.indexOf(']').takeIf { it > 0 } ?: return null
                    val rest = text.substring(end + 1)
                    text.substring(1, end) to when {
                        rest.isEmpty() -> PlainDnsClient.PORT
                        rest.startsWith(":") -> rest.substring(1).toPortOrNull() ?: return null
                        else -> return null
                    }
                }
                text.count { it == ':' } == 1 -> text.substringBefore(':') to (text.substringAfter(':').toPortOrNull() ?: return null)
                else -> text to PlainDnsClient.PORT
            }
            val bytes = if (text.startsWith("[")) ipv6(literal) else ipv4(literal) ?: if (':' in literal) ipv6(literal) else null
            val address = InetAddress.getByAddress(bytes ?: return null) // bytes: never a lookup
            if (address.isAnyLocalAddress || address.isMulticastAddress) return null
            return Plain(address, port, literal.lowercase())
        }

        private fun plainText(address: InetAddress, port: Int, literal: String) = when {
            port == PlainDnsClient.PORT -> literal
            address is Inet6Address -> "[$literal]:$port"
            else -> "$literal:$port"
        }

        private fun String.toPortOrNull() = takeIf { it.length in 1..5 && it.all(Char::isDigit) }?.toInt()?.takeIf { it in 1..65535 }

        private fun looksLikeIp(s: String) = s.startsWith("[") || s.substringBefore(':').all { it.isDigit() || it == '.' } ||
            s.count { it == ':' } > 1

        /** Four decimal numbers 0–255, or null. */
        internal fun ipv4(s: String): ByteArray? {
            val parts = s.split('.')
            if (parts.size != 4) return null
            return ByteArray(4) { i ->
                val p = parts[i]
                if (p.length !in 1..3 || !p.all(Char::isDigit)) return null
                (p.toInt().takeIf { it <= 255 } ?: return null).toByte()
            }
        }

        /** An IPv6 address in text (RFC 4291 §2.2: groups, one `::`, an IPv4 tail), or null; no zone (`%eth0`). */
        internal fun ipv6(s: String): ByteArray? {
            if (s.isEmpty() || s.length > 45) return null
            val halves = s.split("::")
            if (halves.size > 2) return null
            val gapped = halves.size == 2
            fun groups(part: String) = if (part.isEmpty()) emptyList() else part.split(':')
            val head = groups(halves[0])
            val tail = if (gapped) groups(halves[1]) else emptyList()
            // An IPv4 tail (`::ffff:192.0.2.1`) stands for the last two groups, so it must end the address.
            val v4 = (head + tail).lastOrNull()?.takeIf { '.' in it }?.let { ipv4(it) ?: return null }
            if (v4 != null && gapped && tail.isEmpty()) return null
            fun words(g: List<String>): List<Int>? = g.map { h ->
                if (h.length !in 1..4 || !h.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                h.toInt(16)
            }
            val headWords = words(if (v4 != null && !gapped) head.dropLast(1) else head) ?: return null
            val tailWords = words(if (v4 != null && gapped) tail.dropLast(1) else tail) ?: return null
            val gap = 8 - headWords.size - tailWords.size - (if (v4 != null) 2 else 0)
            if (!gapped && gap != 0 || gapped && gap < 1) return null
            val all = headWords + List(if (gapped) gap else 0) { 0 } + tailWords
            val out = ByteArray(16)
            all.forEachIndexed { i, w -> out[2 * i] = (w shr 8).toByte(); out[2 * i + 1] = w.toByte() }
            v4?.copyInto(out, 12)
            return out
        }
    }
}
