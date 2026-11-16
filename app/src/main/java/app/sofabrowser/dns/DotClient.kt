package app.sofabrowser.dns

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.Socket
import java.util.ArrayDeque
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection

/**
 * Asks one DNS over TLS server (RFC 7858): DNS messages, each after its two-byte length, inside TLS to [host] on
 * [port] (853 by default). The certificate must be valid for [host], as for DNS over HTTPS. One query at a time on a
 * connection; a few connections stay open between queries. [host] is reached at the device DNS's addresses for it,
 * like a DNS over HTTPS address typed in by hand. Thread-safe; nothing is logged.
 */
class DotClient(
    val host: String,
    val port: Int = PORT,
    private val verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val connect: () -> Socket = { DnsWire.connectTls(host, port, emptyList(), verifier) },
) : DnsClient {

    private class Connection(val socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val output = BufferedOutputStream(socket.getOutputStream())
        fun close() = runCatching { socket.close() }
    }

    private val idle = ArrayDeque<Connection>()
    @Volatile private var closed = false

    override fun query(host: String, type: Int): DnsMessage.Answer {
        val query = DnsMessage.query(host, type, DnsWire.newId())
        // A kept connection may have been closed by the server meanwhile (they close idle ones): one more try on a new one.
        val reused = synchronized(idle) { idle.pollFirst() }
        if (reused != null) {
            try { return exchange(reused, query) } catch (_: IOException) { reused.close() }
        }
        val fresh = Connection(connect())
        try { return exchange(fresh, query) } catch (e: IOException) { fresh.close(); throw e }
    }

    private fun exchange(c: Connection, query: ByteArray): DnsMessage.Answer {
        DnsWire.writeFramed(c.output, query)
        val answer = DnsWire.answerTo(query, DnsWire.readFramed(c.input))
        if (!closed) synchronized(idle) { if (idle.size < MAX_IDLE) idle.addLast(c) else c.close() } else c.close()
        return answer
    }

    override fun dropIdle() {
        synchronized(idle) { idle.forEach { it.close() }; idle.clear() }
    }

    override fun close() {
        closed = true
        dropIdle()
    }

    companion object {
        const val PORT = 853
        private const val MAX_IDLE = 4
    }
}
