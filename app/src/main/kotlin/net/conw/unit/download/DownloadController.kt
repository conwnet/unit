package net.conw.unit.download

import android.Manifest
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.updateLayoutParams
import com.google.android.material.snackbar.Snackbar
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.ui.Ui

internal class DownloadController(private val activity: BrowserActivity) {
    private val list = DownloadsDialog(activity, ::retry)

    init {
        if (!activity.isIncognito) DownloadService.recover(activity, activity.store)
    }

    fun request(
        url: String,
        userAgent: String?,
        disposition: String?,
        mime: String?,
    ) {
        if (!UrlUtils.isWeb(url)) {
            activity.message(R.string.unsupported_download)
            return
        }
        val filename = DownloadFiles.name(url, disposition, mime)
        val privateNote =
            if (activity.isIncognito) activity.getString(R.string.private_download_note) else ""
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.download_file)
                .setMessage(
                    activity.getString(R.string.download_prompt, filename) + privateNote
                )
                .setPositiveButton(R.string.download) { _, _ ->
                    fun begin() {
                        enqueue(url, userAgent, filename)
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
        activity.showDialog(dialog)
    }

    private fun enqueue(
        url: String,
        userAgent: String?,
        filename: String,
    ): Boolean {
        var download: Download? = null
        return try {
            val session = DownloadSession.capture(activity, url, userAgent)
            require(session.proxy.valid())
            val row = activity.store.addDownload(url, filename, activity.isIncognito)
            download = row
            DownloadService.enqueue(activity, row, session)
            Snackbar.make(
                    Ui.dialogContext(activity),
                    activity.findViewById(R.id.root),
                    activity.getString(R.string.download_started),
                    Snackbar.LENGTH_LONG,
                )
                .setAnchorView(activity.toolbar.view)
                .setAction(R.string.records) { show() }
                .apply {
                    view.updateLayoutParams<FrameLayout.LayoutParams> {
                        width = ViewGroup.LayoutParams.WRAP_CONTENT
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    }
                }
                .show()
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
        list.show()
    }

    private fun retry(download: Download): Boolean =
        enqueue(
            download.url,
            activity.userAgent(),
            download.name,
        )

    fun stop() {
        list.dismiss()
    }

    fun destroy() {
        list.destroy()
    }
}
