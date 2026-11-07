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
        // The 9:16 part of a photo taken sideways, before it is turned upright.
        assertEquals(Size(2160, 3840), PictureSize.stored(4320, 7680))
    }

    /** The stored size of a [width] × [height] picture's kept part. */
    private fun storedPart(width: Int, height: Int, sideways: Boolean = false) =
        PictureSize.kept(width, height, sideways).let { PictureSize.stored(it[2] - it[0], it[3] - it[1]) }

    @Test
    fun onlyTheCentreATvShowsIsKept() {
        // 16:9 already: all of it.
        assertArrayEquals(intArrayOf(0, 0, 3840, 2160), PictureSize.kept(3840, 2160))
        // 4:3: top and bottom go, as the start page would cut them; a full 4K width stays.
        assertArrayEquals(intArrayOf(0, 375, 4000, 2625), PictureSize.kept(4000, 3000))
        assertEquals(Size(3840, 2160), storedPart(4000, 3000))
        // A wide photo: its sides go, then it comes down to 4K (keeping its shape, it would stay 4807 × 2160).
        assertArrayEquals(intArrayOf(1017, 0, 9091, 4542), PictureSize.kept(10109, 4542))
        assertEquals(Size(3840, 2160), storedPart(10109, 4542))
        // A very wide panorama: a 16:9 band from its middle.
        assertEquals(Size(3840, 2160), storedPart(20000, 3000))
        // A phone's portrait photo: a band across the middle, under 4K across, so not scaled.
        assertArrayEquals(intArrayOf(0, 1165, 3024, 2866), PictureSize.kept(3024, 4032))
        assertEquals(Size(3024, 1701), storedPart(3024, 4032))
    }

    @Test
    fun aPhotoTakenSidewaysIsCutAtNineBySixteenInItsFile() {
        // The same portrait photo stored landscape with an EXIF quarter turn: the same band, turned.
        val r = PictureSize.kept(4032, 3024, sideways = true)
        assertArrayEquals(intArrayOf(1165, 0, 2866, 3024), r)
        assertEquals(Size(1701, 3024), storedPart(4032, 3024, sideways = true))
        // A large one comes down to 2160 × 3840, which turns into 3840 × 2160.
        assertEquals(Size(2160, 3840), storedPart(8000, 6000, sideways = true))
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
