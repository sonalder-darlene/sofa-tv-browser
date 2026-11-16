package app.sofabrowser.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress

class DnsMessageTest {

    @Test
    fun aQueryIsTheStandardWireFormatWithIdZero() {
        // The same bytes as the base64url query "AAABAAABAAAAAAAAB2V4YW1wbGUDY29tAAABAAE" (example.com A, id 0).
        val expected = byteArrayOf(0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
            7, 'e'.b, 'x'.b, 'a'.b, 'm'.b, 'p'.b, 'l'.b, 'e'.b, 3, 'c'.b, 'o'.b, 'm'.b, 0, 0, 1, 0, 1)
        assertArrayEquals(expected, DnsMessage.query("example.com", DnsMessage.A))
        assertArrayEquals(expected, DnsMessage.query("example.com.", DnsMessage.A))
        val aaaa = DnsMessage.query("example.com", DnsMessage.AAAA)
        assertEquals(listOf<Byte>(0, 28, 0, 1), aaaa.takeLast(4)) // QTYPE AAAA, QCLASS IN
    }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyLabelCantBeAsked() {
        DnsMessage.query("bad..example", DnsMessage.A)
    }

    @Test
    fun theAddressesComeBackThroughACnameWithTheShortestTtl() {
        val answer = DnsMessage.parse(response(rcode = 0, records = listOf(
            record(type = 5, ttl = 30, data = byteArrayOf(3, 'w'.b, 'w'.b, 'w'.b, 0xC0.toByte(), 12)), // CNAME, skipped
            record(type = 1, ttl = 300, data = byteArrayOf(93, -72, -40, 34)),
            record(type = 1, ttl = 120, data = byteArrayOf(93, -72, -40, 35)),
        )))
        assertEquals(0, answer.rcode)
        assertEquals(listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("93.184.216.35")), answer.addresses)
        assertEquals(120, answer.ttl)
    }

    @Test
    fun anIpv6AnswerIsRead() {
        val v6 = InetAddress.getByName("2001:db8::1").address
        val answer = DnsMessage.parse(response(rcode = 0, type = 28, records = listOf(record(type = 28, ttl = 60, data = v6))))
        assertEquals(InetAddress.getByName("2001:db8::1"), answer.addresses.single())
    }

    @Test
    fun noSuchSiteIsAnAnswerWithNoAddresses() {
        val answer = DnsMessage.parse(response(rcode = 3, records = emptyList()))
        assertEquals(DnsMessage.NXDOMAIN, answer.rcode)
        assertTrue(answer.addresses.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCutOffAnswerIsAFailureNotAnEmptyAnswer() {
        val full = response(rcode = 0, records = listOf(record(type = 1, ttl = 60, data = byteArrayOf(1, 2, 3, 4))))
        DnsMessage.parse(full.copyOf(full.size - 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aQueryEchoedBackIsNotAnAnswer() {
        DnsMessage.parse(DnsMessage.query("example.com", DnsMessage.A))
    }

    @Test
    fun aQueryCarriesTheIdGiven() {
        val q = DnsMessage.query("example.com", DnsMessage.A, 0xBEEF)
        assertEquals(0xBEEF, DnsMessage.id(q))
        assertArrayEquals(DnsMessage.query("example.com", DnsMessage.A).copyOfRange(2, q.size), q.copyOfRange(2, q.size))
    }

    @Test
    fun anAnswerMatchesOnlyItsOwnQuery() {
        val q = DnsMessage.query("example.com", DnsMessage.A, 0x1234)
        val ok = PlainDnsClientTest.answer(q, byteArrayOf(1, 2, 3, 4))
        assertTrue(DnsMessage.answers(q, ok))
        assertFalse(DnsMessage.answers(q, PlainDnsClientTest.answer(DnsMessage.query("example.com", DnsMessage.A, 0x1235), byteArrayOf(1, 2, 3, 4))))
        assertFalse(DnsMessage.answers(q, PlainDnsClientTest.answer(DnsMessage.query("example.com", DnsMessage.AAAA, 0x1234), byteArrayOf(1, 2, 3, 4))))
        assertFalse(DnsMessage.answers(q, PlainDnsClientTest.answer(DnsMessage.query("example.net", DnsMessage.A, 0x1234), byteArrayOf(1, 2, 3, 4))))
        assertFalse("the query itself", DnsMessage.answers(q, q))
        assertFalse("too short", DnsMessage.answers(q, ok.copyOf(20)))
        assertTrue("case", DnsMessage.answers(DnsMessage.query("eXample.COM", DnsMessage.A, 0x1234), ok))
    }

    @Test
    fun theTruncatedBitIsRead() {
        val r = response(rcode = 0, records = emptyList())
        assertFalse(DnsMessage.isTruncated(r))
        assertTrue(DnsMessage.isTruncated(r.copyOf().also { it[2] = (it[2].toInt() or 0x02).toByte() }))
    }

    private val Char.b get() = code.toByte()

    private fun record(type: Int, ttl: Int, data: ByteArray) = ByteArrayOutputStream().apply {
        write(byteArrayOf(0xC0.toByte(), 12, 0, type.toByte(), 0, 1))
        write(byteArrayOf((ttl ushr 24).toByte(), (ttl ushr 16).toByte(), (ttl ushr 8).toByte(), ttl.toByte()))
        write(byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()))
        write(data)
    }.toByteArray()

    private fun response(rcode: Int, records: List<ByteArray>, type: Int = 1) = ByteArrayOutputStream().apply {
        val q = DnsMessage.query("example.com", type)
        write(byteArrayOf(0, 0, 0x81.toByte(), (0x80 or rcode).toByte(), 0, 1, 0, records.size.toByte(), 0, 0, 0, 0))
        write(q, 12, q.size - 12)
        records.forEach { write(it) }
    }.toByteArray()
}
