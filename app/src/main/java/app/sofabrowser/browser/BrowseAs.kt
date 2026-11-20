package app.sofabrowser.browser

import android.content.Context
import app.sofabrowser.Prefs
import app.sofabrowser.PrefsMap
import app.sofabrowser.adblock.Sites
import java.util.concurrent.ConcurrentHashMap

/**
 * "Browse as" for single sites: the sites that have their own choice, by registrable domain ("www.example.co.uk" is on
 * "example.co.uk"), each with one of [Prefs.UA_MODES]; every other site follows the "Browse as" setting ([default]).
 * A site's choice covers its whole page, frames from other sites included: a WebView has one user agent.
 *
 * [stored] is kept in a preferences file of its own. Choices made in private browsing go to [privately] instead, in
 * memory only, and count only there, until private browsing ends ([BrowseAs.forgetPrivate]).
 */
class SiteModes(private val stored: MutableMap<String, String>, private val privately: MutableMap<String, String>) {

    /** The site's own choice, or null when it follows the setting. [private]: as private browsing sees it. */
    fun own(host: String?, private: Boolean): String? {
        val site = siteOf(host) ?: return null
        return (if (private) privately[site] else null)?.takeIf(::valid) ?: stored[site]?.takeIf(::valid)
    }

    /** The mode a page from [host] is browsed as. */
    fun mode(host: String?, private: Boolean, default: String): String = own(host, private) ?: default

    /**
     * "This site": [host]'s site is browsed as [mode]. A choice equal to [default] makes the site follow the setting
     * again, unless private browsing must hold it against a stored choice for the site.
     */
    fun choose(host: String, mode: String, default: String, private: Boolean) {
        val site = siteOf(host) ?: return
        if (!valid(mode)) return
        when {
            private && mode == default && stored[site] == null -> privately.remove(site)
            private -> privately[site] = mode
            mode == default -> stored.remove(site)
            else -> stored[site] = mode
        }
    }

    /** "All sites": [host]'s site drops its own choice (the stored one and private browsing's), so it follows the setting. */
    fun follow(host: String) {
        val site = siteOf(host) ?: return
        privately.remove(site)
        stored.remove(site)
    }

    /** Removes [site] (a name from [all]) from the stored list. */
    fun remove(site: String) {
        stored.remove(site)
    }

    /** The stored choices, sites A–Z; private browsing's are not among them. */
    fun all(): List<Pair<String, String>> = stored.filterValues(::valid).toSortedMap().toList()

    companion object {
        /** The registrable domain of [host], or null when there's no host (the start page, about:blank). */
        fun siteOf(host: String?): String? =
            host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotEmpty() }?.let(Sites::of)

        fun valid(mode: String) = mode in Prefs.UA_MODES
    }
}

object BrowseAs {
    /** The preferences file of the stored choices: site → mode. A setting, so clearing browsing data keeps it. */
    const val PREFS = "browse_as_sites"
    /** Sites a settings file may carry; the same cap as the other site lists. */
    const val MAX_SITES = 5_000

    private val privately = ConcurrentHashMap<String, String>()

    /** The choices, read fresh from their file (Settings may have changed it). */
    fun sites(context: Context) = SiteModes(PrefsMap(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)), privately)

    /** Private browsing ended (or began, or Sofa started): its choices go. */
    fun forgetPrivate() = privately.clear()
}
