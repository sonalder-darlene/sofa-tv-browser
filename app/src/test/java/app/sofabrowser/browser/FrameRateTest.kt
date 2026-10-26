package app.sofabrowser.browser

import app.sofabrowser.browser.FrameRate.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.floor

class FrameRateTest {

    /** Frame times as a page would see them: [count] frames at [fps], each rounded to the container's [tick]. */
    private fun frames(fps: Double, count: Int = 150, start: Double = 12.0, tick: Double = 1e-6, every: Int = 1) =
        (0 until count step every).map { i -> floor((start + i / fps) / tick + 0.5) * tick }

    @Test
    fun eachStandardRateIsRecognised() {
        for (fps in listOf(24000.0 / 1001, 24.0, 25.0, 30000.0 / 1001, 30.0, 50.0, 60000.0 / 1001, 60.0)) {
            assertEquals(fps, FrameRate.estimate(frames(fps, count = (fps * 5.5).toInt()))!!, 1e-9)
        }
    }

    @Test
    fun filmAndItsNtscCousinAreToldApartWithCoarseTimestamps() {
        // MPEG-TS counts in 90 kHz ticks and WebM in milliseconds.
        assertEquals(24000.0 / 1001, FrameRate.estimate(frames(24000.0 / 1001, tick = 1 / 90000.0))!!, 1e-9)
        assertEquals(24.0, FrameRate.estimate(frames(24.0, tick = 1 / 90000.0))!!, 1e-9)
        assertEquals(24000.0 / 1001, FrameRate.estimate(frames(24000.0 / 1001, tick = 0.001))!!, 1e-9)
        assertEquals(24.0, FrameRate.estimate(frames(24.0, tick = 0.001))!!, 1e-9)
    }

    @Test
    fun framesTheScreenSkippedDontChangeTheRate() {
        // 60 fps on a 50 Hz screen: callbacks come at the screen's pace, so every sixth frame is never seen.
        val seen = frames(60.0, count = 400).filterIndexed { i, _ -> i % 6 != 5 }
        assertEquals(60.0, FrameRate.estimate(seen)!!, 1e-9)
        val dropped = frames(25.0, count = 160).filterIndexed { i, _ -> i != 40 && i != 41 && i != 90 }
        assertEquals(25.0, FrameRate.estimate(dropped)!!, 1e-9)
    }

    @Test
    fun tooFewFramesOrTooShortAreUnknown() {
        assertNull(FrameRate.estimate(frames(24.0, count = 40)))
        assertNull(FrameRate.estimate(frames(60.0, count = 200))) // 200 frames at 60 fps: 3.3 s
        assertNull(FrameRate.estimate(emptyList()))
    }

    @Test
    fun streamsWithoutFrameTimesAreUnknown() {
        // Native HLS on older WebViews reports 0 for every frame.
        assertNull(FrameRate.estimate(List(200) { 0.0 }))
    }

    @Test
    fun variableOrOddRatesAreUnknown() {
        val variable = frames(30.0, count = 80) + frames(24.0, count = 80, start = 12.0 + 80 / 30.0).drop(1)
        assertNull(FrameRate.estimate(variable))
        assertNull(FrameRate.estimate(frames(15.0, count = 90)))
        assertNull(FrameRate.estimate(frames(48.0, count = 300)))
    }

    // The modes of a 4K Android 11 TV, as `dumpsys display` listed them (2026-10-20).
    private val tv4k = listOf(
        Mode(1, 3840, 2160, 29.97003f), Mode(2, 3840, 2160, 23.976025f), Mode(3, 1920, 1080, 29.97003f),
        Mode(4, 1920, 1080, 23.976025f), Mode(5, 1920, 1080, 59.94006f), Mode(6, 1280, 720, 59.94006f),
        Mode(7, 1920, 1080, 120.00001f), Mode(8, 720, 480, 60.000004f), Mode(9, 3840, 2160, 30.000002f),
        Mode(10, 3840, 2160, 25.0f), Mode(11, 3840, 2160, 24.000002f), Mode(12, 1920, 1080, 30.000002f),
        Mode(13, 1920, 1080, 25.0f), Mode(14, 1920, 1080, 24.000002f), Mode(15, 1280, 720, 50.0f),
        Mode(16, 1920, 1080, 50.0f), Mode(17, 3840, 2160, 50.0f), Mode(18, 3840, 2160, 60.000004f),
        Mode(19, 1920, 1080, 60.000004f), Mode(20, 1280, 720, 60.000004f), Mode(21, 3840, 2160, 59.94006f),
    )
    private val tv4kDefault = tv4k.first { it.id == 21 }

    @Test
    fun theExactRateIsChosenAtTheSameResolution() {
        assertEquals(2, FrameRate.pick(tv4k, tv4kDefault, 24000.0 / 1001))
        assertEquals(11, FrameRate.pick(tv4k, tv4kDefault, 24.0))
        assertEquals(10, FrameRate.pick(tv4k, tv4kDefault, 25.0))
        assertEquals(17, FrameRate.pick(tv4k, tv4kDefault, 50.0))
        assertEquals(18, FrameRate.pick(tv4k, tv4kDefault, 60.0))
    }

    @Test
    fun noSwitchWhenTheCurrentRateAlreadyFits() {
        assertNull(FrameRate.pick(tv4k, tv4kDefault, 60000.0 / 1001))
        assertNull(FrameRate.pick(tv4k, tv4kDefault, 30000.0 / 1001)) // 59.94 shows each frame twice
        val at50 = tv4k.first { it.id == 17 }
        assertNull(FrameRate.pick(tv4k, at50, 25.0))
    }

    @Test
    fun aMultipleIsUsedWhenTheExactRateIsMissing() {
        val box = listOf(Mode(1, 1920, 1080, 60f), Mode(2, 1920, 1080, 50f), Mode(3, 1920, 1080, 48f))
        assertEquals(3, FrameRate.pick(box, box[0], 24.0))
        assertEquals(2, FrameRate.pick(box, box[0], 25.0))
    }

    @Test
    fun theNtscCousinIsTheLastResort() {
        // A film at 23.976 on a box with 24 but no 23.976: one repeated frame every 42 s instead of constant judder.
        val box = listOf(Mode(1, 3840, 2160, 60f), Mode(2, 3840, 2160, 24f), Mode(3, 3840, 2160, 50f))
        assertEquals(2, FrameRate.pick(box, box[0], 24000.0 / 1001))
        // Already at 60 for 59.94 video: close enough, no blackout for it.
        assertNull(FrameRate.pick(box, box[0], 60000.0 / 1001))
        val ntsc = listOf(Mode(1, 1920, 1080, 50f), Mode(2, 1920, 1080, 59.94006f))
        assertEquals(2, FrameRate.pick(ntsc, ntsc[0], 30.0))
        val pal = listOf(Mode(1, 1920, 1080, 50f), Mode(2, 1920, 1080, 30f))
        assertEquals(2, FrameRate.pick(pal, pal[0], 30000.0 / 1001))
    }

    @Test
    fun aRateMeasuredOnALowerVariantPrefersTwiceItsRate() {
        // Dailymotion's 380p and 480p are 30 fps, 720p and up 60; Vimeo's lowest are 25, the rest 50.
        assertEquals(18, FrameRate.pick(tv4k, tv4kDefault, 30.0, orDouble = true))
        assertEquals(17, FrameRate.pick(tv4k, tv4kDefault, 25.0, orDouble = true))
        // 59.94 already shows 29.97 and 59.94 alike.
        assertNull(FrameRate.pick(tv4k, tv4kDefault, 30000.0 / 1001, orDouble = true))
        // No 48 Hz mode: the film's own rate, as without.
        assertEquals(11, FrameRate.pick(tv4k, tv4kDefault, 24.0, orDouble = true))
        val at50 = tv4k.first { it.id == 17 }
        assertNull(FrameRate.pick(tv4k, at50, 25.0, orDouble = true))
        // A rate above 30 is never doubled.
        assertEquals(17, FrameRate.pick(tv4k, tv4kDefault, 50.0, orDouble = true))
    }

    @Test
    fun theResolutionNeverChanges() {
        val box = listOf(Mode(1, 3840, 2160, 60f), Mode(2, 1920, 1080, 24f))
        assertNull(FrameRate.pick(box, box[0], 24.0))
    }

    @Test
    fun aRateThatFitsNothingStaysPut() {
        val box = listOf(Mode(1, 1920, 1080, 60f), Mode(2, 1920, 1080, 50f))
        assertNull(FrameRate.pick(box, box[0], 24.0))
    }

    @Test
    fun otherRatesAreTheOnesAtTheCurrentResolution() {
        assertEquals(listOf(23.976025f, 24.000002f, 25.0f, 29.97003f, 30.000002f, 50.0f, 60.000004f),
            FrameRate.otherRates(tv4k, tv4kDefault))
        val single = listOf(Mode(1, 3840, 2160, 60f), Mode(2, 1920, 1080, 50f))
        assertEquals(emptyList<Float>(), FrameRate.otherRates(single, single[0]))
    }

    @Test
    fun ratesReadAsPeopleWriteThem() {
        assertEquals("23.976", FrameRate.label(23.976025))
        assertEquals("29.97", FrameRate.label(29.97003))
        assertEquals("24", FrameRate.label(24.000002))
        assertEquals("120", FrameRate.label(120.00001))
    }

    // Sofa Player measures the frames it shows itself, by the same rules as the page's script.

    private fun sample(times: List<Double>): Double? {
        val sampler = FrameRate.Sampler()
        for (t in times) sampler.add(t)?.let { return FrameRate.estimate(it) }
        return null
    }

    @Test
    fun sofaPlayersOwnFramesGiveTheRate() {
        assertEquals(25.0, sample(frames(25.0, count = 200, tick = 1e-6))!!, 0.0)
        assertEquals(24000.0 / 1001, sample(frames(24000.0 / 1001, count = 200))!!, 1e-9)
    }

    @Test
    fun theSamplerWaitsForAboutFiveSeconds() {
        val sampler = FrameRate.Sampler()
        val times = frames(50.0, count = 400)
        val doneAt = times.indexOfFirst { sampler.add(it) != null }
        assertEquals(275, doneAt)
    }

    @Test
    fun aSeekStartsTheSampleOver() {
        val sampler = FrameRate.Sampler()
        frames(25.0, count = 100, start = 10.0).forEach { assertNull(sampler.add(it)) }
        // Back to 2 s, then a jump of 30 s: each starts over, so no sample spans them.
        val after = frames(25.0, count = 60, start = 2.0) + frames(25.0, count = 200, start = 40.0)
        val result = after.firstNotNullOfOrNull { sampler.add(it) }!!
        assertEquals(40.0, result.first(), 1e-6)
        assertEquals(25.0, FrameRate.estimate(result)!!, 0.0)
    }

    @Test
    fun theSampleIsGivenOnce() {
        val sampler = FrameRate.Sampler()
        assertEquals(1, frames(25.0, count = 400).count { sampler.add(it) != null })
    }
}
