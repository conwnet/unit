package net.conw.unit.browser.ui

import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

internal class BrowserAdjustment(
    private val activity: BrowserActivity,
    private val root: FrameLayout,
    private val title: Int,
    private val sliderId: Int,
    private val range: IntRange,
    private val resetLabel: Int,
    private val closeLabel: Int,
    private val description: Int = title,
) {
    private val popup = BrowserPopup(activity, root)
    private var value: TextView? = null
    var slider: SeekBar? = null
        private set

    private var tracking = false
    var onDismiss: (() -> Unit)? = null

    init {
        popup.onDismiss = {
            onDismiss?.invoke()
            slider = null
            value = null
            tracking = false
        }
    }

    val isShowing
        get() = popup.isShowing

    fun show(
        initial: Int,
        changed: (Int) -> Unit,
        reset: () -> Unit,
        stopped: () -> Unit = {},
    ) {
        dismiss()
        val panel =
            Ui.column(activity).apply {
                setPadding(dp(16), dp(8), dp(12), dp(12))
                accessibilityPaneTitle = activity.getString(title)
            }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            Ui.text(activity, title, 16, R.color.text).apply { gravity = Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(0, dp(40), 1f),
        )
        value = Ui.text(activity, "", 14, R.color.muted).apply { gravity = Gravity.CENTER }
        header.addView(value, LinearLayout.LayoutParams(dp(52), dp(40)))
        header.addView(Ui.iconButton(activity, R.drawable.ic_reset, resetLabel, reset))
        header.addView(
            Ui.iconButton(activity, R.drawable.ic_close, closeLabel) { dismiss() },
            LinearLayout.LayoutParams(dp(40), dp(40)),
        )
        panel.addView(header)
        slider =
            SeekBar(activity).apply {
                id = sliderId
                min = range.first
                max = range.last
                progress = initial.coerceIn(range)
                contentDescription = activity.getString(description)
                progressTintList = ColorStateList.valueOf(activity.getColor(R.color.accent))
                thumbTintList = progressTintList
                setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            bar: SeekBar,
                            percent: Int,
                            fromUser: Boolean,
                        ) {
                            if (fromUser) {
                                value?.text = activity.getString(R.string.percent_format, percent)
                                changed(percent)
                            }
                        }

                        override fun onStartTrackingTouch(bar: SeekBar) {
                            tracking = true
                        }

                        override fun onStopTrackingTouch(bar: SeekBar) {
                            tracking = false
                            stopped()
                        }
                    }
                )
            }
        panel.addView(slider, LinearLayout.LayoutParams(-1, dp(44)))
        display(initial)
        popup.show(panel, minOf(dp(480), root.width - dp(24)), allowPageInteraction = true)
    }

    fun display(percent: Int) {
        value?.text = activity.getString(R.string.percent_format, percent)
        if (!tracking) slider?.progress = percent.coerceIn(range)
    }

    fun dismiss(): Boolean = popup.dismiss()

    private fun dp(value: Int) = Ui.dp(activity, value)
}
