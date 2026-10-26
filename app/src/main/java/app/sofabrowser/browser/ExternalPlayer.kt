package app.sofabrowser.browser

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import app.sofabrowser.Prefs

/**
 * Hands a video to another app. The players come from Android itself (whatever is installed and opens web video
 * links), so Sofa names none: the first time, Android's chooser asks, and [PlayerChosen] remembers the answer.
 * Extras are best effort, by extra name: "title" and "position" (ms) are read by mpv-android (its intent
 * spec), VLC (Android Player Intents wiki) and MX Player (its intent API), which also reads "headers"; VLC ignores
 * headers, and so does Just Player (its PlayerActivity). The position is an Int for all but VLC, which takes only a
 * Long ([PlayerRequest.positionIsLong]). Page subtitles go as "subs", "subs.name" and "subs.enable" (MX Player's
 * API, also read by Just Player and mpv-android) and as VLC's "subtitles_location".
 */
object ExternalPlayer {

    /**
     * Apps that open [intent], except Sofa and fallbacks: Android TV answers media links with a stub at a negative
     * priority that only says "You don't have an app that can do this".
     */
    private fun handlers(context: Context, intent: Intent): List<ResolveInfo> {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val found = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return found.filter { it.activityInfo.packageName != context.packageName && it.priority >= 0 }
    }

    /**
     * The installed players, by name, for Settings: the apps that open a web video or an HLS stream, as the chooser
     * would list them.
     */
    fun players(context: Context): List<Pair<ComponentName, String>> {
        val pm = context.packageManager
        val probe = Uri.parse("https://example.com/video")
        return listOf("video/*", PlayerRequest.HLS).flatMap { type -> handlers(context, Intent(Intent.ACTION_VIEW).setDataAndType(probe, type)) }
            .distinctBy { it.activityInfo.packageName to it.activityInfo.name }
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) to it.loadLabel(pm).toString() }
            .sortedBy { it.second.lowercase() }
    }

    /** The name of the app [choice] names, or null when it isn't installed. */
    fun label(context: Context, choice: String): String? = installed(context, choice)?.let { c ->
        runCatching { context.packageManager.getActivityInfo(c, 0).loadLabel(context.packageManager).toString() }.getOrNull()
    }

    /** The remembered player, or null when [choice] isn't a component or that app was uninstalled. */
    fun installed(context: Context, choice: String): ComponentName? {
        val component = ComponentName.unflattenFromString(choice) ?: return null
        return component.takeIf { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess }
    }

    /**
     * [launch] for [player], under the first of the stream's types ([PlayerRequest.types]) the player declares
     * (Android requires an explicit intent to match the target's filters). Null when it takes none.
     */
    private fun intentFor(context: Context, launch: PlayerRequest.Launch, player: ComponentName): Intent? =
        PlayerRequest.types(launch.mime, player.packageName).map { intent(launch, it, player.packageName) }
            .firstOrNull { i -> handlers(context, i).any { it.activityInfo.packageName == player.packageName && it.activityInfo.name == player.className } }
            ?.setComponent(player)

    /** For the chooser: [launch] under each of its types some player takes, in [PlayerRequest.types] order. */
    private fun chooserIntents(context: Context, launch: PlayerRequest.Launch): List<Intent> =
        PlayerRequest.types(launch.mime, null).map { intent(launch, it) }.filter { handlers(context, it).isNotEmpty() }

    private fun intent(launch: PlayerRequest.Launch, type: String, packageName: String? = null): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(launch.uri), type)
        launch.title?.let { putExtra("title", it); putExtra(Intent.EXTRA_TITLE, it) }
        launch.positionMs?.let { ms ->
            if (PlayerRequest.positionIsLong(packageName)) putExtra("position", ms.toLong()) else putExtra("position", ms)
        }
        if (launch.headers.isNotEmpty()) putExtra("headers", launch.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        if (launch.subtitles.isNotEmpty()) {
            val uris = launch.subtitles.map { Uri.parse(it.url) }
            putExtra("subs", uris.toTypedArray<Parcelable>())
            putExtra("subs.name", launch.subtitles.map { it.label.ifEmpty { it.language } }.toTypedArray())
            putExtra("subs.enable", arrayOf<Parcelable>(uris.first()))
            putExtra("subtitles_location", launch.subtitles.first().url)
        }
        // Sofa's own copy of a playlist (StreamCopyProvider): readable by the player it is given to, only.
        if (launch.uri.startsWith("content://")) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /**
     * Starts [launch] in [player], or in Android's chooser (which tells [PlayerChosen] the app picked) when there is
     * none or it can't take this stream. Returns false when no app can open it.
     */
    fun open(context: Context, launch: PlayerRequest.Launch, player: ComponentName?, chooserTitle: String): Boolean {
        val direct = player?.let { intentFor(context, launch, it) }
        return try {
            if (direct != null) context.startActivity(direct)
            else {
                val intents = chooserIntents(context, launch)
                if (intents.isEmpty()) return false
                // Mutable: the system adds the chosen component to it.
                val chosen = PendingIntent.getBroadcast(context, 0, Intent(context, PlayerChosen::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                val chooser = Intent.createChooser(intents.first(), chooserTitle, chosen.intentSender)
                // Players that take only the stream's own type (HLS) are listed too; each app is listed once, with the
                // first intent it takes.
                if (intents.size > 1) chooser.putExtra(Intent.EXTRA_ALTERNATE_INTENTS, intents.drop(1).toTypedArray())
                // VLC reads the position only as a Long, whichever intent it gets.
                launch.positionMs?.let { ms ->
                    chooser.putExtra(Intent.EXTRA_REPLACEMENT_EXTRAS, Bundle().apply {
                        putBundle(PlayerRequest.VLC, Bundle().apply { putLong("position", ms.toLong()) })
                    })
                }
                context.startActivity(chooser)
            }
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/**
 * Remembers the player picked in Android's chooser, so "Play in…" goes straight to it next time; not when the user
 * asked in Settings to choose every time.
 */
class PlayerChosen : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val component = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        val prefs = Prefs(context)
        if (component != null && prefs.videoPlayer != Prefs.ASK_EVERY_TIME) prefs.videoPlayer = component.flattenToString()
    }
}
