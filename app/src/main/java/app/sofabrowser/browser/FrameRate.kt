package app.sofabrowser.browser

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Match frame rate": the rules. The page reports the times of the frames it shows; [estimate] turns them into a
 * frame rate, and [pick] the TV mode to switch to. Plain Kotlin, so it is unit-tested.
 */
object FrameRate {

    /** A display mode, as Display.Mode gives it. */
    data class Mode(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

    /** The rates video uses. Anything else (or a mix) is left alone: no switch beats a wrong one. */
    private val STANDARD = listOf(24000.0 / 1001, 24.0, 25.0, 30000.0 / 1001, 30.0, 50.0, 60000.0 / 1001, 60.0)

    // A film at 23.976 and one at 24 differ by 0.1 %; times rounded to the millisecond over 5 s stay within 0.02 %.
    private const val SNAP = 0.0005
    private const val MIN_FRAMES = 100
    private const val MIN_SPAN = 5.0

    /**
     * The frame rate of a video from the media times (in seconds) of the frames the page showed, measured before any
     * switch. Frames the screen skipped don't matter: each gap is counted in whole frame periods. Null when there
     * are too few, the times are all 0 (native HLS on older WebViews), or the rate isn't one of the standard ones.
     */
    fun estimate(mediaTimes: List<Double>): Double? {
        if (mediaTimes.size < MIN_FRAMES) return null
        val gaps = mediaTimes.zipWithNext { a, b -> b - a }
        if (gaps.any { it <= 0 }) return null
        val span = mediaTimes.last() - mediaTimes.first()
        if (span < MIN_SPAN) return null
        // The shortest steady gap is one frame period; the 10th percentile ignores a stray short one.
        val period = gaps.sorted()[gaps.size / 10]
        val periods = gaps.map { it / period }
        if (periods.count { abs(it - it.roundToInt()) > 0.1 } > gaps.size / 20) return null
        val fps = periods.sumOf { it.roundToInt() } / span
        return STANDARD.firstOrNull { abs(fps / it - 1) < SNAP }
    }

    /**
     * The mode to switch to for a video at [fps], or null to stay. Only modes at the current resolution count: a
     * resolution change would blank the screen longer and the window would be scaled anyway. In order:
     * - stay if the current rate shows each frame a whole number of times;
     * - the exact rate, else a whole multiple of it (48 for 24);
     * - stay if the current rate is the NTSC cousin's (60 for 59.94: one repeated frame every 17 s);
     * - the cousin's rate (24 for 23.976, one repeated frame every 42 s, far better than 3:2 judder at 60).
     */
    fun pick(modes: List<Mode>, current: Mode, fps: Double): Int? {
        val same = modes.filter { it.width == current.width && it.height == current.height && it.id != current.id }
        // NTSC rates are whole rates × 1000/1001.
        val ntsc = abs(fps * 1001 / 1000 - (fps * 1001 / 1000).roundToInt()) < 1e-6
        val cousin = if (ntsc) fps * 1001 / 1000 else fps * 1000 / 1001
        for (rate in listOf(fps, cousin)) {
            if (multiple(current.refreshRate, rate) != null) return null
            same.firstOrNull { multiple(it.refreshRate, rate) == 1 }?.let { return it.id }
            same.filter { multiple(it.refreshRate, rate) != null }
                .minByOrNull { abs(it.refreshRate - current.refreshRate) }?.let { return it.id }
        }
        return null
    }

    /** k when [refresh] shows each frame of [fps] exactly k times. */
    private fun multiple(refresh: Float, fps: Double): Int? {
        val k = (refresh / fps).roundToInt()
        return k.takeIf { it >= 1 && abs(refresh / (k * fps) - 1) < SNAP }
    }

    /** The refresh rates the TV offers at its current resolution besides the current one, lowest first. */
    fun otherRates(modes: List<Mode>, current: Mode): List<Float> =
        modes.filter { it.width == current.width && it.height == current.height && abs(it.refreshRate - current.refreshRate) > 0.001f }
            .map { it.refreshRate }.sorted()
            .fold(emptyList()) { acc, r -> if (acc.isNotEmpty() && abs(acc.last() - r) < 0.001f) acc else acc + r }

    /** 23.976, 29.97, 24: as rates are usually written. */
    fun label(rate: Double): String =
        String.format(Locale.ROOT, "%.3f", rate).trimEnd('0').trimEnd('.')
}
