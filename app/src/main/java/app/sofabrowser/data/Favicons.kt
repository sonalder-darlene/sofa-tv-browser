package app.sofabrowser.data

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import java.io.File
import kotlin.concurrent.thread

/**
 * Site icons, captured from pages the WebView loads anyway. Nothing is ever fetched just to show an icon;
 * sites without a cached icon get a letter tile.
 */
class Favicons private constructor(private val context: Context) {
    private val dir = File(context.filesDir, "favicons").apply { mkdirs() }
    private val memory = LruCache<String, Bitmap>(64)

    fun save(pageUrl: String, icon: Bitmap) {
        val key = key(pageUrl) ?: return
        if (icon.width < 16) return
        val existing = memory.get(key)
        if (existing != null && existing.width >= icon.width) return
        memory.put(key, icon)
        thread(name = "favicon-save") {
            runCatching { File(dir, "$key.png").outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
    }

    fun get(pageUrl: String): Bitmap? {
        val key = key(pageUrl) ?: return null
        memory.get(key)?.let { return it }
        val f = File(dir, "$key.png")
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { memory.put(key, it) }
    }

    /** The cached icon for [pageUrl], or a letter tile drawn in a colour derived from the host. */
    fun drawable(pageUrl: String, title: String, sizePx: Int): Drawable {
        get(pageUrl)?.let { return BitmapDrawable(context.resources, it) }
        return BitmapDrawable(context.resources, letterTile(pageUrl, title, sizePx))
    }

    fun clear() {
        memory.evictAll()
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun letterTile(url: String, title: String, size: Int): Bitmap {
        val host = key(url) ?: url
        val letter = (title.firstOrNull { it.isLetterOrDigit() } ?: host.firstOrNull() ?: '?').uppercaseChar()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = ((host.hashCode() and 0x7fffffff) % 360).toFloat()
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.62f)) }
        c.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.22f, size * 0.22f, bg)
        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = size * 0.56f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        c.drawText(letter.toString(), size / 2f, size / 2f - (fg.descent() + fg.ascent()) / 2, fg)
        return bmp
    }

    private fun key(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.removePrefix("www.")
            ?.filter { it.isLetterOrDigit() || it == '.' || it == '-' }?.takeIf { it.isNotEmpty() }

    companion object {
        @SuppressLint("StaticFieldLeak") // holds the application context only
        @Volatile private var instance: Favicons? = null

        fun get(context: Context): Favicons = instance ?: synchronized(this) {
            instance ?: Favicons(context.applicationContext).also { instance = it }
        }
    }
}
