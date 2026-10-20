package app.sofabrowser.adblock

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A set of blocked domains stored as sorted 64-bit FNV-1a hashes. A host is blocked when it, or any parent
 * domain of it, is in the set. About 8 bytes per domain, so a 100k-domain list costs well under a megabyte.
 */
class DomainBlocklist private constructor(private val hashes: LongArray) {

    val size: Int get() = hashes.size

    fun isBlocked(host: String): Boolean {
        if (hashes.isEmpty()) return false
        var h = host.lowercase().trimEnd('.')
        while (true) {
            if (hashes.binarySearch(fnv1a(h)) >= 0) return true
            val dot = h.indexOf('.')
            // Stop before testing a bare TLD such as "com".
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return false
            h = h.substring(dot + 1)
        }
    }

    fun writeTo(out: OutputStream) {
        DataOutputStream(out.buffered()).run {
            writeInt(MAGIC)
            writeInt(hashes.size)
            hashes.forEach(::writeLong)
            flush()
        }
    }

    companion object {
        private const val MAGIC = 0x534f4641 // "SOFA"

        val EMPTY = DomainBlocklist(LongArray(0))

        /** One list blocking whatever [a] or [b] blocks; a domain in both counts once. */
        fun merge(a: DomainBlocklist, b: DomainBlocklist): DomainBlocklist {
            if (b.hashes.isEmpty()) return a
            if (a.hashes.isEmpty()) return b
            // Both are sorted: one pass, no boxing (about 120k entries on every start).
            val x = a.hashes
            val y = b.hashes
            val out = LongArray(x.size + y.size)
            var i = 0
            var j = 0
            var n = 0
            while (i < x.size || j < y.size) {
                val next = when {
                    j == y.size || (i < x.size && x[i] < y[j]) -> x[i++]
                    i == x.size || y[j] < x[i] -> y[j++]
                    else -> { j++; x[i++] }
                }
                if (n == 0 || out[n - 1] != next) out[n++] = next
            }
            return DomainBlocklist(out.copyOf(n))
        }

        /** Reads the compiled form produced by [writeTo]. */
        fun read(input: InputStream): DomainBlocklist = DataInputStream(input.buffered()).use { inp ->
            require(inp.readInt() == MAGIC) { "not a compiled blocklist" }
            val n = inp.readInt()
            DomainBlocklist(LongArray(n) { inp.readLong() })
        }

        /**
         * Parses hosts-file lines ("0.0.0.0 ads.example.com"), plain domain lines, and Adblock Plus
         * domain-anchor rules without options ("||ads.example.com^"). Every other rule is skipped, because a
         * domain matcher can't honour paths, options or exceptions without over-blocking.
         */
        fun parse(reader: BufferedReader): DomainBlocklist {
            val set = HashSet<Long>(1 shl 17)
            reader.forEachLine { raw -> parseLine(raw)?.let { set.add(fnv1a(it)) } }
            return DomainBlocklist(set.toLongArray().also { it.sort() })
        }

        internal fun parseLine(raw: String): String? {
            // Element-hiding rules ("site##.ad", "site#@#.ad", "#?#", "#$#") would otherwise read as a site block.
            if (COSMETIC.containsMatchIn(raw)) return null
            // "#" starts a comment only at the start of a line or after whitespace, as in hosts files.
            var line = raw.replace(TRAILING_COMMENT, "").trim()
            if (line.isEmpty() || line.startsWith('!') || line.startsWith('[')) return null
            when {
                line.startsWith("||") -> {
                    if (!line.endsWith("^")) return null
                    line = line.substring(2, line.length - 1)
                }
                ' ' in line || '\t' in line -> {
                    val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                    if (parts.size != 2 || parts[0] !in SINK_ADDRESSES) return null
                    line = parts[1]
                }
            }
            line = line.lowercase().trimEnd('.')
            if (line in IGNORED_HOSTS || '.' !in line || !line.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
            return line
        }

        private val COSMETIC = Regex("#[@?$%]?#")
        private val TRAILING_COMMENT = Regex("(^|\\s)#.*$")
        private val SINK_ADDRESSES = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
        private val IGNORED_HOSTS = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0")

        fun fnv1a(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (c in s) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
