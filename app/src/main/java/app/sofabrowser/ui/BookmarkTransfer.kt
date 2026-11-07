package app.sofabrowser.ui

import android.app.Activity
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.sofabrowser.R
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.DownloadStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bookmarks in and out as the bookmarks file desktop browsers use. Export saves it in Download (and in Sofa's
 * Downloads). Import merges, never replaces, and needs the parental PIN when one is set; it reads the file from where
 * [FileImport] offers. [changed] runs on the main thread after bookmarks were added.
 */
class BookmarkTransfer(private val activity: Activity, private val changed: () -> Unit) {

    private val db = Db.get(activity)
    private val main = Handler(Looper.getMainLooper())
    private val files = FileImport(activity, R.string.bookmarks_import, R.string.bookmarks_import_address_message,
        R.string.bookmarks_import_too_large, "text/html", listOf(".html", ".htm"), REQUEST_FILE, BookmarkFile.MAX_BYTES, ::merge)

    fun showMenu() {
        Ui.dialog(activity).setTitle(R.string.bookmarks_transfer)
            .setItems(arrayOf(activity.getString(R.string.bookmarks_import), activity.getString(R.string.bookmarks_export))) { _, which ->
                if (which == 0) PinPad.guard(activity, R.string.pin_title_import_bookmarks) { files.chooseSource() } else export()
            }.show()
    }

    /** The file picked in the TV's document picker, from the activity's onActivityResult. */
    fun onPicked(uri: Uri) = files.onPicked(uri)

    /** Reads and merges on a worker thread, then reports. */
    private fun merge(bytes: ByteArray) {
        thread(name = "bookmarks-import") {
            val outcome = runCatching {
                val contents = BookmarkFile.read(bytes.toString(Charsets.UTF_8))
                contents to db.importBookmarks(contents.entries)
            }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                outcome.onSuccess { (contents, added) ->
                    if (added > 0) changed()
                    report(contents, added)
                }.onFailure {
                    Ui.dialog(activity).setTitle(R.string.bookmarks_import)
                        .setMessage(activity.getString(R.string.bookmarks_import_failed, ErrorText.text(activity, it)))
                        .setPositiveButton(R.string.ok, null).show()
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

    companion object {
        const val REQUEST_FILE = 42
    }
}
