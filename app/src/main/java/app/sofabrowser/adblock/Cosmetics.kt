package app.sofabrowser.adblock

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** One element-hiding rule from a list, with its domains already split out. */
class CosmeticRule(val kind: Int, val domain: String, val selector: String) {
    companion object {
        /** "##.ad": hidden everywhere. */
        const val GENERIC = 0
        /** "example.com##.ad": hidden on [domain] and its subdomains ("example.*" covers every ending). */
        const val SPECIFIC = 1
        /** "example.com#@#.ad", or "~example.com##.ad": not hidden on [domain]. */
        const val EXCEPTION = 2
        /** "#@#.ad": never hidden. */
        const val GENERIC_EXCEPTION = 3

        /**
         * Reads "domains##selector" or "domains#@#selector" into [out]; returns false for anything Sofa can't apply
         * safely: scriptlets, HTML filtering, procedural selectors and CSS injection. A selector that could break out
         * of its CSS rule ("{", "}", comments, at-rules) is refused too, since lists are only data.
         */
        fun parse(line: String, out: MutableList<CosmeticRule>): Boolean {
            val m = SEPARATOR.find(line) ?: return false
            val domains = line.substring(0, m.range.first)
            val exception = m.groupValues[1] == "@"
            if (m.groupValues[2].isNotEmpty()) return false // #?#, #$#, #%#: extended syntaxes
            val selector = line.substring(m.range.last + 1).trim()
            if (!usable(selector)) return false
            val include = ArrayList<String>()
            val exclude = ArrayList<String>()
            for (raw in domains.split(',')) {
                val d = raw.trim().lowercase()
                if (d.isEmpty() || d == "*") continue
                if ('/' in d || ' ' in d) return false // uBlock's regular-expression domains
                if (d.startsWith('~')) exclude += d.substring(1).trimEnd('.') else include += d.trimEnd('.')
            }
            when {
                exception && include.isEmpty() -> out += CosmeticRule(GENERIC_EXCEPTION, "", selector)
                exception -> include.forEach { out += CosmeticRule(EXCEPTION, it, selector) }
                include.isEmpty() -> {
                    out += CosmeticRule(GENERIC, "", selector)
                    exclude.forEach { out += CosmeticRule(EXCEPTION, it, selector) }
                }
                else -> {
                    include.forEach { out += CosmeticRule(SPECIFIC, it, selector) }
                    exclude.forEach { out += CosmeticRule(EXCEPTION, it, selector) }
                }
            }
            return true
        }

        /** True when [line] is written in an element-hiding syntax, supported or not, so it isn't read as a network rule. */
        fun looksCosmetic(line: String) = ('#' in line || '$' in line) &&
            (SEPARATOR.containsMatchIn(line) || "\$\$" in line || "\$@\$" in line)

        internal fun usable(selector: String): Boolean {
            if (selector.isEmpty() || selector.length > 1000) return false
            val first = selector[0]
            if (first == '+' || first == '^' || first == '@') return false // scriptlets, HTML filters, at-rules
            if ('{' in selector || '}' in selector || "/*" in selector || '\n' in selector) return false
            return PROCEDURAL.none { it in selector }
        }

        /**
         * The class or id an element must carry for [selector] to match it, as ".name" or "#name", when the selector
         * starts with one (Brave and uBlock call these "lowly generic"); null for any other selector, which then
         * applies to every page.
         */
        fun key(selector: String): String? {
            if (selector.length < 2 || (selector[0] != '.' && selector[0] != '#')) return null
            var end = 1
            while (end < selector.length && selector[end].let { it.isLetterOrDigit() || it == '-' || it == '_' }) end++
            if (end == 1 || selector[1].isDigit()) return null
            if (end < selector.length && selector[end] == '\\') return null
            return selector.substring(0, end)
        }

        fun write(rules: List<CosmeticRule>, out: DataOutputStream) {
            out.writeInt(rules.size)
            for (r in rules) {
                out.writeByte(r.kind)
                out.writeUTF(r.domain)
                out.writeUTF(r.selector)
            }
        }

        fun read(input: DataInputStream): List<CosmeticRule> {
            val n = input.readInt()
            if (n < 0) throw IOException("bad count")
            return List(n) {
                val kind = input.readUnsignedByte()
                if (kind > GENERIC_EXCEPTION) throw IOException("bad kind")
                CosmeticRule(kind, input.readUTF(), input.readUTF())
            }
        }

        // domains, then ## or #@#, or an extended separator such as #?#, #$#, #@$?#, #%#
        private val SEPARATOR = Regex("#(@?)([?$%]{0,2})#")

        /** uBlock and Adblock Plus procedural operators, which are not CSS: a page would just drop them. */
        private val PROCEDURAL = listOf(
            ":has-text(", ":-abp-", ":upward(", ":remove(", ":remove-attr(", ":remove-class(", ":style(", ":xpath(",
            ":matches-", ":min-text-length(", ":watch-attr(", ":others(", ":if(", ":if-not(", ":nth-ancestor(",
            ":contains(", ":shadow(",
        )
    }
}

/**
 * Element hiding for all lists together, laid out the way Brave and uBlock do it so pages stay fast on small TVs:
 * selectors for one site are looked up by host; generic selectors that start with a class or id are looked up only
 * for the classes and ids a page actually uses; only the few remaining generic selectors go to every page.
 * Selectors are kept as newline-joined strings, a fraction of the memory of one object per rule.
 */
class CosmeticIndex private constructor(
    private val specific: SelectorTable,
    private val exceptions: SelectorTable,
    private val byKey: SelectorTable,
    private val everywhere: List<String>,
) {
    /** What one page gets: [selectors] to hide now, and whether to look up generic ones by class and id. */
    class Page(val selectors: List<String>, val generic: Boolean, internal val except: Set<String>)

    val size: Int = specific.count + byKey.count + everywhere.size

    /** The selectors for [host]; [generic] is false on pages with a $generichide exception. */
    fun page(host: String, generic: Boolean): Page {
        val names = namesFor(host)
        val except = HashSet<String>()
        names.forEach { n -> exceptions[n]?.let { except += it.split('\n') } }
        val out = LinkedHashSet<String>()
        names.forEach { n -> specific[n]?.split('\n')?.forEach { if (it !in except) out += it } }
        if (generic) everywhere.forEach { if (it !in except) out += it }
        return Page(out.toList(), generic && byKey.count > 0, except)
    }

    /** Generic selectors for the classes and ids ([keys], as ".name" and "#name") a page uses. */
    fun forKeys(page: Page, keys: Collection<String>): List<String> {
        if (!page.generic) return emptyList()
        val out = ArrayList<String>()
        for (k in keys) byKey[k]?.split('\n')?.forEach { if (it !in page.except) out += it }
        return out
    }

    /** "a.b.example.co.uk" is looked up as itself, "b.example.co.uk", "example.co.uk", "co.uk", "uk", and "a.b.example.*" … "example.*". */
    private fun namesFor(host: String): List<String> {
        val names = ArrayList<String>()
        var h = host
        while (true) {
            names += h
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        var e = Sites.withoutSuffix(host)
        while (e != null) {
            names += "$e.*"
            val dot = e.indexOf('.')
            e = if (dot < 0) null else e.substring(dot + 1)
        }
        return names
    }

    class Builder {
        private val specific = HashMap<String, StringBuilder>()
        private val exceptions = HashMap<String, StringBuilder>()
        private val byKey = HashMap<String, StringBuilder>()
        private val everywhere = LinkedHashSet<String>()
        private val neverHidden = HashSet<String>()

        fun add(rules: List<CosmeticRule>) {
            for (r in rules) when (r.kind) {
                CosmeticRule.GENERIC -> {
                    val key = CosmeticRule.key(r.selector)
                    if (key == null) everywhere += r.selector else append(byKey, key, r.selector)
                }
                CosmeticRule.SPECIFIC -> append(specific, r.domain, r.selector)
                CosmeticRule.EXCEPTION -> append(exceptions, r.domain, r.selector)
                CosmeticRule.GENERIC_EXCEPTION -> neverHidden += r.selector
            }
        }

        private fun append(map: HashMap<String, StringBuilder>, key: String, selector: String) {
            val sb = map[key]
            if (sb == null) map[key] = StringBuilder(selector) else sb.append('\n').append(selector)
        }

        fun build(): CosmeticIndex {
            fun HashMap<String, StringBuilder>.finish(): SelectorTable {
                val out = HashMap<String, String>(size * 2)
                for ((k, v) in this) {
                    val kept = v.split('\n').filter { it !in neverHidden }.distinct()
                    if (kept.isNotEmpty()) out[k] = kept.joinToString("\n")
                }
                return SelectorTable.of(out)
            }
            return CosmeticIndex(specific.finish(), exceptions.finish(), byKey.finish(), everywhere.filter { it !in neverHidden })
        }
    }

    companion object {
        val EMPTY = Builder().build()
    }
}

/**
 * Newline-joined selectors by domain or class, packed like DomainBlocklist: sorted 64-bit hashes of the keys, and
 * every value as a range of one shared string. About 16 bytes per key on top of the selector text itself, where a
 * HashMap of strings costs over 100.
 */
class SelectorTable private constructor(private val hashes: LongArray, private val starts: IntArray, private val blob: String) {

    /** Number of selectors in the table. */
    val count: Int = if (hashes.isEmpty()) 0 else blob.count { it == '\n' } + 1

    operator fun get(key: String): String? {
        val i = hashes.binarySearch(DomainBlocklist.fnv1a(key))
        if (i < 0) return null
        return blob.substring(starts[i], if (i + 1 < starts.size) starts[i + 1] - 1 else blob.length)
    }

    companion object {
        fun of(map: Map<String, String>): SelectorTable {
            val entries = map.entries.map { DomainBlocklist.fnv1a(it.key) to it.value }.sortedBy { it.first }
            val starts = IntArray(entries.size)
            val blob = StringBuilder()
            entries.forEachIndexed { i, (_, v) ->
                if (i > 0) blob.append('\n')
                starts[i] = blob.length
                blob.append(v)
            }
            return SelectorTable(LongArray(entries.size) { entries[it].first }, starts, blob.toString())
        }
    }
}
