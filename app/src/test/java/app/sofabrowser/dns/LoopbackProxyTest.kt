package app.sofabrowser.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

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
