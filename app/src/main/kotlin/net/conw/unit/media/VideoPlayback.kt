package net.conw.unit.media

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.view.ViewCompat
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

/** Displays WebView fullscreen content with the shared PlayOn setting. */
internal class VideoPlayback(private val activity: BrowserActivity, private val root: FrameLayout) {
    private var container: FrameLayout? = null
    private var callback: WebChromeClient.CustomViewCallback? = null
    private var playOnButton: ImageButton? = null

    val isShowing
        get() = container != null

    fun show(view: View, hidden: WebChromeClient.CustomViewCallback) {
        if (isShowing) {
            hidden.onCustomViewHidden()
            return
        }
        activity.toolbar.cancelEditing()
        activity.dismissPopups()
        callback = hidden
        val frame =
            object : FrameLayout(activity) {
                override fun dispatchWindowVisibilityChanged(visibility: Int) {
                    if (visibility != View.VISIBLE && activity.preferences.playOn) return
                    super.dispatchWindowVisibilityChanged(visibility)
                }
            }.apply {
                setBackgroundColor(Color.BLACK)
                addView(view, FrameLayout.LayoutParams(-1, -1))
            }
        container = frame
        val button = Ui.iconButton(
            activity,
            R.drawable.ic_play_on,
            R.string.play_on,
            activity::togglePlayOn,
        )
        playOnButton = button
        frame.addView(
            button,
            FrameLayout.LayoutParams(
                    Ui.dp(activity, 44),
                    Ui.dp(activity, 44),
                    Gravity.TOP or Gravity.END,
                )
                .apply {
                    setMargins(0, Ui.dp(activity, 16), Ui.dp(activity, 16), 0)
                },
        )
        root.addView(frame, FrameLayout.LayoutParams(-1, -1))
        activity.fullscreen.setVideo(true)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshPlayOn()
    }

    fun refreshPlayOn() {
        val enabled = activity.preferences.playOn
        playOnButton?.apply {
            isSelected = enabled
            imageTintList = ColorStateList.valueOf(
                if (enabled) activity.getColor(R.color.accent) else Color.WHITE
            )
            ViewCompat.setStateDescription(
                this,
                activity.getString(if (enabled) R.string.on else R.string.off),
            )
        }
    }

    fun hide(): Boolean {
        val frame = container ?: return false
        container = null
        root.removeView(frame)
        playOnButton = null
        val hidden = callback
        callback = null
        hidden?.onCustomViewHidden()
        activity.fullscreen.setVideo(false)
        activity.fullscreen.set(activity.fullscreen.isFullscreen, false)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return true
    }
}
