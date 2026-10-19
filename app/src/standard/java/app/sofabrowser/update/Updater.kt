package app.sofabrowser.update

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import app.sofabrowser.BuildConfig
import app.sofabrowser.Prefs
import app.sofabrowser.R
import app.sofabrowser.downloads.Http
import app.sofabrowser.ui.Ui
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Updates from Sofa's GitHub releases, for copies that no app store keeps up to date. It is offered only when no
 * store installed Sofa and this copy carries the release signature (anything else couldn't take a GitHub APK), and
 * it does nothing until the user asks: automatic checks are opt-in, and every install needs Android's confirmation.
 * Google Play builds get a stub instead (src/play).
 */
object Updater {

    /** App stores and update managers: when one of them installed Sofa, it also updates it. */
    private val STORES = setOf(
        "com.android.vending",                                  // Google Play
        "org.fdroid.fdroid", "org.fdroid.basic",                // the F-Droid client, whatever the repository
        "com.looker.droidify", "com.machiav3lli.fdroid",        // Droid-ify, Neo Store
        "dev.imranr.obtainium", "dev.imranr.obtainium.fdroid",  // Obtainium
        "cm.aptoide.pt", "cm.aptoidetv.pt",                     // Aptoide, Aptoide TV
        "com.aurora.store", "app.accrescent.client", "com.amazon.venezia",
    )
    private const val DAY = 24 * 60 * 60 * 1000L
    private const val MAX_APK = 100L * 1024 * 1024

    class Release(val version: String, val notes: String, val apk: Releases.Asset)

    private var offered: Boolean? = null
    /** An update found at launch while a page was open; offered when the home screen next shows. */
    private var pending: Release? = null
    @Volatile private var downloading = false

    fun offered(context: Context): Boolean = offered ?: (installer(context) !in STORES &&
        BuildConfig.RELEASE_CERT_SHA256 in certificates(installedInfo(context))).also { offered = it }

    /** At launch: checks for a new release when the user turned checks on and a day has passed since the last one. */
    fun checkOnLaunch(activity: Activity, canShowNow: () -> Boolean) {
        if (!offered(activity)) return
        if (!downloading) File(activity.cacheDir, "update").deleteRecursively()
        val prefs = Prefs(activity)
        if (!prefs.checkUpdates || System.currentTimeMillis() - prefs.lastUpdateCheck < DAY) return
        thread(name = "update-check") {
            // Offline or rate-limited: try again at the next launch.
            val release = runCatching { fetchLatest() }.getOrNull() ?: return@thread
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (!Releases.isNewer(release.version, BuildConfig.VERSION_NAME) || release.version == prefs.skippedUpdate) return@thread
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (canShowNow()) offer(activity, release, atLaunch = true) else pending = release
            }
        }
    }

    fun showPending(activity: Activity) {
        val release = pending ?: return
        pending = null
        offer(activity, release, atLaunch = true)
    }

    /** Settings' "Check for updates now". */
    fun checkNow(activity: Activity) {
        toast(activity, activity.getString(R.string.update_checking))
        thread(name = "update-check") {
            val result = runCatching { fetchLatest() }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.onSuccess {
                    if (Releases.isNewer(it.version, BuildConfig.VERSION_NAME)) offer(activity, it, atLaunch = false)
                    else toast(activity, activity.getString(R.string.update_none, BuildConfig.VERSION_NAME))
                }.onFailure { toast(activity, activity.getString(R.string.update_check_failed, reason(it))) }
            }
        }
    }

    private fun offer(activity: Activity, release: Release, atLaunch: Boolean) {
        val details = activity.getString(R.string.update_details, Http.formatBytes(release.apk.size.coerceAtLeast(0)))
        Ui.dialog(activity)
            .setTitle(activity.getString(R.string.update_available, release.version))
            .setMessage(if (release.notes.isEmpty()) details else release.notes + "\n\n" + details)
            .setPositiveButton(R.string.update_install) { _, _ -> download(activity, release) }
            .setNegativeButton(R.string.update_later, null)
            .apply {
                if (atLaunch) setNeutralButton(R.string.update_skip) { _, _ -> Prefs(activity).skippedUpdate = release.version }
            }
            .show()
    }

    private fun download(activity: Activity, release: Release) {
        if (downloading) return
        downloading = true
        val cancelled = AtomicBoolean(false)
        val dialog: AlertDialog = Ui.dialog(activity)
            .setTitle(activity.getString(R.string.update_downloading, release.version))
            .setMessage(activity.getString(R.string.percent, 0))
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .setOnCancelListener { cancelled.set(true) }
            .show()
        thread(name = "update-download") {
            val result = runCatching {
                val file = fetchApk(activity, release, cancelled) { done, total ->
                    val text = if (total > 0) activity.getString(R.string.percent, (done * 100 / total).toInt()) else Http.formatBytes(done)
                    activity.runOnUiThread { if (dialog.isShowing) dialog.setMessage(text) }
                }
                try {
                    if (!cancelled.get()) install(activity, file)
                } finally {
                    file.delete()
                }
            }
            downloading = false
            activity.runOnUiThread {
                if (!activity.isDestroyed && dialog.isShowing) dialog.dismiss()
                result.onFailure { if (!cancelled.get()) toast(activity, activity.getString(R.string.update_failed, reason(it))) }
            }
        }
    }

    // Network

    private fun fetchLatest(): Release {
        val conn = URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "Sofa")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            val list = (0 until (assets?.length() ?: 0)).map { i ->
                val a = assets!!.getJSONObject(i)
                Releases.Asset(a.getString("name"), a.getString("browser_download_url"), a.optLong("size", -1),
                    Releases.sha256(a.optString("digest")))
            }
            val apk = Releases.pickApk(list, version) ?: throw IOException("release $version has no APK")
            val notes = if (json.isNull("body")) "" else Releases.plainNotes(json.optString("body"))
            return Release(version, notes, apk)
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads the release APK into the cache and checks it is complete, intact, newer and signed like this copy. */
    private fun fetchApk(context: Context, release: Release, cancelled: AtomicBoolean, progress: (Long, Long) -> Unit): File {
        val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "sofa-${release.version}.apk")
        val conn = URL(release.apk.url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "Sofa")
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apk.size
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled.get()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        if (done > MAX_APK) throw IOException(context.getString(R.string.update_corrupt))
                        if (done - shown >= 64 * 1024) { shown = done; progress(done, total) }
                    }
                }
            }
            if (release.apk.size > 0 && done != release.apk.size) throw IOException(context.getString(R.string.update_corrupt))
            val expected = release.apk.sha256
            if (expected != null && hex(sha.digest()) != expected) throw IOException(context.getString(R.string.update_corrupt))
            verify(context, file)
            return file
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    // Checks and install

    private fun verify(context: Context, file: File) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, SIGNATURE_FLAGS)
        if (info == null || info.packageName != context.packageName) throw IOException(context.getString(R.string.update_corrupt))
        if (versionCode(info) <= versionCode(installedInfo(context))) throw IOException(context.getString(R.string.update_not_newer))
        // Android refuses an update signed by anyone else anyway; this just says so before the install screen.
        val signers = certificates(info)
        if (signers.isNotEmpty() && BuildConfig.RELEASE_CERT_SHA256 !in signers) throw IOException(context.getString(R.string.update_not_signed))
    }

    /** Hands the APK to Android's installer, which asks the user to confirm; [InstallReceiver] hears the outcome. */
    private fun install(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("sofa.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java)
            // The installer adds the status to this intent, so it has to be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }

    private fun installer(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)
    }.getOrNull()

    // Signatures are compared as a set of fingerprints, so a package with several signers can't slip one past.
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private val SIGNATURE_FLAGS =
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        else PackageManager.GET_SIGNATURES

    private fun installedInfo(context: Context): PackageInfo =
        context.packageManager.getPackageInfo(context.packageName, SIGNATURE_FLAGS)

    /** SHA-256 fingerprints (lowercase hex) of the certificates that signed a package. */
    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signing = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let {
            if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory
        } else null
        return (signing ?: info.signatures).orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun reason(e: Throwable) = e.message ?: e.javaClass.simpleName

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}
