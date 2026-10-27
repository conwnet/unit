package net.conw.unit.download

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import com.google.android.material.checkbox.MaterialCheckBox
import java.util.concurrent.Executors
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.ui.BrowserListAdapter
import net.conw.unit.browser.ui.BrowserSheet
import net.conw.unit.data.BrowserStore
import net.conw.unit.ui.Ui

internal class DownloadsDialog(
    private val activity: BrowserActivity,
    private val retry: (Download) -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val fileWorkers = Executors.newSingleThreadExecutor()
    private var screen: BrowserSheet? = null

    fun show() {
        dismiss()
        val sheet = BrowserSheet(activity, R.string.downloads)
        val adapter =
            BrowserListAdapter<Download>(
                activity,
                R.string.no_downloads,
                R.string.download_hint,
                searchText = { "${it.name}\n${it.url}" },
                row = { item ->
                    val status = item.statusText(activity)
                    val row =
                        Ui.row(
                            activity,
                            item.name,
                            if (item.incognito)
                                activity.getString(R.string.private_download_status, status)
                            else status,
                            icon = R.drawable.ic_download,
                        )
                    if (item.state.isActive)
                        row.addView(
                            ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
                                .apply {
                                    isIndeterminate = item.total <= 0
                                    if (item.total > 0) progress = item.percent
                                    progressTintList =
                                        android.content.res.ColorStateList.valueOf(
                                            activity.getColor(R.color.accent)
                                        )
                                },
                            LinearLayout.LayoutParams(-1, Ui.dp(activity, 3)).apply {
                                topMargin = Ui.dp(activity, 12)
                            },
                        )
                    row
                },
                emptyIcon = R.drawable.ic_download,
            )
        val list = sheet.list(adapter)
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let(::actions)
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let {
                actions(it)
                true
            } ?: false
        }
        sheet.search(adapter::filter)
        sheet.actions.addView(
            Ui.iconButton(activity, R.drawable.ic_folder, R.string.open_downloads) {
                try {
                    activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
                } catch (_: ActivityNotFoundException) {
                    activity.message(R.string.downloads_unavailable)
                }
            }
        )
        screen = sheet
        val refresh =
            object : Runnable {
                override fun run() {
                    if (!sheet.dialog.isShowing) return
                    adapter.submit(activity.store.downloads())
                    handler.postDelayed(this, 1000)
                }
            }
        sheet.onDismiss = {
            handler.removeCallbacks(refresh)
            if (screen === sheet) screen = null
        }
        adapter.submit(activity.store.downloads())
        sheet.show(compact = true)
        handler.postDelayed(refresh, 1000)
    }

    private fun actions(item: Download) {
        val download = activity.store.download(item.id) ?: return
        val labels =
            when (download.state) {
                DownloadState.COMPLETED ->
                    listOf(R.string.open, R.string.copy_link, R.string.delete)
                DownloadState.FAILED -> listOf(R.string.retry, R.string.copy_link, R.string.delete)
                else -> listOf(R.string.copy_link, R.string.cancel)
            }
        Ui.actions(activity, download.name, labels) { action ->
            when (action) {
                R.string.open ->
                    try {
                        activity.startActivity(DownloadFiles.openIntent(activity, download))
                    } catch (_: ActivityNotFoundException) {
                        activity.message(R.string.no_file_app)
                    } catch (_: Exception) {
                        activity.message(R.string.file_unavailable)
                    }
                R.string.retry -> {
                    if (download.incognito != activity.isIncognito)
                        activity.message(R.string.retry_original_mode)
                    else if (retry(download)) cancel(download)
                }
                R.string.copy_link ->
                    Ui.copy(activity, R.string.copy_link, download.url, R.string.link_copied)
                R.string.cancel -> cancel(download)
                R.string.delete -> confirmDelete(download)
            }
        }
    }

    private fun confirmDelete(download: Download) {
        val deleteFile =
            download.uri?.let {
                MaterialCheckBox(Ui.dialogContext(activity)).apply {
                    id = R.id.download_delete_file
                    setText(R.string.also_delete_file)
                    setTextColor(activity.getColor(R.color.text))
                    textSize = 14f
                    minimumHeight = Ui.dp(activity, 48)
                    isChecked = false
                }
            }
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.remove_download_title)
                .apply {
                    if (deleteFile != null)
                        setView(
                            Ui.column(activity).apply {
                                setPadding(Ui.dp(activity, 24), Ui.dp(activity, 8), Ui.dp(activity, 24), 0)
                                addView(deleteFile, LinearLayout.LayoutParams(-1, -2))
                            }
                        )
                }
                .setPositiveButton(R.string.remove, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        activity.showDialog(dialog)
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        confirm.setOnClickListener {
            val includeFile = deleteFile?.isChecked == true
            confirm.isEnabled = false
            cancel.isEnabled = false
            deleteFile?.isEnabled = false
            dialog.setCancelable(false)
            fileWorkers.execute {
                var failure = R.string.remove_download_failed
                val result = runCatching {
                    BrowserStore(activity.applicationContext).use { store ->
                        val current = store.download(download.id) ?: download
                        check(!current.state.isActive)
                        if (includeFile) {
                            failure = R.string.delete_file_failed
                            DownloadFiles.delete(activity, current)
                        }
                        failure = R.string.remove_download_failed
                        DownloadService.removeRecord(activity, store, current.id)
                    }
                }
                handler.post {
                    if (!dialog.isShowing) return@post
                    if (result.isSuccess) dialog.dismiss()
                    else {
                        confirm.isEnabled = true
                        cancel.isEnabled = true
                        deleteFile?.isEnabled = true
                        dialog.setCancelable(true)
                        activity.message(failure)
                    }
                }
            }
        }
    }

    private fun cancel(download: Download) {
        val context = activity.applicationContext
        fileWorkers.execute {
            BrowserStore(context).use { store -> DownloadService.cancel(context, store, download) }
        }
    }

    fun dismiss() {
        screen?.dialog?.dismiss()
        screen = null
    }

    fun destroy() {
        dismiss()
        handler.removeCallbacksAndMessages(null)
        fileWorkers.shutdown()
    }
}
