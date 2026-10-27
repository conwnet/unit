package net.conw.unit.proxy

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/** An explicit proxy prevents failed requests from falling back to a direct connection. */
internal class ProxyClient(private val settings: ProxySettings) : Call.Factory {
    private val rules = settings.bypass.map { requireNotNull(ProxyBypass.parse(it)) }
    private val client = lazy { create(settings) }
    private val direct = lazy { create(settings.copy(enabled = false), forceDirect = true) }

    override fun newCall(request: Request): Call =
        (if (settings.enabled && rules.any { it.matches(request.url) }) direct.value else client.value)
            .newCall(request)

    fun close() {
        if (client.isInitialized()) close(client.value)
        if (direct.isInitialized()) close(direct.value)
    }

    private companion object {
        val proxyTrustManager by lazy {
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(null as KeyStore?) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
        }
        val proxyTlsFactory by lazy {
            SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf(proxyTrustManager), null) }.socketFactory
        }
    }

    private fun close(client: OkHttpClient) {
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    private fun create(settings: ProxySettings, forceDirect: Boolean = false): OkHttpClient {
        require(settings.valid()) { "Invalid proxy configuration" }
        val proxy =
            if (forceDirect) Proxy.NO_PROXY
            else if (!settings.enabled) null
            else
                Proxy(
                    if (settings.type == ProxySettings.Type.SOCKS5) Proxy.Type.SOCKS
                    else Proxy.Type.HTTP,
                    InetSocketAddress(settings.host, settings.port),
                )
        return OkHttpClient.Builder()
            .proxy(proxy)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .apply {
                if (settings.enabled && settings.type == ProxySettings.Type.HTTPS) {
                    sslSocketFactory(proxyTlsFactory, proxyTrustManager)
                    socketFactory(HttpsProxySocketFactory(settings.host, proxyTlsFactory))
                }
                if (settings.enabled && settings.authenticated)
                    proxyAuthenticator(ProxyAuthentication(settings))
            }
            .build()
    }

    internal class AuthenticationException : IOException("Proxy authentication failed")

    internal class ProxyAuthentication(private val settings: ProxySettings) : Authenticator {
        override fun authenticate(route: Route?, response: Response): Request? {
            if (route?.proxy?.type() != Proxy.Type.HTTP) return null
            // A 407 inside the origin's TLS connection is not a proxy challenge.
            if (response.request.url.isHttps && response.request.method != "CONNECT") return null
            val request = authenticatedRequest(response)
            if (request == null && response.code == 407 &&
                !response.header("Proxy-Authenticate").equals("OkHttp-Preemptive", true))
                throw AuthenticationException()
            return request
        }

        private fun authenticatedRequest(response: Response): Request? {
            val previous = response.request.header("Proxy-Authorization")
            val challenges = response.challenges()
            val challenge =
                challenges.firstOrNull { it.scheme.equals("Digest", true) }
                    ?: challenges.firstOrNull { it.scheme.equals("Basic", true) }
                    // Send credentials over proxy TLS before an unauthenticated CONNECT can be closed.
                    ?: challenges.firstOrNull {
                        settings.type == ProxySettings.Type.HTTPS &&
                            response.request.method == "CONNECT" &&
                            it.scheme.equals("OkHttp-Preemptive", true)
                    }
                    ?: return null
            // A preemptive Basic attempt may need Digest or the proxy's requested charset.
            if (previous != null && !previous.startsWith("Basic ", true) &&
                challenge.authParams["stale"] != "true") return null
            val preemptive = challenge.scheme.equals("OkHttp-Preemptive", true)
            val header =
                if (preemptive || challenge.scheme.equals("Basic", true))
                    Credentials.basic(
                        settings.username,
                        settings.password,
                        if (preemptive) Charsets.UTF_8 else challenge.charset,
                    )
                else {
                    val values = challenge.authParams
                    val nonce = values["nonce"] ?: return null
                    val realm = values["realm"] ?: return null
                    val algorithm = values["algorithm"] ?: "MD5"
                    val hash = algorithm.uppercase().removeSuffix("-SESS")
                    if (hash !in listOf("MD5", "SHA-256", "SHA-512-256")) return null
                    val qop = values["qop"]?.split(',')?.map { it.trim() }
                    if (qop != null && "auth" !in qop) return null
                    val request = response.request
                    val uri =
                        if (request.method == "CONNECT") {
                            val host = request.url.host
                            if (host.contains(':')) "[$host]:${request.url.port}"
                            else "$host:${request.url.port}"
                        } else request.url.toString()
                    val cnonce = UUID.randomUUID().toString().replace("-", "")
                    fun digest(text: String): String =
                        MessageDigest.getInstance(
                                if (hash == "SHA-512-256") "SHA-512/256" else hash
                            )
                            .digest(text.toByteArray(challenge.charset))
                            .joinToString("") { "%02x".format(it) }
                    var a1 = digest("${settings.username}:$realm:${settings.password}")
                    if (algorithm.endsWith("-sess", true)) {
                        a1 = digest("$a1:$nonce:$cnonce")
                    }
                    val a2 = digest("${request.method}:$uri")
                    val signed =
                        if (qop == null) digest("$a1:$nonce:$a2")
                        else digest("$a1:$nonce:00000001:$cnonce:auth:$a2")
                    fun quote(value: String) =
                        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
                    buildList {
                            add("username=${quote(settings.username)}")
                            add("realm=${quote(realm)}")
                            add("nonce=${quote(nonce)}")
                            add("uri=${quote(uri)}")
                            add("response=${quote(signed)}")
                            add("algorithm=$algorithm")
                            values["opaque"]?.let { add("opaque=${quote(it)}") }
                            if (qop != null) {
                                add("qop=auth")
                                add("nc=00000001")
                            }
                            if (qop != null || algorithm.endsWith("-sess", true))
                                add("cnonce=${quote(cnonce)}")
                        }
                        .joinToString(", ", "Digest ")
                }
            if (header == previous) return null
            return response.request.newBuilder().header("Proxy-Authorization", header).build()
        }
    }
}
