package net.conw.unit

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.app.AlertDialog
import net.conw.unit.browser.BrowserLanguages
import net.conw.unit.browser.BrowserRequests
import net.conw.unit.browser.BrowserTab
import net.conw.unit.browser.BrowserTheme
import net.conw.unit.browser.HomePage
import net.conw.unit.browser.SitePermissions
import net.conw.unit.browser.TabSessions
import net.conw.unit.browser.UrlUtils
import net.conw.unit.browser.page.BrowserPdf
import net.conw.unit.browser.page.BrowserReader
import net.conw.unit.browser.ui.BrowserDialogs
import net.conw.unit.browser.ui.BrowserMenu
import net.conw.unit.browser.ui.BrowserTextSize
import net.conw.unit.browser.ui.BrowserToolbar
import net.conw.unit.browser.ui.BrowserZoom
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.data.BrowserStore
import net.conw.unit.download.DownloadController
import net.conw.unit.media.FullscreenController
import net.conw.unit.media.PlayOnService
import net.conw.unit.media.VideoPlayback
import net.conw.unit.privacy.PrivateSession
import net.conw.unit.proxy.ProxyNotice
import net.conw.unit.ui.Ui

open class BrowserActivity : Activity() {
    internal val tabs = mutableListOf<BrowserTab>()
    internal lateinit var store: BrowserStore
    internal lateinit var preferences: BrowserPreferences
    internal lateinit var dialogs: BrowserDialogs
    internal lateinit var downloads: DownloadController
    internal lateinit var sitePermissions: SitePermissions
    internal lateinit var requests: BrowserRequests
    internal lateinit var fullscreen: FullscreenController
    internal lateinit var toolbar: BrowserToolbar
    internal lateinit var menu: BrowserMenu
    internal lateinit var zoom: BrowserZoom
    internal lateinit var textSize: BrowserTextSize
    internal lateinit var pdf: BrowserPdf
    internal lateinit var tabSessions: TabSessions
    internal lateinit var homePage: HomePage
    internal lateinit var reader: BrowserReader
    internal lateinit var playback: VideoPlayback
    internal lateinit var proxyNotice: ProxyNotice
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var progressBar: ProgressBar
    private var selected = 0
    private val openDialogs = mutableListOf<Dialog>()
    private var ending = false
    private var exiting = false
    private var appliedNightMode = 0
    internal val proxy
        get() = (application as UnitApplication).proxy

    internal open val isIncognito
        get() = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(BrowserLanguages.wrap(BrowserTheme.wrap(base)))
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                ::handleBack,
            )
        appliedNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        store = BrowserStore(this)
        preferences = BrowserPreferences(store)
        BrowserLanguages.sync(this, preferences)
        if (isIncognito) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_browser)
        root = findViewById(R.id.root)
        content = findViewById(R.id.content)
        progressBar = findViewById(R.id.progress)
        val toolbarView = findViewById<View>(R.id.toolbar)
        fullscreen = FullscreenController(this, store, root, content, toolbarView)
        dialogs = BrowserDialogs(this)
        downloads = DownloadController(this)
        sitePermissions = SitePermissions(this)
        requests = BrowserRequests(this)
        homePage = HomePage(this)
        reader = BrowserReader(this)
        playback = VideoPlayback(this, root)
        toolbar = BrowserToolbar(this, toolbarView)
        proxyNotice = ProxyNotice(this, root)
        menu = BrowserMenu(this, root)
        zoom = BrowserZoom(this, root)
        textSize = BrowserTextSize(this, root)
        pdf = BrowserPdf(this)
        tabSessions = TabSessions(this)
        restore()
        syncOrientation()
        // A configuration recreation carries the original VIEW intent; open it only once.
        if (state == null) handleIntent(intent)
        if (tabs.isEmpty() && !ending) tabs.add(BrowserTab(this, startUrl(), getString(R.string.home)))
        showCurrent()
        save()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.dataString
        if (intent.action == Intent.ACTION_VIEW && UrlUtils.isWeb(url))
            newTab(requireNotNull(url), true)
        if (intent.getBooleanExtra("new_tab", false)) newTab(startUrl(), true)
        if (intent.hasExtra("select_tab")) select(intent.getIntExtra("select_tab", 0))
        if (intent.getBooleanExtra("show_downloads", false)) content.post { downloads.show() }
    }

    private fun restore() {
        if (isIncognito) {
            (lastNonConfigurationInstance as? RetainedTabs)?.let { retained ->
                tabs.addAll(retained.pages.map { BrowserTab(this, it.url, it.title) })
                selected = retained.selected
            }
        } else {
            val session = store.session()
            tabs.addAll(session.pages.map { BrowserTab(this, it.url, it.title) })
            selected = session.selected
        }
    }

    override fun onRetainNonConfigurationInstance(): Any? =
        if (isIncognito) {
            // Private tabs remain in memory across configuration changes.
            RetainedTabs(tabs.map { BrowserStore.Page(it.url, it.title) }, selected)
        } else null

    private data class RetainedTabs(val pages: List<BrowserStore.Page>, val selected: Int)

    internal fun startUrl(): String = preferences.startUrl(isIncognito)

    internal fun current(): BrowserTab? = tabs.getOrNull(minOf(selected, tabs.lastIndex))

    internal fun currentUrl() = current()?.url ?: UrlUtils.HOME

    internal fun userAgent(): String? = current()?.web?.settings?.userAgentString

    internal fun syncLanguages() {
        val previousLocales = resources.configuration.locales
        val websiteLanguagesChanged = BrowserLanguages.sync(this, preferences)
        if (resources.configuration.locales != previousLocales) {
            recreate()
            return
        }
        if (!websiteLanguagesChanged) return
        // Attachment lets Chromium update its public locale-derived browser preferences.
        current()
            ?.web
            ?.takeIf { it.parent === content }
            ?.let {
                content.removeView(it)
                content.addView(it, FrameLayout.LayoutParams(-1, -1))
            }
    }

    internal fun newTab(url: String, select: Boolean): BrowserTab {
        val tab =
            BrowserTab(
                this,
                url,
                if (url == UrlUtils.HOME) getString(R.string.home) else UrlUtils.host(url),
            )
        tabs.add(tab)
        if (select) select(tabs.lastIndex)
        else {
            save()
            updateToolbar()
        }
        return tab
    }

    internal fun select(index: Int) {
        if (index !in tabs.indices) return
        playback.hide()
        toolbar.cancelEditing()
        dismissPopups()
        current()?.web?.onPause()
        selected = index
        showCurrent()
        save()
    }

    internal fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        toolbar.cancelEditing()
        dismissPopups()
        tabs.removeAt(index).destroy()
        if (tabs.isEmpty()) {
            if (isIncognito) {
                endPrivate()
                return
            }
            tabs.add(BrowserTab(this, startUrl(), getString(R.string.home)))
        }
        if (index < selected) selected--
        selected = selected.coerceIn(0, tabs.lastIndex)
        showCurrent()
        save()
    }

    internal fun closeAll() {
        toolbar.cancelEditing()
        dismissPopups()
        if (isIncognito) {
            endPrivate()
            return
        }
        destroyAllTabs()
        tabs.add(BrowserTab(this, startUrl(), getString(R.string.home)))
        selected = 0
        showCurrent()
        save()
    }

    internal fun navigate(input: String) {
        toolbar.cancelEditing()
        dismissPopups()
        current()?.load(UrlUtils.resolve(input, preferences.searchEngine))
    }

    internal fun goHome() {
        current()?.load(startUrl())
    }

    internal fun refreshHome() {
        current()?.takeIf { it.isHome }?.web?.let { homePage.update(it) }
    }

    internal fun showCurrent() {
        val tab = current() ?: return
        if (ending) return
        tab.ensureLoaded()
        val desired = tab.web?.also { it.onResume() }
        if (desired != null && content.getChildAt(0) != desired) {
            content.removeAllViews()
            (desired.parent as? ViewGroup)?.removeView(desired)
            content.addView(desired, FrameLayout.LayoutParams(-1, -1))
        }
        refreshHome()
        updateToolbar()
    }

    internal fun togglePlayOn() {
        preferences.playOn = !preferences.playOn
        if (syncPlayOn())
            message(if (preferences.playOn) R.string.play_on_enabled else R.string.play_on_disabled)
        playback.refreshPlayOn()
    }

    private fun syncPlayOn(): Boolean {
        if (preferences.playOn) {
            try {
                startForegroundService(PlayOnService.intent(this))
            } catch (error: RuntimeException) {
                Log.w("PlayOn", "Unable to start playback service", error)
                message(R.string.play_on_failed)
                return false
            }
        } else {
            PlayOnService.stop(this)
        }
        return true
    }

    internal fun pageChanged(tab: BrowserTab) {
        if (ending) return
        if (current() == tab) {
            if (content.getChildAt(0) != tab.web) showCurrent() else updateToolbar()
        }
        save()
    }

    private fun updateToolbar() {
        val tab = current()
        toolbar.render()
        progress(if (tab?.isHome == true) 100 else tab?.web?.progress ?: 100)
    }

    internal fun progress(value: Int) {
        progressBar.progress = value
        progressBar.visibility = if (value < 100) View.VISIBLE else View.GONE
        if (value >= 100) menu.refresh()
    }

    internal fun save() {
        if (!::store.isInitialized || ending) return
        if (isIncognito) {
            store.put("private_count", tabs.size.toString())
            return
        }
        store.session(tabs.map { BrowserStore.Page(it.url, it.title) }, selected)
    }

    internal val selectedIndex
        get() = selected

    internal fun activateTab(incognito: Boolean, index: Int? = null) {
        if (incognito == isIncognito) {
            if (index == null) newTab(startUrl(), true) else select(index)
            return
        }
        save()
        openDialogs.toList().forEach { it.dismiss() }
        startActivity(
            Intent(
                    this,
                    if (incognito) IncognitoActivity::class.java else BrowserActivity::class.java,
                )
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .apply {
                    if (index == null) putExtra("new_tab", true) else putExtra("select_tab", index)
                }
        )
    }

    internal fun endPrivate() {
        ending = true
        store.put("private_count", "0")
        PrivateSession.finish(this)
    }

    internal fun exit() {
        if (exiting) return
        exiting = true
        tabSessions.request(!isIncognito, "exit") { finishBrowsing() }
    }

    internal fun finishBrowsing() {
        save()
        ending = true
        if (isIncognito) store.put("private_count", "0")
        finishAndRemoveTask()
    }

    internal fun destroyAllTabs() {
        tabs.forEach { it.destroy() }
        tabs.clear()
    }

    internal fun dismissPopups() {
        menu.dismiss()
        zoom.dismiss()
        textSize.dismiss()
    }

    internal fun showDialog(dialog: Dialog, onDismiss: () -> Unit = {}) {
        toolbar.cancelEditing()
        dismissPopups()
        fullscreen.hold()
        openDialogs.add(dialog)
        dialog.setOnDismissListener {
            openDialogs.remove(dialog)
            fullscreen.release()
            onDismiss()
        }
        dialog.show()
        dialog.window?.let {
            fullscreen.bars(it)
            if (isIncognito) it.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (dialog is AlertDialog) Ui.styleDialog(dialog)
    }

    internal fun message(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    internal fun message(text: Int) = message(getString(text))

    internal fun pageTouched() {
        proxyNotice.dismiss()
        toolbar.pageTouched()
        if (!toolbar.isEditing && !zoom.isShowing && !textSize.isShowing) {
            menu.dismiss()
            fullscreen.pageTouched()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        requests.activityResult(requestCode, resultCode, data)
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, results)
        requests.permissionResult(code, results)
    }

    // API 33+ uses the platform dispatcher; this fallback serves API 28–32.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Platform fallback for Android 9–12")
    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        if (playback.hide()) return
        if (menu.dismiss() || zoom.dismiss() || textSize.dismiss() || toolbar.cancelEditing())
            return
        if (fullscreen.isFullscreen) {
            fullscreen.set(false, true)
            return
        }
        current()?.web?.let {
            if (it.canGoBack()) {
                current()?.back()
                return
            }
        }
        moveTaskToBack(true)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::fullscreen.isInitialized) fullscreen.touch()
        return super.dispatchTouchEvent(event)
    }

    override fun onResume() {
        super.onResume()
        syncOrientation()
        syncTheme()
        syncLanguages()
        proxy.sync()
        current()?.web?.onResume()
        syncPlayOn()
        playback.refreshPlayOn()
        if (!isIncognito) PrivateSession.cleanIfStopped(this)
        refreshHome()
    }

    override fun onPause() {
        save()
        if (!preferences.playOn) current()?.web?.onPause()
        if (!ending) CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        if (!preferences.playOn) {
            playback.hide()
        }
        proxyNotice.dismiss()
        dismissPopups()
        downloads.stop()
        super.onStop()
    }

    override fun onWindowFocusChanged(focused: Boolean) {
        super.onWindowFocusChanged(focused)
        if (focused && ::fullscreen.isInitialized) fullscreen.bars()
    }

    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        syncLanguages()
        syncTheme()
        dismissPopups()
    }

    internal fun syncOrientation() {
        val orientation = preferences.orientation
        if (requestedOrientation != orientation) requestedOrientation = orientation
    }

    internal fun syncTheme() {
        val resourceNightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val requestedNightMode = BrowserTheme.apply(this, preferences.theme)
        // Configuration changes can reset resources while the saved theme remains unchanged.
        if (appliedNightMode != requestedNightMode || resourceNightMode != requestedNightMode) {
            appliedNightMode = requestedNightMode
            theme.applyStyle(R.style.Theme_Unit, true)
            @Suppress("DEPRECATION")
            window.statusBarColor = getColor(R.color.background)
            @Suppress("DEPRECATION")
            window.navigationBarColor = getColor(R.color.surface)
            root.setBackgroundColor(getColor(R.color.background))
            toolbar.applyTheme()
            progressBar.progressTintList =
                android.content.res.ColorStateList.valueOf(getColor(R.color.accent))
            openDialogs.toList().forEach { it.dismiss() }
            tabs.forEach { it.applyTheme() }
            updateToolbar()
            dismissPopups()
        }
        fullscreen.refresh()
    }

    override fun onDestroy() {
        stopService(PlayOnService.intent(this))
        pdf.cancel()
        playback.hide()
        proxyNotice.dismiss()
        save()
        ending = true
        dismissPopups()
        toolbar.cancelEditing()
        openDialogs.toList().forEach { it.dismiss() }
        requests.destroy()
        sitePermissions.destroy()
        downloads.destroy()
        tabSessions.destroy()
        fullscreen.destroy()
        destroyAllTabs()
        store.close()
        super.onDestroy()
    }
}
