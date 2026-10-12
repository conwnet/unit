package net.conw.unit.download

import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import net.conw.unit.BrowserActivity
import net.conw.unit.browser.UrlUtils
import net.conw.unit.proxy.ProxySettings

/** Request credentials are transferred to the service in memory and never written to SQLite. */
internal data class DownloadSession(
    val proxy: ProxySettings,
    val headers: DownloadRequest.Headers = DownloadRequest.Headers(),
    val video: Boolean = false,
) {
    fun applyTo(intent: Intent): Intent =
        intent
            .putExtra(
                "proxy",
                Bundle().apply {
                    putBoolean("enabled", proxy.enabled)
                    putString("type", proxy.type.name)
                    putString("host", proxy.host)
                    putInt("port", proxy.port)
                    putString("username", proxy.username)
                    putString("password", proxy.password)
                    putStringArrayList("bypass", ArrayList(proxy.bypass))
                },
            )
            .putExtra("agent", headers.agent)
            .putExtra("cookie", headers.cookie)
            .putExtra("referer", headers.referer)
            .putExtra("language", headers.language)
            .putExtra("video", video)

    companion object {
        fun capture(
            activity: BrowserActivity,
            url: String,
            userAgent: String?,
            video: Boolean = false,
        ): DownloadSession {
            val referer =
                activity.currentUrl().takeIf {
                    UrlUtils.isWeb(it) && UrlUtils.origin(it) == UrlUtils.origin(url)
                }
            return DownloadSession(
                activity.proxy.settings(),
                DownloadRequest.Headers(
                    userAgent,
                    CookieManager.getInstance().getCookie(url),
                    referer,
                    activity.languages.header(),
                ),
                video,
            )
        }

        fun from(intent: Intent): DownloadSession? {
            val data = intent.getBundleExtra("proxy") ?: return null
            return DownloadSession(
                ProxySettings(
                    data.getBoolean("enabled"),
                    ProxySettings.Type.valueOf(requireNotNull(data.getString("type"))),
                    requireNotNull(data.getString("host")),
                    data.getInt("port"),
                    requireNotNull(data.getString("username")),
                    requireNotNull(data.getString("password")),
                    requireNotNull(data.getStringArrayList("bypass")),
                ),
                DownloadRequest.Headers(
                    intent.getStringExtra("agent"),
                    intent.getStringExtra("cookie"),
                    intent.getStringExtra("referer"),
                    intent.getStringExtra("language"),
                ),
                intent.getBooleanExtra("video", false),
            )
        }
    }
}
