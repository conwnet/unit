package net.conw.unit.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import java.io.IOException
import java.util.concurrent.Executors
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.data.BrowserStore
import net.conw.unit.proxy.ProxyClient
import okhttp3.Call

/** Two streaming workers, with task state in SQLite and session headers held only in memory. */
class DownloadService : Service() {
    private lateinit var store: BrowserStore
    private lateinit var notifications: NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val cancellations = Executors.newSingleThreadExecutor()
    private val tasks = mutableMapOf<Long, Task>()
    private var lastStart = 0
    private var firstStart = true
    private var destroyed = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = BrowserStore(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.downloads),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStart = startId
        ServiceCompat.startForeground(
            this,
            SUMMARY,
            summary(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val id = intent?.getLongExtra("id", -1) ?: -1
        if (intent?.action == CANCEL) {
            cancel(id)
        } else if (id >= 0 && id !in tasks) {
            if (firstStart) {
                recover(this, store, id, true)
                firstStart = false
            }
            val download = store.download(id)
            val session = intent?.let(DownloadSession::from)
            if (download != null && session != null && download.state.isActive) {
                val task = Task(download, session)
                tasks[id] = task
                notify(task.row)
                workers.execute { task.run() }
            }
        }
        updateSummary()
        return START_NOT_STICKY
    }

    private fun summary(): Notification =
        Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.downloads))
            .setContentText(
                resources.getQuantityString(
                    R.plurals.active_downloads,
                    maxOf(1, tasks.size),
                    maxOf(1, tasks.size),
                )
            )
            .setContentIntent(listIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setGroup(CHANNEL)
            .setGroupSummary(true)
            .build()

    private fun listIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, BrowserActivity::class.java)
                .putExtra("show_downloads", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notify(row: Download) {
        val active = row.state.isActive
        val text = row.statusText(this)
        val builder =
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(row.name)
                .setContentText(text)
                .setContentIntent(listIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(active)
                .setAutoCancel(!active)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setGroup(CHANNEL)
        if (active) {
            builder.setProgress(
                100,
                row.percent,
                row.total <= 0,
            )
            val cancel =
                PendingIntent.getService(
                    this,
                    notificationId(row.id),
                    Intent(this, DownloadService::class.java)
                        .setAction(CANCEL)
                        .putExtra("id", row.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(
                Notification.Action.Builder(null, getString(R.string.cancel), cancel).build()
            )
        }
        notifications.notify(notificationId(row.id), builder.build())
    }

    private fun updateSummary() {
        if (tasks.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastStart)
        } else notifications.notify(SUMMARY, summary())
    }

    private fun cancel(id: Long) {
        tasks[id]?.cancel()
        val download = store.download(id)
        if (download != null) discard(this, store, download)
        else notifications.cancel(notificationId(id))
    }

    private inner class Task(
        @Volatile var row: Download,
        val session: DownloadSession,
    ) {
        @Volatile var cancelled = false
        @Volatile var call: Call? = null
        private var lastProgress = 0L

        fun cancel() {
            if (cancelled) return
            cancelled = true
            // TLS proxy sockets may send close_notify; cancellation must not do network I/O on UI.
            call?.let { active -> cancellations.execute { active.cancel() } }
        }

        fun run() {
            var client: ProxyClient? = null
            try {
                checkCancelled()
                row = row.copy(state = DownloadState.RUNNING, error = null)
                store.updateDownload(row)
                client = ProxyClient(session.proxy)
                DownloadRequest.open(
                        this@DownloadService,
                        client,
                        row.url,
                        session.headers,
                    ) { next ->
                        call = next
                        checkCancelled()
                    }
                    .use { response ->
                        val body = response.body
                        val mime = body.contentType()?.let { "${it.type}/${it.subtype}" }
                        val filename =
                            DownloadFiles.name(
                                response.request.url.toString(),
                                response.header("Content-Disposition"),
                                mime,
                            )
                        row =
                            row.copy(
                                total = body.contentLength(),
                                mime = DownloadFiles.mimeType(filename, mime),
                                name = filename,
                            )
                        checkCancelled()
                        val uri = DownloadFiles.create(this@DownloadService, row)
                        row = row.copy(uri = uri.toString())
                        store.updateDownload(row)
                        DownloadFiles.output(this@DownloadService, uri).use { output ->
                            body.byteStream().use { input ->
                                val buffer = ByteArray(32 * 1024)
                                while (true) {
                                    checkCancelled()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    row = row.copy(bytes = row.bytes + read)
                                    progress()
                                }
                            }
                        }
                        checkCancelled()
                        if (row.total >= 0 && row.bytes != row.total)
                            throw IOException(getString(R.string.download_incomplete))
                        row =
                            DownloadFiles.complete(this@DownloadService, row)
                                .copy(state = DownloadState.COMPLETED)
                        checkCancelled()
                        store.updateDownload(row)
                    }
            } catch (error: Exception) {
                runCatching { DownloadFiles.delete(this@DownloadService, row) }
                if (!cancelled) {
                    val reason =
                        when (error) {
                            is javax.net.ssl.SSLException -> getString(R.string.invalid_certificate)
                            is java.net.SocketTimeoutException ->
                                getString(R.string.download_timeout)
                            is java.net.UnknownHostException ->
                                getString(R.string.download_host_error)
                            is java.net.ConnectException ->
                                getString(R.string.download_connection_error)
                            is IOException -> error.message ?: getString(R.string.download_failed)
                            else -> getString(R.string.download_failed)
                        }
                    row =
                        row.copy(
                            state = DownloadState.FAILED,
                            error = reason,
                            uri = null,
                        )
                    store.updateDownload(row)
                }
            } finally {
                call = null
                client?.let(ProxyClient::close)
                handler.post {
                    tasks.remove(row.id)
                    if (!cancelled && store.download(row.id) != null) notify(row)
                    if (destroyed) {
                        if (tasks.isEmpty()) store.close()
                    } else updateSummary()
                }
            }
        }

        private fun checkCancelled() {
            if (cancelled) throw IOException("Cancelled")
        }

        private fun progress() {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastProgress < 500) return
            lastProgress = now
            val snapshot = row
            store.updateDownload(snapshot)
            handler.post { if (!cancelled && row.id in tasks) notify(snapshot) }
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        tasks.values.forEach {
            it.cancel()
            runCatching { DownloadFiles.delete(this, it.row) }
            store.updateDownload(
                it.row.copy(
                    state = DownloadState.FAILED,
                    uri = null,
                    error = getString(R.string.download_timeout),
                )
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        tasks.values.forEach { it.cancel() }
        workers.shutdown()
        cancellations.shutdown()
        if (tasks.isEmpty()) store.close()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "downloads"
        private const val SUMMARY = 1
        private const val CANCEL = "cancel"
        @Volatile private var instance: DownloadService? = null

        private fun notificationId(id: Long) = ((id xor (id ushr 32)).toInt() and 0x3fffffff) + 100

        internal fun enqueue(context: Context, download: Download, session: DownloadSession) {
            context.startForegroundService(
                session.applyTo(
                    Intent(context, DownloadService::class.java).putExtra("id", download.id)
                )
            )
        }

        internal fun cancel(
            context: Context,
            store: BrowserStore,
            download: Download,
        ) {
            if (!download.state.isActive) {
                discard(context, store, download)
                return
            }
            context.startService(
                Intent(context, DownloadService::class.java)
                    .setAction(CANCEL)
                    .putExtra("id", download.id)
            )
        }

        private fun discard(context: Context, store: BrowserStore, download: Download) {
            runCatching { DownloadFiles.delete(context, download) }
            removeRecord(context, store, download.id)
        }

        internal fun removeRecord(context: Context, store: BrowserStore, id: Long) {
            store.deleteDownload(id)
            context.getSystemService(NotificationManager::class.java).cancel(notificationId(id))
        }

        internal fun recover(
            context: Context,
            store: BrowserStore,
            except: Long = -1,
            force: Boolean = false,
        ) {
            if (!force && instance != null) return
            store
                .downloads()
                .filter { it.id != except && it.state.isActive }
                .forEach {
                    runCatching { DownloadFiles.delete(context, it) }
                    store.updateDownload(
                        it.copy(
                            state = DownloadState.FAILED,
                            uri = null,
                            error = context.getString(R.string.download_interrupted),
                        )
                    )
                }
        }
    }
}
