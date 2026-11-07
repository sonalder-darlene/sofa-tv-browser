package app.sofabrowser.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.adblock.AdBlocker
import app.sofabrowser.adblock.Allowlist
import app.sofabrowser.adblock.FilterList
import app.sofabrowser.adblock.SiteAllowlist
import app.sofabrowser.browser.ExternalPlayer
import app.sofabrowser.data.BackupCrypto
import app.sofabrowser.data.BookmarkFile
import app.sofabrowser.data.Db
import app.sofabrowser.data.SettingsFile
import app.sofabrowser.data.SettingsFile.ListChoice
import app.sofabrowser.dns.SecureDns
import app.sofabrowser.downloads.DownloadEngine
import app.sofabrowser.downloads.DownloadStorage
import app.sofabrowser.update.Updater
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Settings out to a file and back in, to move them to another TV or keep them through a reset. The file holds the
 * settings [SettingsFile] lists, the filter lists, the sites with ad blocking off or let through Site filters, Quick
 * access and bookmarks. Export protects it with a password ([BackupCrypto]) unless the user chooses not to.
 * Import replaces the settings with the file's and adds its lists, sites, tiles and bookmarks to what is here; it
 * never removes any. Both are inside Settings, behind the parental PIN when one is set. [imported] runs on the main
 * thread once a file was imported.
 *
 * The file is JSON: `{"format": "sofa-settings", "version": 1, …}` with the contents, or, with a password, the same
 * two fields, `"encryption"` (the KDF, its iterations and salt, the cipher and its IV) and `"data"`: the contents
 * encrypted, in Base64.
 */
class SettingsTransfer(private val activity: Activity, private val imported: () -> Unit) {

    private val prefs = Prefs(activity)
    private val db = Db.get(activity)
    private val blocker = AdBlocker.get(activity)
    private val main = Handler(Looper.getMainLooper())
    val files = FileImport(activity, R.string.settings_import, R.string.settings_import_address_message,
        R.string.settings_import_too_large, "application/json", listOf(".json"), REQUEST_FILE, SettingsFile.MAX_BYTES, ::opened)

    // Export

    /** Asks for a password; "No password" is the way out of encryption, never the default. */
    fun export() {
        val field = passwordField()
        val d = Ui.dialog(activity).setTitle(R.string.settings_export)
            .setMessage(R.string.settings_export_password_message)
            .setView(padded(field))
            .setPositiveButton(R.string.settings_export_go, null)
            .setNeutralButton(R.string.settings_export_plain) { _, _ -> save(null) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.setOnShowListener {
            // Kept open on a password too short, so what was typed stays.
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = field.text.toString()
                if (password.length < BackupCrypto.MIN_PASSWORD) {
                    Toast.makeText(activity, activity.getString(R.string.settings_password_short, BackupCrypto.MIN_PASSWORD), Toast.LENGTH_LONG).show()
                } else { d.dismiss(); save(password) }
            }
        }
        d.show()
    }

    private fun save(password: String?) {
        if (password != null) Toast.makeText(activity, R.string.settings_export_encrypting, Toast.LENGTH_SHORT).show()
        val picture = prefs.homeBackground == HomeBackground.PICTURE
        work(R.string.settings_export, {
            val plain = contents().toString().toByteArray(Charsets.UTF_8)
            if (password == null) plain else sealed(BackupCrypto.seal(plain, password)).toString().toByteArray(Charsets.UTF_8)
        }) { bytes ->
            DownloadEngine.init(activity)
            val name = "sofa-settings-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json"
            DownloadEngine.saveBytes(name, "application/json", bytes, "data:", private = false) { result ->
                if (activity.isFinishing || activity.isDestroyed) return@saveBytes
                result.onSuccess { d ->
                    val message = listOfNotNull(
                        activity.getString(if (password != null) R.string.settings_exported else R.string.settings_exported_plain,
                            DownloadStorage.locationLabel(activity), d.name),
                        if (picture) activity.getString(R.string.settings_exported_no_picture) else null,
                    ).joinToString("\n\n")
                    Ui.dialog(activity).setTitle(R.string.settings_export).setMessage(message).setPositiveButton(R.string.ok, null).show()
                }.onFailure {
                    Toast.makeText(activity, activity.getString(R.string.bookmarks_export_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** What the file holds, unencrypted. Call on a worker thread. */
    private fun contents(): JSONObject {
        // The lists load as Sofa starts; Settings is rarely open sooner.
        repeat(50) { if (!blocker.ready) Thread.sleep(100) }
        val stored = activity.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).all
        val settings = JSONObject()
        for (key in SettingsFile.RULES.keys) settings.put(key, json(stored[key]))
        // 1.0's history switch still counts where no period was ever chosen.
        settings.put("history_days", prefs.historyDays)
        // The picture never travels: left out, so an import keeps the background the TV has (its own picture, on this TV).
        if (prefs.homeBackground == HomeBackground.PICTURE) settings.remove("home_background")
        val defaults = FilterList.defaults().map { it.id }.toSet()
        return header().put("app", BuildConfig.VERSION_NAME).put("saved", System.currentTimeMillis())
            .put("settings", settings)
            .put("filter_lists", JSONArray(blocker.lists.filter { it.url != null }.map { l ->
                JSONObject().put("id", l.id.takeIf { it in defaults }).put("url", l.url).put("on", l.enabled).put("site", l.site)
            }))
            .put("ad_blocking_off", JSONArray(Allowlist.all(activity)))
            .put("allowed_sites", JSONArray(SiteAllowlist.all(activity)))
            .put("quick_access", JSONArray(db.tiles().map { JSONObject().put("title", it.title).put("url", it.url) }))
            .put("bookmarks", JSONArray(db.bookmarksToExport().map { JSONObject().put("title", it.title).put("url", it.url).put("added", it.added) }))
    }

    private fun json(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Set<*> -> JSONArray(v.map { it.toString() }.sorted())
        else -> v
    }

    private fun header() = JSONObject().put("format", SettingsFile.FORMAT).put("version", SettingsFile.VERSION)

    private fun sealed(s: BackupCrypto.Sealed) = header()
        .put("encryption", JSONObject().put("kdf", BackupCrypto.KDF).put("iterations", s.iterations).put("salt", base64(s.salt))
            .put("cipher", BackupCrypto.CIPHER).put("iv", base64(s.iv)))
        .put("data", base64(s.data))

    // Import

    /** Asks for the parental PIN first, when Settings didn't (one set while Settings was open). */
    fun import() = PinPad.guard(activity, R.string.pin_title_import_settings) { files.chooseSource() }

    private class NotSettings : Exception()
    private class Newer : Exception()

    /** A file read, either the backup itself or its encrypted form. */
    private sealed interface Opened
    private class Locked(val sealed: BackupCrypto.Sealed) : Opened
    private class Backup(
        val app: String,
        val saved: Long,
        val settings: Map<String, Any?>,
        val leftOut: Int,
        val lists: List<ListChoice>,
        val adBlockingOff: List<String>,
        val allowedSites: List<String>,
        val tiles: List<BookmarkFile.Entry>,
        val bookmarks: List<BookmarkFile.Entry>,
    ) : Opened

    private fun opened(bytes: ByteArray) = work(R.string.settings_import, { read(bytes, outer = true) }) { opened ->
        when (opened) {
            is Locked -> askPassword(opened.sealed, wrong = false)
            is Backup -> confirm(opened)
        }
    }

    private fun read(bytes: ByteArray, outer: Boolean): Opened {
        val o = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        if (o == null || o.optString("format") != SettingsFile.FORMAT) throw NotSettings()
        if (o.optInt("version", 0) > SettingsFile.VERSION) throw Newer()
        val e = o.optJSONObject("encryption")
        if (e != null) {
            // Encrypted twice is not a file Sofa writes.
            if (!outer || e.optString("kdf") != BackupCrypto.KDF || e.optString("cipher") != BackupCrypto.CIPHER) throw NotSettings()
            return Locked(BackupCrypto.Sealed(e.optInt("iterations"), unbase64(e.optString("salt")), unbase64(e.optString("iv")),
                unbase64(o.optString("data"))))
        }
        val (settings, leftOut) = SettingsFile.clean(map(o.optJSONObject("settings")), ::acceptable)
        val lists = SettingsFile.cleanLists(objects(o.optJSONArray("filter_lists")).mapNotNull { l ->
            val url = l.optString("url").ifEmpty { return@mapNotNull null }
            ListChoice(if (l.isNull("id")) null else l.optString("id"), url, l.optBoolean("on"), l.optBoolean("site"))
        }, http = BuildConfig.DEBUG)
        return Backup(
            app = o.optString("app"),
            saved = o.optLong("saved"),
            settings = settings,
            leftOut = leftOut,
            lists = lists,
            adBlockingOff = SettingsFile.cleanNames(strings(o.optJSONArray("ad_blocking_off"))),
            allowedSites = SettingsFile.cleanNames(strings(o.optJSONArray("allowed_sites"))),
            tiles = SettingsFile.cleanEntries(entries(o.optJSONArray("quick_access")), SettingsFile.MAX_TILES),
            bookmarks = SettingsFile.cleanEntries(entries(o.optJSONArray("bookmarks")), BookmarkFile.MAX_ENTRIES),
        )
    }

    /** The values that depend on this TV: its "Play in…" app must be here, and updates offered to this copy. */
    private fun acceptable(key: String, value: Any): Boolean = when (key) {
        "video_player" -> value == "" || value == Prefs.ASK_EVERY_TIME || ExternalPlayer.installed(activity, value as String) != null
        "check_updates" -> Updater.offered(activity)
        else -> true
    }

    private fun askPassword(sealed: BackupCrypto.Sealed, wrong: Boolean) {
        val field = passwordField()
        val d = Ui.dialog(activity).setTitle(R.string.settings_import)
            .setMessage(if (wrong) R.string.settings_password_wrong else R.string.settings_password_needed)
            .setView(padded(field))
            .setPositiveButton(R.string.bookmarks_import_go) { _, _ ->
                val password = field.text.toString()
                Toast.makeText(activity, R.string.settings_import_decrypting, Toast.LENGTH_SHORT).show()
                work(R.string.settings_import, {
                    try { read(BackupCrypto.open(sealed, password), outer = false) }
                    catch (e: BackupCrypto.WrongPassword) { null }
                }) { opened -> if (opened is Backup) confirm(opened) else askPassword(sealed, wrong = true) }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        d.show()
    }

    private fun confirm(b: Backup) {
        val from = listOfNotNull(
            b.app.takeIf { it.isNotEmpty() }?.let { activity.getString(R.string.settings_import_from_version, it) },
            b.saved.takeIf { it > 0 }?.let { activity.getString(R.string.settings_import_from_date, Dates.date(it)) },
        ).joinToString(", ")
        val message = listOf(from, activity.getString(R.string.settings_import_confirm)).filter { it.isNotEmpty() }.joinToString("\n\n")
        Ui.dialog(activity).setTitle(R.string.settings_import).setMessage(message)
            .setPositiveButton(R.string.bookmarks_import_go) { _, _ -> apply(b) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private class Applied(val tiles: Int, val bookmarks: Int, val listsOn: Boolean)

    // commit, not apply: on a worker thread, and what follows reads the settings back.
    @SuppressLint("ApplySharedPref")
    private fun apply(b: Backup) = work(R.string.settings_import, {
        val edit = activity.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
        for ((key, value) in b.settings) when (value) {
            null -> edit.remove(key)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is String -> edit.putString(key, value)
            is List<*> -> edit.putStringSet(key, value.map { it as String }.toHashSet())
        }
        edit.commit()
        // What Settings does when these change by hand.
        if (prefs.clearOnExit) { prefs.lastPage = ""; prefs.tabs = "" }
        db.pruneHistory(prefs.historyDays)
        val listsOn = applyLists(b.lists)
        b.adBlockingOff.forEach { Allowlist.set(activity, it, allowed = true, private = false) }
        b.allowedSites.forEach { SiteAllowlist.allow(activity, it, private = false) }
        val here = db.tiles().map { it.url }.toHashSet()
        val tiles = b.tiles.filter { here.add(it.url) }
        tiles.forEach { db.addTile(it.title.ifBlank { it.url }, it.url) }
        Applied(tiles.size, db.importBookmarks(b.bookmarks), listsOn)
    }) { a ->
        SecureDns.apply(activity.applicationContext)
        val r = activity.resources
        val message = listOfNotNull(
            activity.getString(R.string.settings_imported),
            a.bookmarks.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.bookmarks_imported, it, it) },
            a.tiles.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.settings_imported_tiles, it, it) },
            b.leftOut.takeIf { it > 0 }?.let { r.getQuantityString(R.plurals.settings_imported_left_out, it, it) },
            if (a.listsOn) activity.getString(R.string.settings_imported_lists) else null,
        ).joinToString(" ")
        Ui.dialog(activity).setTitle(R.string.settings_import).setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { imported() }
            .show()
    }

    /**
     * Each list as the file has it on or off; a list the user added that isn't here is added when it is on (and
     * downloaded, like one added by hand). Returns whether a list was switched on, so it is downloading.
     */
    private fun applyLists(choices: List<ListChoice>): Boolean {
        repeat(50) { if (!blocker.ready) Thread.sleep(100) }
        var switchedOn = false
        for (c in choices) {
            val here = blocker.lists.firstOrNull { if (c.id != null) it.id == c.id else it.url == c.url && it.site == c.site }
            when {
                here == null -> if (c.on) { blocker.add(c.url, c.site) {}; switchedOn = true }
                here.site -> {
                    val url = c.url.takeIf { here.id == FilterList.GAMBLING && it != here.url }
                    if (here.enabled != c.on || url != null && c.on) { blocker.setSite(here.id, c.on, url); switchedOn = switchedOn || c.on }
                }
                here.enabled != c.on -> { blocker.setEnabled(here.id, c.on); switchedOn = switchedOn || c.on }
            }
        }
        return switchedOn
    }

    // Shared

    /**
     * A password field that shows what is typed: a typo made with a remote would lock the file for good, and the TV
     * keyboards don't learn from a password field. Never handed to autofill services.
     */
    private fun passwordField() = EditText(activity).apply {
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        hint = activity.getString(R.string.settings_password_hint, BackupCrypto.MIN_PASSWORD)
        filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(BackupCrypto.MAX_PASSWORD))
        if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    private fun padded(v: View) = FrameLayout(activity).apply { setPadding(48.px, 16.px, 48.px, 0); addView(v) }

    /** Runs [task] on a worker thread and [done] with its result on the main thread; a failure is told in a dialog. */
    private fun <T> work(title: Int, task: () -> T, done: (T) -> Unit) {
        thread(name = "settings-transfer") {
            val outcome = runCatching(task)
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                outcome.onSuccess(done).onFailure {
                    val message = when (it) {
                        is NotSettings, is IllegalArgumentException -> activity.getString(R.string.settings_import_not_settings)
                        is Newer -> activity.getString(R.string.settings_import_newer)
                        else -> activity.getString(R.string.settings_failed, ErrorText.text(activity, it))
                    }
                    Ui.dialog(activity).setTitle(title).setMessage(message).setPositiveButton(R.string.ok, null).show()
                }
            }
        }
    }

    private fun map(o: JSONObject?): Map<String, Any?> {
        o ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (key in o.keys()) out[key] = when (val v = o.opt(key)) {
            JSONObject.NULL -> null
            is JSONArray -> (0 until v.length()).map { v.opt(it) }
            else -> v
        }
        return out
    }

    private fun objects(a: JSONArray?): List<JSONObject> = a?.let { (0 until it.length()).mapNotNull(it::optJSONObject) }.orEmpty()

    private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).mapNotNull { i -> it.opt(i) as? String } }.orEmpty()

    private fun entries(a: JSONArray?): List<BookmarkFile.Entry> = objects(a).mapNotNull { e ->
        val url = e.optString("url").ifEmpty { return@mapNotNull null }
        BookmarkFile.Entry(e.optString("title"), url, e.optLong("added").takeIf { it > 0 }?.coerceAtMost(System.currentTimeMillis()))
    }

    private fun base64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun unbase64(s: String): ByteArray = runCatching { Base64.decode(s, Base64.DEFAULT) }.getOrElse { throw NotSettings() }

    companion object {
        const val REQUEST_FILE = 43
        private const val SETTINGS = "settings"
    }
}
