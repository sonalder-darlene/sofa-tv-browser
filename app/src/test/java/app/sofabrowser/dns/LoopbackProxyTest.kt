package app.sofabrowser.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LoopbackProxyTest {

    @Test
    fun aConnectTunnelNamesItsHostAndPort() {
        val r = LoopbackProxy.parse("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")!!
        assertTrue(r.connect)
        assertEquals("example.com", r.host)
        assertEquals(443, r.port)
        val v6 = LoopbackProxy.parse("CONNECT [2001:db8::1]:8443 HTTP/1.1\r\n\r\n")!!
        assertEquals("2001:db8::1", v6.host)
        assertEquals(8443, v6.port)
    }

    @Test
    fun aPlainRequestIsRewrittenForTheSiteWithOneRequestPerConnection() {
        val r = LoopbackProxy.parse("GET http://example.com:8080/a/b?c=d HTTP/1.1\r\nHost: example.com:8080\r\n" +
            "Proxy-Connection: keep-alive\r\nConnection: keep-alive\r\nAccept: */*\r\n\r\n")!!
        assertEquals("example.com", r.host)
        assertEquals(8080, r.port)
        assertEquals("GET /a/b?c=d HTTP/1.1\r\nHost: example.com:8080\r\nAccept: */*\r\nConnection: close\r\n\r\n",
            String(r.forwardHead, Charsets.ISO_8859_1))
    }

    @Test
    fun aMissingHostHeaderIsAdded() {
        val r = LoopbackProxy.parse("GET http://example.com HTTP/1.0\r\n\r\n")!!
        assertEquals(80, r.port)
        assertEquals("GET / HTTP/1.0\r\nHost: example.com\r\nConnection: close\r\n\r\n", String(r.forwardHead, Charsets.ISO_8859_1))
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(LoopbackProxy.parse("GET /relative HTTP/1.1\r\n\r\n")) // not a proxy request
        assertNull(LoopbackProxy.parse("GET ftp://example.com/ HTTP/1.1\r\n\r\n"))
        assertNull(LoopbackProxy.parse("CONNECT example.com HTTP/1.1\r\n\r\n"))
        assertNull(LoopbackProxy.parse("CONNECT example.com:0 HTTP/1.1\r\n\r\n"))
        assertNull(LoopbackProxy.parse("hello\r\n\r\n"))
    }

    @Test
    fun theHeadEndsAtTheBlankLineAndTheRestStaysForTheTunnel() {
        val input = ByteArrayInputStream("CONNECT a.example:443 HTTP/1.1\r\n\r\nTLS".toByteArray())
        assertEquals("CONNECT a.example:443 HTTP/1.1\r\n\r\n", LoopbackProxy.readHead(input))
        assertEquals("TLS", input.readBytes().decodeToString())
        assertNull(LoopbackProxy.readHead(ByteArrayInputStream("CONNECT a.example:443".toByteArray())))
    }
}

/** [LoopbackProxy.pipe] between a "page" and a "site" on loopback, with the proxy's two sockets in between. */
class LoopbackProxyTunnelTest {

    private class Rig(idleMs: Long, tickMs: Int, pageBuffer: Int = 0) : AutoCloseable {
        private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        private val proxySide = ServerSocket(0, 1, loopback)
        private val origin = ServerSocket(0, 1, loopback)
        val page = Socket().apply {
            if (pageBuffer > 0) receiveBufferSize = pageBuffer
            connect(InetSocketAddress(loopback, proxySide.localPort))
        }
        private val client = proxySide.accept().apply { if (pageBuffer > 0) sendBufferSize = pageBuffer }
        private val upstream = Socket().apply { connect(InetSocketAddress(loopback, origin.localPort)) }
        val site: Socket = origin.accept()
        val piping = thread { LoopbackProxy.pipe(client.getInputStream(), client, upstream, idleMs, tickMs) }

        fun ended(withinMs: Long): Boolean { piping.join(withinMs); return !piping.isAlive }

        override fun close() {
            listOf(page, site, client, upstream, proxySide, origin).forEach { runCatching { it.close() } }
        }
    }

    @Test(timeout = 10_000)
    fun aTunnelWhereNothingMovesIsClosed() = Rig(idleMs = 300, tickMs = 50).use { rig ->
        assertTrue(rig.ended(3_000))
        assertEquals(-1, rig.page.getInputStream().read())
        assertEquals(-1, rig.site.getInputStream().read())
    }

    @Test(timeout = 10_000)
    fun aSteadyStreamOneWayOutlivesTheIdleTime() = Rig(idleMs = 300, tickMs = 50).use { rig ->
        thread {
            val out = rig.site.getOutputStream()
            repeat(12) { out.write('a'.code + it); out.flush(); Thread.sleep(100) }
            rig.site.close()
        }
        val got = rig.page.getInputStream().readBytes().decodeToString()
        assertEquals("abcdefghijkl", got)
    }

    @Test(timeout = 10_000)
    fun aPageThatStopsReadingKeepsItsTunnel() = Rig(idleMs = 200, tickMs = 50, pageBuffer = 4096).use { rig ->
        val size = 8 * 1024 * 1024
        thread {
            runCatching {
                rig.site.getOutputStream().write(ByteArray(size))
                rig.site.close()
            }
        }
        Thread.sleep(1_500) // a paused video: the page reads nothing for many idle times
        assertTrue(rig.piping.isAlive)
        assertEquals(size, rig.page.getInputStream().readBytes().size)
    }

    @Test(timeout = 10_000)
    fun oneSidesEndIsPassedOnAsAHalfClose() = Rig(idleMs = 60_000, tickMs = 50).use { rig ->
        rig.page.getOutputStream().write("hi".toByteArray())
        rig.page.shutdownOutput()
        assertEquals("hi", rig.site.getInputStream().readBytes().decodeToString())
        rig.site.getOutputStream().write("bye".toByteArray())
        rig.site.close()
        assertEquals("bye", rig.page.getInputStream().readBytes().decodeToString())
        assertTrue(rig.ended(2_000))
    }

    @Test(timeout = 10_000)
    fun aFailureOnOneSideClosesTheOther() = Rig(idleMs = 60_000, tickMs = 50).use { rig ->
        rig.site.setSoLinger(true, 0)
        rig.site.close() // a reset, not an end of stream
        assertTrue(rig.ended(2_000)) // the page keeps its side open and says nothing
        assertEquals(-1, rig.page.getInputStream().read())
    }
}

/** [LoopbackProxy.ClosingResponse]: a plain HTTP answer tells the page to close the connection after it. */
class ClosingResponseTest {

    /** [answer] written in pieces of [step] bytes, as a socket may hand it over. */
    private fun through(answer: String, step: Int = Int.MAX_VALUE): String {
        val sink = java.io.ByteArrayOutputStream()
        val filter = LoopbackProxy.ClosingResponse(sink)
        val bytes = answer.toByteArray(Charsets.ISO_8859_1)
        var i = 0
        while (i < bytes.size) {
            val n = minOf(step, bytes.size - i)
            filter.write(bytes, i, n)
            i += n
        }
        return sink.toString(Charsets.ISO_8859_1.name())
    }

    @Test
    fun aKeptOpenAnswerIsToldToClose() {
        val answer = "HTTP/1.1 200 OK\r\nContent-Type: image/gif\r\nContent-Length: 5\r\nConnection: keep-alive, X-Hop\r\n" +
            "Keep-Alive: timeout=60\r\nX-Hop: 1\r\nX-End: 2\r\n\r\nGIF89"
        val expected = "HTTP/1.1 200 OK\r\nContent-Type: image/gif\r\nContent-Length: 5\r\nX-End: 2\r\n" +
            "Connection: close\r\nProxy-Connection: close\r\n\r\nGIF89"
        assertEquals(expected, through(answer))
        assertEquals(expected, through(answer, step = 1))
        assertEquals(expected, through(answer, step = 7))
    }

    @Test
    fun theBodyPassesByteForByteEvenWhenItLooksLikeAHead() {
        val body = "HTTP/1.1 200 OK\r\nConnection: keep-alive\r\n\r\nÿ\u0000"
        val got = through("HTTP/1.0 404 Not Found\r\nContent-Length: ${body.length}\r\n\r\n$body", step = 3)
        assertEquals("HTTP/1.0 404 Not Found\r\nContent-Length: ${body.length}\r\nConnection: close\r\n" +
            "Proxy-Connection: close\r\n\r\n$body", got)
    }

    @Test
    fun interimAnswersPassAndTheFinalOneIsToldToClose() {
        val got = through("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: </a.css>\r\n\r\n" +
            "HTTP/1.1 204\r\nConnection: keep-alive\r\n\r\n")
        assertEquals("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: </a.css>\r\n\r\n" +
            "HTTP/1.1 204\r\nConnection: close\r\nProxy-Connection: close\r\n\r\n", got)
    }

    @Test
    fun aSwitchOfProtocolOrAnAnswerThatIsNotHttpPassesUnchanged() {
        val upgrade = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n\u0081\u0002hi"
        assertEquals(upgrade, through(upgrade, step = 2))
        assertEquals("<html>hello", through("<html>hello", step = 1))
    }

    @Test
    fun aFoldedLineGoesWithItsHeader() {
        val got = through("HTTP/1.1 200 OK\r\nKeep-Alive: timeout=5,\r\n max=100\r\nX-A: 1,\r\n\t2\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK\r\nX-A: 1,\r\n\t2\r\nConnection: close\r\nProxy-Connection: close\r\n\r\n", got)
    }

    @Test(expected = java.io.IOException::class)
    fun aHeadThatNeverEndsIsRefused() {
        through("HTTP/1.1 200 OK\r\n" + "X: y\r\n".repeat(20_000))
    }

    @Test(timeout = 10_000)
    fun aPlainRequestsTunnelPassesTheAnswerToldToClose() {
        val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        ServerSocket(0, 1, loopback).use { proxySide ->
            ServerSocket(0, 1, loopback).use { origin ->
                val page = Socket().apply { connect(InetSocketAddress(loopback, proxySide.localPort)) }
                val client = proxySide.accept()
                val upstream = Socket().apply { connect(InetSocketAddress(loopback, origin.localPort)) }
                val site = origin.accept()
                thread { LoopbackProxy.pipe(client.getInputStream(), client, upstream, 60_000, 50, oneRequest = true) }
                // A site that keeps its side open after answering.
                site.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: keep-alive\r\n\r\nok".toByteArray())
                val want = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\nProxy-Connection: close\r\n\r\nok"
                val got = ByteArray(want.length)
                java.io.DataInputStream(page.getInputStream()).readFully(got)
                assertEquals(want, got.decodeToString())
                listOf(page, client, upstream, site).forEach { runCatching { it.close() } }
            }
        }
    }
}
