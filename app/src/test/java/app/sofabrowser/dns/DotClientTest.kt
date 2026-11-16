package app.sofabrowser.dns

import app.sofabrowser.dns.PlainDnsClientTest.Companion.answer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** DNS over TLS framing and connection reuse; the TLS itself is the platform's, set up as for DNS over HTTPS. */
class DotClientTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val server = ServerSocket(0, 8, loopback)
    private val accepted = AtomicInteger()

    @After
    fun closeServer() = server.close()

    /** Answers [perConnection] queries on each connection, then closes it; [reply] makes each answer. */
    private fun serve(perConnection: Int = Int.MAX_VALUE, reply: (ByteArray) -> ByteArray = { answer(it, byteArrayOf(192.toByte(), 0, 2, 1)) }) {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: IOException) { return@thread }
                accepted.incrementAndGet()
                thread(isDaemon = true) {
                    s.use {
                        val input = BufferedInputStream(it.getInputStream())
                        repeat(perConnection) { _ ->
                            val q = try { DnsWire.readFramed(input) } catch (_: IOException) { return@thread }
                            DnsWire.writeFramed(it.getOutputStream(), reply(q))
                        }
                    }
                }
            }
        }
    }

    private fun client() = DotClient("dns.example", server.localPort, connect = { Socket(loopback, server.localPort) })

    @Test
    fun queriesAreLengthFramedAndShareOneConnection() {
        serve()
        val c = client()
        repeat(3) { assertEquals(listOf(InetAddress.getByName("192.0.2.1")), c.query("example.com", DnsMessage.A).addresses) }
        assertEquals(1, accepted.get())
        c.close()
    }

    @Test
    fun aConnectionTheServerClosedIsReplacedOnce() {
        serve(perConnection = 1)
        val c = client()
        repeat(3) { assertEquals(1, c.query("example.com", DnsMessage.A).addresses.size) }
        assertEquals(3, accepted.get())
    }

    @Test
    fun droppingIdleConnectionsOpensANewOneNextTime() {
        serve()
        val c = client()
        c.query("example.com", DnsMessage.A)
        c.dropIdle()
        c.query("example.com", DnsMessage.A)
        assertEquals(2, accepted.get())
    }

    @Test
    fun anAnswerToAnotherQueryIsAFailure() {
        serve { q -> answer(q.copyOf().also { it[0] = (it[0] + 1).toByte() }, byteArrayOf(6, 6, 6, 6)) }
        val failed = try { client().query("example.com", DnsMessage.A); false } catch (_: IOException) { true }
        assertTrue(failed)
    }

    @Test
    fun theQueryIdIsNotAlwaysZero() {
        val ids = mutableSetOf<Int>()
        serve { q -> synchronized(ids) { ids += DnsMessage.id(q) }; answer(q, byteArrayOf(192.toByte(), 0, 2, 1)) }
        val c = client()
        repeat(4) { c.query("example.com", DnsMessage.A) }
        assertFalse(synchronized(ids) { ids == setOf(0) })
        assertTrue(synchronized(ids) { ids.size >= 3 })
    }
}
