package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.ui.HomeBackground.Choice

/**
 * Settings > Start page background: the theme's background, the built-in colours and gradients and the user's own
 * picture as swatches (OK picks one), with the start page as it will look beside them; below, Choose a picture (only
 * when the TV has a document picker), Remove picture and Dim. Every choice is saved at once and keeps the focus.
 */
@SuppressLint("ViewConstructor")
class BackgroundPage(private val activity: Activity, private val prefs: Prefs) : LinearLayout(activity) {

    private val hint = Ui.text(context, 28, Ui.p.textMid)
    private val swatches = ArrayList<Pair<Choice, FrameLayout>>()
    private val pictureLine = LinearLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val rows = LinearLayout(context).apply { orientation = VERTICAL; clipChildren = false }
    private val preview = FrameLayout(context).apply { A11y.skipAll(this) }
    private var thumbnail: Bitmap? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        addView(Ui.text(context, 56, Ui.p.textMid, bold = true).apply { setText(R.string.background_setting_title); A11y.heading(this) },
            LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 64.px })
        addView(hint, LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 16.px; bottomMargin = 24.px })

        val grid = LinearLayout(context).apply { orientation = VERTICAL; clipChildren = false }
        grid.addView(label(R.string.background_colours))
        grid.addView(line(listOf(Choice.Theme) + HomeBackground.Swatch.entries.filter { !it.gradient }.map { Choice.Look(it) }))
        grid.addView(label(R.string.background_gradients))
        grid.addView(line(HomeBackground.Swatch.entries.filter { it.gradient }.map { Choice.Look(it) }))
        grid.addView(label(R.string.background_your_picture))
        grid.addView(pictureLine)
        addView(LinearLayout(context).apply {
            clipChildren = false
            addView(grid, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(preview, LayoutParams(PREVIEW_W.px, PREVIEW_H.px).apply { topMargin = 56.px; marginStart = 40.px })
        }, LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT))
        addView(rows, LayoutParams(WIDTH.px, LayoutParams.WRAP_CONTENT).apply { topMargin = 32.px })
        refresh()
        post { swatches.firstOrNull { it.first == current() }?.second?.requestFocus() ?: swatches.firstOrNull()?.second?.requestFocus() }
    }

    private fun current() = HomeBackground.decode(prefs.homeBackground, HomePicture.exists(context))

    /** Everything again after a choice: the ticks, the picture's line and rows, the preview. Focus stays where it was. */
    fun refresh() {
        val focusedRow = rows.indexOfChild(rows.focusedChild)
        val pictureFocused = pictureLine.hasFocus()
        swatches.removeAll { it.first == Choice.Picture }
        pictureLine.removeAllViews()
        rows.removeAllViews()
        if (HomePicture.exists(context)) {
            pictureLine.addView(swatch(Choice.Picture), swatchParams())
            loadThumbnail()
        } else {
            thumbnail = null
            pictureLine.addView(Ui.text(context, 24, Ui.p.textWeak).apply { setText(R.string.background_no_picture); gravity = Gravity.CENTER_VERTICAL },
                LayoutParams(LayoutParams.WRAP_CONTENT, SWATCH_H.px).apply { marginStart = 8.px })
        }
        if (pickerIntent().let { canPick(activity, it) }) {
            row(R.string.background_choose, null, R.string.background_hint_choose) {
                activity.startActivityForResult(pickerIntent(), REQUEST_PICTURE)
            }
        }
        if (HomePicture.exists(context)) row(R.string.background_remove, null, R.string.background_hint_remove) {
            HomePicture.remove(context)
            if (prefs.homeBackground == HomeBackground.PICTURE) prefs.homeBackground = ""
            refresh()
        }
        row(R.string.background_dim, context.getString(HomeBackground.dim(prefs.homeDim).label), R.string.background_hint_dim) { pickDim() }
        // The picture's swatch and the rows were made again: back on the same one, or the nearest one still there.
        if (pictureFocused) (pictureLine.getChildAt(0)?.takeIf { it.isFocusable } ?: rows.getChildAt(0)).requestFocus()
        else if (focusedRow >= 0) (rows.getChildAt(focusedRow) ?: rows.getChildAt(rows.childCount - 1)).requestFocus()
        val chosen = current()
        swatches.forEach { (choice, view) -> (view.getChildAt(1) as ImageView).visibility = if (choice == chosen) VISIBLE else GONE }
        showPreview()
    }

    private fun label(text: Int) = Ui.text(context, 24, Ui.p.textWeak).apply { setText(text); setPadding(8.px, 20.px, 0, 12.px) }

    private fun line(choices: List<Choice>) = LinearLayout(context).apply {
        clipChildren = false
        clipToPadding = false
        choices.forEach { addView(swatch(it), swatchParams()) }
    }

    private fun swatchParams() = LayoutParams(SWATCH_W.px, SWATCH_H.px).apply { marginEnd = 10.px; marginStart = 4.px }

    private fun swatch(choice: Choice) = FrameLayout(context).apply {
        isFocusable = true
        isClickable = true
        foreground = Ui.focusBorder(14)
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, 14.px.toFloat())
        }
        addView(View(context).apply { background = look(choice) })
        addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_check)
            setColorFilter(Ui.p.text)
            background = Ui.rounded(if (Ui.p.dark) 0x99000000.toInt() else 0x99FFFFFF.toInt(), 18)
            setPadding(4.px, 4.px, 4.px, 4.px)
            A11y.skip(this)
        }, FrameLayout.LayoutParams(36.px, 36.px, Gravity.CENTER))
        setOnClickListener { prefs.homeBackground = HomeBackground.encode(choice); refresh() }
        // Only a colour to see: named for a screen reader, the chosen one selected (its tick stays silent).
        contentDescription = name(choice)
        A11y.role(this, A11y.BUTTON, selected = { current() == choice })
        setOnFocusChangeListener { v, has ->
            Ui.animateScale(v, if (has) 1.1f else 1f, has)
            if (has) hint.text = name(choice)
        }
        swatches.add(choice to this)
    }

    private fun look(choice: Choice): Drawable = when (choice) {
        Choice.Picture -> thumbnail?.let { HomeBackground.Cover(it, Ui.p.bgMid, 0) } ?: HomeBackground.drawable(Choice.Theme)
        else -> HomeBackground.drawable(choice)
    }

    private fun name(choice: Choice): String = when (choice) {
        Choice.Theme -> context.getString(R.string.background_theme)
        Choice.Picture -> context.getString(R.string.background_your_picture)
        is Choice.Look -> context.getString(choice.swatch.label)
    }

    /** The start page in small: its background as chosen (dimmed like the real one) under bars for its rows. */
    private fun showPreview() {
        preview.removeAllViews()
        val choice = current()
        val picture = thumbnail
        preview.background = if (choice == Choice.Picture && picture != null)
            HomeBackground.Cover(picture, Ui.p.bgMid, HomeBackground.scrim(HomeBackground.dim(prefs.homeDim), Ui.p.dark))
        else HomeBackground.drawable(choice)
        preview.foreground = Ui.rounded(Color.TRANSPARENT, 12, Ui.p.textWeak, 2)
        val page = LinearLayout(context).apply { orientation = VERTICAL; setPadding(24.px, 20.px, 0, 0) }
        page.addView(View(context).apply { background = Ui.rounded(Ui.p.fillFocused, 8) }, LayoutParams(150.px, 18.px))
        for (title in listOf(R.string.quick_access, R.string.bookmarks)) {
            page.addView(Ui.text(context, 14, Ui.p.textMid).apply { setText(title); setPadding(0, 20.px, 0, 8.px) })
            page.addView(LinearLayout(context).apply {
                repeat(4) { addView(View(context).apply { background = Ui.rounded(Ui.p.card, 6) }, LayoutParams(90.px, 34.px).apply { marginEnd = 12.px }) }
            })
        }
        preview.addView(page)
        preview.clipToOutline = true
        preview.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }

    private fun loadThumbnail() {
        val stamp = HomePicture.stamp(context)
        HomePicture.load(context, PREVIEW_W.px, PREVIEW_H.px, hardware = false) { bmp ->
            if (HomePicture.stamp(context) != stamp || bmp == null) return@load
            thumbnail = bmp
            swatches.firstOrNull { it.first == Choice.Picture }?.second?.getChildAt(0)?.background = look(Choice.Picture)
            showPreview()
        }
    }

    private fun row(title: Int, value: String?, hintText: Int, onClick: () -> Unit) {
        val t = Ui.text(context, 32, Ui.p.text).apply { setText(title) }
        val v = value?.let { Ui.text(context, 28, Ui.p.textMid).apply { text = it } }
        rows.addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            background = Ui.fillSelector(Ui.p.fill, Ui.p.focus, 16)
            setPadding(40.px, 0, 32.px, 0)
            addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            v?.let(::addView)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, has ->
                t.setTextColor(if (has) Ui.p.onFocus else Ui.p.text)
                (v as TextView?)?.setTextColor(if (has) Ui.p.onFocusWeak else Ui.p.textMid)
                if (has) hint.setText(hintText)
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, 96.px).apply { bottomMargin = 4.px })
    }

    private fun pickDim() {
        val dims = HomeBackground.Dim.entries
        Ui.dialog(context).setTitle(R.string.background_dim)
            .setSingleChoiceItems(dims.map { context.getString(it.label) }.toTypedArray(), dims.indexOf(HomeBackground.dim(prefs.homeDim))) { d, which ->
                prefs.homeDim = dims[which].id
                d.dismiss()
                refresh()
            }.show()
    }

    companion object {
        const val REQUEST_PICTURE = 41
        private const val WIDTH = 1600
        private const val SWATCH_W = 112
        private const val SWATCH_H = 64
        private const val PREVIEW_W = 400
        private const val PREVIEW_H = 225
        /** A TV's placeholder that answers the document picker's intent with only a "not available" notice. */
        private const val TV_STUBS = "com.android.tv.frameworkpackagestubs"

        fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")

        /** Whether this TV has a document picker that really picks: many have none, or only the placeholder. */
        fun canPick(activity: Activity, intent: Intent) =
            activity.packageManager.queryIntentActivities(intent, 0).any { it.activityInfo.packageName != TV_STUBS }
    }
}
