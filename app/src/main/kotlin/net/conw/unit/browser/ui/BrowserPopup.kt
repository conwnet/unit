package net.conw.unit.browser.ui

import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

/** Transparent, dismissible tool overlays never dim or resize the webpage. */
internal class BrowserPopup(
    private val activity: BrowserActivity,
    private val root: FrameLayout,
    private val anchor: View? = null,
) {
    private var layer: FrameLayout? = null
    private var blocksPage = false
    private val content = root.findViewById<View>(R.id.content)
    var onDismiss: (() -> Unit)? = null

    init {
        activity.toolbar.view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> position() }
    }

    val isShowing
        get() = layer != null

    fun show(panel: View, width: Int, allowPageInteraction: Boolean = false) {
        dismiss()
        val overlay =
            FrameLayout(activity).apply {
                if (!allowPageInteraction)
                    setOnClickListener {
                        dismiss()
                        activity.pageTouched()
                    }
            }
        panel.apply {
            background = Ui.background(activity, R.color.surface, 20)
            elevation = Ui.dp(activity, 8).toFloat()
            clipToOutline = true
            isClickable = true
        }
        val horizontalGravity = if (anchor == null) Gravity.CENTER_HORIZONTAL else Gravity.END
        overlay.addView(
            panel,
            FrameLayout.LayoutParams(width, -2, Gravity.BOTTOM or horizontalGravity).apply {
                bottomMargin = Ui.dp(activity, 8)
            },
        )
        layer = overlay
        blocksPage = !allowPageInteraction
        if (blocksPage)
            content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        activity.fullscreen.hold()
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        position()
        panel.alpha = 0f
        panel.translationY = Ui.dp(activity, 8).toFloat()
        panel.animate().alpha(1f).translationY(0f).setDuration(140).start()
    }

    private fun position() {
        val overlay = layer ?: return
        val params = overlay.layoutParams as FrameLayout.LayoutParams
        val bottom = root.height - activity.toolbar.view.top
        if (params.bottomMargin != bottom) {
            params.bottomMargin = bottom
            overlay.layoutParams = params
        }
        val anchor = anchor ?: return
        val bounds = Rect()
        anchor.getDrawingRect(bounds)
        root.offsetDescendantRectToMyCoords(anchor, bounds)
        val panel = overlay.getChildAt(0)
        val panelParams = panel.layoutParams as FrameLayout.LayoutParams
        val margin = Ui.dp(activity, 12)
        val end =
            if (root.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.left
            else root.width - bounds.right
        val endMargin = end.coerceIn(margin, maxOf(margin, root.width - panelParams.width - margin))
        if (panelParams.marginEnd != endMargin) {
            panelParams.marginEnd = endMargin
            panel.layoutParams = panelParams
        }
    }

    fun dismiss(): Boolean {
        val overlay = layer ?: return false
        layer = null
        overlay.getChildAt(0)?.animate()?.cancel()
        root.removeView(overlay)
        if (blocksPage) content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        blocksPage = false
        activity.fullscreen.release()
        onDismiss?.invoke()
        return true
    }
}
