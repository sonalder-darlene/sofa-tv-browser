package app.sofabrowser.downloads

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import app.sofabrowser.data.Db
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

enum class Status { QUEUED, RUNNING, PAUSED, FAILED, DONE }

class Download internal constructor(
    val id: Long,
    val url: String,
    @Volatile var name: String,
    @Volatile var mime: String?,
    @Volatile var total: Long,
    @Volatile var done: Long,
    @Volatile var status: Status,
    @Volatile var error: String?,
    val created: Long,
    @Volatile var finished: Long,
    @Volatile var target: String?,
    val referer: String?,
    val userAgent: String?,
    @Volatile var validator: String?,
    val private: Boolean,
) {
    /** Bytes per second, while running. */
    @Volatile var speed = 0.0
        internal set

    /** Cookies for the request; kept in memory only, never written to disk. */
    @Volatile internal var cookies: String? = null
    @Volatile internal var stop: Stop? = null

    val active get() = status == Status.QUEUED || status == Status.RUNNING
    val fraction get() = if (total > 0) (done.toDouble() / total).coerceIn(0.0, 1.0) else -1.0
}

internal enum class Stop { PAUSE, CANCEL }

/**
 * Sofa's download manager: up to three transfers at a time, resumable with HTTP Range requests (and If-Range, so
 * a changed file restarts cleanly), automatic retries with backoff, and state kept in SQLite so interrupted
 * downloads pick up again. A foreground service keeps transfers alive while Sofa is in the background.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only
object DownloadEngine {
    private const val MAX_PARALLEL = 3
    private const val RETRIES = 5
    private const val BLOB_LIMIT = 64L * 1024 * 1024

    @SuppressLint("StaticFieldLeak") // the application context
    private lateinit var app: Context
    private val items = LinkedHashMap<Long, Download>()
    private val running = HashSet<Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notifyPending = false
    private var lastNotify = 0L
    @Volatile internal var serviceRunning = false

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        load()
        schedule()
    }

    val all: List<Download> get() = synchronized(this) { items.values.sortedByDescending { it.created } }
    val activeCount: Int get() = synchronized(this) { items.values.count { it.active } }
    val maxBlobBytes get() = BLOB_LIMIT

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Queues a download of [url]; call on the main thread. */
    fun enqueue(url: String, name: String, mime: String?, length: Long, referer: String?, userAgent: String?,
                cookies: String?, private: Boolean): Download {
        val now = System.currentTimeMillis()
        val safe = Http.safeFileName(name)
        val id = db().insert("downloads", null, ContentValues().apply {
            put("url", url); put("name", safe); put("mime", mime); put("total", if (length > 0) length else -1)
            put("status", Status.QUEUED.name); put("created", now); put("referer", referer)
            put("user_agent", userAgent); put("private", if (private) 1 else 0)
        })
        val d = Download(id, url, safe, mime, if (length > 0) length else -1, 0, Status.QUEUED, null, now, 0, null,
            referer, userAgent, null, private)
        d.cookies = cookies
        synchronized(this) { items[id] = d }
        schedule()
        changed(true)
        return d
    }

    /** Saves bytes the page already has (data: and blob: downloads) as a finished download. */
    fun saveBytes(name: String, mime: String?, bytes: ByteArray, sourceUrl: String, private: Boolean, done: (Result<Download>) -> Unit) =
        thread(name = "download-save") {
            val result = runCatching {
                val safe = Http.safeFileName(name)
                val target = DownloadStorage.create(app, safe, mime)
                try {
                    DownloadStorage.open(app, target, append = false).use { it.write(bytes) }
                    DownloadStorage.finish(app, target)
                } catch (e: Exception) {
                    DownloadStorage.delete(app, target)
                    throw e
                }
                val now = System.currentTimeMillis()
                val finalName = DownloadStorage.displayName(app, target) ?: safe
                val stored = if (sourceUrl.startsWith("data:")) "data:" else sourceUrl
                val id = db().insert("downloads", null, ContentValues().apply {
                    put("url", stored); put("name", finalName); put("mime", mime); put("total", bytes.size.toLong())
                    put("done", bytes.size.toLong()); put("status", Status.DONE.name); put("created", now)
                    put("finished", now); put("target", target); put("private", if (private) 1 else 0)
                })
                Download(id, stored, finalName, mime, bytes.size.toLong(), bytes.size.toLong(), Status.DONE, null, now, now,
                    target, null, null, null, private).also { synchronized(this) { items[id] = it } }
            }
            main.post { done(result); changed(true) }
        }

    fun pause(id: Long) {
        val d = get(id) ?: return
        when (d.status) {
            Status.RUNNING -> d.stop = Stop.PAUSE
            Status.QUEUED -> { d.status = Status.PAUSED; persist(d); changed(true) }
            else -> Unit
        }
    }

    fun pauseAll() = all.filter { it.active }.forEach { pause(it.id) }

    /** Resumes a paused download or retries a failed one; call on the main thread. */
    fun resume(id: Long) {
        val d = get(id) ?: return
        if (d.status != Status.PAUSED && d.status != Status.FAILED) return
        if (d.cookies == null && !d.private) d.cookies = runCatching { CookieManager.getInstance().getCookie(d.url) }.getOrNull()
        d.status = Status.QUEUED
        d.error = null
        d.stop = null
        persist(d)
        schedule()
        changed(true)
    }

    /** Stops a download if needed, deletes its partial or finished file, and forgets it. */
    fun delete(id: Long) {
        val d = get(id) ?: return
        // A worker thread owns it (running, or just about to start): let the worker clean up.
        if (synchronized(this) { d.id in running }) { d.stop = Stop.CANCEL; return }
        DownloadStorage.delete(app, d.target)
        forget(d)
    }

    /** Removes a finished or failed entry from the list; a finished file stays on the device. */
    fun removeFromList(id: Long) {
        val d = get(id) ?: return
        if (d.active) return
        if (d.status != Status.DONE) DownloadStorage.delete(app, d.target)
        forget(d)
    }

    fun clearFinished() = all.filter { it.status == Status.DONE }.forEach { forget(it) }

    /** Drops private-mode entries from the list (their files stay); called when private browsing ends. */
    fun forgetPrivate() = all.filter { it.private && !it.active }.forEach { forget(it) }

    fun fileExists(d: Download) = DownloadStorage.exists(app, d.target)

    /** An intent that opens the finished file in another app, or null when it's gone. */
    fun openIntent(d: Download): Intent? {
        val target = d.target ?: return null
        if (!DownloadStorage.exists(app, target)) return null
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(DownloadStorage.viewUri(app, target), d.mime ?: guessMime(d.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun guessMime(name: String) =
        android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    // Internals

    private fun db() = Db.get(app).writableDatabase

    private fun get(id: Long) = synchronized(this) { items[id] }

    private fun load() {
        db().rawQuery("""SELECT id, url, name, mime, total, done, status, error, created, finished, target, referer,
            user_agent, validator, private FROM downloads""", null).use { c ->
            while (c.moveToNext()) {
                val private = c.getInt(14) == 1
                var status = runCatching { Status.valueOf(c.getString(6)) }.getOrDefault(Status.FAILED)
                // Private downloads don't outlive the private session that started them (their files do).
                if (private && status == Status.DONE) { db().delete("downloads", "id = ?", arrayOf(c.getLong(0).toString())); continue }
                // Interrupted by the app closing: carry on where it stopped.
                if (status == Status.RUNNING) status = Status.QUEUED
                if (private && status != Status.DONE) status = Status.PAUSED
                val d = Download(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5),
                    status, c.getString(7), c.getLong(8), c.getLong(9), c.getString(10), c.getString(11), c.getString(12),
                    c.getString(13), private)
                items[d.id] = d
            }
        }
    }

    private fun persist(d: Download) {
        db().update("downloads", ContentValues().apply {
            put("name", d.name); put("mime", d.mime); put("total", d.total); put("done", d.done)
            put("status", d.status.name); put("error", d.error); put("finished", d.finished)
            put("target", d.target); put("validator", d.validator)
        }, "id = ?", arrayOf(d.id.toString()))
    }

    private fun forget(d: Download) {
        synchronized(this) { items.remove(d.id) }
        db().delete("downloads", "id = ?", arrayOf(d.id.toString()))
        changed(true)
    }

    private fun schedule() {
        val start = synchronized(this) {
            val free = MAX_PARALLEL - running.size
            items.values.filter { it.status == Status.QUEUED && it.id !in running }.sortedBy { it.created }.take(free.coerceAtLeast(0))
                .onEach { running.add(it.id) }
        }
        start.forEach { d -> thread(name = "download-${d.id}") { work(d) } }
        if (activeCount > 0) ensureService()
    }

    private fun ensureService() {
        if (serviceRunning) return
        val intent = Intent(app, DownloadService::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent) }
    }

    /** Notifies listeners on the main thread; progress updates are limited to four a second. */
    private fun changed(immediate: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!immediate && now - lastNotify < 250) {
            if (!notifyPending) {
                notifyPending = true
                main.postDelayed({ notifyPending = false; lastNotify = SystemClock.uptimeMillis(); listeners.forEach { it() } }, 250)
            }
            return
        }
        lastNotify = now
        main.post { listeners.forEach { it() } }
    }

    private class StopRequested : Exception()
    private class PermanentError(message: String) : Exception(message)

    private fun work(d: Download) {
        var attempt = 0
        try {
            if (d.stop != null) { stopped(d); return }
            d.status = Status.RUNNING
            d.error = null
            persist(d)
            changed(true)
            while (true) {
                try {
                    transfer(d)
                    d.target?.let { DownloadStorage.finish(app, it) }
                    d.status = Status.DONE
                    d.finished = System.currentTimeMillis()
                    persist(d)
                    break
                } catch (e: StopRequested) {
                    stopped(d)
                    break
                } catch (e: PermanentError) {
                    fail(d, e.message)
                    break
                } catch (e: IOException) {
                    if (d.stop != null) { stopped(d); break }
                    if (++attempt > RETRIES) { fail(d, e.message ?: e.javaClass.simpleName); break }
                    d.error = "retry"
                    d.speed = 0.0
                    changed(true)
                    // 2, 4, 8, 16, 30 s, waking early for pause or cancel.
                    val until = SystemClock.uptimeMillis() + minOf(30_000L, 1000L shl attempt)
                    while (SystemClock.uptimeMillis() < until && d.stop == null) Thread.sleep(250)
                    if (d.stop != null) { stopped(d); break }
                    d.error = null
                } catch (e: Exception) {
                    fail(d, e.message ?: e.javaClass.simpleName)
                    break
                }
            }
        } finally {
            d.speed = 0.0
            synchronized(this) { running.remove(d.id) }
            changed(true)
            schedule()
        }
    }

    private fun stopped(d: Download) {
        val reason = d.stop
        d.stop = null
        if (reason == Stop.CANCEL) {
            DownloadStorage.delete(app, d.target)
            forget(d)
        } else {
            d.status = Status.PAUSED
            persist(d)
        }
    }

    private fun fail(d: Download, message: String?) {
        d.status = Status.FAILED
        d.error = message
        persist(d)
    }

    private fun transfer(d: Download) {
        var url = URL(d.url)
        var redirects = 0
        var conn: HttpURLConnection
        // Follow redirects ourselves: HttpURLConnection won't switch between http and https.
        while (true) {
            if (url.protocol != "http" && url.protocol != "https") throw PermanentError("Unsupported address")
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.useCaches = false
            // Identity encoding keeps byte counts and ranges meaningful.
            conn.setRequestProperty("Accept-Encoding", "identity")
            d.userAgent?.let { conn.setRequestProperty("User-Agent", it) }
            d.referer?.takeIf { it.startsWith("http") }?.let { conn.setRequestProperty("Referer", it) }
            d.cookies?.takeIf { url.toString() == d.url }?.let { conn.setRequestProperty("Cookie", it) }
            if (d.done > 0 && d.target != null) {
                conn.setRequestProperty("Range", "bytes=${d.done}-")
                d.validator?.let { conn.setRequestProperty("If-Range", it) }
            }
            val code = conn.responseCode
            if (code in 300..399 && code != 304) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location == null || ++redirects > 8) throw PermanentError("Too many redirects")
                url = URL(url, location)
                continue
            }
            break
        }
        try {
            val code = conn.responseCode
            var append = false
            when {
                code == 206 -> {
                    val range = Http.parseContentRange(conn.getHeaderField("Content-Range"))
                    if (range != null && range.start == d.done) {
                        append = true
                        if (range.total > 0) d.total = range.total
                    } else {
                        d.done = 0
                    }
                }
                code == 416 && d.total > 0 && d.done >= d.total -> return
                code == 416 -> {
                    // Our partial file no longer matches the server's: start again from the beginning.
                    d.done = 0
                    throw IOException("HTTP 416")
                }
                code in 200..299 -> {
                    // The server ignored the range (or the file changed): start over.
                    d.done = 0
                    conn.contentLengthLong.takeIf { it > 0 }?.let { d.total = it }
                }
                Http.isRetryable(code) -> throw IOException("HTTP $code")
                else -> throw PermanentError("HTTP $code")
            }
            if (!append && code != 206) d.validator = conn.getHeaderField("ETag") ?: conn.getHeaderField("Last-Modified")
            if (d.target == null) {
                Http.filenameFromContentDisposition(conn.getHeaderField("Content-Disposition"))?.let { d.name = Http.safeFileName(it) }
                if (d.mime.isNullOrBlank() || d.mime == "application/octet-stream") {
                    conn.contentType?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" }?.let { d.mime = it }
                }
                val target = DownloadStorage.create(app, d.name, d.mime)
                d.target = target
                DownloadStorage.displayName(app, target)?.let { d.name = it }
            }
            persist(d)
            changed(true)

            val buffer = ByteArray(64 * 1024)
            var windowStart = SystemClock.uptimeMillis()
            var windowBytes = 0L
            var lastPersist = windowStart
            DownloadStorage.open(app, d.target!!, append).use { out ->
                conn.inputStream.use { input ->
                    while (true) {
                        if (d.stop != null) throw StopRequested()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        d.done += n
                        windowBytes += n
                        val now = SystemClock.uptimeMillis()
                        if (now - windowStart >= 500) {
                            val rate = windowBytes * 1000.0 / (now - windowStart)
                            d.speed = if (d.speed == 0.0) rate else d.speed * 0.6 + rate * 0.4
                            windowStart = now
                            windowBytes = 0
                            changed()
                        }
                        if (now - lastPersist >= 2000) { persist(d); lastPersist = now }
                    }
                }
            }
            if (d.total > 0 && d.done < d.total) throw IOException("Connection closed early")
            if (d.total <= 0) d.total = d.done
        } finally {
            conn.disconnect()
        }
    }
}
