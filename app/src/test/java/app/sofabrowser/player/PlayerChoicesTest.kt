package app.sofabrowser.player

import app.sofabrowser.player.PlayerChoices.Track
import app.sofabrowser.player.Variants.Quality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerChoicesTest {

    @Test
    fun subtitlesInTheLanguageArePicked() {
        assertEquals(1, PlayerChoices.match("fr", listOf(Track("en"), Track("fr"), Track("de"))))
        // The exact tag first, else any of the language.
        assertEquals(2, PlayerChoices.match("pt-BR", listOf(Track("en"), Track("pt-PT"), Track("pt-br"))))
        assertEquals(1, PlayerChoices.match("pt-BR", listOf(Track("en"), Track("pt"))))
        assertEquals(0, PlayerChoices.match("pt", listOf(Track("pt-BR"))))
    }

    @Test
    fun forcedAndCommentaryTracksAreSkipped() {
        assertEquals(2, PlayerChoices.match("en", listOf(Track("en", forced = true), Track("en", commentary = true), Track("en"))))
        assertNull(PlayerChoices.match("en", listOf(Track("en", forced = true), Track("fr"))))
    }

    @Test
    fun noMatchLeavesItToTheTv() {
        assertNull(PlayerChoices.match("ja", listOf(Track("en"), Track(null), Track("und"))))
        assertNull(PlayerChoices.match("und", listOf(Track("und"))))
        assertNull(PlayerChoices.match("fr", emptyList()))
    }

    @Test
    fun qualitySurvivesPrefs() {
        val q = Quality(1088, "HEVC", "HDR10", 2560)
        assertEquals(q, PlayerChoices.decode(PlayerChoices.encode(q)))
        assertEquals(Quality(720, null, null), PlayerChoices.decode(PlayerChoices.encode(Quality(720, null, null))))
        assertEquals("", PlayerChoices.encode(null))
        assertNull(PlayerChoices.decode(""))
        assertNull(PlayerChoices.decode(null))
        assertNull(PlayerChoices.decode("x|1|2"))
    }

    // A quality remembered on one site, offered another's pictures.

    @Test
    fun oddHeightsCountAsTheirSize() {
        assertEquals(0, Variants.closest(Quality(1080, "H.264", null, 1920), listOf(Quality(1088, "H.264", null, 1920), Quality(720, "H.264", null, 1280))))
        assertEquals(1, Variants.closest(Quality(1088, "VP9", null, 1920), listOf(Quality(1440, "VP9", null, 2560), Quality(1080, "VP9", null, 1920))))
    }

    @Test
    fun widePicturesCountAsTheirWidth() {
        // 1920 × 804 is the 1080p of a wide film; 2560 × 1088 its 1440p.
        assertEquals(1, Variants.closest(Quality(1080, "H.264", null, 1920), listOf(Quality(1608, "H.264", null, 3840), Quality(804, "H.264", null, 1920))))
        assertEquals(0, Variants.closest(Quality(1440, "VP9", null, 2560), listOf(Quality(1088, "VP9", null, 2560), Quality(804, "VP9", null, 1920))))
    }

    @Test
    fun theSameSizePrefersTheSameCodecAndRange() {
        val offered = listOf(Quality(1080, "H.264", "SDR", 1920), Quality(1080, "HEVC", "HDR10", 1920), Quality(1080, "HEVC", "SDR", 1920))
        assertEquals(2, Variants.closest(Quality(1088, "HEVC", "SDR", 1920), offered))
        assertEquals(1, Variants.closest(Quality(1080, "AV1", "HDR10", 1920), offered))
    }

    @Test
    fun withinTheVersionsKeptForThePanel() {
        // Auto kept to the HDR versions (keepToPanel): an SDR 2160p remembered gets the HDR 2160p, not Auto.
        val kept = listOf(Quality(2160, "HEVC", "HDR10", 3840), Quality(1080, "HEVC", "HDR10", 1920))
        assertEquals(0, Variants.closest(Quality(2160, "HEVC", "SDR", 3840), kept))
        assertNull(Variants.closest(Quality(720, "HEVC", "SDR", 1280), kept))
    }
}
