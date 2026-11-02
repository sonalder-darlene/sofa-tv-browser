package app.sofabrowser.adblock

import org.json.JSONArray
import org.json.JSONObject

/**
 * A filter list in Settings → Filter lists: one bundled with Sofa ([asset] set, can be refreshed from [url] and reset),
 * one Sofa offers to download (EasyList and EasyPrivacy, off until switched on), or one the user added by address.
 * A [site] list belongs to Settings → Site filters instead: it blocks whole sites, every way in, rather than the ads
 * on a page. [rules] is what the list contributed when it was last compiled.
 */
data class FilterList(
    val id: String,
    /** The list's own title once read ("! Title: EasyList"), else a name Sofa gave it. */
    val name: String,
    val url: String?,
    val asset: String? = null,
    val enabled: Boolean = true,
    val rules: Int = 0,
    /** When it was last downloaded (milliseconds since 1970), or 0 for the copy bundled with Sofa. */
    val updated: Long = 0,
    /** Why the last download failed, until one succeeds. */
    val error: String? = null,
    /** How often the list asks to be refreshed ("! Expires: 4 days"), in hours, or 0 when it doesn't say. */
    val expiresHours: Int = 0,
    /** Blocks whole sites (Site filters) rather than ads. */
    val site: Boolean = false,
) {
    val bundled: Boolean get() = asset != null

    /** One of the lists behind "Stronger ad blocking", which go stale within days. */
    val stronger: Boolean get() = id in STRONGER

    /** One of the categories on the Site filters page, whose name and address Sofa sets. */
    val category: Boolean get() = id in CATEGORIES

    /**
     * How long a download stays fresh for automatic updates: what the list asks for, between one and seven days, and
     * at least three days for a very large list, which costs a TV a few megabytes to download and compile each time.
     */
    val refreshHours: Int get() =
        (expiresHours.takeIf { it > 0 } ?: DEFAULT_REFRESH_HOURS).coerceIn(if (rules > LARGE_RULES) 3 * 24 else 24, 7 * 24)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        url?.let { put("url", it) }
        asset?.let { put("asset", it) }
        put("enabled", enabled)
        put("rules", rules)
        put("updated", updated)
        error?.let { put("error", it) }
        put("expires", expiresHours)
        if (site) put("site", true)
    }

    companion object {
        const val STEVENBLACK = "stevenblack"
        const val POPUPS = "hagezi-popups"
        const val STEVENBLACK_URL = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        const val POPUPS_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/popupads-onlydomains.txt"

        const val EASYLIST = "easylist"
        const val EASYPRIVACY = "easyprivacy"
        const val EASYLIST_URL = "https://easylist.to/easylist/easylist.txt"
        const val EASYPRIVACY_URL = "https://easylist.to/easylist/easyprivacy.txt"
        val STRONGER = setOf(EASYLIST, EASYPRIVACY)
        private const val DEFAULT_REFRESH_HOURS = 4 * 24
        private const val LARGE_RULES = 200_000

        // Site filters, all off until switched on. HaGeZi's lists are "wildcard" domains: each covers its subdomains too.
        const val ADULT = "site-adult"
        const val GAMBLING = "site-gambling"
        const val UNSAFE = "site-unsafe"
        const val DRUGS = "site-drugs"
        val CATEGORIES = setOf(ADULT, GAMBLING, UNSAFE, DRUGS)
        const val ADULT_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/nsfw-onlydomains.txt"
        const val GAMBLING_MINI_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling.mini-onlydomains.txt"
        const val GAMBLING_MEDIUM_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling.medium-onlydomains.txt"
        const val GAMBLING_FULL_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/gambling-onlydomains.txt"
        /** The sizes of the gambling list, smallest first; one is used at a time. */
        val GAMBLING_URLS = listOf(GAMBLING_MINI_URL, GAMBLING_MEDIUM_URL, GAMBLING_FULL_URL)
        /** FMHY's Basic list: only sites it found malicious (its Plus list adds software it doesn't recommend). */
        const val UNSAFE_URL = "https://raw.githubusercontent.com/fmhy/FMHYFilterlist/main/filterlist-basic-domains.txt"
        const val DRUGS_URL = "https://blocklistproject.github.io/Lists/alt-version/drugs-nl.txt"

        /**
         * The lists every install has: two bundled in the APK and on, EasyList and EasyPrivacy, which are only
         * downloaded once switched on ("Stronger ad blocking"), and the Site filters categories, likewise.
         */
        fun defaults() = listOf(
            FilterList(STEVENBLACK, "StevenBlack hosts", STEVENBLACK_URL, AdBlocker.BUNDLED_ASSET),
            FilterList(POPUPS, "HaGeZi Pop-Up Ads", POPUPS_URL, AdBlocker.POPUP_ASSET),
            FilterList(EASYLIST, "EasyList", EASYLIST_URL, enabled = false),
            FilterList(EASYPRIVACY, "EasyPrivacy", EASYPRIVACY_URL, enabled = false),
            FilterList(ADULT, "HaGeZi NSFW", ADULT_URL, enabled = false, site = true),
            FilterList(GAMBLING, "HaGeZi Gambling", GAMBLING_MEDIUM_URL, enabled = false, site = true),
            FilterList(UNSAFE, "FMHY", UNSAFE_URL, enabled = false, site = true),
            FilterList(DRUGS, "Blocklist Project", DRUGS_URL, enabled = false, site = true),
        )

        /**
         * [stored] lists with the ones every install has added where missing; a list the user added by the address of
         * an ad list every install has becomes that one, so its switch and "Stronger ad blocking" agree. Never for a
         * Site filters category: that would turn an ad list into one that blocks whole sites. Ids that change are
         * returned as old to new, for the files kept by id.
         */
        fun complete(stored: List<FilterList>): Pair<List<FilterList>, Map<String, String>> {
            val renamed = HashMap<String, String>()
            var lists = stored
            for (d in defaults()) {
                if (lists.any { it.id == d.id }) continue
                val same = lists.firstOrNull { it.url == d.url && !it.bundled && !d.site }
                lists = if (same != null) {
                    renamed[same.id] = d.id
                    lists.map { if (it === same) it.copy(id = d.id, name = d.name) else it }
                } else lists + d
            }
            return lists to renamed
        }

        /** Whether automatic updates should download [list] again at [now]; a bundled copy dates from [installed]. */
        fun isDue(list: FilterList, installed: Long, now: Long): Boolean {
            val since = maxOf(list.updated, if (list.bundled) installed else 0L)
            return now - since >= list.refreshHours * 60 * 60 * 1000L
        }

        fun fromJson(o: JSONObject) = FilterList(
            id = o.getString("id"),
            name = o.optString("name", o.getString("id")),
            url = o.optString("url").ifEmpty { null },
            asset = o.optString("asset").ifEmpty { null },
            enabled = o.optBoolean("enabled", true),
            rules = o.optInt("rules"),
            updated = o.optLong("updated"),
            error = o.optString("error").ifEmpty { null },
            expiresHours = o.optInt("expires"),
            site = o.optBoolean("site"),
        )

        fun listToJson(lists: List<FilterList>): String = JSONArray().apply { lists.forEach { put(it.toJson()) } }.toString()

        /**
         * Whether the user already chose about "Stronger ad blocking" before Sofa asked: one of its lists is on, or was
         * downloaded (or tried) once, so it was switched on at some point.
         */
        fun strongerChosen(lists: List<FilterList>): Boolean =
            lists.any { it.stronger && (it.enabled || it.updated > 0 || it.error != null) }

        /** Reads [json]; a damaged entry is dropped, never fatal. */
        fun listFromJson(json: String): List<FilterList> {
            val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> runCatching { fromJson(a.getJSONObject(i)) }.getOrNull() }
        }

        /**
         * The lists to start with when upgrading from Sofa 1.1, which had one main list. A custom address the user set
         * is kept as a list of its own (or is EasyList or EasyPrivacy, switched on); when its download was in use
         * ([downloaded]), it replaced the bundled hosts list, so that one starts switched off, as before.
         */
        fun migrate(oldUrl: String?, downloaded: Boolean): List<FilterList> {
            val defaults = defaults()
            if (oldUrl.isNullOrBlank() || oldUrl == STEVENBLACK_URL) return defaults
            val replaced = defaults.map { if (it.id == STEVENBLACK && downloaded) it.copy(enabled = false) else it }
            if (defaults.any { it.url == oldUrl && !it.site }) return replaced.map { if (it.url == oldUrl && !it.site) it.copy(enabled = true) else it }
            return replaced + FilterList(CUSTOM_ID, RequestUrl.hostOf(oldUrl) ?: oldUrl, oldUrl)
        }

        const val CUSTOM_ID = "custom"
    }
}
