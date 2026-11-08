package app.sofabrowser.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.sofabrowser.adblock.SiteBlockedException
import app.sofabrowser.adblock.SiteFilteredHttp
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.concurrent.Executors

/**
 * The start page's own picture: one file in the app's private storage, never sent anywhere, kept when browsing data
 * is cleared. Setting it makes a fresh copy of the part a TV shows ([PictureSize.kept], [PictureSize.stored], lossy
 * WebP), which also drops the source's EXIF data; showing it decodes only as many pixels as the screen has, into
 * graphics memory where Android allows.
 */
object HomePicture {

    enum class Outcome { SET, TOO_LARGE, UNREADABLE, BLOCKED }

    private const val NAME = "start-page.webp"
    private const val QUALITY = 90
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "start-page-picture").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun file(context: Context) = File(context.filesDir, NAME)
    fun exists(context: Context) = file(context).isFile
    /** Changes whenever the picture does. */
    fun stamp(context: Context) = file(context).lastModified()

    fun remove(context: Context) = file(context).delete()

    /**
     * Stores the picture [open] reads (on a worker thread; it is closed after), replacing the one there was; [done]
     * runs on the main thread. A picture that can't be read or is too large leaves the old one as it was.
     */
    fun set(context: Context, open: () -> InputStream, done: (Outcome) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val outcome = try {
                store(app, open)
            } catch (_: OutOfMemoryError) {
                Outcome.TOO_LARGE
            } catch (_: SiteBlockedException) {
                Outcome.BLOCKED
            } catch (_: Exception) {
                Outcome.UNREADABLE
            }
            main.post { done(outcome) }
        }
    }

    /**
     * The picture at [url], fetched without cookies, as a page would load it; its redirects are checked by Site
     * filters ([blocked]) before each is followed, and none from https to http. Call on a worker thread.
     */
    fun fetch(url: String, userAgent: String?, referer: String?, blocked: (String) -> Boolean): InputStream {
        val conn = SiteFilteredHttp.open(url, blocked, downgrade = false) { conn ->
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            referer?.takeIf { it.startsWith("http") }?.let { conn.setRequestProperty("Referer", it) }
            conn.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) { conn.disconnect(); throw IOException("HTTP ${conn.responseCode}") }
        return conn.inputStream
    }

    /**
     * The picture decoded for a [width] × [height] screen (null if there is none or it can't be read); [done] runs on
     * the main thread. [hardware]: in graphics memory (Android 8 and later), for showing it, not for drawing on it.
     */
    fun load(context: Context, width: Int, height: Int, hardware: Boolean = true, done: (Bitmap?) -> Unit) {
        val f = file(context)
        worker.execute {
            val bitmap = runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, bounds)
                if (bounds.outWidth <= 0) return@runCatching null
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
                    inSampleSize = PictureSize.sample(bounds.outWidth, bounds.outHeight, width, height)
                    if (hardware && Build.VERSION.SDK_INT >= 26) inPreferredConfig = Bitmap.Config.HARDWARE
                })
            }.getOrNull()
            main.post { done(bitmap) }
        }
    }

    private fun store(context: Context, open: () -> InputStream): Outcome {
        val source = File(context.cacheDir, "start-page-source")
        try {
            val bytes = open().use { input -> source.outputStream().use { copy(input, it, PictureSize.MAX_SOURCE_BYTES + 1) } }
            if (bytes > PictureSize.MAX_SOURCE_BYTES) return Outcome.TOO_LARGE
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return Outcome.UNREADABLE
            if (PictureSize.refused(w, h, bytes)) return Outcome.TOO_LARGE

            // A photo turned by its EXIF orientation is stored upright, since the copy has no EXIF to turn it by.
            val head = ByteArray(minOf(bytes, 256 * 1024L).toInt()).also { h ->
                source.inputStream().use { input -> var at = 0; while (at < h.size) { val n = input.read(h, at, h.size - at); if (n < 0) break; at += n } }
            }
            val orientation = PictureSize.jpegOrientation(head)
            val (turn, mirror) = PictureSize.turn(orientation)
            // Only the part the start page shows is kept, so the copy is never larger than what a TV draws.
            val part = PictureSize.kept(w, h, sideways = turn % 180 != 0)
            val partW = part[2] - part[0]
            val partH = part[3] - part[1]
            val target = PictureSize.stored(partW, partH)
            // Animated GIF and WebP: BitmapFactory gives the first frame.
            val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inSampleSize = PictureSize.sample(partW, partH, target.width, target.height)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return Outcome.UNREADABLE
            // The part in the decoded pixels, which are fewer when sampled down.
            val fx = decoded.width / w.toFloat()
            val fy = decoded.height / h.toFloat()
            val x = (part[0] * fx).toInt().coerceIn(0, decoded.width - 1)
            val y = (part[1] * fy).toInt().coerceIn(0, decoded.height - 1)
            val cw = (partW * fx).toInt().coerceIn(1, decoded.width - x)
            val ch = (partH * fy).toInt().coerceIn(1, decoded.height - y)
            val m = Matrix().apply {
                postScale(target.width / cw.toFloat() * (if (mirror) -1 else 1), target.height / ch.toFloat())
                postRotate(turn.toFloat())
            }
            val copy = Bitmap.createBitmap(decoded, x, y, cw, ch, m, true)
            if (copy !== decoded) decoded.recycle()
            val fresh = File(context.filesDir, "$NAME.new")
            val written = fresh.outputStream().use { copy.compress(webp(), QUALITY, it) }
            copy.recycle()
            if (!written || !fresh.renameTo(file(context))) { fresh.delete(); return Outcome.UNREADABLE }
            return Outcome.SET
        } finally {
            source.delete()
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream, limit: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return total
    }

    /** Lossy WebP: the one modern format Bitmap.compress writes on every Android version Sofa runs on. */
    private fun webp(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
}
