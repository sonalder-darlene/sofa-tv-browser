package app.sofabrowser.dns

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The smallest HTTP/2 client (RFC 9113) that DNS over HTTPS needs: one POST at a time on one connection, many
 * requests over its life. Several resolvers answer only over HTTP/2. Header compression (HPACK, RFC 7541) is kept
 * to the static table: Sofa sets the server's dynamic table size to 0 and reads only `:status` from the answer
 * (any other header is skipped). Not thread-safe: the caller holds the connection for each exchange.
 */
class Http2(input: InputStream, private val output: OutputStream) {

    private val input = DataInputStream(input)
    private var nextStream = 1
    /** Set when the server sent GOAWAY or broke the protocol: the connection takes no more requests. */
    var closed = false
        private set

    /** The client preface and settings; call once, right after the TLS handshake. */
    fun start() {
        output.write(PREFACE)
        // HEADER_TABLE_SIZE 0, ENABLE_PUSH 0.
        frame(SETTINGS, 0, 0, byteArrayOf(0, 1, 0, 0, 0, 0, 0, 2, 0, 0, 0, 0))
        output.flush()
    }

    /** One POST to [path] on [authority]; returns the status (0 when it couldn't be read) and the body. */
    fun post(authority: String, path: String, contentType: String, body: ByteArray): Pair<Int, ByteArray> {
        if (closed || nextStream > MAX_STREAM) throw IOException("HTTP/2 connection closed")
        val stream = nextStream
        nextStream += 2
        val headers = ByteArrayOutputStream().apply {
            write(0x83) // :method POST
            write(0x87) // :scheme https
            literal(this, 4, path)
            literal(this, 1, authority)
            literal(this, 31, contentType)
            literal(this, 19, contentType) // accept
            literal(this, 28, body.size.toString())
        }.toByteArray()
        frame(HEADERS, FLAG_END_HEADERS, stream, headers)
        frame(DATA, FLAG_END_STREAM, stream, body)
        output.flush()

        var status = 0
        val data = ByteArrayOutputStream()
        val block = ByteArrayOutputStream()
        while (true) {
            val length = (input.readUnsignedByte() shl 16) or input.readUnsignedShort()
            val type = input.readUnsignedByte()
            val flags = input.readUnsignedByte()
            val id = input.readInt() and 0x7FFFFFFF
            if (length > MAX_FRAME) { closed = true; throw IOException("HTTP/2 frame too long") }
            val payload = ByteArray(length).also { input.readFully(it) }
            when (type) {
                SETTINGS -> if (flags and FLAG_ACK == 0) { frame(SETTINGS, FLAG_ACK, 0, ByteArray(0)); output.flush() }
                PING -> if (flags and FLAG_ACK == 0) { frame(PING, FLAG_ACK, 0, payload); output.flush() }
                GOAWAY -> {
                    closed = true
                    val last = if (payload.size >= 4) int(payload, 0) and 0x7FFFFFFF else 0
                    if (last < stream) throw IOException("HTTP/2 GOAWAY")
                }
                RST_STREAM -> if (id == stream) throw IOException("HTTP/2 stream reset")
                HEADERS, CONTINUATION -> if (id == stream) {
                    block.write(if (type == HEADERS) headerFragment(payload, flags) else payload)
                    if (flags and FLAG_END_HEADERS != 0) {
                        // A second header block is trailers: its status, if any, doesn't count.
                        if (status == 0) status = statusOf(block.toByteArray())
                        block.reset()
                    }
                }
                DATA -> if (id == stream) {
                    val content = unpadded(payload, flags)
                    if (data.size() + content.size > MAX_BODY) { closed = true; throw IOException("DNS answer too long") }
                    data.write(content)
                    // Give the window back, or a long-lived connection stalls after 64 KB of answers.
                    // (Each answer fits the stream's own 64 KB window, so only the connection's is updated.)
                    if (length > 0) { windowUpdate(0, length); output.flush() }
                }
            }
            if (id == stream && (type == HEADERS || type == DATA) && flags and FLAG_END_STREAM != 0) {
                if (block.size() > 0 && status == 0) status = statusOf(block.toByteArray())
                return status to data.toByteArray()
            }
        }
    }

    private fun frame(type: Int, flags: Int, stream: Int, payload: ByteArray) {
        output.write(byteArrayOf((payload.size ushr 16).toByte(), (payload.size ushr 8).toByte(), payload.size.toByte(),
            type.toByte(), flags.toByte(), (stream ushr 24).toByte(), (stream ushr 16).toByte(), (stream ushr 8).toByte(), stream.toByte()))
        output.write(payload)
    }

    private fun windowUpdate(stream: Int, n: Int) =
        frame(WINDOW_UPDATE, 0, stream, byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))

    private fun headerFragment(p: ByteArray, flags: Int): ByteArray {
        var start = 0
        var end = p.size
        if (flags and FLAG_PADDED != 0) {
            if (p.isEmpty()) throw IOException("Bad HTTP/2 HEADERS")
            end -= p[0].toInt() and 0xFF
            start = 1
        }
        if (flags and FLAG_PRIORITY != 0) start += 5
        if (start > end) throw IOException("Bad HTTP/2 HEADERS")
        return p.copyOfRange(start, end)
    }

    private fun unpadded(p: ByteArray, flags: Int): ByteArray {
        if (flags and FLAG_PADDED == 0) return p
        if (p.isEmpty()) throw IOException("Bad HTTP/2 DATA")
        val end = p.size - (p[0].toInt() and 0xFF)
        if (end < 1) throw IOException("Bad HTTP/2 DATA")
        return p.copyOfRange(1, end)
    }

    companion object {
        private val PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(Charsets.US_ASCII)
        private const val DATA = 0
        private const val HEADERS = 1
        private const val RST_STREAM = 3
        private const val SETTINGS = 4
        private const val PING = 6
        private const val GOAWAY = 7
        private const val WINDOW_UPDATE = 8
        private const val CONTINUATION = 9
        private const val FLAG_END_STREAM = 0x1
        private const val FLAG_ACK = 0x1
        private const val FLAG_END_HEADERS = 0x4
        private const val FLAG_PADDED = 0x8
        private const val FLAG_PRIORITY = 0x20
        /** The largest frame a server may send without our asking (SETTINGS_MAX_FRAME_SIZE default). */
        private const val MAX_FRAME = 16_384
        private const val MAX_BODY = 65_535
        private const val MAX_STREAM = 0x3FFFFFFF

        private fun int(b: ByteArray, i: Int) =
            (b[i].toInt() and 0xFF shl 24) or (b[i + 1].toInt() and 0xFF shl 16) or (b[i + 2].toInt() and 0xFF shl 8) or (b[i + 3].toInt() and 0xFF)

        /** A literal header field without indexing, its name from the static table, its value as plain bytes. */
        internal fun literal(out: ByteArrayOutputStream, nameIndex: Int, value: String) {
            integer(out, nameIndex, 4, 0x00)
            val bytes = value.toByteArray(Charsets.ISO_8859_1)
            integer(out, bytes.size, 7, 0x00)
            out.write(bytes)
        }

        /** An HPACK integer with an [n]-bit prefix, the first byte's other bits being [mask]. */
        internal fun integer(out: ByteArrayOutputStream, value: Int, n: Int, mask: Int) {
            val max = (1 shl n) - 1
            if (value < max) { out.write(mask or value); return }
            out.write(mask or max)
            var v = value - max
            while (v >= 128) { out.write((v and 0x7F) or 0x80); v = v ushr 7 }
            out.write(v)
        }

        /** The `:status` of a response header block, or 0 if it can't be told (the body then decides). */
        internal fun statusOf(block: ByteArray): Int {
            var i = 0
            fun readInt(n: Int): Int {
                val max = (1 shl n) - 1
                var v = block[i++].toInt() and max
                if (v < max) return v
                var shift = 0
                while (true) {
                    if (i >= block.size || shift > 21) throw IOException("Bad HPACK integer")
                    val b = block[i++].toInt() and 0xFF
                    v += (b and 0x7F) shl shift
                    shift += 7
                    if (b and 0x80 == 0) return v
                }
            }
            fun readString(): Pair<Boolean, ByteArray> {
                if (i >= block.size) throw IOException("Bad HPACK string")
                val huffman = block[i].toInt() and 0x80 != 0
                val length = readInt(7)
                if (i + length > block.size) throw IOException("Bad HPACK string")
                return huffman to block.copyOfRange(i, i + length).also { i += length }
            }
            try {
                while (i < block.size) {
                    val b = block[i].toInt() and 0xFF
                    when {
                        b and 0x80 != 0 -> STATIC_STATUS[readInt(7)]?.let { return it }
                        b and 0xE0 == 0x20 -> readInt(5) // dynamic table size update
                        else -> {
                            val nameIndex = readInt(if (b and 0x40 != 0) 6 else 4)
                            val name = if (nameIndex == 0) readString() else null
                            val (huffman, value) = readString()
                            val isStatus = nameIndex in 8..14 || (name != null && !name.first && String(name.second, Charsets.ISO_8859_1) == ":status")
                            if (isStatus) return (if (huffman) huffmanDigits(value) else String(value, Charsets.ISO_8859_1)).toIntOrNull() ?: 0
                        }
                    }
                }
            } catch (_: IndexOutOfBoundsException) {
            } catch (_: IOException) {
            }
            return 0
        }

        private val STATIC_STATUS = mapOf(8 to 200, 9 to 204, 10 to 206, 11 to 304, 12 to 400, 13 to 404, 14 to 500)

        /**
         * Decodes a Huffman-coded string of digits (RFC 7541 Appendix B: '0'–'2' are 5-bit codes 0–2, '3'–'9' are
         * 6-bit codes 0x19–0x1F); anything else gives "" (unknown).
         */
        internal fun huffmanDigits(bytes: ByteArray): String {
            val sb = StringBuilder()
            var bits = 0L
            var count = 0
            fun take(n: Int) = ((bits ushr (count - n)) and ((1L shl n) - 1)).toInt()
            for (byte in bytes) {
                bits = (bits shl 8) or (byte.toLong() and 0xFF)
                count += 8
                while (count >= 6 || (count >= 5 && take(5) <= 2)) {
                    val five = take(5)
                    if (five <= 2) { sb.append('0' + five); count -= 5; continue }
                    if (count < 6) break
                    val six = take(6)
                    if (six in 0x19..0x1F) { sb.append('3' + (six - 0x19)); count -= 6 } else break
                }
                bits = bits and ((1L shl count) - 1)
            }
            // What's left must be padding: fewer than 8 bits, all ones.
            return if (count < 8 && take(count) == (1 shl count) - 1) sb.toString() else ""
        }
    }
}
