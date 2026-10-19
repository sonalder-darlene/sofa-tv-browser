package app.sofabrowser.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.ImageView
import app.sofabrowser.SearchEngine
import app.sofabrowser.data.Favicons

/** A search engine's bundled icon, or a letter tile for a custom engine. Never loaded from the network. */
object EngineIcon {
    fun drawable(context: Context, engine: SearchEngine): Drawable =
        engine.icon.takeIf { it != 0 }?.let { context.getDrawable(it) }
            ?: Favicons.get(context).drawable(engine.template, engine.name, 128)

    fun view(context: Context, engine: SearchEngine) = ImageView(context).apply {
        setImageDrawable(drawable(context, engine))
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
}
