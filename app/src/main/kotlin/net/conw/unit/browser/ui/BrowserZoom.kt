package net.conw.unit.browser.ui

import android.widget.FrameLayout
import kotlin.math.roundToInt
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.BrowserTab

internal class BrowserZoom(private val activity: BrowserActivity, root: FrameLayout) {
    private val panel =
        BrowserAdjustment(
            activity,
            root,
            R.string.zoom,
            R.id.zoom_slider,
            50..300,
            R.string.reset_zoom,
            R.string.close_zoom,
            R.string.page_zoom,
        )
    private var tab: BrowserTab? = null
    private var requestedPercent = 100
    private val viewportScript by lazy {
        activity.assets.open("page-zoom.js").bufferedReader().use { it.readText() }
    }
    private val sync = Runnable { tab?.let { scaleChanged(it, it.scale) } }
    private val apply = Runnable {
        val page = tab
        val control = panel.slider
        if (page != null && control != null)
            page.web?.let { web ->
                val factor =
                    (page.initialScale * requestedPercent / 100f / page.scale).coerceIn(.01f, 100f)
                web.zoomBy(factor)
                control.removeCallbacks(sync)
                control.postDelayed(sync, 120)
            }
    }

    init {
        panel.onDismiss = ::clear
    }

    fun show() {
        val page = activity.current()?.takeUnless { it.isHome } ?: return
        if (page.web == null) return
        page.allowPageZoom(viewportScript) { show(page) }
    }

    private fun show(page: BrowserTab) {
        activity.toolbar.cancelEditing()
        activity.dismissPopups()
        tab = page
        requestedPercent = (page.scale / page.initialScale * 100).roundToInt().coerceIn(50, 300)
        panel.show(
            requestedPercent,
            changed = { percent ->
                requestedPercent = percent
                applyAfter(40)
            },
            reset = {
                requestedPercent = 100
                panel.slider?.progress = 100
                applyAfter(0)
            },
            stopped = { applyAfter(0) },
        )
        scaleChanged(page, page.scale)
    }

    private fun applyAfter(delay: Long) {
        panel.slider?.let {
            it.removeCallbacks(apply)
            it.postDelayed(apply, delay)
        }
    }

    fun scaleChanged(page: BrowserTab, scale: Float) {
        if (tab !== page) return
        val percent = (scale / page.initialScale * 100).roundToInt()
        panel.display(percent)
    }

    private fun clear() {
        panel.slider?.removeCallbacks(apply)
        panel.slider?.removeCallbacks(sync)
        tab = null
    }

    fun dismiss(): Boolean = panel.dismiss()

    val isShowing
        get() = panel.isShowing
}
