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
import app.sofabrowser.Prefs

/**
 * Hands a video to another app. The players come from Android itself (whatever is installed and opens web video
 * links), so Sofa names none: the first time, Android's chooser asks, and [PlayerChosen] remembers the answer.
 * Extras are best effort, by extra name: "title" and "position" (int ms) are read by mpv-android (its intent
 * spec), VLC (Android Player Intents wiki) and MX Player (its intent API), which also reads "headers"; VLC ignores
 * headers.
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

    /** The remembered player, or null when [choice] isn't a component or that app was uninstalled. */
    fun installed(context: Context, choice: String): ComponentName? {
        val component = ComponentName.unflattenFromString(choice) ?: return null
        return component.takeIf { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess }
    }

    /**
     * [launch] for [player], with the stream's own type or else the generic video type, whichever the player
     * declares (Android requires an explicit intent to match the target's filters). Null when it takes neither.
     */
    private fun intentFor(context: Context, launch: PlayerRequest.Launch, player: ComponentName): Intent? =
        listOf(launch.mime, "video/*").distinct().map { intent(launch, it) }
            .firstOrNull { i -> handlers(context, i).any { it.activityInfo.packageName == player.packageName && it.activityInfo.name == player.className } }
            ?.setComponent(player)

    fun intent(launch: PlayerRequest.Launch, type: String = launch.mime): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(Uri.parse(launch.uri), type)
        launch.title?.let { putExtra("title", it); putExtra(Intent.EXTRA_TITLE, it) }
        launch.positionMs?.let { putExtra("position", it) }
        if (launch.headers.isNotEmpty()) putExtra("headers", launch.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
    }

    /**
     * Starts [launch] in [player], or in Android's chooser (which tells [PlayerChosen] the app picked) when there is
     * none or it can't take this stream. Returns false when no app can open it.
     */
    fun open(context: Context, launch: PlayerRequest.Launch, player: ComponentName?, chooserTitle: String): Boolean {
        val intent = intent(launch)
        val direct = player?.let { intentFor(context, launch, it) }
        return try {
            if (direct != null) context.startActivity(direct)
            else if (handlers(context, intent).isNotEmpty()) {
                // Mutable: the system adds the chosen component to it.
                val chosen = PendingIntent.getBroadcast(context, 0, Intent(context, PlayerChosen::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                context.startActivity(Intent.createChooser(intent, chooserTitle, chosen.intentSender))
            }
            else return false
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}

/** Remembers the player picked in Android's chooser, so "Play in…" goes straight to it next time. */
class PlayerChosen : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val component = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT)
        if (component != null) Prefs(context).videoPlayer = component.flattenToString()
    }
}
