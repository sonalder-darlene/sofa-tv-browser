package app.sofabrowser.data

import app.sofabrowser.Prefs
import app.sofabrowser.adblock.FilterList
import app.sofabrowser.browser.ProtectedMedia
import app.sofabrowser.browser.WatchTarget
import app.sofabrowser.dns.DnsProviders
import app.sofabrowser.player.PlayerChoices
import app.sofabrowser.ui.HomeBackground
import app.sofabrowser.ui.OpenAtStart

/**
 * What a settings file may carry, and the checks a file's values pass before Sofa takes them: only the values Settings
 * itself could have set, so a file from anywhere can't leave Sofa in a state it can't show or start from.
 *
 * Never in a file: the parental PIN (a preferences file of its own), browsing data (history, cookies, site storage,
 * the open tabs, "Where I left off", where films were left), the start page picture, and what Sofa only remembers
 * for itself (questions already asked, update checks, warnings shown).
 */
object SettingsFile {
    const val FORMAT = "sofa-settings"
    const val VERSION = 1
    /** Larger files are refused before they are read; a file with 20,000 bookmarks is about 3 MB. */
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_SITES = 5_000
    const val MAX_LISTS = 100
    const val MAX_TILES = 200
    private const val MAX_URL = 4_000
    private const val MAX_TITLE = 500

    sealed interface Rule { fun accepts(v: Any): Boolean }

    private object Bool : Rule { override fun accepts(v: Any) = v is Boolean }

    private class OneOf(val values: Collection<Any>) : Rule { override fun accepts(v: Any) = v in values }

    /** One line of text up to [max] characters that passes [check]. */
    private class Text(val max: Int, val check: (String) -> Boolean = { true }) : Rule {
        override fun accepts(v: Any) = v is String && v.length <= max && v.none { it == '\n' || it == '\r' } && check(v)
    }

    /** A set of site names or origins, stored as a string set. */
    private object Names : Rule {
        override fun accepts(v: Any) = v is List<*> && v.size <= MAX_SITES && v.all { it is String && isName(it) }
    }

    /**
     * Every setting a file carries, by its key in Sofa's settings. A file's null means "the default" (the key isn't
     * set): the key is removed on import, so a default stays a default that later versions may change.
     */
    val RULES: Map<String, Rule> = linkedMapOf(
        // Appearance
        "theme" to OneOf(Prefs.THEMES),
        "text_zoom" to OneOf(Prefs.TEXT_SIZES),
        "menu_buttons" to Text(2_000),
        "open_at_start" to OneOf(OpenAtStart.Choice.entries.map { it.id }),
        "start_page" to Text(MAX_URL) { it.isEmpty() || isWebAddress(it) },
        "home_layout" to Text(2_000),
        // The picture itself never travels, so a file never asks for one.
        "home_background" to Text(100) { it.isEmpty() || it != HomeBackground.PICTURE && HomeBackground.decode(it, false) != HomeBackground.Choice.Theme },
        "home_dim" to OneOf(HomeBackground.Dim.entries.map { it.id }),
        "dark_pages" to Bool,
        "most_visited" to Bool,
        // Browsing
        "search_engine" to OneOf(Prefs.ENGINES.map { it.id } + "custom"),
        "custom_search" to Text(2_000) { it.startsWith("https://") && "%s" in it },
        "search_suggestions" to Bool,
        "voice_search" to Bool,
        "user_agent" to OneOf(Prefs.UA_MODES),
        "custom_user_agent" to Text(1_000),
        "cursor_speed" to OneOf(listOf(1, 2, 3)),
        "back_goes_back" to Bool,
        "javascript" to Bool,
        "autoplay" to Bool,
        "sofa_player" to Bool,
        "open_in_player" to Bool,
        "auto_watch" to OneOf(WatchTarget.Auto.entries.map { it.name }),
        "auto_watch_sites" to Names,
        "player_quality" to Text(200) { it.isEmpty() || PlayerChoices.decode(it) != null },
        "player_subtitles" to Text(200),
        "player_audio" to Text(200),
        // An app of this TV: SettingsTransfer takes it only when the TV has it.
        "video_player" to Text(300),
        "force_external_player" to Bool,
        "match_frame_rate" to Bool,
        "keys_to_page" to Names,
        // Privacy
        "adblock" to Bool,
        "auto_update_lists" to Bool,
        "dns_main" to Text(2_000, ::isDns),
        "dns_fallback" to Text(2_000, ::isDns),
        "dns_strict" to Bool,
        "cookies" to Bool,
        "block_3p_cookies" to Bool,
        "send_gpc" to Bool,
        "safe_browsing" to Bool,
        "drm_mode" to OneOf(listOf(ProtectedMedia.ASK, ProtectedMedia.ALLOW, ProtectedMedia.BLOCK)),
        "drm_allowed" to Names,
        "drm_blocked" to Names,
        "history_days" to OneOf(HistoryRetention.CHOICES),
        "clear_on_exit" to Bool,
        // Updates: SettingsTransfer takes it only on a copy that offers them.
        "check_updates" to Bool,
    )

    /**
     * The settings in a file that Sofa takes: keys it knows, with values that pass their rule and [accept]. Numbers
     * arrive from JSON as Int, Long or Double; whole ones count as Int. Also returns how many were left out.
     */
    fun clean(settings: Map<String, Any?>, accept: (String, Any) -> Boolean = { _, _ -> true }): Pair<Map<String, Any?>, Int> {
        val taken = LinkedHashMap<String, Any?>()
        var leftOut = 0
        for ((key, raw) in settings) {
            val rule = RULES[key]
            val value = raw?.let(::number)
            when {
                rule == null -> leftOut++
                value == null -> taken[key] = null
                rule.accepts(value) && accept(key, value) -> taken[key] = value
                else -> leftOut++
            }
        }
        return taken to leftOut
    }

    private fun number(v: Any): Any = when (v) {
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
        is Double -> if (v % 1.0 == 0.0 && v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) v.toInt() else v
        else -> v
    }

    /** A filter list as a file has it; [id] is one every install has, or null for a list the user added. */
    data class ListChoice(val id: String?, val url: String, val on: Boolean, val site: Boolean)

    /**
     * The lists in a file that Sofa takes: ones every install has, by id (a Site filters category with an address
     * Sofa offers for it), and ones the user added, by an https address. [http] lets debug builds take http too.
     */
    fun cleanLists(lists: List<ListChoice>, http: Boolean = false): List<ListChoice> {
        val defaults = FilterList.defaults().associateBy { it.id }
        val seen = HashSet<String>()
        return lists.mapNotNull { l ->
            val known = l.id?.let(defaults::get)
            when {
                l.id != null && known == null -> null
                known != null -> when {
                    !seen.add(known.id) -> null
                    known.id == FilterList.GAMBLING && l.url in FilterList.GAMBLING_URLS -> l.copy(site = true)
                    else -> l.copy(url = known.url!!, site = known.site)
                }
                l.url.length <= MAX_URL && l.url.none(Char::isWhitespace) &&
                    (l.url.startsWith("https://") || http && l.url.startsWith("http://")) && seen.add(l.url + l.site) -> l
                else -> null
            }
        }.take(MAX_LISTS)
    }

    /** Site names a file lists (sites with ad blocking off, sites let through Site filters), checked and capped. */
    fun cleanNames(names: List<String>): List<String> = names.filter(::isName).distinct().take(MAX_SITES)

    /** Quick access tiles or bookmarks a file lists: web addresses only, first copy of each, titles capped. */
    fun cleanEntries(entries: List<BookmarkFile.Entry>, max: Int): List<BookmarkFile.Entry> {
        val seen = HashSet<String>()
        return entries.filter { isWebAddress(it.url) && seen.add(it.url) }.take(max)
            .map { it.copy(title = it.title.replace('\n', ' ').trim().take(MAX_TITLE)) }
    }

    fun isWebAddress(url: String) = url.length <= MAX_URL && BookmarkFile.isWeb(url) && url.none(Char::isWhitespace)

    private fun isName(s: String) = s.length in 1..300 && s.none(Char::isWhitespace)

    private fun isDns(s: String) = s.isEmpty() || DnsProviders.byId(s) != null || DnsProviders.isCustom(s) && s.none(Char::isWhitespace)
}
