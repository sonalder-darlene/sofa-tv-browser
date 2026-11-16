package app.sofabrowser.dns

import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * The DNS wire format (RFC 1035), just enough for Custom DNS: one A or AAAA question out, the addresses and the
 * shortest TTL back. A CNAME chain needs no work: a resolver puts the final addresses in the answer section too, so
 * every A or AAAA record in it is taken.
 */
object DnsMessage {

    const val A = 1
    const val AAAA = 28
    const val NOERROR = 0
    const val SERVFAIL = 2
    const val NXDOMAIN = 3

    /** What a resolver said. [ttl] is in seconds, the smallest of the records taken (0 when none). */
    class Answer(val rcode: Int, val addresses: List<InetAddress>, val ttl: Int)

    /**
     * A query for [host]'s [type] records with recursion desired. Over HTTPS the [id] is 0, as RFC 8484 asks, so that
     * identical queries are cached alike; plain DNS and DNS over TLS use a random one. A name that can't be encoded (an
     * empty or over-long label) throws.
     */
    fun query(host: String, type: Int, id: Int = 0): ByteArray {
        val out = ByteArrayOutputStream(32 + host.length)
        out.write(byteArrayOf((id shr 8).toByte(), id.toByte(), 0x01, 0, 0, 1, 0, 0, 0, 0, 0, 0)) // id, RD, one question
        host.trimEnd('.').split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.size in 1..63) { "Bad name: $host" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        require(out.size() <= 12 + 255 + 4) { "Bad name: $host" }
        out.write(byteArrayOf(0, type.toByte(), 0, 1)) // QTYPE, QCLASS IN (types used here fit in one byte)
        return out.toByteArray()
    }

    /** Reads a response; anything malformed throws, so a broken server counts as a failure, never as "no such site". */
    fun parse(msg: ByteArray): Answer {
        require(msg.size >= 12) { "Short DNS message" }
        fun u16(i: Int): Int {
            require(i + 1 < msg.size) { "Truncated DNS message" }
            return (msg[i].toInt() and 0xFF shl 8) or (msg[i + 1].toInt() and 0xFF)
        }
        require(msg[2].toInt() and 0x80 != 0) { "Not a DNS response" }
        require(msg[2].toInt() and 0x02 == 0) { "Truncated DNS response" }
        val rcode = msg[3].toInt() and 0x0F
        val questions = u16(4)
        val answers = u16(6)
        var i = 12
        repeat(questions) { i = skipName(msg, i) + 4 }
        val addresses = ArrayList<InetAddress>()
        var ttl = Int.MAX_VALUE
        repeat(answers) {
            i = skipName(msg, i)
            val type = u16(i)
            val klass = u16(i + 2)
            val recordTtl = (u16(i + 4).toLong() shl 16 or u16(i + 6).toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val length = u16(i + 8)
            i += 10
            require(i + length <= msg.size) { "Truncated DNS record" }
            if (klass == 1 && (type == A && length == 4 || type == AAAA && length == 16)) {
                addresses += InetAddress.getByAddress(msg.copyOfRange(i, i + length))
                ttl = minOf(ttl, recordTtl)
            }
            i += length
        }
        return Answer(rcode, addresses, if (addresses.isEmpty()) 0 else ttl)
    }

    /** A message's id. */
    fun id(msg: ByteArray) = (msg[0].toInt() and 0xFF shl 8) or (msg[1].toInt() and 0xFF)

    /** Whether a response says it was cut short (the TC bit): plain DNS then asks again over TCP. */
    fun isTruncated(msg: ByteArray) = msg.size >= 12 && msg[2].toInt() and 0x02 != 0

    /**
     * Whether [response] is a response to [query] (made by [DnsMessage.query]): the same id and the same one question,
     * the name compared without regard to case. Plain DNS drops anything else, so a forged answer has to guess both.
     */
    fun answers(query: ByteArray, response: ByteArray): Boolean {
        if (response.size < query.size || response[0] != query[0] || response[1] != query[1]) return false
        if (response[2].toInt() and 0x80 == 0) return false // not a response
        if (response[4] != 0.toByte() || response[5] != 1.toByte()) return false // exactly one question
        // The question: labels (lengths ≤ 63, below 'A'), then type and class (below 'A' for A and AAAA).
        for (i in 12 until query.size) if (lower(response[i]) != lower(query[i])) return false
        return true
    }

    private fun lower(b: Byte): Int = (b.toInt() and 0xFF).let { if (it in 'A'.code..'Z'.code) it + 32 else it }

    /** The index just past the name at [start]: labels, ending in a zero byte or a two-byte pointer. */
    private fun skipName(msg: ByteArray, start: Int): Int {
        var i = start
        while (true) {
            require(i < msg.size) { "Truncated DNS name" }
            val len = msg[i].toInt() and 0xFF
            when {
                len == 0 -> return i + 1
                len and 0xC0 == 0xC0 -> return i + 2
                len and 0xC0 != 0 -> throw IllegalArgumentException("Bad DNS label")
                else -> i += 1 + len
            }
        }
    }
}
