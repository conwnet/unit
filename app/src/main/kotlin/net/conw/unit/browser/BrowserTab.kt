package net.conw.unit.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.page.PageConsole

/** Restored tabs create their WebView only when selected. Live tabs keep their navigation stack. */
internal class BrowserTab(val activity: BrowserActivity, var url: String, var title: String) {
    var web: WebView? = null
        private set

    var favicon: Bitmap? = null
        private set

    var scale = activity.resources.displayMetrics.density
        private set

    var initialScale = scale
        private set

    private var scaleCaptured = false
    private var navigation = 0
    private var readerEnabled = false
    private var readerAttempted = -1
    private var mobileAgent: String? = null
    private var desktopAgent = ""
    val console = PageConsole()
    @Volatile private var nonGetNavigation = ""
    val isHome
        get() = url == UrlUtils.HOME

    @SuppressLint(
        "SetJavaScriptEnabled",
        "ClickableViewAccessibility",
        "MissingOnRenderProcessGone",
    )
    fun create(): WebView {
        web?.let {
            return it
        }
        val view = object : WebView(activity) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                // Keep WebView media active while PlayOn is enabled.
                if (visibility != View.VISIBLE && activity.preferences.playOn) return
                super.onWindowVisibilityChanged(visibility)
            }
        }
        web = view
        view.id = View.generateViewId()
        view.setBackgroundColor(activity.getColor(R.color.background))
        // Observe page touches; WebView keeps handling its own click and accessibility events.
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) activity.pageTouched()
            false
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = true
            mobileAgent = userAgentString
            val version =
                Regex("Chrome/([0-9.]+)").find(userAgentString)?.groupValues?.get(1) ?: "116.0.0.0"
            desktopAgent =
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Safari/537.36"
        }
        applyUserAgent()
        applyTextSize()
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.setDownloadListener { next, agent, disposition, mime, _ ->
            if (next == nonGetNavigation) activity.message(R.string.unsupported_download)
            else activity.downloads.request(next, agent, disposition, mime)
        }
        view.setOnLongClickListener { showContextMenu(view) }
        view.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    activity.homePage.response(request)?.let {
                        return it
                    }
                    if (request.isForMainFrame)
                        nonGetNavigation =
                            if (request.method == "GET") "" else request.url.toString()
                    return null
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (activity.homePage.navigation(view, request)) return true
                    val next = request.url.toString()
                    if (UrlUtils.isWeb(next)) {
                        if (request.isForMainFrame) applyUserAgent()
                        return false
                    }
                    if (next == UrlUtils.HOME) return false
                    if (request.isForMainFrame) activity.requests.external(next)
                    return true
                }

                override fun onPageStarted(view: WebView, next: String, favicon: Bitmap?) {
                    activity.pdf.cancel(view)
                    navigation++
                    readerEnabled = activity.reader.enabled
                    applyTextSize()
                    console.clear()
                    activity.toolbar.pageStarted(this@BrowserTab)
                    if (activity.current() === this@BrowserTab) {
                        activity.dismissPopups()
                    }
                    url = next
                    title = if (isHome) activity.getString(R.string.home) else UrlUtils.host(next)
                    this@BrowserTab.favicon = favicon
                    scaleCaptured = false
                    activity.pageChanged(this@BrowserTab)
                }

                override fun onScaleChanged(view: WebView, old: Float, next: Float) {
                    scale = next
                    if (!scaleCaptured) initialScale = next
                    activity.zoom.scaleChanged(this@BrowserTab, next)
                }

                override fun onPageFinished(view: WebView, next: String) {
                    url = next
                    if (!scaleCaptured) {
                        initialScale = scale
                        scaleCaptured = true
                    }
                    recordHistory()
                    if (isHome) activity.homePage.update(view)
                    activity.pageChanged(this@BrowserTab)
                    if (!isHome && readerEnabled && readerAttempted != navigation) {
                        readerAttempted = navigation
                        activity.reader.apply(this@BrowserTab)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView, next: String, reload: Boolean) {
                    url = next
                    recordHistory()
                    activity.pageChanged(this@BrowserTab)
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: SslError,
                ) {
                    handler.cancel()
                    if (activity.current() == this@BrowserTab)
                        activity.message(R.string.invalid_certificate)
                }

                override fun onReceivedHttpAuthRequest(
                    view: WebView,
                    handler: HttpAuthHandler,
                    host: String,
                    realm: String,
                ) {
                    val credentials = activity.proxy.credentials(host)
                    if (
                        credentials != null &&
                            handler.useHttpAuthUsernamePassword() &&
                            !host.equals(url.toUri().host, true)
                    ) {
                        handler.proceed(credentials.first, credentials.second)
                    } else {
                        handler.cancel()
                        if (credentials != null) activity.message(R.string.proxy_auth_error)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame && activity.current() == this@BrowserTab)
                        activity.message(R.string.page_load_failed)
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    activity.pdf.cancel(view)
                    activity.toolbar.pageStarted(this@BrowserTab)
                    if (activity.current() === this@BrowserTab) {
                        activity.dismissPopups()
                    }
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                    web = null
                    if (activity.current() == this@BrowserTab) {
                        activity.showCurrent()
                        activity.message(R.string.page_restarted)
                    }
                    return true
                }
            }
        view.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    console.record(message)
                    return true
                }

                override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                    favicon = icon
                    if (activity.current() === this@BrowserTab) activity.toolbar.render()
                }

                override fun onProgressChanged(view: WebView, progress: Int) {
                    if (activity.current() == this@BrowserTab) activity.progress(progress)
                }

                override fun onReceivedTitle(view: WebView, value: String?) {
                    title = if (isHome) activity.getString(R.string.home) else value.orEmpty()
                    recordHistory()
                    activity.pageChanged(this@BrowserTab)
                }

                override fun onCreateWindow(
                    view: WebView,
                    dialog: Boolean,
                    userGesture: Boolean,
                    message: Message,
                ): Boolean {
                    if (!userGesture) return false
                    val tab = activity.newTab(UrlUtils.HOME, false)
                    (message.obj as WebView.WebViewTransport).webView = tab.create()
                    message.sendToTarget()
                    activity.select(activity.tabs.indexOf(tab))
                    return true
                }

                override fun onCloseWindow(view: WebView) {
                    activity.closeTab(activity.tabs.indexOf(this@BrowserTab))
                }

                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    activity.requests.chooseFiles(callback, params)
                    return true
                }

                override fun onPermissionRequest(request: PermissionRequest) {
                    activity.sitePermissions.media(request)
                }

                override fun onPermissionRequestCanceled(request: PermissionRequest) {
                    activity.sitePermissions.canceled(request)
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String,
                    callback: GeolocationPermissions.Callback,
                ) {
                    activity.sitePermissions.location(origin, callback)
                }

                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    activity.playback.show(view, callback)
                }

                override fun onHideCustomView() {
                    activity.playback.hide()
                }
            }
        return view
    }

    private fun showContextMenu(view: WebView): Boolean {
        val hit = view.hitTestResult
        val target = hit.extra?.takeIf { it.isNotBlank() } ?: return false
        when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                if (!UrlUtils.isWeb(target)) return false
                activity.dialogs.pageContext(target, null)
            }
            WebView.HitTestResult.IMAGE_TYPE -> activity.dialogs.pageContext(null, target)
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val version = navigation
                val response =
                    Handler(Looper.getMainLooper()) { message ->
                        if (isCurrentDocument(view, version)) {
                            // WebView returns the anchor's href and image's src separately.
                            activity.dialogs.pageContext(
                                message.data.getString("url")?.takeIf(UrlUtils::isWeb),
                                message.data.getString("src")?.takeIf { it.isNotBlank() } ?: target,
                            )
                        }
                        true
                    }
                view.requestFocusNodeHref(response.obtainMessage())
            }
            else -> return false
        }
        return true
    }

    fun load(next: String) {
        url = next
        val view = create()
        applyUserAgent()
        activity.proxy.sync { ready ->
            if (!isAlive(view) || url != next) return@sync
            if (ready || next == UrlUtils.HOME) view.loadUrl(next)
            else
                activity.message(
                    activity.proxy.error ?: activity.getString(R.string.proxy_apply_error)
                )
        }
        activity.pageChanged(this)
    }

    fun ensureLoaded() {
        if (web == null) load(url)
    }

    fun reload() {
        web?.let { view ->
            applyUserAgent()
            activity.proxy.sync { ready ->
                if (!isAlive(view)) return@sync
                if (ready) view.reload()
                else
                    activity.message(
                        activity.proxy.error ?: activity.getString(R.string.proxy_apply_error)
                    )
            }
        }
    }

    fun allowPageZoom(script: String, ready: () -> Unit) {
        val view = web ?: return
        val pageUrl = url
        val version = navigation
        fun active() = isCurrentDocument(view, version) && url == pageUrl
        view.evaluateJavascript(script) {
            if (!active()) return@evaluateJavascript
            view.postVisualStateCallback(
                0,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (active()) {
                            view.invalidate()
                            ready()
                        }
                    }
                },
            )
        }
    }

    /** Drop asynchronous feature results after navigation, tab selection or activity teardown. */
    fun evaluate(script: String, result: (String) -> Unit) {
        val view = web ?: return
        val version = navigation
        view.evaluateJavascript(script) { value ->
            if (isCurrentDocument(view, version)) result(value)
        }
    }

    private fun isCurrentDocument(view: WebView, version: Int): Boolean =
        isAlive(view) && navigation == version && activity.current() === this

    private fun isAlive(view: WebView): Boolean =
        web === view && !activity.isFinishing && !activity.isDestroyed

    private fun recordHistory() {
        if (!activity.isIncognito) activity.store.savePage(false, url, title)
    }

    fun back() {
        applyUserAgent()
        web?.goBack()
    }

    fun forward() {
        applyUserAgent()
        web?.goForward()
    }

    private fun applyUserAgent() {
        val view = web ?: return
        val mobile = mobileAgent ?: return
        val agent =
            activity.preferences.userAgent.ifEmpty {
                if (activity.preferences.desktop) desktopAgent else mobile
            }
        if (view.settings.userAgentString != agent) view.settings.userAgentString = agent
    }

    private fun applyTextSize() {
        web?.settings?.textZoom = activity.preferences.textSize
    }

    fun applyTheme() {
        val view = web ?: return
        view.setBackgroundColor(activity.getColor(R.color.background))
        view.dispatchConfigurationChanged(activity.resources.configuration)
        if (isHome) activity.homePage.update(view)
        else if (readerEnabled && readerAttempted == navigation) activity.reader.updateTheme(view)
    }

    @SuppressLint("MissingOnRenderProcessGone") // This client is detached immediately by destroy().
    fun destroy() {
        web?.let { view ->
            activity.pdf.cancel(view)
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webChromeClient = null
            view.webViewClient = WebViewClient()
            view.destroy()
        }
        web = null
        console.changed = null
        console.clear()
    }
}
