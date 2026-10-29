package app.sofabrowser.player

import app.sofabrowser.player.Variants.Quality
import app.sofabrowser.player.Variants.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantsTest {

    // Shortened from Vimeo 247443729's master (2026-10-26): HEVC variants in PQ and SDR at the same sizes.
    private val vimeo = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-STREAM-INF:BANDWIDTH=10624558,AVERAGE-BANDWIDTH=8051400,RESOLUTION=2732x1144,FRAME-RATE=50.000,CODECS="hvc1.2.4.L153.90,mp4a.40.2",VIDEO-RANGE=PQ,AUDIO="audio"
        https://vod.test/pq/1144.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=9846018,RESOLUTION=2732x1144,FRAME-RATE=50.000,CODECS="hvc1.1.6.L153.90,mp4a.40.2",VIDEO-RANGE=SDR,AUDIO="audio"
        https://vod.test/sdr/1144.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=23480165,RESOLUTION=4096x1714,FRAME-RATE=50.000,CODECS="hvc1.1.6.L156.90",VIDEO-RANGE=SDR
        https://vod.test/sdr/1714.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=900000,RESOLUTION=960x402,CODECS="dvh1.05.01",VIDEO-RANGE=PQ
        https://vod.test/dv/402.m3u8
    """.trimIndent()

    @Test
    fun theMasterSaysWhatEachVariantIs() {
        val v = Variants.parse(vimeo)
        assertEquals(4, v.size)
        assertEquals(Variants.Variant(10624558, 2732, 1144, "hvc1.2.4.L153.90,mp4a.40.2", 50f, Range.PQ), v[0])
        assertEquals(Range.SDR, v[1].range)
        assertEquals(Range.UNKNOWN, Variants.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\na.m3u8")[0].range)
    }

    @Test
    fun rangesAreNamedAsTheStreamDeclaresThem() {
        assertEquals("HDR10", Variants.rangeName(Range.PQ, "hvc1.2.4.L153.90", mixed = true))
        assertEquals("Dolby Vision", Variants.rangeName(Range.PQ, "dvh1.05.01", mixed = true))
        assertEquals("HLG", Variants.rangeName(Range.HLG, "hvc1.2.4.L153.90", mixed = false))
        assertEquals("SDR", Variants.rangeName(Range.SDR, "hvc1.1.6.L153.90", mixed = true))
        // SDR is said only where the stream also has HDR.
        assertNull(Variants.rangeName(Range.SDR, "avc1.640028", mixed = false))
        assertNull(Variants.rangeName(Range.UNKNOWN, "avc1.640028", mixed = true))
    }

    @Test
    fun hdrAndSdrOfTheSameSizeAreTwoEntries() {
        assertNotEquals(Variants.label(1144, 50f, "HEVC", "HDR10"), Variants.label(1144, 50f, "HEVC", "SDR"))
        assertEquals("1144p 50 HEVC HDR10", Variants.label(1144, 50f, "HEVC", "HDR10"))
        assertEquals("720p H.264", Variants.label(720, 25f, "H.264", null))
    }

    // A quality picked by hand sticks for later videos; one without it gets the closest one below, or Auto.

    private fun q(height: Int, codec: String = "H.264", range: String? = null) = Quality(height, codec, range)

    @Test
    fun theSameQualityIsPickedAgain() {
        assertEquals(1, Variants.closest(q(360), listOf(q(1080), q(360), q(720))))
        assertEquals(2, Variants.closest(q(1144, "HEVC", "SDR"), listOf(q(1144, "HEVC", "HDR10"), q(1714, "HEVC", "SDR"), q(1144, "HEVC", "SDR"))))
    }

    @Test
    fun aMissingQualityFallsToTheClosestBelow() {
        assertEquals(2, Variants.closest(q(544), listOf(q(1080), q(720), q(480), q(240))))
        assertEquals(0, Variants.closest(q(1080, "AV1"), listOf(q(1080, "H.264"), q(720))))
    }

    @Test
    fun nothingBelowMeansAuto() {
        assertNull(Variants.closest(q(240), listOf(q(1080), q(720))))
        assertNull(Variants.closest(q(240), emptyList()))
    }

    // The same master as served later that day: no VIDEO-RANGE, each size twice (one PQ, one SDR), told apart only
    // by the colr box of each variant's init segment (f9297517 and 7d473d54 are PQ).
    private val twins = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=23480103,AVERAGE-BANDWIDTH=16037000,RESOLUTION=4096x1714,FRAME-RATE=50.000,CODECS="hvc1.2.4.H153.90,mp4a.40.2",AUDIO="audio-high"
        https://vod.test/avf/67cb01da/media.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=22412605,AVERAGE-BANDWIDTH=16116000,RESOLUTION=4096x1714,FRAME-RATE=50.000,CODECS="hvc1.2.4.H153.90,mp4a.40.2",AUDIO="audio-high"
        https://vod.test/avf/f9297517/media.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1874738,AVERAGE-BANDWIDTH=1612000,RESOLUTION=960x402,FRAME-RATE=25.000,CODECS="hvc1.2.4.L93.90,mp4a.40.2",AUDIO="audio-low"
        https://vod.test/avf/dc7b1613/media.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1901021,AVERAGE-BANDWIDTH=1404000,RESOLUTION=960x402,FRAME-RATE=25.000,CODECS="hvc1.2.4.L93.90,mp4a.40.2",AUDIO="audio-low"
        https://vod.test/avf/7d473d54/media.m3u8
    """.trimIndent()

    @Test
    fun twinsWithoutARangeAreWorthReading() {
        assertEquals(true, Variants.twinsWithoutRange(twins))
        // Declared ranges: nothing to read.
        assertEquals(false, Variants.twinsWithoutRange(vimeo))
        // One variant per size (Dailymotion): nothing to tell apart.
        val lines = twins.lines()
        assertEquals(false, Variants.twinsWithoutRange((lines.take(3) + lines.subList(5, 7)).joinToString("\n")))
    }

    @Test
    fun variantAddressesInOrder() {
        assertEquals(listOf("67cb01da", "f9297517", "dc7b1613", "7d473d54").map { "https://vod.test/avf/$it/media.m3u8" },
            Variants.addresses(twins))
    }

    @Test
    fun rangesReadElsewhereGoIntoTheMaster() {
        val ranges = mapOf("https://vod.test/avf/f9297517/media.m3u8" to Range.PQ, "https://vod.test/avf/67cb01da/media.m3u8" to Range.SDR,
            "https://vod.test/avf/7d473d54/media.m3u8" to Range.UNKNOWN)
        val v = Variants.parse(Variants.withRanges(twins, ranges))
        assertEquals(listOf(Range.SDR, Range.PQ, Range.UNKNOWN, Range.UNKNOWN), v.map { it.range })
        assertEquals(23480103, v[0].bandwidth)
        // A declared range is never replaced.
        assertEquals(vimeo, Variants.withRanges(vimeo, mapOf("https://vod.test/pq/1144.m3u8" to Range.SDR)))
    }

    /** An init segment's start with a colr box of [type] ("nclx" or "nclc") and [transfer]. */
    private fun init(type: String, transfer: Int): ByteArray {
        val colr = byteArrayOf(0, 0, 0, 19) + "colr".toByteArray() + type.toByteArray() +
            byteArrayOf(0, 9, 0, transfer.toByte(), 0, 9, 0)
        return ByteArray(600) { 7 } + colr + ByteArray(40)
    }

    @Test
    fun theInitSegmentsColourBoxTellsTheRange() {
        assertEquals(Range.PQ, Variants.rangeOfInit(init("nclx", 16)))
        assertEquals(Range.HLG, Variants.rangeOfInit(init("nclx", 18)))
        assertEquals(Range.SDR, Variants.rangeOfInit(init("nclx", 14)))
        assertEquals(Range.SDR, Variants.rangeOfInit(init("nclc", 1)))
        assertEquals(Range.UNKNOWN, Variants.rangeOfInit(ByteArray(900) { 7 }))
        // Cut off inside the box: unknown, not a crash.
        assertEquals(Range.UNKNOWN, Variants.rangeOfInit(init("nclx", 16).copyOf(612)))
        assertEquals(Range.UNKNOWN, Variants.rangeOfInit(ByteArray(0)))
    }

    private fun variant(range: Range, codecs: String = "hvc1.2.4.L153.90") = Variants.Variant(1, 1920, 1080, codecs, 0f, range)

    @Test
    fun autoStaysOnWhatThePanelShows() {
        val pq = variant(Range.PQ)
        val sdr = variant(Range.SDR)
        val dv = variant(Range.PQ, "dvh1.05.01")
        val hlg = variant(Range.HLG)
        val hdr10Panel = Variants.Panel(hdr10 = true, hlg = false, dolbyVision = false)
        val sdrPanel = Variants.Panel(hdr10 = false, hlg = false, dolbyVision = false)
        assertEquals(setOf(pq), Variants.autoKeeps(listOf(pq, sdr, dv), hdr10Panel))
        assertEquals(setOf(sdr), Variants.autoKeeps(listOf(pq, sdr, dv), sdrPanel))
        // Dolby Vision and HLG only where the panel takes them; else the SDR ones.
        assertEquals(setOf(sdr), Variants.autoKeeps(listOf(dv, sdr), hdr10Panel))
        assertEquals(setOf(dv), Variants.autoKeeps(listOf(dv, sdr), Variants.Panel(hdr10 = true, hlg = false, dolbyVision = true)))
        assertEquals(setOf(sdr), Variants.autoKeeps(listOf(hlg, sdr), hdr10Panel))
        // Nothing to choose between: no limit.
        assertNull(Variants.autoKeeps(listOf(sdr, sdr), hdr10Panel))
        assertNull(Variants.autoKeeps(listOf(pq, pq), sdrPanel))
        assertNull(Variants.autoKeeps(listOf(pq, variant(Range.UNKNOWN)), hdr10Panel))
    }

    // A video site's 2.39:1 master: H.264 only, no CODECS, largest first.
    private val wide = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=6402000,RESOLUTION=2560x1072
        a.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4141000,RESOLUTION=1920x804
        b.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2172000,RESOLUTION=1280x536
        c.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=711000,RESOLUTION=640x268
        d.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=223000,RESOLUTION=640x268
        e.m3u8
    """.trimIndent()

    @Test
    fun autoStartsAtTheSitesPicture() {
        val v = Variants.parse(wide)
        assertEquals(6402000, Variants.startBandwidth(v, 2560, 1072))
        assertEquals(4141000, Variants.startBandwidth(v, 1920, 804))
        // Two variants of one size: the larger, so Auto opens at that size.
        assertEquals(711000, Variants.startBandwidth(v, 640, 268))
        // The same class of size counts (2560 × 1088 is 1440p, as 2560 × 1072); else the largest below (1440 × 600).
        assertEquals(6402000, Variants.startBandwidth(v, 2560, 1088))
        assertEquals(2172000, Variants.startBandwidth(v, 1440, 600))
    }

    @Test
    fun noStartWhenTheSiteSaysNoSizeOrEverythingIsLarger() {
        val v = Variants.parse(wide)
        assertNull(Variants.startBandwidth(v, 0, 0))
        assertNull(Variants.startBandwidth(v, 320, 134))
        assertNull(Variants.startBandwidth(emptyList(), 1920, 1080))
        assertNull(Variants.startBandwidth(Variants.parse("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\nx.m3u8"), 1920, 1080))
    }

    // A wide 4K stream whose AV1 top picture doesn't decode on a TV; its H.264 pictures do, one as large.
    private val av1Top = listOf(Quality(1632, "AV1", null, 3840), Quality(1080, "H.264", null, 2560),
        Quality(816, "H.264", null, 1920), Quality(544, "H.264", null, 1280), Quality(1632, "H.264", null, 3840))

    @Test
    fun autoGoesOnWithEveryPictureThatIsLeft() {
        assertEquals(Variants.Fallback(listOf(1, 2, 3, 4), byHand = false), Variants.afterFailure(av1Top, setOf(0), picked = null))
        assertEquals(Variants.Fallback(listOf(1, 2, 3), byHand = false), Variants.afterFailure(av1Top, setOf(0, 4), picked = null))
    }

    @Test
    fun aPictureByHandStepsToTheClosestBelow() {
        // 3840 × 1632 AV1 picked: the other 3840 × 1632 (H.264) is the same size, not below; 2560 × 1080 is next.
        assertEquals(Variants.Fallback(listOf(1), byHand = true), Variants.afterFailure(av1Top, setOf(0), picked = 0))
        assertEquals(Variants.Fallback(listOf(3), byHand = true), Variants.afterFailure(av1Top, setOf(0, 2), picked = 2))
        // Nothing below the smallest: Auto with the rest.
        assertEquals(Variants.Fallback(listOf(0, 1, 2, 4), byHand = false), Variants.afterFailure(av1Top, setOf(3), picked = 3))
    }

    @Test
    fun nothingLeftHandsBackToThePage() {
        assertNull(Variants.afterFailure(av1Top, setOf(0, 1, 2, 3, 4), picked = null))
        assertNull(Variants.afterFailure(emptyList(), emptySet(), picked = null))
    }

    @Test
    fun softwareKeepsTo1080pAt30() {
        assertTrue(Variants.fitsSoftware(Quality(1080, "H.264", null, 1920), 30f))
        assertTrue(Variants.fitsSoftware(Quality(1088, "H.264", null, 1920), 29.97f))
        assertTrue(Variants.fitsSoftware(Quality(804, "H.264", null, 1920), 0f))
        assertTrue(Variants.fitsSoftware(Quality(720, "H.264", null, 1280), 25f))
        assertFalse(Variants.fitsSoftware(Quality(1080, "H.264", null, 1920), 60f))
        assertFalse(Variants.fitsSoftware(Quality(1088, "AV1", null, 2560), 25f))
        assertFalse(Variants.fitsSoftware(Quality(2160, "HEVC", null, 3840), 30f))
    }
}
