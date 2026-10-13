package net.conw.unit.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import net.conw.unit.proxy.ProxyClient
import okhttp3.Call

internal class DownloadMetadata(context: Context) {
    data class FileInfo(val name: String, val mime: String?)

    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newSingleThreadExecutor()
    private val cancellations = Executors.newSingleThreadExecutor()
    private var pending: Lookup? = null

    fun lookup(url: String, session: DownloadSession, result: (Result<FileInfo>) -> Unit) {
        cancel()
        val lookup = Lookup()
        pending = lookup
        workers.execute {
            var client: ProxyClient? = null
            val information = runCatching {
                if (!lookup.active.get()) throw IOException("Cancelled")
                client = ProxyClient(session.proxy)
                DownloadRequest.open(context, requireNotNull(client), url, session.headers) { call
                        ->
                        lookup.call.set(call)
                        if (!lookup.active.get()) {
                            call.cancel()
                            throw IOException("Cancelled")
                        }
                    }
                    .use { response ->
                        val mime = response.body.contentType()?.let { "${it.type}/${it.subtype}" }
                        val address = response.request.url.toString()
                        FileInfo(
                            DownloadFiles.name(
                                address,
                                response.header("Content-Disposition"),
                                mime,
                            ),
                            mime,
                        )
                    }
            }
            lookup.call.set(null)
            client?.let(ProxyClient::close)
            handler.post {
                if (pending !== lookup || !lookup.active.get()) return@post
                pending = null
                result(information)
            }
        }
    }

    fun cancel() {
        val lookup = pending ?: return
        pending = null
        handler.removeCallbacksAndMessages(null)
        if (lookup.active.compareAndSet(true, false))
            lookup.call.get()?.let { call -> cancellations.execute { call.cancel() } }
    }

    fun destroy() {
        cancel()
        workers.shutdown()
        cancellations.shutdown()
    }

    private class Lookup {
        val active = AtomicBoolean(true)
        val call = AtomicReference<Call?>()
    }
}
