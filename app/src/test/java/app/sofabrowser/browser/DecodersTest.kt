package app.sofabrowser.browser

import app.sofabrowser.browser.Decoders.Listed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DecodersTest {

    @Test
    fun everyMainCodecIsListedInOrderEvenWithoutADecoder() {
        val s = Decoders.summarize(emptyList())
        assertEquals(listOf("AV1", "HEVC", "VP9", "H.264"), s.map { it.codec })
        s.forEach { assertNull(it.hardware); assertNull(it.software) }
    }

    @Test
    fun dolbyVisionOnlyWhenTheTvHasOne() {
        val s = Decoders.summarize(listOf(Listed("Dolby Vision", true, 3840, 2160, 60)))
        assertEquals("Dolby Vision", s.last().codec)
        assertEquals(5, s.size)
    }

    @Test
    fun theLargestHardwareAndSoftwareDecoderOfACodecAreKept() {
        val s = Decoders.summarize(listOf(
            Listed("HEVC", true, 1920, 1080, 60),
            Listed("HEVC", true, 3840, 2160, 30),
            Listed("HEVC", true, 3840, 2160, 60),
            Listed("HEVC", false, 1920, 1080, 30),
            Listed("H.264", false, 1920, 1080, null),
        ))
        val hevc = s.single { it.codec == "HEVC" }
        assertEquals(Listed("HEVC", true, 3840, 2160, 60), hevc.hardware)
        assertEquals(Listed("HEVC", false, 1920, 1080, 30), hevc.software)
        val avc = s.single { it.codec == "H.264" }
        assertNull(avc.hardware)
        assertEquals(1920, avc.software?.width)
    }
}
