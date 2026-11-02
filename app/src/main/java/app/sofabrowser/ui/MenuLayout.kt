package app.sofabrowser.ui

import app.sofabrowser.R

/**
 * The browse menu's buttons, as arranged in Settings > Menu buttons: an [Arrangement] of [Item]s, stored in
 * Settings as `menu_buttons`.
 */
object MenuLayout {

    /** A movable button. [ZOOM] is the zoom group (out, the percentage, in), moved as one. */
    enum class Item(override val id: String, override val icon: Int, override val label: Int) : Arrangement.Item {
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
        override val locked get() = this == SETTINGS || this == HOME
    }

    val DEFAULT = Arrangement.default(Item.entries)

    fun decode(stored: String?) = Arrangement.decode(Item.entries, stored)
}
