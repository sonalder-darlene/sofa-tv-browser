package app.sofabrowser.player

/**
 * What an HLS master playlist says about each picture it offers, before anything loads: Media3 learns a variant's
 * colours only once it plays it, so HDR10 and SDR versions of one size would look the same in the quality menu.
 * Also the rule for a quality picked by hand on another video of the site. Plain Kotlin, so it is unit-tested.
 */
object Variants {

    /** VIDEO-RANGE as the playlist writes it; UNKNOWN when it doesn't say. */
    enum class Range { SDR, PQ, HLG, UNKNOWN }

    /** One #EXT-X-STREAM-INF: its BANDWIDTH, RESOLUTION, CODECS, FRAME-RATE and VIDEO-RANGE (0 or null when absent). */
    data class Variant(val bandwidth: Int, val width: Int, val height: Int, val codecs: String?, val frameRate: Float, val range: Range)

    /** Every variant of the HLS master playlist [master], in its order. */
    fun parse(master: String): List<Variant> = master.lineSequence().map { it.trim() }
        .filter { it.startsWith("#EXT-X-STREAM-INF:") }
        .map { line ->
            fun attr(name: String) = Regex("""(?:^|[:,])$name=("[^"]*"|[^,]*)""").find(line)?.groupValues?.get(1)?.trim('"')
            val size = attr("RESOLUTION")?.split('x')
            Variant(attr("BANDWIDTH")?.toIntOrNull() ?: 0, size?.getOrNull(0)?.toIntOrNull() ?: 0, size?.getOrNull(1)?.toIntOrNull() ?: 0,
                attr("CODECS"), attr("FRAME-RATE")?.toFloatOrNull() ?: 0f,
                when (attr("VIDEO-RANGE")) { "SDR" -> Range.SDR; "PQ" -> Range.PQ; "HLG" -> Range.HLG; else -> Range.UNKNOWN })
        }.toList()

    /**
     * Whether [master] declares no VIDEO-RANGE yet offers one size and codec twice: as Vimeo does with HDR10 and SDR
     * versions, which only their init segments tell apart ([rangeOfInit]).
     */
    fun twinsWithoutRange(master: String): Boolean {
        val v = parse(master)
        return v.none { it.range != Range.UNKNOWN } && v.groupBy { Triple(it.width, it.height, it.codecs) }.values.any { it.size > 1 }
    }

    /** The address of each variant of [master], in the order of [parse]. */
    fun addresses(master: String): List<String> {
        val lines = master.lines().map { it.trim() }
        return lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF:") }
            .mapNotNull { i -> lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } }
    }

    /** [master] with VIDEO-RANGE added to the variants whose range was learnt elsewhere ([ranges] by address). */
    fun withRanges(master: String, ranges: Map<String, Range>): String {
        val lines = master.lines()
        return lines.mapIndexed { i, line ->
            if (!line.trim().startsWith("#EXT-X-STREAM-INF:") || "VIDEO-RANGE=" in line) return@mapIndexed line
            val address = lines.drop(i + 1).map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            when (val r = ranges[address]) {
                Range.SDR, Range.PQ, Range.HLG -> "$line,VIDEO-RANGE=${r.name}"
                else -> line
            }
        }.joinToString("\n")
    }

    /**
     * The range an init segment's start ([bytes]) declares in its colr box (ISO/IEC 23001-8 transfer: 16 is PQ, 18
     * HLG, the rest SDR); UNKNOWN without one.
     */
    fun rangeOfInit(bytes: ByteArray): Range {
        for (i in 0..bytes.size - 12) {
            if (bytes[i] != 'c'.code.toByte() || bytes[i + 1] != 'o'.code.toByte() || bytes[i + 2] != 'l'.code.toByte() ||
                bytes[i + 3] != 'r'.code.toByte()) continue
            val type = String(bytes, i + 4, 4, Charsets.ISO_8859_1)
            if (type != "nclx" && type != "nclc") continue
            return when (((bytes[i + 10].toInt() and 0xff) shl 8) or (bytes[i + 11].toInt() and 0xff)) {
                16 -> Range.PQ
                18 -> Range.HLG
                else -> Range.SDR
            }
        }
        return Range.UNKNOWN
    }

    /** The HDR formats a TV's panel shows. */
    data class Panel(val hdr10: Boolean, val hlg: Boolean, val dolbyVision: Boolean)

    /**
     * The variants Auto keeps to when [variants] mix ranges: the HDR ones [panel] shows (HDR is the default where the
     * TV has it), else the SDR ones, so a film never turns from HDR to SDR, or shows washed out, halfway. Null when
     * there is nothing to choose between, or a range is unknown.
     */
    fun autoKeeps(variants: List<Variant>, panel: Panel): Set<Variant>? {
        if (variants.any { it.range == Range.UNKNOWN }) return null
        fun shown(v: Variant) = when (v.range) {
            Range.PQ -> if (dolbyVision(v.codecs)) panel.dolbyVision else panel.hdr10
            Range.HLG -> panel.hlg
            else -> false
        }
        val hdr = variants.filter(::shown)
        val keep = hdr.ifEmpty { variants.filter { it.range == Range.SDR } }
        return keep.toSet().takeIf { keep.isNotEmpty() && keep.size < variants.size }
    }

    private fun dolbyVision(codecs: String?) = codecs.orEmpty().split(',').any { it.trim().lowercase().let { c -> c.startsWith("dvh") || c.startsWith("dva") } }

    /**
     * The name of a variant's dynamic range, as the stream declares it: PQ is HDR10, or Dolby Vision with its codec.
     * SDR is named only when the stream [mixed] it with HDR, where it tells two entries of one size apart.
     */
    fun rangeName(range: Range, codecs: String?, mixed: Boolean): String? = when (range) {
        Range.PQ -> if (dolbyVision(codecs)) "Dolby Vision" else "HDR10"
        Range.HLG -> "HLG"
        Range.SDR -> if (mixed) "SDR" else null
        Range.UNKNOWN -> null
    }

    /** A menu entry: "1144p 50 HEVC HDR10"; the rate only above 30, where it is worth knowing. */
    fun label(height: Int, frameRate: Float, codec: String?, range: String?): String = listOfNotNull(
        if (height > 0) "${height}p" else null,
        frameRate.takeIf { it > 30.5f }?.let { "%.0f".format(java.util.Locale.ROOT, it) },
        codec, range,
    ).joinToString(" ").ifEmpty { "?" }

    /** A picked quality, kept for the site: size, codec name and range name as the menu showed them. */
    data class Quality(val height: Int, val codec: String?, val range: String?)

    /**
     * Which of [offered] to pick for [wanted]: the same, else another of its size, else the largest below it.
     * Null (Auto) when every one is larger.
     */
    fun closest(wanted: Quality, offered: List<Quality>): Int? {
        offered.indexOf(wanted).takeIf { it >= 0 }?.let { return it }
        val same = offered.withIndex().filter { it.value.height == wanted.height }
        (same.firstOrNull { it.value.range == wanted.range } ?: same.firstOrNull())?.let { return it.index }
        return offered.withIndex().filter { it.value.height < wanted.height }.maxByOrNull { it.value.height }?.index
    }
}
