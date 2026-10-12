package net.conw.unit.proxy

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.conw.unit.data.BrowserStore
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One atomic SQLite setting shared by both browsing processes. Credentials stay on this device. */
internal data class ProxySettings(
    val enabled: Boolean = false,
    val type: Type = Type.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
    val bypass: List<String> = emptyList(),
) {
    enum class Type(val scheme: String, val defaultPort: Int) {
        HTTP("http", 8080),
        HTTPS("https", 443),
        SOCKS5("socks5", 1080),
    }

    val authenticated
        get() = type != Type.SOCKS5 && username.isNotEmpty()

    val authority
        get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"

    val rule
        get() = "${type.scheme}://$authority"

    fun valid(): Boolean =
        port in 1..65535 && (!enabled && host.isEmpty() || canonicalHost(host) != null) &&
            bypass.all { ProxyBypass.parse(it) != null }

    fun save(context: Context, store: BrowserStore) {
        store.put(
            "proxy",
            JSONObject()
                .put("enabled", enabled)
                .put("type", type.name)
                .put("host", host)
                .put("port", port)
                .put("username", username)
                .put("password", if (password.isEmpty()) "" else encrypt(context, password))
                .put("bypass", JSONArray(bypass))
                .toString(),
        )
    }

    companion object {
        fun load(context: Context, store: BrowserStore): ProxySettings {
            val value = store.get("proxy") ?: return ProxySettings()
            val data = JSONObject(value)
            val password = data.getString("password")
            val rules = data.getJSONArray("bypass")
            return ProxySettings(
                data.getBoolean("enabled"),
                Type.valueOf(data.getString("type")),
                data.getString("host"),
                data.getInt("port"),
                data.getString("username"),
                if (password.isEmpty()) "" else decrypt(context, password),
                List(rules.length()) { rules.getString(it) },
            )
        }

        fun canonicalHost(input: String): String? {
            val host = input.trim().removeSurrounding("[", "]")
            if (host.isEmpty() || host.any { it.isWhitespace() || it in "/@?#\\" }) return null
            val authority = if (host.contains(':')) "[$host]" else host
            return "http://$authority/".toHttpUrlOrNull()?.host
        }

        private fun key(context: Context): SecretKey {
            val alias = "${context.packageName}.proxy"
            val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keys.getKey(alias, null) as? SecretKey)?.let {
                return it
            }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
        }

        private fun encrypt(context: Context, text: String): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(context))
            return Base64.encodeToString(
                cipher.iv + cipher.doFinal(text.toByteArray()),
                Base64.NO_WRAP,
            )
        }

        private fun decrypt(context: Context, text: String): String {
            val bytes = Base64.decode(text, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(context),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
            )
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
}
