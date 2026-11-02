package app.sofabrowser.ui

import app.sofabrowser.R

/**
 * The start page as arranged in Settings > Start page: its rows and its round buttons, each an [Arrangement], and
 * how many rows of bookmarks it shows.
 *
 * Stored as one setting, `home_layout` ([encode]): the rows, ";", the buttons, ";", the number of bookmark rows, for
 * example `bookmarks,quick_access,most_visited;history,-exit;2`. "" is the default page (a part left empty is that
 * part's default). Whether Most visited is shown is its own older switch ([mostVisited], Prefs.showMostVisited), so
 * the string only keeps its place and the two can never disagree.
 */
data class HomeLayout(val rows: Arrangement<Row>, val buttons: Arrangement<Button>, val bookmarkRows: Int = 1) {

    enum class Row(override val id: String, override val icon: Int, override val label: Int) : Arrangement.Item {
        QUICK_ACCESS("quick_access", R.drawable.ic_home, R.string.quick_access),
        MOST_VISITED("most_visited", R.drawable.ic_history, R.string.most_visited),
        BOOKMARKS("bookmarks", R.drawable.ic_bookmarks, R.string.bookmarks);

        override val locked get() = false
    }

    /** The round buttons at the top, after the search pill (which always stays) and the mic (Settings > Voice search). */
    enum class Button(override val id: String, override val icon: Int, override val label: Int) : Arrangement.Item {
        BOOKMARKS("bookmarks", R.drawable.ic_bookmarks, R.string.bookmarks),
        HISTORY("history", R.drawable.ic_history, R.string.history),
        DOWNLOADS("downloads", R.drawable.ic_download, R.string.downloads),
        PRIVATE("private", R.drawable.ic_private, R.string.private_mode),
        ADBLOCK("adblock", R.drawable.ic_shield, R.string.ad_blocker),
        SETTINGS("settings", R.drawable.ic_settings, R.string.settings),
        EXIT("exit", R.drawable.ic_exit, R.string.exit_sofa);

        /** Without Settings there would be no way back to this choice; the others all stay in the menu or the remote. */
        override val locked get() = this == SETTINGS
    }

    val mostVisited get() = rows.isShown(Row.MOST_VISITED)
    val isDefault get() = rows.show(Row.MOST_VISITED, true).isDefault && buttons.isDefault && bookmarkRows == 1
    val hiddenCount get() = rows.hiddenCount + buttons.hiddenCount
    /** How many bookmarks the page shows at most. */
    val bookmarkSlots get() = bookmarkRows * PER_ROW

    /** The start page's lines of bookmarks: [bookmarks] in their order, [PER_ROW] to a line, none empty. */
    fun <T> bookmarkLines(bookmarks: List<T>): List<List<T>> = bookmarks.take(bookmarkSlots).chunked(PER_ROW)

    fun withBookmarkRows(n: Int) = copy(bookmarkRows = n.coerceIn(1, MAX_BOOKMARK_ROWS))

    /** Most visited's shown state is not written here: see the class comment. */
    fun encode(): String =
        if (isDefault) "" else "${rows.show(Row.MOST_VISITED, true).encode()};${buttons.encode()};$bookmarkRows"

    companion object {
        const val PER_ROW = 12
        const val MAX_BOOKMARK_ROWS = 4

        val DEFAULT = HomeLayout(Arrangement.default(Row.entries), Arrangement.default(Button.entries))

        /** [stored] as written by [encode], with Most visited shown or hidden as its switch says ([mostVisited]). */
        fun decode(stored: String?, mostVisited: Boolean = true): HomeLayout {
            val parts = stored.orEmpty().split(';')
            return HomeLayout(
                Arrangement.decode(Row.entries, parts.getOrNull(0)).show(Row.MOST_VISITED, mostVisited),
                Arrangement.decode(Button.entries, parts.getOrNull(1)),
                (parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1).coerceIn(1, MAX_BOOKMARK_ROWS))
        }
    }
}
