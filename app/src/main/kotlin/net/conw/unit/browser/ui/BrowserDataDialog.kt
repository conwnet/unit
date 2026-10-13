package net.conw.unit.browser.ui

import android.webkit.WebStorage
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebStorageCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.checkbox.MaterialCheckBox
import kotlin.concurrent.thread
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.data.BrowserStore
import net.conw.unit.download.DownloadService
import net.conw.unit.ui.Ui

internal class BrowserDataDialog(private val activity: BrowserActivity) {
    private enum class Data(val title: Int) {
        HISTORY(R.string.browsing_history),
        CACHE(R.string.cached_files),
        SITE_DATA(R.string.cookies_site_data),
        PERMISSIONS(R.string.site_permissions),
        DOWNLOADS(R.string.download_records),
    }

    fun show() {
        val content = Ui.form(activity)
        val choices =
            Data.entries.associateWith { data ->
                MaterialCheckBox(Ui.dialogContext(activity)).apply {
                    setText(data.title)
                    setTextColor(activity.getColor(R.color.text))
                    textSize = 14f
                    minimumHeight = Ui.dp(activity, 48)
                    isChecked = data == Data.HISTORY || data == Data.CACHE
                    content.addView(this, LinearLayout.LayoutParams(-1, -2))
                }
            }
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.clear_browsing_data)
                .setView(ScrollView(activity).apply { addView(content) })
                .setPositiveButton(R.string.clear, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        activity.showDialog(dialog)
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
        fun updateChoices() {
            val sites = choices.getValue(Data.SITE_DATA).isChecked
            val cache = choices.getValue(Data.CACHE)
            if (sites) cache.isChecked = true
            cache.isEnabled = !sites
            confirm.isEnabled = choices.values.any { it.isChecked }
        }
        choices.values.forEach { choice ->
            choice.setOnCheckedChangeListener { _, _ -> updateChoices() }
        }
        confirm.setOnClickListener {
            val selected = choices.filterValues { it.isChecked }.keys
            confirm.isEnabled = false
            cancel.isEnabled = false
            confirm.setText(R.string.clearing_data)
            choices.values.forEach { it.isEnabled = false }
            dialog.setCancelable(false)
            clear(selected) { error ->
                if (error == null) {
                    dialog.dismiss()
                    activity.message(R.string.browsing_data_cleared)
                } else if (dialog.isShowing) {
                    confirm.setText(R.string.clear)
                    cancel.isEnabled = true
                    choices.values.forEach { it.isEnabled = true }
                    dialog.setCancelable(true)
                    updateChoices()
                    activity.message(error)
                }
            }
        }
    }

    private fun clear(selected: Set<Data>, complete: (Int?) -> Unit) {
        val context = activity.applicationContext
        val sites = Data.SITE_DATA in selected
        fun clearRecords() {
            thread(name = "clear-browser-data") {
                val result = runCatching {
                    BrowserStore(context).use { store ->
                        if (Data.HISTORY in selected) store.deletePage(false, null)
                        if (Data.DOWNLOADS in selected)
                            store.downloads().filter { !it.state.isActive }.forEach {
                                DownloadService.removeRecord(context, store, it.id)
                            }
                    }
                }
                activity.runOnUiThread {
                    if (activity.isDestroyed || activity.isFinishing) return@runOnUiThread
                    val finished = result.mapCatching {
                        if (Data.PERMISSIONS in selected) activity.sitePermissions.clear()
                        if (sites) activity.showCurrent()
                    }
                    if (finished.isFailure && sites) runCatching { activity.showCurrent() }
                    complete(if (finished.isSuccess) null else R.string.clear_browsing_data_failed)
                }
            }
        }
        runCatching {
                if (sites) {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DELETE_BROWSING_DATA)) {
                        // Stop documents from writing website data while deletion is in progress.
                        activity.playback.hide()
                        activity.tabs.forEach { it.destroy() }
                        WebStorageCompat.deleteBrowsingData(WebStorage.getInstance()) { clearRecords() }
                    } else complete(R.string.clear_site_data_unsupported)
                } else {
                    if (Data.HISTORY in selected)
                        activity.tabs.forEach { it.web?.clearHistory() }
                    if (Data.CACHE in selected) activity.current()?.create()?.clearCache(true)
                    clearRecords()
                }
            }
            .onFailure {
                if (sites) runCatching { activity.showCurrent() }
                complete(R.string.clear_browsing_data_failed)
            }
    }
}
