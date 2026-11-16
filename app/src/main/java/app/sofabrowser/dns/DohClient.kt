package app.sofabrowser.dns

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.ArrayDeque
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket

/**
 * A DNS over HTTPS server (RFC 8484): [url] is its address, such as `https://dns.example/dns-query`, and [bootstrap]
 * the IP addresses it answers on, so reaching it needs no DNS lookup (empty: ask the device's DNS for its host).
 */
class DohEndpoint(val url: String, val bootstrap: List<String> = emptyList()) {
    private val uri = URI(url)
    val host: String = requireNotNull(uri.host) { "No host in $url" }
    val port: Int = if (uri.port == -1) 443 else uri.port
    val path: String = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")

    init { require(uri.scheme == "https") { "Not an https address: $url" } }

    override fun toString() = url
}

/**
 * Asks one DoH server, over HTTP/2 or HTTP/1.1 POST, keeping a few connections open between queries. The certificate
 * must be valid for the server's host name even when it is reached by a bootstrap address. Thread-safe; nothing is
 * logged.
 */
class DohClient(val endpoint: DohEndpoint, private val verifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()) : DnsClient {

    private class Connection(val socket: SSLSocket, http2: Boolean) {
        val input = BufferedInputStream(socket.inputStream)
        val output = java.io.BufferedOutputStream(socket.outputStream)
        /** Set when the server chose HTTP/2 (several resolvers answer only that way). */
        val h2: Http2? = if (http2) Http2(input, output).also { it.start() } else null
        fun close() = runCatching { socket.close() }
    }

    private val idle = ArrayDeque<Connection>()
    @Volatile private var closed = false

    override fun query(host: String, type: Int): DnsMessage.Answer {
        val body = DnsMessage.query(host, type)
        // A kept connection may have been closed by the server meanwhile: one more try on a new one.
        val reused = synchronized(idle) { idle.pollFirst() }
        if (reused != null) {
            try { return exchange(reused, body) } catch (_: IOException) { reused.close() }
        }
        val fresh = open()
        try { return exchange(fresh, body) } catch (e: IOException) { fresh.close(); throw e }
    }

    override fun dropIdle() {
        synchronized(idle) { idle.forEach { it.close() }; idle.clear() }
    }

    override fun close() {
        closed = true
        dropIdle()
    }

    private fun exchange(c: Connection, body: ByteArray): DnsMessage.Answer {
        val response = c.h2?.let { h2 ->
            val (status, data) = h2.post(hostHeader(), endpoint.path, DNS_MESSAGE, body)
            // 0: the status couldn't be read; the answer must then parse as DNS to count.
            Response(if (status == 0) 200 else status, data, !h2.closed)
        } ?: run {
            val head = "POST ${endpoint.path} HTTP/1.1\r\nHost: ${hostHeader()}\r\n" +
                "Content-Type: $DNS_MESSAGE\r\nAccept: $DNS_MESSAGE\r\nContent-Length: ${body.size}\r\n\r\n"
            c.output.write(head.toByteArray(Charsets.US_ASCII) + body)
            c.output.flush()
            readResponse(c.input)
        }
        if (response.status != 200) throw IOException("HTTP ${response.status}")
        val answer = try { DnsMessage.parse(response.body) } catch (e: IllegalArgumentException) { throw IOException(e.message) }
        if (response.keepAlive && !closed) synchronized(idle) { if (idle.size < MAX_IDLE) idle.addLast(c) else c.close() } else c.close()
        return answer
    }

    private fun hostHeader() = if (endpoint.port == 443) endpoint.host else "${endpoint.host}:${endpoint.port}"

    private fun open(): Connection {
        val tls = DnsWire.connectTls(endpoint.host, endpoint.port, endpoint.bootstrap, verifier, ALPN)
        return Connection(tls, DnsWire.chosenProtocol(tls) == "h2")
    }

    internal class Response(val status: Int, val body: ByteArray, val keepAlive: Boolean)

    companion object {
        private const val MAX_IDLE = 4
        private const val MAX_BODY = 65_535
        private const val MAX_HEAD = 16_384
        private const val DNS_MESSAGE = "application/dns-message"
        private val ALPN = arrayOf("h2", "http/1.1")

        /** Reads one HTTP/1.1 response: status line, headers, then a Content-Length or chunked body. */
        internal fun readResponse(input: InputStream): Response {
            val status = readLine(input).split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad HTTP response")
            var length = -1
            var chunked = false
            var keepAlive = true
            var headBytes = 0
            while (true) {
                val line = readLine(input)
                headBytes += line.length
                if (headBytes > MAX_HEAD) throw IOException("HTTP headers too long")
                if (line.isEmpty()) break
                val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                val value = line.substring(colon + 1).trim()
                when (line.substring(0, colon).trim().lowercase()) {
                    "content-length" -> length = value.toIntOrNull() ?: throw IOException("Bad Content-Length")
                    "transfer-encoding" -> chunked = value.lowercase().contains("chunked")
                    "connection" -> if (value.lowercase().contains("close")) keepAlive = false
                }
            }
            val body = when {
                chunked -> ByteArrayOutputStream().also { out ->
                    while (true) {
                        val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("Bad chunk")
                        if (size == 0) { while (readLine(input).isNotEmpty()) Unit; break }
                        if (out.size() + size > MAX_BODY) throw IOException("DNS answer too long")
                        out.write(DnsWire.readExactly(input, size))
                        readLine(input)
                    }
                }.toByteArray()
                length in 0..MAX_BODY -> DnsWire.readExactly(input, length)
                length > MAX_BODY -> throw IOException("DNS answer too long")
                else -> { keepAlive = false; readToEnd(input) }
            }
            return Response(status, body, keepAlive)
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) throw IOException("Connection closed")
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length >= MAX_HEAD) throw IOException("HTTP line too long")
                sb.append(b.toChar())
            }
        }

        private fun readToEnd(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (true) {
                val r = input.read(buf)
                if (r == -1) return out.toByteArray()
                if (out.size() + r > MAX_BODY) throw IOException("DNS answer too long")
                out.write(buf, 0, r)
            }
        }
    }
}
