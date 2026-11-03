package app.sofabrowser.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import app.sofabrowser.R

/**
 * What the start page is drawn on, as chosen in Settings > Start page background: the theme's own background, one of
 * the built-in colours and gradients (drawn in code, no files), or the user's own picture (HomePicture), with a
 * [Dim] veil over a picture so titles stay readable. Private browsing keeps its own look whatever is chosen.
 *
 * Stored as `home_background`: "" (the theme's), `color:<id>`, `gradient:<id>` or `image`; and `home_dim`. The ids
 * never change. A settings export should write `image` as "": the picture itself stays on the TV.
 */
object HomeBackground {

    /** A built-in look: one colour, or a gradient of two or three from top left to bottom right, for each theme. */
    enum class Swatch(val id: String, val label: Int, val dark: IntArray, val light: IntArray) {
        GRAPHITE("graphite", R.string.bg_graphite, c(0x1E1F24), c(0xECEDF0)),
        MIDNIGHT("midnight", R.string.bg_midnight, c(0x0F1830), c(0xE2E8F5)),
        OCEAN("ocean", R.string.bg_ocean, c(0x0A2A40), c(0xDAEAF5)),
        FOREST("forest", R.string.bg_forest, c(0x11281C), c(0xE0EEE4)),
        TEAL("teal", R.string.bg_teal, c(0x0B2E2E), c(0xDCEFED)),
        PLUM("plum", R.string.bg_plum, c(0x2A1736), c(0xEDE3F3)),
        WINE("wine", R.string.bg_wine, c(0x35131C), c(0xF4E2E6)),
        COCOA("cocoa", R.string.bg_cocoa, c(0x2D2118), c(0xF2EADF)),

        AURORA("aurora", R.string.bg_aurora, c(0x0E3A3A, 0x1B2350, 0x3A1C4E), c(0xD3F0EA, 0xDCE2F6, 0xEADCF4)),
        SUNSET("sunset", R.string.bg_sunset, c(0x2A1440, 0x561D3B, 0x5E3218), c(0xECDFF6, 0xF6DCE5, 0xFAE5D2)),
        LAGOON("lagoon", R.string.bg_lagoon, c(0x062A3E, 0x0A4548), c(0xD2EBF6, 0xD1F0EC)),
        DUSK("dusk", R.string.bg_dusk, c(0x191E48, 0x46224E), c(0xDEE1F6, 0xEFDCF1)),
        EMBER("ember", R.string.bg_ember, c(0x2A0E0E, 0x55280F), c(0xF6DCD7, 0xFAE7D1)),
        NEBULA("nebula", R.string.bg_nebula, c(0x110B2D, 0x2D1452, 0x0D344E), c(0xE1DDF4, 0xE9DAF6, 0xD7E8F4)),
        MEADOW("meadow", R.string.bg_meadow, c(0x0D2317, 0x1E3C2B, 0x0E2E32), c(0xDEEFE2, 0xE4F1D9, 0xD8EEEE)),
        STEEL("steel", R.string.bg_steel, c(0x1B212A, 0x333F4E), c(0xE5E9EF, 0xD5DCE6));

        val gradient get() = dark.size > 1
        fun colors(dark: Boolean) = if (dark) this.dark else light
    }

    sealed class Choice {
        /** The theme's own background, as before there was a choice. */
        object Theme : Choice()
        data class Look(val swatch: Swatch) : Choice()
        object Picture : Choice()
    }

    /** How much a picture is veiled, in the theme's background colour (dark for Dark, white for Light). */
    enum class Dim(val id: String, val label: Int, val alpha: Float) {
        OFF("off", R.string.bg_dim_off, 0f),
        LIGHT("light", R.string.bg_dim_light, 0.25f),
        MEDIUM("medium", R.string.bg_dim_medium, 0.45f),
        STRONG("strong", R.string.bg_dim_strong, 0.65f),
    }

    /** [stored] as kept in Settings; anything unknown, or a picture that is gone, is the theme's background. */
    fun decode(stored: String?, pictureExists: Boolean): Choice {
        val s = stored?.trim().orEmpty()
        if (s == PICTURE) return if (pictureExists) Choice.Picture else Choice.Theme
        val kind = s.substringBefore(':')
        val swatch = Swatch.entries.firstOrNull { it.id == s.substringAfter(':') } ?: return Choice.Theme
        return if (kind == (if (swatch.gradient) GRADIENT else COLOR)) Choice.Look(swatch) else Choice.Theme
    }

    fun encode(choice: Choice): String = when (choice) {
        Choice.Theme -> ""
        Choice.Picture -> PICTURE
        is Choice.Look -> (if (choice.swatch.gradient) GRADIENT else COLOR) + ":" + choice.swatch.id
    }

    fun dim(stored: String?) = Dim.entries.firstOrNull { it.id == stored } ?: Dim.MEDIUM

    /** The veil over a picture, as a colour. */
    fun scrim(dim: Dim, dark: Boolean): Int = (Math.round(dim.alpha * 255) shl 24) or (if (dark) 0x000000 else 0xFFFFFF)

    /** The background for [choice] in the current theme; a picture's needs HomePicture, so this gives the theme's instead. */
    fun drawable(choice: Choice, private: Boolean = false): Drawable = when {
        private || choice !is Choice.Look -> Ui.background(private)
        else -> swatchDrawable(choice.swatch, Ui.p.dark)
    }

    fun swatchDrawable(swatch: Swatch, dark: Boolean): Drawable {
        val colors = swatch.colors(dark)
        return if (colors.size == 1) ColorDrawable(colors[0]) else GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
    }

    /**
     * A picture filling its bounds, centre-cropped (the stored file is never cut), on the theme's colour (for any
     * transparent parts) and under a [scrim].
     */
    class Cover(private val bitmap: Bitmap, private val base: Int, private val scrim: Int) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val src = Rect()

        override fun onBoundsChange(bounds: Rect) {
            val r = PictureSize.crop(bitmap.width, bitmap.height, bounds.width(), bounds.height())
            src.set(r[0], r[1], r[2], r[3])
        }

        override fun draw(canvas: Canvas) {
            canvas.drawColor(base)
            canvas.drawBitmap(bitmap, src, bounds, paint)
            if (scrim ushr 24 != 0) canvas.drawColor(scrim)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.OPAQUE
    }

    private const val COLOR = "color"
    private const val GRADIENT = "gradient"
    const val PICTURE = "image"

    private fun c(vararg rgb: Int) = IntArray(rgb.size) { rgb[it] or 0xFF000000.toInt() }
}
