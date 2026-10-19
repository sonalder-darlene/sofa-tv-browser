package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Site

/** The 510x116 website card: icon, title and short address, with the white focus border. */
@SuppressLint("ViewConstructor")
class SiteCard(context: Context, val site: Site?, icon: Drawable? = null, label: String? = null) : LinearLayout(context) {

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = Ui.fillSelector(Ui.p.card, Ui.p.cardFocused, 24)
        foreground = Ui.focusBorder(24)
        setPadding(32.px, 0, 24.px, 0)
        layoutParams = LayoutParams(510.px, 116.px).apply { marginEnd = 40.px }

        val iconView = ImageView(context).apply {
            setImageDrawable(icon ?: site?.let { Favicons.get(context).drawable(it.url, it.title, 96) })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        addView(iconView, LayoutParams(48.px, 48.px).apply { marginEnd = 24.px })

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(Ui.text(context, 26, Ui.p.text).apply {
            text = label ?: site?.title?.ifBlank { null } ?: UrlInput.display(site?.url)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        if (site != null) {
            texts.addView(Ui.text(context, 18, Ui.p.textWeak).apply {
                text = UrlInput.display(site.url).substringBefore('/')
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 8.px, 0, 0)
            })
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        Ui.focusScale(this)
    }
}
