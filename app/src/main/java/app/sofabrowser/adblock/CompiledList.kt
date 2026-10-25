package app.sofabrowser.adblock

import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_LISTS
import jp.hazuki.yuzubrowser.adblock.filter.abp.ABP_PREFIX_BADFILTER
import jp.hazuki.yuzubrowser.adblock.filter.abp.AbpFilterDecoder
import jp.hazuki.yuzubrowser.adblock.filter.unified.FilterMap
import jp.hazuki.yuzubrowser.adblock.filter.unified.Tag
import jp.hazuki.yuzubrowser.adblock.filter.unified.UnifiedFilter
import jp.hazuki.yuzubrowser.adblock.filter.unified.UnifiedFilterInfo
import jp.hazuki.yuzubrowser.adblock.filter.unified.io.FilterReader
import jp.hazuki.yuzubrowser.adblock.filter.unified.io.FilterWriter
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * One filter list, parsed and ready to load: blocked domains as hashes (most of any list), the remaining network
 * rules for the Yuzu engine by kind (block, exception, important, page-wide exceptions, and $badfilter
 * cancellations), and element-hiding rules. Stored compiled so starting Sofa never re-reads list text.
 */
class CompiledList(
    val title: String?,
    /** Hours until the list asks to be refreshed ("! Expires: 4 days"), or 0 when it doesn't say. */
    val expiresHours: Int,
    val domains: DomainBlocklist,
    val filters: Map<String, List<Pair<String, UnifiedFilter>>>,
    val cosmetics: List<CosmeticRule>,
    /** Rules read but not supported (scriptlets, redirects, header changes …). */
    val skipped: Int,
) {
    /** Rules in use: domains, network rules and element-hiding rules. */
    val rules: Int get() = domains.size + filters.values.sumOf { it.size } + cosmetics.size

    fun writeTo(out: DataOutputStream, source: Long) {
        out.writeInt(MAGIC)
        out.writeInt(FORMAT)
        out.writeLong(source)
        out.writeUTF(title ?: "")
        out.writeInt(expiresHours)
        out.writeInt(skipped)
        domains.writeTo(out)
        for (name in NAMES) FilterWriter(out).writeWithTag(filters[name].orEmpty())
        CosmeticRule.write(cosmetics, out)
        out.writeInt(MAGIC)
        out.flush()
    }

    companion object {
        private const val MAGIC = 0x534f464c // "SOFL"
        /** Bump when the format or the parser changes: older caches are then compiled again from the list text. */
        const val FORMAT = 2

        private val NAMES = ABP_LISTS + ABP_LISTS.map { ABP_PREFIX_BADFILTER + it }

        /** The source stamp written by [writeTo], or null when [input] isn't a current compiled list. */
        fun readSource(input: DataInputStream): Long? {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT) return null
            return input.readLong()
        }

        /** Reads the rest of a compiled list after [readSource]; throws on damage. */
        fun readBody(input: DataInputStream): CompiledList {
            val title = input.readUTF().ifEmpty { null }
            val expires = input.readInt()
            val skipped = input.readInt()
            val domains = DomainBlocklist.readFrom(input)
            val filters = NAMES.associateWith { FilterReader(input).readAll() }
            val cosmetics = CosmeticRule.read(input)
            if (input.readInt() != MAGIC) throw IOException("truncated")
            return CompiledList(title, expires, domains, filters, cosmetics, skipped)
        }

        fun domainsOnly(domains: DomainBlocklist) = CompiledList(null, 0, domains, emptyMap(), emptyList(), 0)

        /**
         * Parses a list in Adblock Plus / uBlock Origin syntax or in hosts format (a list may mix them): comments,
         * "!#if" blocks for other platforms, hosts lines, domain rules, network rules and element hiding. A broken line
         * is skipped, never fatal.
         */
        fun parse(reader: BufferedReader): CompiledList {
            val info = UnifiedFilterInfo()
            val decoder = AbpFilterDecoder()
            val domains = HashSet<Long>(1 shl 15)
            val filters = FilterMap()
            val cosmetics = ArrayList<CosmeticRule>()
            val conditions = Directives()
            var skipped = 0
            var first = true
            var started = false
            var html = false
            reader.forEachLine { raw ->
                var line = raw.trim()
                if (first) { line = line.removePrefix("\uFEFF"); first = false }
                if (line.isEmpty() || html) return@forEachLine
                // A web page (an error or sign-in page served instead of the list) is not a list at all.
                if (!started) { started = true; if (line[0] == '<') { html = true; return@forEachLine } }
                if (line.startsWith("!#")) { conditions.directive(line); return@forEachLine }
                if (!conditions.active) return@forEachLine
                when {
                    line[0] == '!' -> decoder.decodeComment(line, info)
                    line[0] == '[' -> Unit // "[Adblock Plus 2.0]"
                    // "# comment" in hosts files, but "##.ad" and "#@#.ad" hide elements
                    line[0] == '#' && line.length > 1 && line[1] != '#' && line[1] != '@' && line[1] != '?' && line[1] != '$' -> Unit
                    CosmeticRule.looksCosmetic(line) -> if (!runCatching { CosmeticRule.parse(line, cosmetics) }.getOrDefault(false)) skipped++
                    else -> {
                        // Most lines of most lists are a bare domain or a hosts line: no regex for those.
                        val plain = plainDomain(line)
                        if (plain != null) { domains += DomainBlocklist.fnv1a(plain); return@forEachLine }
                        val hosts = hostsLine(line)
                        if (hosts != null) {
                            hosts.forEach { domains += DomainBlocklist.fnv1a(it) }
                            return@forEachLine
                        }
                        val domain = DomainBlocklist.parseLine(line)?.let(::plainDomain)
                        if (domain != null) domains += DomainBlocklist.fnv1a(domain)
                        else if (!runCatching { decoder.decodeFilter(line, filters) }.getOrDefault(false)) skipped++
                    }
                }
            }
            if (html) return CompiledList(null, 0, DomainBlocklist.EMPTY, emptyMap(), emptyList(), 1)
            val tagged = NAMES.associateWith { name -> filters[name].map { Tag.createBest(it) to it } }
            return CompiledList(info.title, info.expires?.coerceAtLeast(0) ?: 0, DomainBlocklist.of(domains), tagged, cosmetics, skipped)
        }

        /** [s] in lower case when it is a host name ("ads.example.com", "x-1.cdn.test."), else null. */
        internal fun plainDomain(s: String): String? {
            val end = if (s.endsWith('.')) s.length - 1 else s.length
            if (end < 3 || !s[0].isLetterOrDigit() || !s[end - 1].isLetterOrDigit()) return null
            var dot = false
            for (i in 0 until end) {
                val c = s[i]
                if (c == '.') { if (s[i - 1] == '.') return null; dot = true }
                else if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c in 'A'..'Z')) return null
            }
            if (!dot) return null
            val host = s.substring(0, end).lowercase()
            return host.takeIf { it !in IGNORED_HOSTS }
        }

        /** The hosts on a hosts-file line pointing at a sink address ("0.0.0.0 a.com b.com # note"), or null. */
        internal fun hostsLine(line: String): List<String>? {
            if (line[0] != '0' && line[0] != '1' && line[0] != ':') return null
            val parts = line.substringBefore('#').split(' ', '\t').filter { it.isNotEmpty() }
            if (parts.size < 2 || parts[0] !in SINKS) return null
            return parts.drop(1).mapNotNull(::plainDomain)
        }

        private val SINKS = setOf("0.0.0.0", "127.0.0.1", "::", "::1")
        private val IGNORED_HOSTS = setOf("localhost.localdomain", "0.0.0.0", "127.0.0.1")
    }

    /**
     * uBlock's "!#if … / !#else / !#endif" blocks. Sofa runs in Chromium's WebView on Android, so only env_chromium
     * and env_mobile are true; lines for other browsers, extensions and capabilities are left out.
     */
    private class Directives {
        private val stack = ArrayList<Boolean>()
        val active get() = stack.all { it }

        fun directive(line: String) {
            when {
                line.startsWith("!#if") -> stack += eval(line.removePrefix("!#if").trim())
                line.startsWith("!#else") -> if (stack.isNotEmpty()) stack[stack.lastIndex] = !stack.last()
                line.startsWith("!#endif") -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
            }
        }

        /** Evaluates "a && !(b || c)" over the known tokens. */
        private fun eval(expr: String): Boolean {
            val tokens = Regex("\\|\\||&&|!|\\(|\\)|[A-Za-z0-9_]+").findAll(expr).map { it.value }.toList()
            var i = 0
            fun or(): Boolean {
                fun and(): Boolean {
                    fun unary(): Boolean = when (tokens.getOrNull(i++)) {
                        "!" -> !unary()
                        "(" -> or().also { if (tokens.getOrNull(i) == ")") i++ }
                        null -> false
                        else -> tokens[i - 1] in TRUE
                    }
                    var v = unary()
                    while (tokens.getOrNull(i) == "&&") { i++; v = unary() && v }
                    return v
                }
                var v = and()
                while (tokens.getOrNull(i) == "||") { i++; v = and() || v }
                return v
            }
            return runCatching { or() }.getOrDefault(false)
        }

        private companion object {
            val TRUE = setOf("env_chromium", "env_mobile")
        }
    }
}
