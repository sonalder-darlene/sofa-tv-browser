package app.sofabrowser.downloads

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.sofabrowser.R
import app.sofabrowser.ui.DownloadsActivity

/**
 * Keeps downloads running while Sofa is in the background, with a progress notification. It stops itself as
 * soon as nothing is downloading. On Android 13+ the notification only shows if notifications are allowed for
 * Sofa; the service runs either way.
 */
class DownloadService : Service() {

    private val listener: () -> Unit = { update() }
    private lateinit var manager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        DownloadEngine.serviceRunning = true
        manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.downloads), NotificationManager.IMPORTANCE_LOW))
        }
        DownloadEngine.init(this)
        DownloadEngine.addListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification()
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        update()
        return START_NOT_STICKY
    }

    /** Android 15+ caps data-sync services; pause cleanly so the downloads can be resumed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        DownloadEngine.pauseAll()
        stopSelf()
    }

    private fun update() {
        if (DownloadEngine.activeCount == 0) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (canNotify) manager.notify(ID, notification())
    }

    private val canNotify get() =
        Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notification(): Notification {
        val active = DownloadEngine.all.filter { it.active }
        val total = active.sumOf { maxOf(it.total, 0) }
        val done = active.sumOf { it.done }
        val speed = active.sumOf { it.speed }
        val known = active.isNotEmpty() && active.all { it.total > 0 }
        val title = resources.getQuantityString(R.plurals.downloading_files, active.size, active.size)
        val text = listOfNotNull(
            if (known) "${(done * 100 / maxOf(total, 1))}%" else Http.formatBytes(done),
            if (speed > 0) Http.formatBytes(speed.toLong()) + "/s" else null,
        ).joinToString(" · ")
        val open = PendingIntent.getActivity(this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setProgress(100, if (known) (done * 100 / maxOf(total, 1)).toInt() else 0, !known)
            .build()
    }

    override fun onDestroy() {
        DownloadEngine.removeListener(listener)
        DownloadEngine.serviceRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 7
    }
}
