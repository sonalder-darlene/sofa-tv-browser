package app.sofabrowser.ui

import app.sofabrowser.ui.HomeBackground.Choice
import app.sofabrowser.ui.HomeBackground.Swatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class HomeBackgroundTest {

    @Test
    fun theIdsNeverChange() {
        // Stored in Settings and written by a settings export.
        assertEquals(listOf("graphite", "midnight", "ocean", "forest", "teal", "plum", "wine", "cocoa"),
            Swatch.entries.filter { !it.gradient }.map { it.id })
        assertEquals(listOf("aurora", "sunset", "lagoon", "dusk", "ember", "nebula", "meadow", "steel"),
            Swatch.entries.filter { it.gradient }.map { it.id })
        assertEquals(listOf("off", "light", "medium", "strong"), HomeBackground.Dim.entries.map { it.id })
    }

    @Test
    fun nothingStoredIsTheThemesBackground() {
        assertEquals(Choice.Theme, HomeBackground.decode(null, pictureExists = true))
        assertEquals(Choice.Theme, HomeBackground.decode("", pictureExists = true))
        assertEquals("", HomeBackground.encode(Choice.Theme))
        assertEquals(HomeBackground.Dim.MEDIUM, HomeBackground.dim(null))
        assertEquals(HomeBackground.Dim.MEDIUM, HomeBackground.dim("loud"))
    }

    @Test
    fun everyChoiceComesBackAsStored() {
        for (s in Swatch.entries) assertEquals(Choice.Look(s), HomeBackground.decode(HomeBackground.encode(Choice.Look(s)), false))
        assertEquals("color:ocean", HomeBackground.encode(Choice.Look(Swatch.OCEAN)))
        assertEquals("gradient:aurora", HomeBackground.encode(Choice.Look(Swatch.AURORA)))
        assertEquals("image", HomeBackground.encode(Choice.Picture))
        assertEquals(Choice.Picture, HomeBackground.decode("image", pictureExists = true))
    }

    @Test
    fun anythingUnknownOrAMissingPictureIsTheThemesBackground() {
        for (s in listOf("color:sky", "gradient:ocean", "color:aurora", "ocean", "picture", "image:x", "color:"))
            assertEquals(s, Choice.Theme, HomeBackground.decode(s, pictureExists = true))
        assertEquals(Choice.Theme, HomeBackground.decode("image", pictureExists = false))
    }

    @Test
    fun theVeilIsDarkInTheDarkThemeAndWhiteInTheLight() {
        assertEquals(0, HomeBackground.scrim(HomeBackground.Dim.OFF, true) ushr 24)
        assertEquals(0x73000000, HomeBackground.scrim(HomeBackground.Dim.MEDIUM, true))
        assertEquals(0x73FFFFFF, HomeBackground.scrim(HomeBackground.Dim.MEDIUM, false))
        assertTrue(HomeBackground.Dim.entries.zipWithNext().all { (a, b) -> a.alpha < b.alpha })
    }

    @Test
    fun rowTitlesAndCardLabelsStayReadableOnEveryLookInBothThemes() {
        // WCAG AA for normal text: 4.5:1, on every colour of every look (a gradient's colours are its extremes).
        for (palette in listOf(Ui.DARK, Ui.LIGHT)) for (s in Swatch.entries) for (bg in s.colors(palette.dark)) {
            val title = contrast(over(palette.textMid, bg), bg)
            assertTrue("${s.id} ${if (palette.dark) "dark" else "light"}: row title $title", title >= 4.5)
            val card = over(palette.card, bg)
            val label = contrast(over(palette.text, card), card)
            assertTrue("${s.id} ${if (palette.dark) "dark" else "light"}: card label $label", label >= 4.5)
        }
    }

    @Test
    fun theDefaultBackgroundMeetsTheSameBar() {
        // So the bar above asks of the new looks no more and no less than the start page already gives.
        for (palette in listOf(Ui.DARK, Ui.LIGHT)) for (bg in listOf(palette.bgTop, palette.bgMid, palette.bgBottom))
            assertTrue(contrast(over(palette.textMid, bg), bg) >= 4.5)
    }

    /** [fg] (with its alpha) drawn over the opaque [bg]. */
    private fun over(fg: Int, bg: Int): Int {
        val a = (fg ushr 24) / 255.0
        fun ch(shift: Int) = ((fg shr shift and 0xFF) * a + (bg shr shift and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun luminance(c: Int): Double {
        fun lin(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin(c shr 16 and 0xFF) + 0.7152 * lin(c shr 8 and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val (hi, lo) = luminance(a).let { la -> luminance(b).let { lb -> maxOf(la, lb) to minOf(la, lb) } }
        return (hi + 0.05) / (lo + 0.05)
    }
}
