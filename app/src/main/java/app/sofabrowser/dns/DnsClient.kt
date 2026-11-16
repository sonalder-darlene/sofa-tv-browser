package app.sofabrowser.dns

import android.os.Build
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One DNS server Sofa asks, over DNS over HTTPS, DNS over TLS or plain DNS. Thread-safe; nothing is logged. */
interface DnsClient {
    /** [host]'s [type] records (DnsMessage.A or AAAA); throws IOException when the server can't be asked or answers badly. */
    fun query(host: String, type: Int): DnsMessage.Answer

    /** Closes the connections kept for later queries, as when the TV's network changes; the client stays usable. */
    fun dropIdle()

    /** Closes the kept connections for good; queries already running finish on theirs. */
    fun close()
}

/** What the DNS clients share: timeouts, query ids, TLS set-up, and the two-byte length framing of DNS over TCP. */
internal object DnsWire {
    const val CONNECT_TIMEOUT_MS = 3_000
    const val READ_TIMEOUT_MS = 3_000

    private val random = SecureRandom()

    /** A fresh random query id: an answer must carry it, which makes a forged plain DNS answer much harder to slip in. */
    fun newId() = random.nextInt(0x10000)

    /**
     * A TLS connection to [host] on [port], at the literal [bootstrap] addresses, or at the device DNS's addresses for
     * [host] when there are none. The certificate must be valid for [host] (a name, or an IP address the certificate
     * lists). [alpn]: the protocols to offer, if any. Never through a proxy: Sofa's own proxy depends on this.
     */
    fun connectTls(host: String, port: Int, bootstrap: List<String>, verifier: HostnameVerifier, alpn: Array<String>? = null): SSLSocket {
        val name = host.removePrefix("[").removeSuffix("]")
        val byIp = Resolver.isIpLiteral(name)
        val addresses = bootstrap.map { InetAddress.getByName(it) } // literals: no lookup
            .ifEmpty { InetAddress.getAllByName(name).toList() }
        var failure: IOException? = null
        for (address in addresses) {
            // Proxy.NO_PROXY: this socket must never go through Sofa's own proxy (SecureDns.Selector).
            val raw = Socket(Proxy.NO_PROXY)
            try {
                raw.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                raw.soTimeout = READ_TIMEOUT_MS
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, name, port, true) as SSLSocket
                val params = tls.sslParameters.apply {
                    // A server name (SNI) is a name, never an IP address (RFC 6066).
                    if (!byIp) serverNames = listOf(SNIHostName(name))
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                val modern = Build.VERSION.SDK_INT >= 29
                if (alpn != null && modern) params.applicationProtocols = alpn
                tls.sslParameters = params
                // Android 7–9: Conscrypt's own method, as OkHttp uses it there.
                if (alpn != null && !modern) runCatching { tls.javaClass.getMethod("setAlpnProtocols", ByteArray::class.java).invoke(tls, alpnWire(alpn)) }
                tls.startHandshake()
                if (!verifier.verify(name, tls.session)) {
                    tls.close()
                    throw IOException("Certificate not valid for $name")
                }
                return tls
            } catch (e: IOException) {
                runCatching { raw.close() }
                failure = e
            }
        }
        throw failure ?: IOException("No address for $name")
    }

    /** The protocol the server chose from those offered (ALPN), or null. */
    fun chosenProtocol(tls: SSLSocket): String? =
        if (Build.VERSION.SDK_INT >= 29) tls.applicationProtocol
        else runCatching { (tls.javaClass.getMethod("getAlpnSelectedProtocol").invoke(tls) as ByteArray?)?.let { String(it, Charsets.US_ASCII) } }.getOrNull()

    /** [protocols] in the ALPN wire format: each name after its length. */
    private fun alpnWire(protocols: Array<String>) = protocols.fold(ByteArray(0)) { acc, p ->
        acc + p.length.toByte() + p.toByteArray(Charsets.US_ASCII)
    }

    /** Sends one DNS message over TCP or TLS: its length in two bytes, then the message (RFC 1035 §4.2.2, RFC 7858). */
    fun writeFramed(out: OutputStream, msg: ByteArray) {
        out.write(byteArrayOf((msg.size shr 8).toByte(), msg.size.toByte()) + msg)
        out.flush()
    }

    /** Reads one length-framed DNS message. */
    fun readFramed(input: InputStream): ByteArray {
        val hi = input.read()
        val lo = input.read()
        if (hi < 0 || lo < 0) throw IOException("Connection closed")
        val length = hi shl 8 or lo
        if (length < 12) throw IOException("Short DNS message")
        return readExactly(input, length)
    }

    fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) throw IOException("Connection closed")
            off += r
        }
        return buf
    }

    /** Parses [response] after checking it answers [query]; a malformed or mismatched one is an IOException (a failed server). */
    fun answerTo(query: ByteArray, response: ByteArray): DnsMessage.Answer {
        if (!DnsMessage.answers(query, response)) throw IOException("DNS answer doesn't match the question")
        return try { DnsMessage.parse(response) } catch (e: IllegalArgumentException) { throw IOException(e.message) }
    }
}
