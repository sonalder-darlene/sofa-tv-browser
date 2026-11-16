package app.sofabrowser.dns

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

class PlainDnsClientTest {

    private val loopback = InetAddress.getByName("127.0.0.1")
    private val udp = DatagramSocket(0, loopback)
    private val received = mutableListOf<ByteArray>()

    @After
    fun closeServer() = udp.close()

    /** Answers each query that [reply] gives bytes for, on another thread. */
    private fun serve(reply: (index: Int, query: ByteArray) -> List<ByteArray>) {
        thread(isDaemon = true) {
            val buf = ByteArray(512)
            var i = 0
            while (!udp.isClosed) {
                val packet = DatagramPacket(buf, buf.size)
                try { udp.receive(packet) } catch (_: Exception) { return@thread }
                val query = buf.copyOf(packet.length)
                synchronized(received) { received += query }
                reply(i++, query).forEach { udp.send(DatagramPacket(it, it.size, packet.socketAddress)) }
            }
        }
    }

    private fun client(resendMs: Int = 300, timeoutMs: Int = 900) =
        PlainDnsClient(InetSocketAddress(loopback, udp.localPort), resendMs, timeoutMs)

    @Test
    fun eachQueryHasItsOwnRandomId() {
        serve { _, q -> listOf(answer(q, ip = byteArrayOf(192.toByte(), 0, 2, 1))) }
        val c = client()
        repeat(4) { c.query("example.com", DnsMessage.A) }
        val ids = synchronized(received) { received.map { DnsMessage.id(it) } }
        assertTrue("ids $ids", ids.toSet().size >= 3) // four random 16-bit ids: a repeat is very unlikely
    }

    @Test
    fun answersWithAnotherIdOrQuestionAreDropped() {
        serve { _, q ->
            val wrongId = q.copyOf().also { it[1] = (it[1] + 1).toByte() }
            val otherName = DnsMessage.query("example.org", DnsMessage.A, DnsMessage.id(q))
            listOf(answer(wrongId, byteArrayOf(6, 6, 6, 6)), answer(otherName, byteArrayOf(6, 6, 6, 7)), answer(q, byteArrayOf(192.toByte(), 0, 2, 1)))
        }
        val a = client().query("example.com", DnsMessage.A)
        assertEquals(listOf(InetAddress.getByName("192.0.2.1")), a.addresses)
    }

    @Test
    fun theNameIsComparedWithoutCase() {
        serve { _, q -> listOf(answer(q.also { it[13] = 'E'.code.toByte() }, byteArrayOf(192.toByte(), 0, 2, 1))) }
        assertEquals(1, client().query("example.com", DnsMessage.A).addresses.size)
    }

    @Test
    fun aLostDatagramIsSentAgain() {
        serve { i, q -> if (i == 0) emptyList() else listOf(answer(q, byteArrayOf(192.toByte(), 0, 2, 1))) }
        assertEquals(1, client().query("example.com", DnsMessage.A).addresses.size)
        assertEquals(2, synchronized(received) { received.size })
    }

    @Test
    fun noAnswerTimesOut() {
        serve { _, _ -> emptyList() }
        val start = System.nanoTime()
        try {
            client().query("example.com", DnsMessage.A)
            throw AssertionError("answered")
        } catch (_: SocketTimeoutException) {
            val ms = (System.nanoTime() - start) / 1_000_000
            assertTrue("$ms ms", ms in 800..2_000)
        }
        assertEquals(2, synchronized(received) { received.size })
    }

    @Test
    fun aCutShortAnswerIsAskedAgainOverTcp() {
        // TCP on the same port number as the UDP server.
        val tcp = ServerSocket(udp.localPort, 1, loopback)
        thread(isDaemon = true) {
            tcp.accept().use { s ->
                val q = DnsWire.readFramed(s.getInputStream())
                DnsWire.writeFramed(s.getOutputStream(), answer(q, byteArrayOf(192.toByte(), 0, 2, 9)))
            }
        }
        serve { _, q -> listOf(answer(q, byteArrayOf(192.toByte(), 0, 2, 1)).also { it[2] = (it[2].toInt() or 0x02).toByte() }) }
        val a = client().query("example.com", DnsMessage.A)
        assertEquals(listOf(InetAddress.getByName("192.0.2.9")), a.addresses)
        tcp.close()
    }

    @Test
    fun aTcpAnswerToAnotherQuestionIsAFailure() {
        val tcp = ServerSocket(udp.localPort, 1, loopback)
        thread(isDaemon = true) {
            tcp.accept().use { s ->
                val q = DnsWire.readFramed(s.getInputStream())
                val other = q.copyOf().also { it[0] = (it[0] + 1).toByte() }
                DnsWire.writeFramed(s.getOutputStream(), answer(other, byteArrayOf(6, 6, 6, 6)))
            }
        }
        serve { _, q -> listOf(answer(q, byteArrayOf(192.toByte(), 0, 2, 1)).also { it[2] = (it[2].toInt() or 0x02).toByte() }) }
        try {
            client().query("example.com", DnsMessage.A)
            throw AssertionError("answered")
        } catch (e: java.io.IOException) {
            assertEquals("DNS answer doesn't match the question", e.message)
        }
        tcp.close()
    }

    companion object {
        /** A response to [query] (made by DnsMessage.query) with one A record. */
        fun answer(query: ByteArray, ip: ByteArray, ttl: Int = 300): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(query, 0, 2)
            out.write(byteArrayOf(0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)) // response, RD RA, 1 question, 1 answer
            out.write(query, 12, query.size - 12)
            out.write(byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, (ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte(), 0, 4))
            out.write(ip)
            return out.toByteArray()
        }
    }
}
