package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import app.sofabrowser.BuildConfig
import app.sofabrowser.R
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db
import app.sofabrowser.downloads.Download
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.DownloadStorage
import app.sofabrowser.downloads.Status
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bookmarks in and out as the bookmarks file desktop browsers use. Export saves it in Download (and in Sofa's
 * Downloads). Import merges, never replaces, and needs the parental PIN when one is set. It reads a file from the
 * TV's document picker when the TV has a real one, a file at a web address (many TVs have no picker), or a bookmarks
 * file Sofa saved itself. [changed] runs on the main thread after bookmarks were added.
 */
class BookmarkTransfer(private val activity: Activity, private val changed: () -> Unit) {

    private val db = Db.get(activity)
    private val main = Handler(Looper.getMainLooper())

    fun showMenu() {
        Ui.dialog(activity).setTitle(R.string.bookmarks_transfer)
            .setItems(arrayOf(activity.getString(R.string.bookmarks_import), activity.getString(R.string.bookmarks_export))) { _, which ->
                if (which == 0) PinPad.guard(activity, R.string.pin_title_import_bookmarks) { chooseSource() } else export()
            }.show()
    }

    /** The picked file from [pickerIntent], from the activity's onActivityResult. */
    fun onPicked(uri: Uri) = importFrom { activity.contentResolver.openInputStream(uri) ?: throw IOException() }

    private fun chooseSource() {
        DownloadEngine.init(activity)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (BackgroundPage.canPick(activity, pickerIntent())) {
            labels += activity.getString(R.string.bookmarks_import_file)
            actions += { activity.startActivityForResult(pickerIntent(), REQUEST_FILE) }
        }
        labels += activity.getString(R.string.bookmarks_import_address)
        actions += { enterAddress() }
        savedFiles().forEach { d ->
            labels += activity.getString(R.string.bookmarks_import_saved, d.name,
                Dates.date(d.finished.takeIf { it > 0 } ?: d.created))
            actions += { importFrom { DownloadStorage.read(activity, d.target!!) } }
        }
        Ui.dialog(activity).setTitle(R.string.bookmarks_import)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Bookmarks files among Sofa's finished downloads (its own exports, for one), newest first. */
    private fun savedFiles(): List<Download> = DownloadEngine.all.filter { d ->
        d.status == Status.DONE && d.target != null && !d.private &&
            (d.name.endsWith(".html", ignoreCase = true) || d.name.endsWith(".htm", ignoreCase = true)) &&
            DownloadEngine.fileExists(d)
    }.take(MAX_SAVED)

    /** Asks for the file's address, "https://" already typed, cursor at the end (the TV keyboard keeps it there). */
    @SuppressLint("SetTextI18n") // a URL scheme, not words
    private fun enterAddress() {
        val field = EditText(activity).apply {
            setText("https://")
            setSelection(text.length)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val box = FrameLayout(activity).apply { setPadding(48.px, 16.px, 48.px, 0); addView(field) }
        Ui.dialog(activity).setTitle(R.string.bookmarks_import_address)
            .setMessage(R.string.bookmarks_import_address_message)
            .setView(box)
            .setPositiveButton(R.string.bookmarks_import_go) { _, _ ->
                // "example.com/bookmarks.html" typed over the prefilled "https://", or without it, means https.
                val url = field.text.toString().trim().removePrefix("https://https://").let { if ("://" in it) it else "https://$it" }
                // Debug builds also take http://, so tests can serve a file from the development machine.
                if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) {
                    Toast.makeText(activity, R.string.https_only, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, R.string.bookmarks_import_fetching, Toast.LENGTH_SHORT).show()
                    importFrom { fetch(url) }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Reads, merges and reports on a worker thread; the file is refused when it is larger than any real one. */
    private fun importFrom(open: () -> InputStream) {
        thread(name = "bookmarks-import") {
            val outcome = runCatching {
                val text = open().use { readCapped(it) }.toString(Charsets.UTF_8)
                val contents = BookmarkFile.read(text)
                contents to db.importBookmarks(contents.entries)
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                outcome.onSuccess { (contents, added) ->
                    if (added > 0) changed()
                    report(contents, added)
                }.onFailure {
                    val message = if (it is TooLarge) activity.getString(R.string.bookmarks_import_too_large)
                    else activity.getString(R.string.bookmarks_import_failed, ErrorText.text(activity, it))
                    Ui.dialog(activity).setTitle(R.string.bookmarks_import).setMessage(message).setPositiveButton(R.string.ok, null).show()
                }
            }
        }
    }

    private fun report(contents: BookmarkFile.Contents, added: Int) {
        val r = activity.resources
        val message = if (contents.entries.isEmpty()) activity.getString(R.string.bookmarks_import_none)
        else listOfNotNull(
            if (added > 0) r.getQuantityString(R.plurals.bookmarks_imported, added, added) else activity.getString(R.string.bookmarks_import_nothing_new),
            (contents.entries.size - added).takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.bookmarks_import_known, it, it) },
            contents.skipped.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.bookmarks_import_skipped, it, it) },
        ).joinToString(" ")
        Ui.dialog(activity).setTitle(R.string.bookmarks_import).setMessage(message).setPositiveButton(R.string.ok, null).show()
    }

    private fun export() {
        val entries = db.bookmarksToExport()
        if (entries.isEmpty()) { Toast.makeText(activity, R.string.bookmarks_export_none, Toast.LENGTH_SHORT).show(); return }
        DownloadEngine.init(activity)
        val name = "sofa-bookmarks-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".html"
        DownloadEngine.saveBytes(name, "text/html", BookmarkFile.write(entries).toByteArray(), "data:", private = false) { result ->
            if (activity.isFinishing || activity.isDestroyed) return@saveBytes
            result.onSuccess { d ->
                val message = activity.resources.getQuantityString(R.plurals.bookmarks_exported, entries.size,
                    entries.size, DownloadStorage.locationLabel(activity), d.name)
                Ui.dialog(activity).setTitle(R.string.bookmarks_export).setMessage(message).setPositiveButton(R.string.ok, null).show()
            }.onFailure {
                Toast.makeText(activity, activity.getString(R.string.bookmarks_export_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private class TooLarge : IOException()

    private fun readCapped(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > BookmarkFile.MAX_BYTES) throw TooLarge()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** The file at [url], without cookies, as a list update fetches one. Call on a worker thread. */
    private fun fetch(url: String): InputStream {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) { conn.disconnect(); throw IOException("HTTP ${conn.responseCode}") }
        return object : java.io.FilterInputStream(conn.inputStream) {
            override fun close() { try { super.close() } finally { conn.disconnect() } }
        }
    }

    companion object {
        const val REQUEST_FILE = 42
        private const val MAX_SAVED = 5

        fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/html")
    }
}
