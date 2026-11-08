package app.sofabrowser.dns

import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * An HTTP proxy on 127.0.0.1 that exists only so Sofa's traffic resolves host names with [resolve] (Sofa's chosen
 * DNS) instead of the device's: WebView and HttpURLConnection have no resolver setting, but both take a proxy.
 * HTTPS goes through as a `CONNECT` tunnel, so TLS stays end to end between the page and the site; plain HTTP is
 * forwarded one request per connection. Nothing is logged, cached or changed beyond the proxy headers.
 *
 * Only loopback can connect (the socket is bound there), and the proxy never connects to loopback itself, so it
 * can't reach services on the device or loop into itself.
 */
class LoopbackProxy(@Volatile var resolve: (String) -> List<InetAddress>) {

    private val server = ServerSocket(0, 64, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    val port: Int = server.localPort

    private val workers = ThreadPoolExecutor(0, MAX_THREADS, 30, TimeUnit.SECONDS, SynchronousQueue()) { r ->
        Thread(null, r, "sofa-proxy", 256 * 1024L).apply { isDaemon = true }
    }

    fun start() {
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: IOException) { break }
                try { workers.execute { serve(client) } } catch (_: RejectedExecutionException) { closeQuietly(client) }
            }
        }, "sofa-proxy-accept").apply { isDaemon = true }.start()
    }

    /** Stops taking connections; tunnels already open run until both sides close them or they go idle. */
    fun close() = closeQuietly(server)

    private fun serve(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = HEAD_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val out = client.getOutputStream()
            val head = readHead(input) ?: return
            val request = parse(head) ?: return reply(out, 400, "Bad Request")
            upstream = try { connect(request.host, request.port) } catch (e: IOException) {
                return refuse(out, if (e is UnknownHostException) "no address for ${request.host}" else "can't reach ${request.host}")
            }
            if (request.connect) {
                out.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                out.flush()
            } else {
                upstream.getOutputStream().apply { write(request.forwardHead); flush() }
            }
            pipe(input, client, upstream)
        } catch (_: IOException) {
        } finally {
            upstream?.let(::closeQuietly)
            closeQuietly(client)
        }
    }

    private fun connect(host: String, port: Int): Socket {
        val addresses = resolve(host)
        var failure: IOException? = null
        for (address in addresses) {
            if (address.isLoopbackAddress || address.isAnyLocalAddress) { failure = IOException("Loopback refused"); continue }
            val socket = Socket(Proxy.NO_PROXY)
            try {
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                return socket
            } catch (e: IOException) {
                closeQuietly(socket)
                failure = e
            }
        }
        throw failure ?: IOException("No address")
    }

    /**
     * Ends a request the proxy can't serve with a line that is not an HTTP status. Android's HttpURLConnection (its
     * built-in OkHttp) retries a refused tunnel without the proxy, so with the device's DNS, after a valid error
     * status; a malformed status line is a protocol error, which it never retries. WebView has no route without the
     * proxy anyway. The text is what the user may see in an error message.
     */
    private fun refuse(out: OutputStream, why: String) {
        runCatching {
            out.write("$REFUSED$why\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
    }

    private fun reply(out: OutputStream, code: Int, text: String) {
        runCatching {
            out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            out.flush()
        }
    }

    /** A request the proxy can serve: a CONNECT tunnel, or a plain HTTP request rewritten for the origin server. */
    internal class Request(val connect: Boolean, val host: String, val port: Int, val forwardHead: ByteArray)

    companion object {
        private const val MAX_THREADS = 512
        private const val HEAD_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 8_000
        /** A tunnel with no bytes either way for this long is closed (see [pipe]). */
        private const val IDLE_MS = 5 * 60_000L
        /** How often a waiting read looks at the idle time, so a tunnel goes at most this much after [IDLE_MS]. */
        private const val TICK_MS = 30_000
        private const val MAX_HEAD = 64 * 1024
        /** The start of [refuse]'s line. */
        internal const val REFUSED = "Custom DNS: "

        private val DROPPED = setOf("proxy-connection", "proxy-authorization", "connection", "keep-alive")

        /** The request head up to the blank line, as ISO-8859-1 text; null if the client left or sent too much. */
        internal fun readHead(input: InputStream): String? {
            val sb = StringBuilder()
            while (sb.length < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                sb.append(b.toChar())
                if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) return sb.toString()
            }
            return null
        }

        /**
         * `CONNECT host:port`, or an absolute `http://` request, whose head is rewritten for the origin: the path
         * only, no proxy headers, and `Connection: close`, so each connection carries one request and one host.
         */
        internal fun parse(head: String): Request? {
            val lines = head.split("\r\n", "\n").dropLastWhile { it.isEmpty() }
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
            val (method, target, version) = parts
            if (method.equals("CONNECT", ignoreCase = true)) {
                val colon = target.lastIndexOf(':')
                if (colon <= 0) return null
                val host = target.substring(0, colon).removePrefix("[").removeSuffix("]")
                val port = target.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                if (host.isEmpty()) return null
                return Request(true, host, port, ByteArray(0))
            }
            val uri = runCatching { URI(target) }.getOrNull() ?: return null
            if (!uri.scheme.equals("http", ignoreCase = true) || uri.host.isNullOrEmpty()) return null
            val port = if (uri.port == -1) 80 else uri.port
            val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val sb = StringBuilder("$method $path $version\r\n")
            var hasHost = false
            for (line in lines.drop(1)) {
                val name = line.substringBefore(':').trim().lowercase()
                if (name in DROPPED) continue
                if (name == "host") hasHost = true
                sb.append(line).append("\r\n")
            }
            if (!hasHost) sb.append("Host: ").append(uri.rawAuthority).append("\r\n")
            sb.append("Connection: close\r\n\r\n")
            return Request(false, uri.host.removePrefix("[").removeSuffix("]"), port, sb.toString().toByteArray(Charsets.ISO_8859_1))
        }

        /**
         * Copies [upstream] → [client] on this thread and client → upstream on another, until both are done. One
         * side's end of stream is passed on as a half-close; a failure on either side closes both, so the other copy
         * doesn't wait on a socket nobody uses. A tunnel where no byte has moved either way for [idleMs] is closed:
         * a peer that went silent (a NAT that dropped the connection, a server that keeps its half open) would
         * otherwise hold two threads until TCP keep-alive gives up, hours later. A copy waiting for the client to
         * take its bytes isn't idle: that is a page that stopped reading, such as a paused video, and it decides when
         * to read again. [clientIn] is the client's stream after the request head.
         */
        internal fun pipe(clientIn: InputStream, client: Socket, upstream: Socket, idleMs: Long = IDLE_MS, tickMs: Int = TICK_MS) {
            val tunnel = Tunnel(client, upstream, idleMs)
            client.soTimeout = tickMs
            upstream.soTimeout = tickMs
            val up = Thread(null, {
                if (tunnel.copy(clientIn, upstream.getOutputStream(), toClient = false)) {
                    runCatching { upstream.shutdownOutput() }
                } else tunnel.closeBoth()
            }, "sofa-proxy-up", 128 * 1024L).apply { isDaemon = true; start() }
            if (tunnel.copy(upstream.getInputStream(), client.getOutputStream(), toClient = true)) {
                runCatching { client.shutdownOutput() }
            } else tunnel.closeBoth()
            up.join()
        }

        private class Tunnel(val client: Socket, val upstream: Socket, idleMs: Long) {
            private val idleNs = idleMs * 1_000_000
            /** When a byte last moved either way. */
            @Volatile private var moved = System.nanoTime()
            /** A write to the client is waiting for it to read. */
            @Volatile private var waitingForClient = false

            fun closeBoth() {
                closeQuietly(client)
                closeQuietly(upstream)
            }

            /** Copies until [from] ends (true), or a failure or the idle limit ends the tunnel (false). */
            fun copy(from: InputStream, to: OutputStream, toClient: Boolean): Boolean {
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = try {
                        from.read(buf)
                    } catch (_: SocketTimeoutException) {
                        if (waitingForClient || System.nanoTime() - moved < idleNs) continue
                        return false
                    } catch (_: IOException) {
                        return false
                    }
                    if (n < 0) return true
                    moved = System.nanoTime()
                    try {
                        if (toClient) waitingForClient = true
                        to.write(buf, 0, n)
                        to.flush()
                    } catch (_: IOException) {
                        return false
                    } finally {
                        if (toClient) waitingForClient = false
                    }
                    moved = System.nanoTime()
                }
            }
        }

        private fun closeQuietly(c: AutoCloseable) = runCatching { c.close() }
    }
}
