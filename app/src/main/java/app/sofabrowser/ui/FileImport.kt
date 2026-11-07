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
import app.sofabrowser.downloads.Download
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.DownloadStorage
import app.sofabrowser.downloads.Status
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Where an import reads its file from: the TV's document picker when it has a real one, a file at a web address (many
 * TVs have no picker, and Sofa opens links to such files as pages rather than downloading them), or a file of this
 * kind Sofa saved itself (its own exports, for one). [read] gets the file's bytes on the main thread; a file larger
 * than [maxBytes] is refused before it is read. The activity hands the picker's answer to [onPicked].
 */
class FileImport(
    private val activity: Activity,
    /** The import's name, for its dialogs. */
    private val title: Int,
    /** What the web address dialog says about the file. */
    private val addressMessage: Int,
    private val tooLargeMessage: Int,
    private val mime: String,
    private val extensions: List<String>,
    val requestCode: Int,
    private val maxBytes: Int,
    private val read: (ByteArray) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    fun pickerIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime)

    /** The picked file from [pickerIntent], from the activity's onActivityResult. */
    fun onPicked(uri: Uri) = readFrom { activity.contentResolver.openInputStream(uri) ?: throw IOException() }

    fun chooseSource() {
        DownloadEngine.init(activity)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (BackgroundPage.canPick(activity, pickerIntent())) {
            labels += activity.getString(R.string.bookmarks_import_file)
            actions += { activity.startActivityForResult(pickerIntent(), requestCode) }
        }
        labels += activity.getString(R.string.bookmarks_import_address)
        actions += { enterAddress() }
        savedFiles().forEach { d ->
            labels += activity.getString(R.string.bookmarks_import_saved, d.name,
                Dates.date(d.finished.takeIf { it > 0 } ?: d.created))
            actions += { readFrom { DownloadStorage.read(activity, d.target!!) } }
        }
        Ui.dialog(activity).setTitle(title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Files of this kind among Sofa's finished downloads, newest first. */
    private fun savedFiles(): List<Download> = DownloadEngine.all.filter { d ->
        d.status == Status.DONE && d.target != null && !d.private &&
            extensions.any { d.name.endsWith(it, ignoreCase = true) } && DownloadEngine.fileExists(d)
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
            .setMessage(addressMessage)
            .setView(box)
            .setPositiveButton(R.string.bookmarks_import_go) { _, _ ->
                // "example.com/file" typed over the prefilled "https://", or without it, means https.
                val url = field.text.toString().trim().removePrefix("https://https://").let { if ("://" in it) it else "https://$it" }
                // Debug builds also take http://, so tests can serve a file from the development machine.
                if (!url.startsWith("https://") && !(BuildConfig.DEBUG && url.startsWith("http://"))) {
                    Toast.makeText(activity, R.string.https_only, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(activity, R.string.bookmarks_import_fetching, Toast.LENGTH_SHORT).show()
                    readFrom { fetch(url) }
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** Reads on a worker thread; a failure is told in a dialog. */
    private fun readFrom(open: () -> InputStream) {
        thread(name = "file-import") {
            val outcome = runCatching { open().use(::readCapped) }
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                outcome.onSuccess(read).onFailure {
                    val message = if (it is TooLarge) activity.getString(tooLargeMessage)
                    else activity.getString(R.string.bookmarks_import_failed, ErrorText.text(activity, it))
                    Ui.dialog(activity).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).show()
                }
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
            if (out.size() + n > maxBytes) throw TooLarge()
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

    private companion object {
        const val MAX_SAVED = 5
    }
}
