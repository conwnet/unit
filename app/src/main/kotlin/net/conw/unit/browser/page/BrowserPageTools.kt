package net.conw.unit.browser.page

import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.ui.BrowserSheet
import net.conw.unit.ui.Ui
import org.json.JSONObject

internal class BrowserPageTools(private val activity: BrowserActivity) {
    fun debug() = BrowserDebug(activity).show()

    fun source() {
        val tab = activity.current() ?: return
        val script =
            """(function(){const html=(document.doctype?new XMLSerializer().serializeToString(document.doctype)+'\n':'')+document.documentElement.outerHTML;return {html:html.slice(0,250000),truncated:html.length>250000};})()"""
        tab.evaluate(script) { result ->
            val data = runCatching { JSONObject(result) }.getOrNull()
            if (data == null) {
                activity.message(R.string.source_unavailable)
                return@evaluate
            }
            val html = data.optString("html")
            if (html.isEmpty()) {
                activity.message(R.string.source_unavailable)
                return@evaluate
            }
            val sheet = BrowserSheet(activity, R.string.source)
            val text =
                codeText().apply {
                    id = R.id.page_source
                    setPadding(dp(8), dp(4), dp(8), dp(8))
                    this.text = html
                }
            sheet.body.addView(ScrollView(activity).apply { addView(text) })
            sheet.footer.addView(
                Ui.text(
                    activity,
                    if (data.optBoolean("truncated")) R.string.source_truncated
                    else R.string.current_html,
                    12,
                    R.color.muted,
                )
            )
            sheet.actions.addView(
                Ui.iconButton(activity, R.drawable.ic_copy, R.string.copy_source) {
                    Ui.copy(activity, R.string.source, html)
                }
            )
            sheet.show()
        }
    }

    private fun codeText() =
        TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(activity.getColor(R.color.text))
            setTextIsSelectable(true)
        }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
