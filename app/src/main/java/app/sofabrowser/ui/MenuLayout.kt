package app.sofabrowser.ui

import app.sofabrowser.R

/**
 * The order of the browse menu's buttons and which of them are shown, as chosen in Settings > Menu buttons.
 *
 * Stored as one setting ([encode]): button ids in order, separated by commas, a hidden one prefixed with "-", for
 * example `watch,home,-back`. "" is the default menu, so a user who never changed it gets a later version's default.
 * The ids never change, so a stored or exported layout stays readable: unknown ids are skipped, and a button added
 * in a later version shows up at its default place.
 */
class MenuLayout private constructor(val entries: List<Entry>) {

    /** A movable button. [ZOOM] is the zoom group (out, the percentage, in), moved as one. */
    enum class Item(val id: String, val icon: Int, val label: Int) {
        HOME("home", R.drawable.ic_home, R.string.home),
        // Watch is the action a TV is most often used for: next to Home.
        WATCH("watch", R.drawable.ic_play_circle, R.string.watch),
        BACK("back", R.drawable.ic_arrow_back, R.string.back),
        FORWARD("forward", R.drawable.ic_arrow_forward, R.string.forward),
        RELOAD("reload", R.drawable.ic_refresh, R.string.reload),
        FIND("find", R.drawable.ic_find_in_page, R.string.find_in_page),
        BOOKMARK("bookmark", R.drawable.ic_star, R.string.bookmark),
        PIN("pin", R.drawable.ic_add, R.string.pin_to_home),
        HISTORY("history", R.drawable.ic_history, R.string.history),
        BOOKMARKS("bookmarks", R.drawable.ic_bookmarks, R.string.bookmarks),
        DOWNLOADS("downloads", R.drawable.ic_download, R.string.downloads),
        // "Allow once" (shown only after a block) always follows it.
        ADBLOCK("adblock", R.drawable.ic_shield, R.string.adblock_site),
        // With the other choices kept per site; never shown in private browsing, which saves none.
        AUTO_WATCH("auto_watch", R.drawable.ic_autoplay, R.string.auto_watch),
        USER_AGENT("user_agent", R.drawable.ic_desktop_windows, R.string.user_agent),
        SCROLL("scroll", R.drawable.ic_swap_vert, R.string.scroll_mode),
        POINTER("pointer", R.drawable.ic_mouse, R.string.pointer),
        ZOOM("zoom", R.drawable.ic_zoom_in, R.string.zoom),
        SETTINGS("settings", R.drawable.ic_settings, R.string.settings);

        /** Can't be hidden: without Settings there would be no way back to this choice, without Home no way to the start page. */
        val locked get() = this == SETTINGS || this == HOME
    }

    data class Entry(val item: Item, val shown: Boolean)

    val shown: List<Item> get() = entries.filter { it.shown }.map { it.item }
    val isDefault get() = entries == DEFAULT.entries
    val hiddenCount get() = entries.count { !it.shown }

    /** [from] moved to [to] (indexes in [entries]); out-of-range moves change nothing. */
    fun move(from: Int, to: Int): MenuLayout {
        if (from !in entries.indices || to !in entries.indices || from == to) return this
        return MenuLayout(entries.toMutableList().apply { add(to, removeAt(from)) })
    }

    /** The button at [index] shown or hidden; a [Item.locked] one stays shown. */
    fun show(index: Int, on: Boolean): MenuLayout {
        val e = entries.getOrNull(index) ?: return this
        if (e.item.locked || e.shown == on) return this
        return MenuLayout(entries.toMutableList().apply { set(index, e.copy(shown = on)) })
    }

    fun encode(): String = if (isDefault) "" else entries.joinToString(",") { (if (it.shown) "" else "-") + it.item.id }

    override fun equals(other: Any?) = other is MenuLayout && other.entries == entries
    override fun hashCode() = entries.hashCode()

    companion object {
        val DEFAULT = MenuLayout(Item.entries.map { Entry(it, true) })

        fun decode(stored: String?): MenuLayout {
            if (stored.isNullOrBlank()) return DEFAULT
            val list = ArrayList<Entry>()
            for (raw in stored.split(',')) {
                val token = raw.trim()
                val hidden = token.startsWith("-")
                val item = Item.entries.firstOrNull { it.id == token.removePrefix("-") } ?: continue
                if (list.none { it.item == item }) list.add(Entry(item, !hidden || item.locked))
            }
            // A button this layout doesn't name (new in this version) goes after the one before it in the default order.
            for (item in Item.entries) {
                if (list.any { it.item == item }) continue
                val before = Item.entries.subList(0, item.ordinal).asReversed().firstOrNull { b -> list.any { it.item == b } }
                list.add(if (before == null) 0 else list.indexOfFirst { it.item == before } + 1, Entry(item, true))
            }
            return MenuLayout(list)
        }
    }
}
