package app.sofabrowser.ui

import app.sofabrowser.ui.PictureSize.Size
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureSizeTest {

    @Test
    fun theStoredCopyIsNeverScaledUp() {
        assertEquals(Size(1920, 1080), PictureSize.stored(1920, 1080))
        assertEquals(Size(640, 360), PictureSize.stored(640, 360))
        assertEquals(Size(1, 1), PictureSize.stored(1, 1))
    }

    @Test
    fun fourKStaysFourKAndLargerComesDownToIt() {
        assertEquals(Size(3840, 2160), PictureSize.stored(3840, 2160))
        assertEquals(Size(3840, 2160), PictureSize.stored(7680, 4320))
        assertEquals(Size(3840, 2160), PictureSize.stored(5120, 2880))
    }

    @Test
    fun aPictureOfAnotherShapeKeepsItsShapeAndStillCoversAFourKScreen() {
        // 4:3: the start page crops it to 16:9, so it keeps a full 4K width.
        assertEquals(Size(3840, 2880), PictureSize.stored(4000, 3000))
        // A phone's portrait photo is smaller than 4K across: kept whole.
        assertEquals(Size(3024, 4032), PictureSize.stored(3024, 4032))
        // Portrait and larger: down, keeping its shape, to at most twice 4K's pixels (a full 4K width would be more).
        val tall = PictureSize.stored(6000, 8000)
        assertEquals(Size(3527, 4703), tall)
        assertTrue(tall.width.toLong() * tall.height <= PictureSize.MAX_PIXELS)
        // A very wide panorama is held to twice 4K's pixels.
        val pano = PictureSize.stored(20000, 3000)
        assertTrue(pano.width.toLong() * pano.height <= PictureSize.MAX_PIXELS)
        assertEquals(20000.0 / 3000, pano.width.toDouble() / pano.height, 0.01)
    }

    @Test
    fun theScreenSamplesDownByPowersOfTwoButNeverBelowIt() {
        // A 4K picture on a 1080p screen: half, so it never holds 4K's pixels.
        assertEquals(2, PictureSize.sample(3840, 2160, 1920, 1080))
        assertEquals(1, PictureSize.sample(3840, 2160, 3840, 2160))
        // 1080p on a 4K screen: as it is, the GPU stretches it.
        assertEquals(1, PictureSize.sample(1920, 1080, 3840, 2160))
        // 4:3 on a 1080p screen: both sides must still cover the screen for the crop.
        assertEquals(2, PictureSize.sample(3840, 2880, 1920, 1080))
        assertEquals(1, PictureSize.sample(3000, 2000, 1920, 1080))
        assertEquals(4, PictureSize.sample(7680, 4320, 1920, 1080))
        assertEquals(1, PictureSize.sample(100, 100, 1920, 1080))
    }

    @Test
    fun theCropIsTheCentreAtTheScreensShape() {
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), PictureSize.crop(1920, 1080, 1920, 1080))
        // 4:3 on 16:9: top and bottom cut evenly.
        assertArrayEquals(intArrayOf(0, 180, 1920, 1260), PictureSize.crop(1920, 1440, 1920, 1080))
        // Portrait: a band across the middle.
        assertArrayEquals(intArrayOf(0, 1165, 3024, 2866), PictureSize.crop(3024, 4032, 1920, 1080).also { r ->
            assertEquals(16.0 / 9, (r[2] - r[0]).toDouble() / (r[3] - r[1]), 0.01)
        })
        // Wider than the screen: sides cut evenly.
        assertArrayEquals(intArrayOf(500, 0, 2420, 1080), PictureSize.crop(2920, 1080, 1920, 1080))
    }

    @Test
    fun absurdPicturesAreRefused() {
        assertFalse(PictureSize.refused(7680, 4320, 20L * 1024 * 1024))
        assertTrue(PictureSize.refused(20000, 20000, 1000))
        assertTrue(PictureSize.refused(100, 100, 60L * 1024 * 1024))
    }

    @Test
    fun aJpegsOrientationIsReadFromItsExif() {
        assertEquals(6, PictureSize.jpegOrientation(jpeg(6, little = false)))
        assertEquals(8, PictureSize.jpegOrientation(jpeg(8, little = true)))
        // After a JFIF segment, as most cameras and editors write it.
        assertEquals(3, PictureSize.jpegOrientation(jpeg(3, little = false, jfif = true)))
    }

    @Test
    fun anythingElseReadsAsUpright() {
        assertEquals(1, PictureSize.jpegOrientation(ByteArray(0)))
        assertEquals(1, PictureSize.jpegOrientation(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0)))
        assertEquals(1, PictureSize.jpegOrientation(jpeg(42, little = false)))
        // Cut short anywhere, or with offsets pointing outside the segment: no crash, no orientation.
        val whole = jpeg(6, little = false)
        for (n in whole.indices) assertTrue(PictureSize.jpegOrientation(whole.copyOf(n)) in 1..8)
        val bad = whole.copyOf().also { it[17] = 0x7F; it[18] = 0x7F }
        assertEquals(1, PictureSize.jpegOrientation(bad))
    }

    /** A JPEG's first bytes with an EXIF segment holding one Orientation entry. */
    private fun jpeg(orientation: Int, little: Boolean, jfif: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun b(vararg v: Int) = v.forEach { out.write(it) }
        fun s16(v: Int) = if (little) b(v and 0xFF, v shr 8 and 0xFF) else b(v shr 8 and 0xFF, v and 0xFF)
        fun s32(v: Int) = if (little) { s16(v and 0xFFFF); s16(v ushr 16) } else { s16(v ushr 16); s16(v and 0xFFFF) }
        b(0xFF, 0xD8)
        if (jfif) b(0xFF, 0xE0, 0x00, 0x10, 'J'.code, 'F'.code, 'I'.code, 'F'.code, 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
        val tiffLength = 8 + 2 + 12 + 4
        b(0xFF, 0xE1, (2 + 6 + tiffLength) shr 8, (2 + 6 + tiffLength) and 0xFF)
        "Exif".forEach { b(it.code) }; b(0, 0)
        if (little) b(0x49, 0x49) else b(0x4D, 0x4D)
        s16(42); s32(8)
        s16(1)
        s16(0x0112); s16(3); s32(1); s16(orientation); s16(0)
        s32(0)
        b(0xFF, 0xDA, 0x00, 0x02)
        return out.toByteArray()
    }

    @Test
    fun exifOrientationsTurnAndMirrorAsExifInterfaceReadsThem() {
        assertEquals(0 to false, PictureSize.turn(1))
        assertEquals(0 to false, PictureSize.turn(0))
        assertEquals(90 to false, PictureSize.turn(6))
        assertEquals(180 to false, PictureSize.turn(3))
        assertEquals(270 to false, PictureSize.turn(8))
        assertEquals(listOf(true, true, true, true), listOf(2, 4, 5, 7).map { PictureSize.turn(it).second })
    }
}
