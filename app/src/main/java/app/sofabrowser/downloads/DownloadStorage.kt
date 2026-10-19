package app.sofabrowser.downloads

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Where downloads are written. Android 10+: the shared Download folder through MediaStore, kept pending (hidden
 * from other apps) until complete. Older Android: Sofa's own download folder, which needs no storage permission;
 * [DownloadFileProvider] lets other apps open those files.
 *
 * A target is stored as a string: a content:// URI, or an absolute file path.
 */
object DownloadStorage {

    private val useMediaStore get() = Build.VERSION.SDK_INT >= 29

    fun create(context: Context, name: String, mime: String?): String {
        if (useMediaStore) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Can't create the file in Download")
            return uri.toString()
        }
        val dir = legacyDir(context)
        val (base, ext) = name.substringBeforeLast('.', name) to name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(dir, name)
        var n = 1
        while (!file.createNewFile()) file = File(dir, "$base (${n++})$ext")
        return file.absolutePath
    }

    fun open(context: Context, target: String, append: Boolean): OutputStream =
        if (isUri(target)) {
            context.contentResolver.openOutputStream(Uri.parse(target), if (append) "wa" else "wt")
                ?: throw FileNotFoundException(target)
        } else {
            FileOutputStream(File(target), append)
        }

    /** Makes a finished download visible to other apps. */
    fun finish(context: Context, target: String) {
        if (!isUri(target)) return
        context.contentResolver.update(Uri.parse(target), ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }, null, null)
    }

    fun delete(context: Context, target: String?) {
        if (target == null) return
        runCatching {
            if (isUri(target)) context.contentResolver.delete(Uri.parse(target), null, null) else File(target).delete()
        }
    }

    fun exists(context: Context, target: String?): Boolean {
        if (target == null) return false
        if (!isUri(target)) return File(target).exists()
        return runCatching {
            context.contentResolver.query(Uri.parse(target), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /** The name the file actually got (MediaStore renames on collisions). */
    fun displayName(context: Context, target: String): String? {
        if (!isUri(target)) return File(target).name
        return runCatching {
            context.contentResolver.query(Uri.parse(target), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /** A URI other apps can read when granted permission. */
    fun viewUri(context: Context, target: String): Uri =
        if (isUri(target)) Uri.parse(target) else DownloadFileProvider.uriFor(context, File(target))

    /** Human-readable save location, for the Downloads screen. */
    fun locationLabel(context: Context): String =
        if (useMediaStore) Environment.DIRECTORY_DOWNLOADS
        else legacyDir(context).absolutePath.removePrefix(Environment.getExternalStorageDirectory().absolutePath + "/")

    internal fun legacyDir(context: Context): File =
        (context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: File(context.filesDir, "downloads")).apply { mkdirs() }

    private fun isUri(target: String) = target.startsWith("content://")
}
