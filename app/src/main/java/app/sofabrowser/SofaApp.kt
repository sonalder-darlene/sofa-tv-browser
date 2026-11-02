package app.sofabrowser

import android.app.Application
import app.sofabrowser.dns.SecureDns

/** Applies Custom DNS before any screen or service opens a connection. */
class SofaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Device DNS (the default) costs nothing here: WebView isn't touched unless a DNS is chosen.
        SecureDns.apply(this)
    }
}
