package net.conw.unit.download

import android.content.Context
import java.io.IOException
import net.conw.unit.R
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

/** Opens the final download response. The caller owns the response and client. */
internal object DownloadRequest {
    fun open(
        context: Context,
        client: Call.Factory,
        address: String,
        headers: Headers,
        onCall: (Call) -> Unit,
    ): Response {
        val original = address.toHttpUrl()
        var url = original
        var redirects = 0
        val cookies = mutableListOf<Cookie>()
        while (true) {
            val request = Request.Builder().url(url)
            headers.agent?.let { request.header("User-Agent", it) }
            headers.language?.let { request.header("Accept-Language", it) }
            val inherited = if (sameOrigin(url, original)) headers.cookie else null
            val selectedCookies = cookies.filter {
                it.matches(url) && it.expiresAt > System.currentTimeMillis()
            }
            val names = selectedCookies.map { it.name }.toSet()
            val snapshot =
                inherited
                    ?.split(';')
                    ?.map { it.trim() }
                    ?.filter { it.substringBefore('=') !in names }
                    .orEmpty()
            val cookie =
                (snapshot + selectedCookies.map { "${it.name}=${it.value}" }).joinToString("; ")
            if (cookie.isNotEmpty()) request.header("Cookie", cookie)
            headers.referer
                ?.takeIf { sameOrigin(url, it.toHttpUrl()) }
                ?.let {
                    request.header("Referer", it)
                }
            val call = client.newCall(request.build())
            onCall(call)
            val response = call.execute()
            if (response.code !in listOf(301, 302, 303, 307, 308)) {
                if (response.isSuccessful) return response
                response.close()
                throw IOException(
                    if (response.code == 407) context.getString(R.string.proxy_auth_error)
                    else "HTTP ${response.code}"
                )
            }
            response.use {
                Cookie.parseAll(url, it.headers).forEach { cookie ->
                    cookies.removeAll {
                        it.name == cookie.name &&
                            it.domain == cookie.domain &&
                            it.path == cookie.path
                    }
                    cookies.add(cookie)
                }
                if (++redirects > 10)
                    throw IOException(context.getString(R.string.download_redirect_error))
                val location = it.header("Location") ?: throw IOException("HTTP ${it.code}")
                url =
                    url.resolve(location)
                        ?: throw IOException(context.getString(R.string.unsupported_download))
            }
        }
    }

    data class Headers(
        val agent: String? = null,
        val cookie: String? = null,
        val referer: String? = null,
        val language: String? = null,
    )

    private fun sameOrigin(first: HttpUrl, second: HttpUrl) =
        first.scheme == second.scheme && first.host == second.host && first.port == second.port
}
