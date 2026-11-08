package app.sofabrowser

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.sofabrowser.dns.SecureDns
import app.sofabrowser.ui.PinGate

/** Applies Custom DNS before any screen or service opens a connection, and locks the parental PIN when Sofa leaves the screen. */
class SofaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Device DNS (the default) costs nothing here: WebView isn't touched unless a DNS is chosen.
        SecureDns.apply(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) { started++; onScreen = true }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                onScreen = started > 0
                // Another app, the TV's home screen or the screen turning off; a theme change rebuilding a screen isn't.
                if (started == 0 && !activity.isChangingConfigurations) PinGate.close()
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    companion object {
        /** A Sofa screen is in sight (read from any thread, e.g. to decide whether a notice can be seen). */
        @Volatile var onScreen = false
            private set
    }
}
