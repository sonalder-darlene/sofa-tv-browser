package app.sofabrowser.ui

import android.content.Context
import app.sofabrowser.R

/** What a bookmarks folder holds, in words, for its card on the start page and its row on the Bookmarks screen. */
object FolderText {

    /** "12 bookmarks", "3 folders, 12 bookmarks", "2 folders", or "Empty". */
    fun contents(context: Context, folders: Int, bookmarks: Int): String {
        val r = context.resources
        val f = folders.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.folder_count, it, it) }
        val b = bookmarks.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.bookmark_count, it, it) }
        return when {
            f != null && b != null -> context.getString(R.string.folder_contents, f, b)
            else -> f ?: b ?: context.getString(R.string.folder_empty_short)
        }
    }

    /** "12 bookmarks", "2 folders and 12 bookmarks": what deleting a folder takes with it (never both zero). */
    fun deleted(context: Context, folders: Int, bookmarks: Int): String {
        val r = context.resources
        val f = folders.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.folder_count, it, it) }
        val b = bookmarks.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.bookmark_count, it, it) }
        return if (f != null && b != null) context.getString(R.string.folder_and, f, b) else f ?: b.orEmpty()
    }
}
