package net.conw.unit.proxy

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.view.isVisible
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.ui.Ui

internal class ProxyNotice(private val activity: BrowserActivity, private val root: FrameLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private var notice: View? = null

    fun show(enabled: Boolean) {
        dismiss()
        val tab = activity.current()
        val bar =
            LinearLayout(activity).apply {
                id = R.id.proxy_notice
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(4), dp(6), dp(4))
                background = Ui.background(activity, R.color.surface, 16)
                elevation = dp(8).toFloat()
            }
        bar.addView(
            Ui.text(
                activity,
                if (enabled) R.string.proxy_enabled_notice else R.string.proxy_disabled_notice,
                14,
                R.color.text,
            ),
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (UrlUtils.isWeb(tab?.url))
            bar.addView(
                Button(activity).apply {
                    setText(R.string.reload)
                    isAllCaps = false
                    setTextColor(activity.getColor(R.color.accent))
                    background = Ui.ripple(activity, R.color.surface, 10)
                    setOnClickListener {
                        dismiss()
                        if (activity.current() === tab) tab?.reload()
                    }
                },
                LinearLayout.LayoutParams(-2, dp(44)),
            )
        notice = bar
        root.addView(
            bar,
            FrameLayout.LayoutParams(
                    minOf(dp(560), root.width - dp(24)),
                    -2,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
                .apply {
                    bottomMargin =
                        dp(8) +
                            if (activity.toolbar.view.isVisible)
                                root.height - activity.toolbar.view.top
                            else 0
                },
        )
        handler.postDelayed(::dismiss, 7000)
    }

    fun dismiss() {
        handler.removeCallbacksAndMessages(null)
        notice?.let(root::removeView)
        notice = null
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
