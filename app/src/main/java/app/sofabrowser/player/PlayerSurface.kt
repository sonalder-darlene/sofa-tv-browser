package app.sofabrowser.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import app.sofabrowser.ui.px

/**
 * Where Sofa Player shows its video: a SurfaceView, which the TV composes as a video layer of its own (at the
 * panel's resolution, in HDR when the stream and panel have it), fitted to the video's shape, and the subtitles
 * over it.
 */
@SuppressLint("ViewConstructor")
class PlayerSurface(context: Context) : FrameLayout(context) {

    val surface = SurfaceView(context)

    /** Text subtitles, bottom centre, white on a dark box as TVs show captions. */
    val subtitles: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 44.px.toFloat())
        setShadowLayer(6f, 0f, 2f, Color.BLACK)
        setBackgroundColor(0x99000000.toInt())
        setPadding(24.px, 8.px, 24.px, 8.px)
        gravity = Gravity.CENTER
        visibility = GONE
    }

    /** Width over height of the picture, 0 until known. */
    private var aspect = 0f

    init {
        setBackgroundColor(Color.BLACK)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(subtitles, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            .apply { bottomMargin = SUBTITLE_MARGIN.px; leftMargin = 96.px; rightMargin = 96.px })
    }

    fun setVideoSize(width: Int, height: Int, pixelRatio: Float) {
        val a = if (width > 0 && height > 0) width * pixelRatio / height else 0f
        if (a == aspect) return
        aspect = a
        requestLayout()
    }

    /** Keeps the subtitles above [px] of controls at the bottom of the screen (0: none there). */
    fun liftSubtitles(px: Int) {
        val lp = subtitles.layoutParams as LayoutParams
        val margin = maxOf(SUBTITLE_MARGIN.px, px + 16.px)
        if (lp.bottomMargin == margin) return
        lp.bottomMargin = margin
        subtitles.layoutParams = lp
    }

    fun showText(text: CharSequence?) {
        subtitles.text = text
        subtitles.visibility = if (text.isNullOrEmpty()) GONE else VISIBLE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (aspect <= 0f) return
        val w = measuredWidth
        val h = measuredHeight
        val (fw, fh) = if (w.toFloat() / h > aspect) (h * aspect).toInt() to h else w to (w / aspect).toInt()
        surface.measure(MeasureSpec.makeMeasureSpec(fw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(fh, MeasureSpec.EXACTLY))
    }

    private companion object {
        const val SUBTITLE_MARGIN = 72
    }
}
