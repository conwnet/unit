package net.conw.unit.browser

import android.net.Uri
import androidx.core.net.toUri

internal object UrlUtils {
    const val HOME = HomePage.URL

    fun isWeb(url: String?): Boolean =
        url?.let {
            val uri = it.toUri()
            (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
                !uri.host.isNullOrBlank() &&
                !uri.host.equals("unit.invalid", true)
        } ?: false

    fun resolve(input: String, engine: String): String {
        val value = input.trim()
        if (value.isEmpty() || value == HOME) return HOME
        if (isWeb(value)) return value
        // Typed script and local-file schemes must never become executable URLs.
        if (
            !value.contains(' ') &&
                !value.contains('\n') &&
                value.matches(
                    Regex(
                        "(?i)(localhost|\\[[0-9a-f:]+\\]|[a-z0-9_-]+(?:\\.[a-z0-9_-]+)+)(:\\d+)?([/?#].*)?"
                    )
                )
        ) {
            val scheme =
                if (value.startsWith("localhost") || value.startsWith("127.")) "http://"
                else "https://"
            return scheme + value
        }
        val base =
            when (engine) {
                "Google" -> "https://www.google.com/search?q="
                "Baidu" -> "https://www.baidu.com/s?wd="
                else -> "https://www.bing.com/search?q="
            }
        return base + Uri.encode(value)
    }

    fun host(url: String?): String =
        if (url == HOME) "Home" else url.orEmpty().toUri().host.orEmpty()

    fun origin(url: String?): String =
        url.orEmpty().toUri().let { "${it.scheme}://${it.authority}" }
}
