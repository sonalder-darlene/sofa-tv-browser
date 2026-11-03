package app.sofabrowser.ui

/**
 * The size rules for the start page's own picture (HomePicture), as plain arithmetic so they are unit-tested.
 *
 * The stored copy keeps every pixel a 4K screen can show and no more: it is scaled down (never up) only as far as it
 * still covers 3840 × 2160, since the start page fills the screen with it, centre-cropped. A 4:3 photo so keeps a
 * full 4K width. The screen the picture is set on doesn't matter: the same file still looks right on a 4K TV later.
 */
object PictureSize {
    const val MAX_WIDTH = 3840
    const val MAX_HEIGHT = 2160
    /** A very wide or tall picture is kept smaller than this, though its crop is then a little under 4K. */
    const val MAX_PIXELS = 2L * MAX_WIDTH * MAX_HEIGHT
    /** Larger pictures are refused: nothing a TV shows needs them, and decoding them takes too long. */
    const val MAX_SOURCE_PIXELS = 200_000_000L
    const val MAX_SOURCE_BYTES = 50L * 1024 * 1024

    data class Size(val width: Int, val height: Int)

    /** The stored copy's size for a picture of [width] × [height]. */
    fun stored(width: Int, height: Int): Size {
        if (width <= 0 || height <= 0) return Size(0, 0)
        val cover = maxOf(MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        val pixels = kotlin.math.sqrt(MAX_PIXELS.toDouble() / (width.toDouble() * height))
        val scale = minOf(1.0, cover, pixels)
        if (scale >= 1.0) return Size(width, height)
        return Size(maxOf(1, Math.round(width * scale).toInt()), maxOf(1, Math.round(height * scale).toInt()))
    }

    /**
     * The largest power-of-two step down (BitmapFactory's inSampleSize) that still leaves [width] × [height] at least
     * [needWidth] × [needHeight]: for the stored copy when setting a picture, and for the screen when showing it, so a
     * 1080p screen never holds a 4K picture.
     */
    fun sample(width: Int, height: Int, needWidth: Int, needHeight: Int): Int {
        var s = 1
        while (width / (s * 2) >= needWidth && height / (s * 2) >= needHeight) s *= 2
        return s
    }

    /** Whether a picture is too large to set at all. */
    fun refused(width: Int, height: Int, bytes: Long) = bytes > MAX_SOURCE_BYTES || width.toLong() * height > MAX_SOURCE_PIXELS

    /** The part of a [width] × [height] picture that fills a [screenWidth] × [screenHeight] screen: its centre, at the screen's shape. */
    fun crop(width: Int, height: Int, screenWidth: Int, screenHeight: Int): IntArray {
        if (width <= 0 || height <= 0 || screenWidth <= 0 || screenHeight <= 0) return intArrayOf(0, 0, width, height)
        return if (width.toLong() * screenHeight > height.toLong() * screenWidth) {
            val w = (height.toLong() * screenWidth / screenHeight).toInt()
            intArrayOf((width - w) / 2, 0, (width - w) / 2 + w, height)
        } else {
            val h = (width.toLong() * screenHeight / screenWidth).toInt()
            intArrayOf(0, (height - h) / 2, width, (height - h) / 2 + h)
        }
    }

    /**
     * The EXIF orientation (1–8) of a JPEG from its first bytes, or 1 when there is none. Only this one tag is read,
     * every offset checked, so a hostile file can't lead it astray (the platform's EXIF reader has had bugs).
     */
    fun jpegOrientation(b: ByteArray): Int {
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16be(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (b.size < 4 || u16be(0) != 0xFFD8) return 1
        var i = 2
        while (i + 4 <= b.size && u8(i) == 0xFF) {
            val marker = u8(i + 1)
            val len = u16be(i + 2)
            if (marker == 0xDA || marker == 0xD9 || len < 2) return 1
            val start = i + 4
            val end = minOf(b.size, i + 2 + len)
            if (marker == 0xE1 && end - start >= 14 && String(b, start, 6, Charsets.ISO_8859_1) == "Exif\u0000\u0000") {
                val t = start + 6
                val little = when (u16be(t)) { 0x4949 -> true; 0x4D4D -> false; else -> return 1 }
                fun u16(at: Int) = if (at + 2 > end) -1 else if (little) u8(at) or (u8(at + 1) shl 8) else u16be(at)
                fun u32(at: Int): Long = if (at + 4 > end) -1 else if (little) (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
                    else ((u16be(at).toLong() shl 16) or u16be(at + 2).toLong())
                val ifd = u32(t + 4).takeIf { it in 8..(end - t).toLong() }?.toInt() ?: return 1
                val count = u16(t + ifd).takeIf { it >= 0 } ?: return 1
                for (n in 0 until count) {
                    val e = t + ifd + 2 + n * 12
                    if (e + 12 > end) return 1
                    if (u16(e) == 0x0112) return u16(e + 8).takeIf { it in 1..8 } ?: 1
                }
                return 1
            }
            i = i + 2 + len
        }
        return 1
    }

    /** EXIF orientation (1–8) as a turn clockwise in degrees after an optional left-right mirror, as ExifInterface reads it. */
    fun turn(orientation: Int): Pair<Int, Boolean> = when (orientation) {
        2 -> 0 to true
        3 -> 180 to false
        4 -> 180 to true
        5 -> 270 to true
        6 -> 90 to false
        7 -> 90 to true
        8 -> 270 to false
        else -> 0 to false
    }
}
