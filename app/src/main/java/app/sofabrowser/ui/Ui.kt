package app.sofabrowser.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import app.sofabrowser.Prefs
import app.sofabrowser.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colour roles for one theme. "Focus" is the filled state of a focused button or row. */
class Palette(
    val dark: Boolean,
    val bgTop: Int, val bgMid: Int, val bgBottom: Int, val bgPrivate: Int,
    /** Primary, secondary, weak and hint text. */
    val text: Int, val textMid: Int, val textWeak: Int, val textHint: Int,
    /** Subtle surface (rows, pills) and card surface, each with a focused variant. */
    val fill: Int, val fillFocused: Int, val card: Int, val cardFocused: Int,
    /** Filled focus state and the text or icon drawn on it. */
    val focus: Int, val onFocus: Int, val onFocusWeak: Int,
    /** Focus outline drawn around cards and rows. */
    val border: Int,
    /** Bottom browse menu and other sheets. */
    val sheet: Int,
    val toggleOff: Int, val knob: Int,
    val dialogTheme: Int,
    val activityTheme: Int,
)

/**
 * Layout works on a 1920x1080 design grid scaled to the real screen, so the UI keeps its proportions whether
 * the TV reports 960x540 dp or 1280x720 dp. Colours come from the current [Palette].
 */
object Ui {
    var scale = 1f
        private set

    const val BRAND = 0xFFFF8A3D.toInt()
    const val SWITCH_ON = 0xFF4560E6.toInt()

    val DARK = Palette(
        dark = true,
        bgTop = 0xFF35427C.toInt(), bgMid = 0xFF1B213C.toInt(), bgBottom = 0xFF121626.toInt(), bgPrivate = 0xFF151519.toInt(),
        text = 0xE6F0F0F2.toInt(), textMid = 0xB3F0F0F2.toInt(), textWeak = 0x66F0F0F2, textHint = 0x4DFFFFFF,
        fill = 0x0AFFFFFF, fillFocused = 0x1FFFFFFF, card = 0x14FFFFFF, cardFocused = 0x29FFFFFF,
        focus = 0xE6FFFFFF.toInt(), onFocus = 0xE6000000.toInt(), onFocusWeak = 0x99000000.toInt(),
        border = Color.WHITE,
        sheet = 0xE6000000.toInt(),
        toggleOff = 0xFF0D0E15.toInt(), knob = 0xFFE7E8ED.toInt(),
        dialogTheme = android.R.style.Theme_Material_Dialog_Alert,
        activityTheme = R.style.Theme_Sofa,
    )

    val LIGHT = Palette(
        dark = false,
        bgTop = 0xFFFFFFFF.toInt(), bgMid = 0xFFF1F3F7.toInt(), bgBottom = 0xFFE4E7EE.toInt(), bgPrivate = 0xFFE3E0EC.toInt(),
        text = 0xF0101318.toInt(), textMid = 0xB3101318.toInt(), textWeak = 0x80101318.toInt(), textHint = 0x66101318,
        fill = 0x0D101318, fillFocused = 0x1A101318, card = 0xCCFFFFFF.toInt(), cardFocused = 0xFFFFFFFF.toInt(),
        focus = 0xF01B1F2A.toInt(), onFocus = 0xF2FFFFFF.toInt(), onFocusWeak = 0xB3FFFFFF.toInt(),
        border = 0xFF1B1F2A.toInt(),
        sheet = 0xF5F7F8FB.toInt(),
        toggleOff = 0xFFC5CAD4.toInt(), knob = 0xFFFFFFFF.toInt(),
        dialogTheme = android.R.style.Theme_Material_Light_Dialog_Alert,
        activityTheme = R.style.Theme_Sofa_Light,
    )

    /** The palette in use; set by [init]. */
    var p: Palette = DARK
        private set

    /** Call first thing in every activity's onCreate, before setContentView. */
    fun init(activity: Activity) {
        val dm = activity.resources.displayMetrics
        val w = max(dm.widthPixels, dm.heightPixels)
        val h = min(dm.widthPixels, dm.heightPixels)
        // Letterbox-style fit for non-16:9 screens.
        scale = min(w / 1920f, h / 1080f)
        p = paletteFor(activity, Prefs(activity).theme)
        activity.setTheme(p.activityTheme)
    }

    fun paletteFor(context: Context, theme: String): Palette = when (theme) {
        "light" -> LIGHT
        "system" -> {
            val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (night == Configuration.UI_MODE_NIGHT_NO) LIGHT else DARK
        }
        else -> DARK
    }

    fun background(private: Boolean): Drawable =
        if (private) ColorDrawable(p.bgPrivate)
        else GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(p.bgTop, p.bgMid, p.bgBottom)
            setGradientCenter(0.5f, 0f)
            gradientRadius = 1400f * scale
        }

    fun rounded(color: Int, radius: Int, strokeColor: Int = 0, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.px.toFloat()
        if (stroke > 0) setStroke(stroke.px, strokeColor)
    }

    /** A fill that changes when focused, as used by buttons, rows and the search pill. */
    fun fillSelector(normal: Int, focused: Int, radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(focused, radius))
        addState(intArrayOf(), rounded(normal, radius))
    }

    /** The 4 px focus outline drawn over cards. */
    fun focusBorder(radius: Int) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), rounded(Color.TRANSPARENT, radius, p.border, 4))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Scales [view] up to [to] with a slight overshoot when it gains focus and back when it loses it. */
    fun focusScale(view: View, to: Float = 1.04f, then: ((Boolean) -> Unit)? = null) {
        view.setOnFocusChangeListener { v, has ->
            animateScale(v, if (has) to else 1f, has)
            then?.invoke(has)
        }
    }

    fun animateScale(v: View, to: Float, up: Boolean) {
        ObjectAnimator.ofPropertyValuesHolder(v,
            PropertyValuesHolder.ofFloat(View.SCALE_X, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, to)).apply {
            duration = if (up) 400 else 250
            interpolator = if (up) OvershootInterpolator(1.6f) else DecelerateInterpolator(2f)
        }.start()
    }

    fun text(context: Context, size: Int, color: Int = p.textMid, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size.px.toFloat())
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

    /** A filled button: subtle when idle, inverted when focused. */
    fun button(context: Context, label: String, size: Int = 28, onClick: () -> Unit) = text(context, size, p.textMid).apply {
        text = label
        isFocusable = true
        isClickable = true
        gravity = android.view.Gravity.CENTER
        background = fillSelector(p.fillFocused, p.focus, 12)
        setPadding(32.px, 14.px, 32.px, 14.px)
        setOnFocusChangeListener { v, has -> (v as TextView).setTextColor(if (has) p.onFocus else p.textMid) }
        setOnClickListener { onClick() }
    }

    fun dialog(context: Context) = AlertDialog.Builder(context, p.dialogTheme)
}

/** Design pixels (1920x1080 grid) to real pixels. */
val Int.px: Int get() = (this * Ui.scale).roundToInt()
