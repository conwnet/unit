package net.conw.unit.browser.page

import android.widget.ScrollView
import androidx.core.net.toUri
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.browser.ui.BrowserSheet
import net.conw.unit.ui.Ui
import org.json.JSONArray

internal class BrowserVideos(private val activity: BrowserActivity) {
    fun show() {
        val tab = activity.current() ?: return
        val script = activity.assets.open("video.js").bufferedReader().use { it.readText() }
        tab.evaluate(script) { value ->
            val entries = runCatching { JSONArray(value) }.getOrNull()
            if (entries == null || entries.length() == 0) {
                activity.message(R.string.video_unavailable)
                return@evaluate
            }
            val sheet = BrowserSheet(activity, R.string.videos)
            val list = Ui.column(activity)
            list.addView(
                Ui.text(activity, R.string.video_description, 14, R.color.muted).apply {
                    setPadding(Ui.dp(activity, 20), 0, Ui.dp(activity, 20), Ui.dp(activity, 12))
                }
            )
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val url = entry.optString("url")
                if (!UrlUtils.isWeb(url)) continue
                val title =
                    entry.optString("title").ifEmpty {
                        url.toUri().lastPathSegment ?: "Video ${index + 1}"
                    }
                list.addView(
                    Ui.row(activity, title, url, icon = R.drawable.ic_video) {
                            sheet.dialog.dismiss()
                            activity.downloads.request(
                                url,
                                activity.userAgent(),
                                null,
                                null,
                                video = true,
                            )
                        }
                        .apply {
                            layoutParams =
                                android.widget.LinearLayout.LayoutParams(-1, -2).apply {
                                    bottomMargin = Ui.dp(activity, 8)
                                }
                        }
                )
            }
            sheet.body.addView(ScrollView(activity).apply { addView(list) })
            sheet.show()
        }
    }

    companion object {
        fun isDirect(address: String, mime: String?): Boolean {
            val path = address.toUri().path.orEmpty()
            if (Regex("\\.(m3u8|mpd)$", RegexOption.IGNORE_CASE).containsMatchIn(path)) return false
            if (mime?.startsWith("video/") == true) return true
            return (mime == null || mime == "application/octet-stream") &&
                Regex("\\.(mp4|m4v|webm|ogv|mov|3gp|3g2|mkv|avi)$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(path)
        }
    }
}
