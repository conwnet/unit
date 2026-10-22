package net.conw.unit

import android.app.Application
import android.webkit.WebView
import net.conw.unit.browser.BrowserLanguages
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.data.BrowserStore
import net.conw.unit.privacy.PrivateSession
import net.conw.unit.proxy.BrowserProxy

class UnitApplication : Application() {
    internal val proxy by lazy { BrowserProxy(this) }

    override fun onCreate() {
        super.onCreate()
        BrowserStore(this).use { BrowserLanguages.sync(this, BrowserPreferences(it)) }
        if (getProcessName().endsWith(":incognito")) {
            // Must happen before any android.webkit API in this process.
            PrivateSession.prepare(this)
            WebView.setDataDirectorySuffix("incognito")
        } else {
            PrivateSession.cleanIfStopped(this)
        }
        // PDF snapshots must include content outside the viewport.
        WebView.enableSlowWholeDocumentDraw()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}
