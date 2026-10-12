package net.conw.unit.proxy

import java.net.InetAddress
import okhttp3.HttpUrl

/** Matches the same host-based exclusions passed to WebView, without resolving domain names. */
internal class ProxyBypass private constructor(
    val rule: String,
    val matches: (HttpUrl) -> Boolean,
) {
    companion object {
        private val hostRule =
            Regex("(?:(https?|wss?)://)?(\\[[^]]+]|[^:/]+)(?::([0-9]+))?", RegexOption.IGNORE_CASE)

        fun parseList(text: String): List<String>? {
            return text.split('\n', ';', ',')
                .map { it.trim() }.filter { it.isNotEmpty() }
                .map { parse(it)?.rule ?: return null }.distinct()
        }

        fun parse(input: String): ProxyBypass? {
            val value = input.trim()
            if (value.equals("<local>", true))
                return ProxyBypass("<local>") { '.' !in it.host && ':' !in it.host }
            if ('<' in value || '>' in value) return null
            if ('/' in value && "://" !in value) {
                val parts = value.split('/')
                if (parts.size != 2) return null
                val ipv6 = ':' in parts[0]
                val host = ProxySettings.canonicalHost(parts[0]) ?: return null
                val network = address(host) ?: return null
                val prefix = parts[1].toIntOrNull()?.takeIf { it in 0..(if (ipv6) 128 else 32) } ?: return null
                val bits = prefix + if (ipv6) 0 else 96
                val literal = if (ipv6 && ':' !in host) "::ffff:$host" else host
                return ProxyBypass("$literal/$prefix") { url ->
                    val target = address(url.host)
                    target != null && network.indices.all { index ->
                        val mask = (0xff shl (8 - (bits - index * 8).coerceIn(0, 8))) and 0xff
                        ((target[index].toInt() xor network[index].toInt()) and mask) == 0
                    }
                }
            }
            val match = hostRule.matchEntire(value) ?: return null
            val scheme = match.groupValues[1].lowercase()
            val portText = match.groupValues[3]
            val port =
                if (portText.isEmpty()) null
                else portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val rawHost = match.groupValues[2].let { if (it.startsWith('.')) "*$it" else it }
            val host = ProxySettings.canonicalHost(rawHost) ?: return null
            val pattern = Regex(host.split('*').joinToString(".*") { Regex.escape(it) })
            val authority = if (':' in host) "[$host]" else host
            val rule =
                (if (scheme.isEmpty()) "" else "$scheme://") + authority +
                    (if (port == null) "" else ":$port")
            return ProxyBypass(rule) { url ->
                (scheme.isEmpty() || scheme == url.scheme) &&
                    (port == null || port == url.port) && pattern.matches(url.host)
            }
        }

        private fun address(host: String): ByteArray? {
            val bytes =
                if (':' in host) InetAddress.getByName(host).address
                else {
                    val parts = host.split('.')
                    if (parts.size != 4 || host.any { it !in "0123456789." }) return null
                    parts.map { part ->
                        part.toIntOrNull()?.takeIf { it in 0..255 }?.toByte() ?: return null
                    }.toByteArray()
                }
            if (bytes.size == 16) return bytes
            // Chromium compares both address families using IPv4-mapped IPv6 addresses.
            return ByteArray(16).apply {
                this[10] = -1
                this[11] = -1
                bytes.copyInto(this, 12)
            }
        }
    }
}
