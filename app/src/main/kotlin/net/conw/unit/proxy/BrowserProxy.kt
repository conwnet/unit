package net.conw.unit.proxy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import net.conw.unit.R
import net.conw.unit.data.BrowserStore

/** Applies the shared setting in each WebView process before new app-initiated navigation. */
internal class BrowserProxy(private val context: Context) {
    private val store = BrowserStore(context)
    private val handler = Handler(Looper.getMainLooper())
    private val action = "${context.packageName}.PROXY"
    private val permission = "${context.packageName}.permission.TABS"
    private val waiting = mutableListOf<(Boolean) -> Unit>()
    private var applying = false
    private var applied: ProxySettings? = null
    private var desired = ProxySettings()
    var error: String? = null
        private set

    val enabled
        get() = applied?.enabled == true

    val supported
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                sync()
            }
        }

    init {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            permission,
            handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    fun settings(): ProxySettings = ProxySettings.load(context, store)

    fun sync(ready: (Boolean) -> Unit = {}) {
        try {
            desired = settings()
            waiting.add(ready)
            applyNext()
        } catch (_: Exception) {
            error = context.getString(R.string.proxy_saved_error)
            ready(false)
        }
    }

    fun save(settings: ProxySettings, ready: (Boolean) -> Unit) {
        try {
            require(settings.valid())
            if (settings.enabled && !supported) {
                error = context.getString(R.string.proxy_unsupported)
                ready(false)
                return
            }
            settings.save(context, store)
            sync { success ->
                if (success) {
                    // Ordered broadcasts are delivered to the cached incognito process as well.
                    context.sendOrderedBroadcast(
                        Intent(action).setPackage(context.packageName),
                        permission,
                    )
                }
                ready(success)
            }
        } catch (_: Exception) {
            error = context.getString(R.string.proxy_saved_error)
            ready(false)
        }
    }

    @SuppressLint("RequiresFeature") // The supported check below guards both ProxyController calls.
    private fun applyNext() {
        if (applying) return
        val target = desired
        if (target == applied) {
            finish(true)
            return
        }
        if (!target.valid() || target.enabled && !supported) {
            error =
                context.getString(
                    if (target.valid()) R.string.proxy_unsupported else R.string.proxy_invalid
                )
            finish(false)
            return
        }
        if (!target.enabled && applied?.enabled != true) {
            applied = target
            finish(true)
            return
        }
        applying = true
        try {
            val complete = Runnable {
                applying = false
                applied = target
                applyNext()
            }
            val controller = ProxyController.getInstance()
            if (target.enabled)
                controller.setProxyOverride(
                    ProxyConfig.Builder().addProxyRule(target.rule).removeImplicitRules().apply {
                        target.bypass.forEach { addBypassRule(it) }
                    }.build(),
                    { task -> handler.post(task) },
                    complete,
                )
            else controller.clearProxyOverride({ task -> handler.post(task) }, complete)
        } catch (_: RuntimeException) {
            applying = false
            error = context.getString(R.string.proxy_apply_error)
            finish(false)
        }
    }

    private fun finish(success: Boolean) {
        if (success) error = null
        val callbacks = waiting.toList()
        waiting.clear()
        callbacks.forEach { it(success) }
    }

    fun credentials(host: String): Pair<String, String>? {
        val config = applied ?: return null
        return if (config.enabled && config.authenticated && host.equals(config.host, true))
            config.username to config.password
        else null
    }
}
