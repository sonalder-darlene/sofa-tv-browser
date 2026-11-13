package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import app.sofabrowser.browser.UrlInput
import app.sofabrowser.data.Favicons
import app.sofabrowser.data.Site

/**
 * The 510x116 website card: icon, title and short address, with the white focus border. A card that isn't a site (a
 * bookmarks folder, "All bookmarks", "Add site") gives its [icon], [label] and, under it, [detail]; [tint] colours its
 * icon as text is. [key] names the card for finding it again after the page is rebuilt: the site's address unless given.
 */
@SuppressLint("ViewConstructor")
class SiteCard(
    context: Context,
    val site: Site?,
    icon: Drawable? = null,
    label: String? = null,
    detail: String? = null,
    tint: Boolean = false,
    key: String? = null,
) : LinearLayout(context) {

    val key: String = key ?: site?.url ?: label.orEmpty()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = Ui.fillSelector(Ui.p.card, Ui.p.cardFocused, 24)
        foreground = Ui.focusBorder(24)
        setPadding(32.px, 0, 24.px, 0)
        layoutParams = LayoutParams(510.px, 116.px).apply { marginEnd = 40.px }
        // Read as its title and site, then "button".
        A11y.role(this, A11y.BUTTON)

        val iconView = ImageView(context).apply {
            setImageDrawable(icon ?: site?.let { Favicons.get(context).drawable(it.url, it.title, 96) })
            scaleType = ImageView.ScaleType.FIT_CENTER
            if (tint) setColorFilter(Ui.p.textMid, PorterDuff.Mode.SRC_IN)
        }
        addView(iconView, LayoutParams(48.px, 48.px).apply { marginEnd = 24.px })

        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(Ui.text(context, 26, Ui.p.text).apply {
            text = label ?: site?.title?.ifBlank { null } ?: UrlInput.display(site?.url)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        val second = detail ?: site?.let { UrlInput.display(it.url).substringBefore('/') }
        if (second != null) {
            texts.addView(Ui.text(context, 18, Ui.p.textWeak).apply {
                text = second
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 8.px, 0, 0)
            })
        }
        addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (tint) Ui.focusScale(this) { has -> iconView.setColorFilter(if (has) Ui.p.text else Ui.p.textMid, PorterDuff.Mode.SRC_IN) }
        else Ui.focusScale(this)
    }
}
