package net.conw.unit.browser

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.ByteArrayInputStream
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import org.json.JSONArray
import org.json.JSONObject

/** Offline content served at an internal HTTPS origin, using the tab's existing WebView. */
internal class HomePage(private val activity: BrowserActivity) {
    private val customHtml: String?
        get() =
            if (activity.isIncognito) null
            else activity.preferences.homePage.takeIf(::isHtml)
    private val template by lazy {
        activity.assets.open("home/index.html").bufferedReader().use { it.readText() }
    }
    private val headers =
        mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Content-Security-Policy" to
                "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
        )

    fun response(request: WebResourceRequest): WebResourceResponse? {
        if (!internal(request.url)) return null
        if (request.method != "GET") return error(405, "Method Not Allowed")
        val path = request.url.path
        if (path == "/") {
            if (!request.isForMainFrame) return error(403, "Forbidden")
            val custom = customHtml
            val html = custom ?: template.replace("__HOME_DATA__", scriptData(state().toString()))
            return WebResourceResponse(
                "text/html",
                "UTF-8",
                200,
                "OK",
                if (custom == null) headers else headers - "Content-Security-Policy",
                ByteArrayInputStream(html.toByteArray()),
            )
        }
        val resource =
            when (path) {
                "/home.css" -> "text/css" to "home/home.css"
                "/home.js" -> "application/javascript" to "home/home.js"
                "/logo.svg" -> "image/svg+xml" to "home/logo.svg"
                else -> return error(404, "Not Found")
            }
        return WebResourceResponse(
            resource.first,
            "UTF-8",
            200,
            "OK",
            headers,
            activity.assets.open(resource.second),
        )
    }

    /** Only the top-level homepage may issue these navigation commands. */
    fun navigation(view: WebView, request: WebResourceRequest): Boolean {
        if (!internal(request.url)) return false
        if (request.url.toString() == URL && request.isForMainFrame) return false
        if (request.isForMainFrame && view.url == URL) {
            when (request.url.path) {
                "/open" -> activity.navigate(request.url.getQueryParameter("q").orEmpty())
                "/bookmarks" -> activity.dialogs.pages(true)
            }
        }
        return true
    }

    fun update(view: WebView) {
        if (view.url == URL)
            view.evaluateJavascript(
                "if (location.href === '$URL' && window.UnitHome) window.UnitHome.update(${scriptData(state().toString())});",
                null,
            )
    }

    private fun state(): JSONObject {
        val pages = activity.store.pages(true).filter { UrlUtils.isWeb(it.url) }
        val bookmarks = JSONArray()
        pages.take(8).forEach {
            bookmarks.put(
                JSONObject()
                    .put("title", it.title)
                    .put("url", it.url)
                    .put("host", UrlUtils.host(it.url))
            )
        }
        val colors = JSONObject()
        mapOf(
                "background" to R.color.background,
                "surface" to R.color.surface,
                "surface-variant" to R.color.surface_variant,
                "text" to R.color.text,
                "muted" to R.color.muted,
                "accent" to R.color.accent,
                "divider" to R.color.divider,
            )
            .forEach { (name, resource) ->
                colors.put(name, "#%06x".format(activity.getColor(resource) and 0xffffff))
            }
        val strings = JSONObject()
        mapOf(
                "home" to R.string.home,
                "incognito" to R.string.incognito,
                "privateNote" to R.string.home_private_note,
                "addressHint" to R.string.address_hint,
                "go" to R.string.go,
                "bookmarks" to R.string.bookmarks,
                "viewAll" to R.string.view_all,
                "bookmarksHint" to R.string.home_bookmarks_hint,
            )
            .forEach { (name, resource) -> strings.put(name, activity.getString(resource)) }
        return JSONObject()
            .put("language", activity.resources.configuration.locales[0].toLanguageTag())
            .put("strings", strings)
            .put("incognito", activity.isIncognito)
            .put("colors", colors)
            .put("bookmarks", bookmarks)
            .put("count", pages.size)
    }

    private fun error(code: Int, reason: String) =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            code,
            reason,
            headers,
            ByteArrayInputStream(reason.toByteArray()),
        )

    companion object {
        const val URL = "https://unit.invalid/"

        fun isHtml(value: String) = value.trimStart().startsWith("<")

        fun internal(uri: Uri) =
            uri.scheme == "https" && uri.host == "unit.invalid" && uri.port == -1

        // JSON is inserted in an inert script element; markup characters must remain data.
        fun scriptData(json: String): String =
            json
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("&", "\\u0026")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029")
    }
}
