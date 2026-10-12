package net.conw.unit.browser.ui

import android.widget.FrameLayout
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.data.BrowserPreferences

/** Text zoom is independent of page zoom; other tabs adopt it on their next load. */
internal class BrowserTextSize(private val activity: BrowserActivity, root: FrameLayout) {
    private val panel =
        BrowserAdjustment(
            activity,
            root,
            R.string.text_size,
            R.id.text_size_slider,
            BrowserPreferences.TEXT_SIZE_RANGE,
            R.string.reset_text_size,
            R.string.close_text_size,
        )

    val isShowing
        get() = panel.isShowing

    fun show() {
        val tab = activity.current() ?: return
        val web = tab.web ?: return
        activity.toolbar.cancelEditing()
        activity.dismissPopups()
        fun apply(percent: Int) {
            if (tab.web !== web || activity.current() !== tab) return
            web.settings.textZoom = percent
            panel.display(percent)
            activity.preferences.textSize = percent
        }
        panel.show(web.settings.textZoom, changed = ::apply, reset = { apply(100) })
    }

    fun dismiss(): Boolean = panel.dismiss()
}
