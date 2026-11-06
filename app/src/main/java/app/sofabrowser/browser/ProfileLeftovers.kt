package app.sofabrowser.browser

import android.content.Context
import java.io.File

/**
 * WebView writes a small preferences file for every profile it opens (`WebViewProfilePrefs…Profile 9.xml`) and keeps it
 * when the profile is deleted. Each private session has a profile of its own, so without this one empty file would pile
 * up per session.
 */
object ProfileLeftovers {
    /** Of [prefFiles] (names in shared_prefs), those of a numbered profile with no folder in [profileDirs] any more. */
    fun orphans(prefFiles: List<String>, profileDirs: Set<String>): List<String> =
        prefFiles.filter { name -> PREFS.matchEntire(name)?.let { "Profile ${it.groupValues[1]}" !in profileDirs } ?: false }

    /** Deletes the preferences files of profiles whose folders are gone. Call after the profiles are deleted. */
    fun deleteOrphanPrefs(context: Context) {
        val data = context.applicationInfo.dataDir ?: return
        val webview = File(data, "app_webview").takeIf { it.isDirectory } ?: return
        val dirs = webview.list()?.toSet() ?: return
        val prefs = File(data, "shared_prefs").list()?.toList() ?: return
        orphans(prefs, dirs).forEach { runCatching { context.deleteSharedPreferences(it.removeSuffix(".xml")) } }
    }

    /** Only the numbered profiles: "Default" (normal browsing) never matches. */
    private val PREFS = Regex("""WebViewProfilePrefs.*Profile (\d+)\.xml""")
}
