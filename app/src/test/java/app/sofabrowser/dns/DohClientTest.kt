package app.sofabrowser.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DohClientTest {

    private fun read(text: String) = DohClient.readResponse(ByteArrayInputStream(text.toByteArray(Charsets.ISO_8859_1)))

    @Test
    fun aSizedAnswerKeepsTheConnection() {
        val r = read("HTTP/1.1 200 OK\r\nContent-Type: application/dns-message\r\nContent-Length: 3\r\n\r\nabcNEXT")
        assertEquals(200, r.status)
        assertArrayEquals("abc".toByteArray(), r.body)
        assertTrue(r.keepAlive)
    }

    @Test
    fun aChunkedAnswerIsJoined() {
        val r = read("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nab\r\n1;x=y\r\nc\r\n0\r\n\r\n")
        assertArrayEquals("abc".toByteArray(), r.body)
        assertTrue(r.keepAlive)
    }

    @Test
    fun connectionCloseOrNoLengthEndsTheConnection() {
        assertFalse(read("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Length: 1\r\n\r\na").keepAlive)
        val r = read("HTTP/1.1 200 OK\r\n\r\nabc")
        assertArrayEquals("abc".toByteArray(), r.body)
        assertFalse(r.keepAlive)
    }

    @Test(expected = IOException::class)
    fun aCutOffAnswerThrows() {
        read("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nabc")
    }

    @Test
    fun anEndpointKeepsItsPathAndQuery() {
        val e = DohEndpoint("https://freedns.example/p0?x=1", listOf("192.0.2.1"))
        assertEquals("freedns.example", e.host)
        assertEquals(443, e.port)
        assertEquals("/p0?x=1", e.path)
        assertEquals("/", DohEndpoint("https://dns.example:8443").path)
        assertEquals(8443, DohEndpoint("https://dns.example:8443").port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aPlainHttpEndpointIsRefused() {
        DohEndpoint("http://dns.example/dns-query")
    }
}
