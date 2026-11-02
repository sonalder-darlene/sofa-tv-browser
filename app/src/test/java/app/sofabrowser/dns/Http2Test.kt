package app.sofabrowser.dns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException

class Http2Test {

    private fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray) = byteArrayOf(
        (payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(), type.toByte(), flags.toByte(),
        (stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte()) + payload

    @Test
    fun hpackIntegersUseTheirPrefixThenSevenBitGroups() {
        fun enc(v: Int, n: Int) = ByteArrayOutputStream().also { Http2.integer(it, v, n, 0) }.toByteArray().toList()
        assertEquals(listOf<Byte>(10), enc(10, 5))
        assertEquals(listOf(31, 0x9A, 10).map { it.toByte() }, enc(1337, 5)) // RFC 7541 C.1.2
        assertEquals(listOf<Byte>(15, 16), enc(31, 4))
    }

    @Test
    fun theStatusIsReadFromTheStaticTableOrALiteral() {
        assertEquals(200, Http2.statusOf(byteArrayOf(0x88.toByte())))
        assertEquals(404, Http2.statusOf(byteArrayOf(0x8D.toByte())))
        // Literal with incremental indexing, name ":status" (index 8), value "505" in plain bytes.
        assertEquals(505, Http2.statusOf(byteArrayOf(0x48, 3, '5'.code.toByte(), '0'.code.toByte(), '5'.code.toByte())))
        // After a dynamic table size update.
        assertEquals(200, Http2.statusOf(byteArrayOf(0x20, 0x88.toByte())))
        assertEquals(0, Http2.statusOf(byteArrayOf(0xC0.toByte()))) // a dynamic entry: unknown
        assertEquals(0, Http2.statusOf(byteArrayOf(0x48, 9)))       // cut off: unknown
    }

    @Test
    fun huffmanCodedDigitsAreDecoded() {
        // '2' 00010, '0' 00000, '0' 00000, then a 1-bit padding: 0001 0000 0000 0001.
        assertEquals("200", Http2.huffmanDigits(byteArrayOf(0x10, 0x01)))
        // '5' 011011, '0' 00000, '3' 011001 (17 bits), then 7 ones of padding: 6C 0C FF.
        assertEquals("503", Http2.huffmanDigits(byteArrayOf(0x6C, 0x0C, 0xFF.toByte())))
        // ":status" by name index, Huffman value "505" (6C 0D FF).
        assertEquals(505, Http2.statusOf(byteArrayOf(0x48, 0x83.toByte(), 0x6C, 0x0D, 0xFF.toByte())))
        assertEquals("", Http2.huffmanDigits(byteArrayOf(0xFE.toByte(), 0x00))) // not digits
    }

    @Test
    fun aPostIsAnsweredAcrossSettingsPingHeadersAndData() {
        val dns = byteArrayOf(1, 2, 3)
        val server = frame(4, 0, 0, ByteArray(0)) +                 // SETTINGS
            frame(6, 0, 0, ByteArray(8) { it.toByte() }) +          // PING
            frame(1, 0x4, 1, byteArrayOf(0x88.toByte())) +          // HEADERS :status 200, END_HEADERS
            frame(0, 0x1 or 0x8, 1, byteArrayOf(2) + dns + byteArrayOf(0, 0)) // DATA, padded, END_STREAM
        val sent = ByteArrayOutputStream()
        val h2 = Http2(ByteArrayInputStream(server), sent)
        h2.start()
        val (status, body) = h2.post("dns.example", "/dns-query", "application/dns-message", byteArrayOf(9))
        assertEquals(200, status)
        assertArrayEquals(dns, body)

        // What went out: preface, SETTINGS, HEADERS + DATA on stream 1, SETTINGS ack, PING ack, connection WINDOW_UPDATE.
        val out = DataInputStream(ByteArrayInputStream(sent.toByteArray()))
        val preface = ByteArray(24).also { out.readFully(it) }
        assertEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n", String(preface))
        val types = ArrayList<Pair<Int, Int>>()
        while (out.available() > 0) {
            val len = (out.readUnsignedByte() shl 16) or out.readUnsignedShort()
            val type = out.readUnsignedByte()
            val flags = out.readUnsignedByte()
            out.readInt()
            out.skipBytes(len)
            types += type to flags
        }
        assertEquals(listOf(4 to 0, 1 to 0x4, 0 to 0x1, 4 to 0x1, 6 to 0x1, 8 to 0), types)
    }

    @Test
    fun aResetStreamOrGoawayIsAFailure() {
        val reset = frame(3, 0, 1, byteArrayOf(0, 0, 0, 2))
        try {
            Http2(ByteArrayInputStream(reset), ByteArrayOutputStream()).post("a", "/", "x", ByteArray(0))
            throw AssertionError()
        } catch (_: IOException) {
        }
        val goaway = frame(7, 0, 0, ByteArray(8))
        val h2 = Http2(ByteArrayInputStream(goaway), ByteArrayOutputStream())
        try {
            h2.post("a", "/", "x", ByteArray(0))
            throw AssertionError()
        } catch (_: IOException) {
        }
        assertTrue(h2.closed)
    }
}
