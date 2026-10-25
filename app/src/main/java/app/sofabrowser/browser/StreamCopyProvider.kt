package app.sofabrowser.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * Hands another player Sofa's copy of an HLS master playlist that the site only serves to its own page (it checks
 * the browser's headers, which other players don't send). The copy has absolute addresses, so the player fetches
 * everything else from the site itself. Not exported: the player gets one copy through a URI permission grant,
 * under a random name. Copies live in the cache, only the last few are kept, and none survives a restart, a switch
 * of private browsing or "clear now".
 */
class StreamCopyProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read-only")
        val token = uri.pathSegments.firstOrNull()?.takeIf { TOKEN.matches(it) } ?: throw FileNotFoundException()
        val file = File(dir(context!!), "$token.m3u8")
        if (!file.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = PlayerRequest.HLS

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

    companion object {
        private val TOKEN = Regex("[a-z0-9]{24}")
        private const val KEEP = 4

        private fun dir(context: Context) = File(context.cacheDir, "streams").apply { mkdirs() }

        /** Stores [playlist] and returns the address to give the player; it ends in .m3u8 for players that look. */
        fun put(context: Context, playlist: String): Uri {
            val rng = SecureRandom()
            val token = (1..24).map { "abcdefghijklmnopqrstuvwxyz0123456789"[rng.nextInt(36)] }.joinToString("")
            val d = dir(context)
            File(d, "$token.m3u8").writeText(playlist)
            d.listFiles()?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            return Uri.Builder().scheme("content").authority(context.packageName + ".streams")
                .appendPath(token).appendPath("master.m3u8").build()
        }

        /** Private browsing and "clear data" leave nothing behind. */
        fun clear(context: Context) {
            dir(context).listFiles()?.forEach { it.delete() }
        }
    }
}
