package net.conw.unit.download

import android.content.Context
import android.text.format.Formatter
import net.conw.unit.R

internal enum class DownloadState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED;

    val isActive
        get() = this == QUEUED || this == RUNNING
}

internal data class Download(
    val id: Long,
    val url: String,
    val name: String,
    val mime: String?,
    val incognito: Boolean,
    val state: DownloadState = DownloadState.QUEUED,
    val bytes: Long = 0,
    val total: Long = -1,
    val uri: String? = null,
    val error: String? = null,
) {
    val percent
        get() = if (total > 0) (100L * bytes / total).toInt().coerceIn(0, 100) else 0
}

internal fun Download.statusText(context: Context): String =
    when (state) {
        DownloadState.COMPLETED -> context.getString(R.string.completed)
        DownloadState.QUEUED -> context.getString(R.string.queued)
        DownloadState.FAILED -> error ?: context.getString(R.string.failed)
        DownloadState.RUNNING ->
            Formatter.formatShortFileSize(context, bytes) +
                if (total > 0) " / ${Formatter.formatShortFileSize(context, total)}" else ""
    }
