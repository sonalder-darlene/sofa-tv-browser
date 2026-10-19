package app.sofabrowser.update

import android.app.Activity
import android.content.Context

/** Google Play keeps this build up to date, and its policy forbids any other way, so there is no updater here. */
object Updater {
    fun offered(context: Context) = false
    fun checkOnLaunch(activity: Activity, canShowNow: () -> Boolean) = Unit
    fun showPending(activity: Activity) = Unit
    fun checkNow(activity: Activity) = Unit
}
