package net.conw.unit.download

import android.Manifest
import android.os.Build
import androidx.appcompat.app.AlertDialog
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.ui.Ui

internal class DownloadController(private val activity: BrowserActivity) {
    private val metadata = DownloadMetadata(activity)
    private val list = DownloadsDialog(activity, ::retry)
    private var metadataDialog: AlertDialog? = null

    init {
        if (!activity.isIncognito) DownloadService.recover(activity, activity.store)
    }

    fun request(
        url: String,
        userAgent: String?,
        disposition: String?,
        mime: String?,
        image: Boolean = false,
        video: Boolean = false,
    ) {
        if (!UrlUtils.isWeb(url)) {
            activity.message(R.string.unsupported_download)
            return
        }
        stopLookup()
        val lookup = image || video
        var filename =
            if (lookup) if (video) "video" else "image"
            else DownloadFiles.name(url, disposition, mime)
        var resolvedMime = mime
        val privateNote =
            if (activity.isIncognito) activity.getString(R.string.private_download_note) else ""
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.download_file)
                .setMessage(
                    (if (lookup) activity.getString(R.string.download_checking)
                    else activity.getString(R.string.download_prompt, filename)) + privateNote
                )
                .setPositiveButton(R.string.download) { _, _ ->
                    fun begin() {
                        enqueue(url, userAgent, filename, resolvedMime, video)
                    }
                    if (Build.VERSION.SDK_INT <= 28)
                        activity.requests.permissions(
                            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        ) { granted ->
                            if (granted) begin() else activity.message(R.string.storage_required)
                        }
                    else if (Build.VERSION.SDK_INT >= 33)
                        activity.requests.permissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                        ) {
                            begin()
                        }
                    else begin()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        if (!lookup) {
            activity.showDialog(dialog)
            return
        }

        metadataDialog = dialog
        activity.showDialog(dialog) {
            if (metadataDialog === dialog) {
                metadataDialog = null
                metadata.cancel()
            }
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        val session = DownloadSession.capture(activity, url, userAgent, video)
        metadata.lookup(url, session) { information ->
            if (!dialog.isShowing) return@lookup
            val message =
                information.fold(
                    onSuccess = { (name, type) ->
                        filename = name
                        resolvedMime = type
                        activity.getString(R.string.download_prompt, name)
                    },
                    onFailure = {
                        activity.getString(
                            if (video) R.string.video_unsupported
                            else R.string.download_info_unavailable
                        )
                    },
                )
            dialog.setMessage(message + privateNote)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                !video || information.isSuccess
        }
    }

    private fun enqueue(
        url: String,
        userAgent: String?,
        filename: String,
        mime: String?,
        video: Boolean = false,
    ): Boolean {
        var download: Download? = null
        return try {
            val session = DownloadSession.capture(activity, url, userAgent, video)
            require(session.proxy.valid())
            val row = activity.store.addDownload(url, filename, mime, activity.isIncognito)
            download = row
            DownloadService.enqueue(activity, row, session)
            activity.message(R.string.download_started)
            true
        } catch (_: Exception) {
            download?.let {
                activity.store.updateDownload(
                    it.copy(
                        state = DownloadState.FAILED,
                        error = activity.getString(R.string.download_failed),
                    )
                )
            }
            activity.message(R.string.download_failed)
            false
        }
    }

    fun show() {
        stopLookup()
        list.show()
    }

    private fun retry(download: Download): Boolean =
        enqueue(
            download.url,
            activity.userAgent(),
            download.name,
            download.mime,
            video = download.mime?.startsWith("video/") == true,
        )

    private fun stopLookup() {
        metadataDialog?.dismiss()
    }

    fun stop() {
        stopLookup()
        list.dismiss()
    }

    fun destroy() {
        stopLookup()
        metadata.destroy()
        list.destroy()
    }
}
