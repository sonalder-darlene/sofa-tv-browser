package app.sofabrowser

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import app.sofabrowser.browser.ProtectedMedia

data class SearchEngine(
    val id: String,
    val name: String,
    val template: String,
    val suggestTemplate: String?,
    /** Short description for the picker (string resource), or 0. */
    val tagline: Int = 0,
    /** Bundled icon (drawable resource), or 0. */
    val icon: Int = 0,
    /** Works only with a paid account or key. */
    val paid: Boolean = false,
) {
    fun searchUrl(query: String) = template.replace("%s", Uri.encode(query))
}

/** User settings. Every default favours privacy; nothing here is ever sent anywhere. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The first-run search engine choice has been made. */
    var setupDone by boolean("setup_done", false)
    /** "dark", "light" or "system". Websites follow it too (prefers-color-scheme). */
    var theme by string("theme", "dark")
    var searchEngineId by string("search_engine", "duckduckgo")
    var customSearchTemplate by string("custom_search", "https://searx.example/search?q=%s")
    /** Sends typed text to the search engine for suggestions; off because it leaks every keystroke. */
    var searchSuggestions by boolean("search_suggestions", false)
    /** Shows the mic buttons; voice search runs on the TV's speech service. */
    var voiceSearch by boolean("voice_search", true)
    var adBlock by boolean("adblock", true)
    var blocklistUrl by string("blocklist_url", "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")
    var cookies by boolean("cookies", true)
    var blockThirdPartyCookies by boolean("block_3p_cookies", true)
    /**
     * Sends the Global Privacy Control signal. Off by default: like the old Do Not Track header (which Sofa
     * never sends), it is set by few browsers and so makes a visitor easier to single out.
     */
    var sendGpc by boolean("send_gpc", false)
    /** One of [UA_MODES]. Desktop suits a big screen and the pointer best. */
    var userAgent by string("user_agent", "desktop")
    var customUserAgent by string("custom_user_agent", "")
    /** BACK goes to the previous page when there is one, instead of opening the menu (BrowsHere's behaviour). */
    var backGoesBack by boolean("back_goes_back", false)
    var javascript by boolean("javascript", true)
    var autoplay by boolean("autoplay", true)
    var textZoom by int("text_zoom", 100)
    var cursorSpeed by int("cursor_speed", 2) // 1 slow .. 3 fast
    var rememberHistory by boolean("remember_history", true)
    /** Clears cookies, site storage, cache and history whenever the app is closed. */
    var clearOnExit by boolean("clear_on_exit", false)
    var safeBrowsing by boolean("safe_browsing", false)
    /** Lets WebView darken pages that have no dark theme of their own. */
    var darkPages by boolean("dark_pages", false)
    var showMostVisited by boolean("most_visited", true)
    var privateMode by boolean("private_mode", false)
    var askedNotifications by boolean("asked_notifications", false)

    /**
     * Looks for a new release on GitHub when Sofa starts, at most once a day. Only offered to copies that no app
     * store keeps up to date, and off until the user turns it on.
     */
    var checkUpdates by boolean("check_updates", false)
    /** The first-start update question has been answered. */
    var updatesAsked by boolean("updates_asked", false)
    var lastUpdateCheck by long("last_update_check", 0L)
    /** A version the user chose to skip when it was offered at launch. */
    var skippedUpdate by string("skipped_update", "")

    /** Sites driven with D-pad keys sent to the page instead of the pointer (e.g. TV-style web apps). */
    fun keysToPage(host: String) = host in p.getStringSet("keys_to_page", emptySet())!!
    fun setKeysToPage(host: String, on: Boolean) = editSet("keys_to_page", host, on)

    /** "Protected content (DRM)": one of ProtectedMedia's ASK, ALLOW and BLOCK. */
    var drmMode by string("drm_mode", ProtectedMedia.ASK)

    /** A site's answer to the protected-content prompt: true allowed, false blocked, null never answered. */
    fun drmAnswer(origin: String): Boolean? = when (origin) {
        in p.getStringSet("drm_allowed", emptySet())!! -> true
        in p.getStringSet("drm_blocked", emptySet())!! -> false
        else -> null
    }

    fun setDrmAnswer(origin: String, allowed: Boolean) {
        editSet("drm_allowed", origin, allowed)
        editSet("drm_blocked", origin, !allowed)
    }

    fun clearDrmAnswers() = p.edit().remove("drm_allowed").remove("drm_blocked").apply()

    private fun editSet(key: String, item: String, on: Boolean) {
        val set = HashSet(p.getStringSet(key, emptySet())!!)
        if (on) set.add(item) else set.remove(item)
        p.edit().putStringSet(key, set).apply()
    }

    val searchEngine: SearchEngine
        get() = if (searchEngineId == "custom") SearchEngine("custom", "Custom", customSearchTemplate, null)
        else ENGINES.firstOrNull { it.id == searchEngineId } ?: ENGINES[0]

    private fun string(key: String, def: String) = Delegate({ p.getString(key, def) ?: def }) { p.edit().putString(key, it).apply() }
    private fun boolean(key: String, def: Boolean) = Delegate({ p.getBoolean(key, def) }) { p.edit().putBoolean(key, it).apply() }
    private fun int(key: String, def: Int) = Delegate({ p.getInt(key, def) }) { p.edit().putInt(key, it).apply() }
    private fun long(key: String, def: Long) = Delegate({ p.getLong(key, def) }) { p.edit().putLong(key, it).apply() }

    private class Delegate<T>(val get: () -> T, val set: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = set(value)
    }

    companion object {
        val UA_MODES = listOf("desktop", "mobile", "webview", "ipad", "tv", "custom")

        /**
         * The first-start choices, in alphabetical order. Templates come from each engine's OpenSearch
         * description, minus traffic-source markers; suggestion endpoints are listed only where they answer in the
         * OpenSearch suggestions format without cookies or keys (verified October 2026). Icons are bundled, so
         * showing the list contacts no one.
         */
        val ENGINES = listOf(
            SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s",
                "https://search.brave.com/api/suggest?q=%s", R.string.engine_brave, R.drawable.engine_brave),
            SearchEngine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s",
                "https://duckduckgo.com/ac/?type=list&q=%s", R.string.engine_duckduckgo, R.drawable.engine_duckduckgo),
            SearchEngine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s",
                "https://ac.ecosia.org/autocomplete?type=list&q=%s", R.string.engine_ecosia, R.drawable.engine_ecosia),
            SearchEngine("google", "Google", "https://www.google.com/search?q=%s",
                "https://www.google.com/complete/search?client=firefox&q=%s", R.string.engine_google, R.drawable.engine_google),
            SearchEngine("kagi", "Kagi", "https://kagi.com/search?q=%s",
                "https://kagisuggest.com/api/autosuggest?q=%s", R.string.engine_kagi, R.drawable.engine_kagi, paid = true),
            SearchEngine("metager", "MetaGer", "https://metager.org/meta/meta.ger3?eingabe=%s", null,
                R.string.engine_metager, R.drawable.engine_metager, paid = true),
            SearchEngine("mojeek", "Mojeek", "https://www.mojeek.com/search?q=%s", null,
                R.string.engine_mojeek, R.drawable.engine_mojeek),
            SearchEngine("murena", "Murena Find", "https://murena.qwant.com/?q=%s",
                "https://api.qwant.com/v3/suggest/?client=opensearch&q=%s", R.string.engine_murena, R.drawable.engine_murena),
            SearchEngine("qwant", "Qwant", "https://www.qwant.com/?q=%s",
                "https://api.qwant.com/v3/suggest/?client=opensearch&q=%s", R.string.engine_qwant, R.drawable.engine_qwant),
            SearchEngine("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s",
                "https://www.startpage.com/osuggestions?q=%s", R.string.engine_startpage, R.drawable.engine_startpage),
            SearchEngine("swisscows", "Swisscows", "https://swisscows.com/web?query=%s", null,
                R.string.engine_swisscows, R.drawable.engine_swisscows),
            SearchEngine("uruky", "Uruky", "https://uruky.com/search?q=%s", null,
                R.string.engine_uruky, R.drawable.engine_uruky, paid = true),
        )
    }
}
