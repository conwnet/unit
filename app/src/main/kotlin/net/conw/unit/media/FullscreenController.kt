package net.conw.unit.media

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import kotlin.math.hypot
import net.conw.unit.R
import net.conw.unit.data.BrowserStore
import net.conw.unit.ui.Ui

/** Only entering or leaving fullscreen changes the content rectangle. Its toolbar is an overlay. */
internal class FullscreenController(
    private val activity: Activity,
    private val store: BrowserStore,
    private val root: FrameLayout,
    private val content: FrameLayout,
    private val toolbar: View,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val hide = Runnable { hideToolbar() }
    private var fullscreen = false
    private var expanded = false
    private var video = false
    private var holds = 0
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private var imeBottom = 0
    private var dock = store.get("bubble_dock", "right")
    private var positionX = store.get("bubble_x", "1").toFloatOrNull() ?: 1f
    private var positionY = store.get("bubble_y", "0.78").toFloatOrNull() ?: .78f
    private var startX = 0f
    private var startY = 0f
    private var rawX = 0f
    private var rawY = 0f
    private var dragged = false
    private var startedCollapsed = false
    private val bubble = ImageView(activity)

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() {
        bubble.setImageResource(R.drawable.ic_menu)
        bubble.contentDescription = activity.getString(R.string.show_toolbar)
        bubble.tooltipText = bubble.contentDescription
        bubble.isFocusable = true
        bubble.isClickable = true
        bubble.elevation = dp(12).toFloat()
        bubble.setOnClickListener {
            if (dock.isNotEmpty() && !expanded) {
                expanded = true
                placeBubble()
            } else if (toolbar.isVisible) hideToolbar()
            else {
                toolbar.visibility = View.VISIBLE
                bubble.visibility = View.GONE
                touch()
            }
        }
        bubble.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubble.x
                    startY = bubble.y
                    rawX = event.rawX
                    rawY = event.rawY
                    dragged = false
                    startedCollapsed = dock.isNotEmpty() && !expanded
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (
                        !dragged && hypot(dx, dy) > ViewConfiguration.get(activity).scaledTouchSlop
                    ) {
                        dragged = true
                        if (startedCollapsed) {
                            expanded = true
                            placeBubble()
                            startX = bubble.x
                            startY = bubble.y
                        }
                    }
                    if (dragged) {
                        bubble.x = clamp(startX + dx, 0, root.width - dp(48))
                        bubble.y = clamp(startY + dy, 0, root.height - dp(48) - imeBottom)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) bubble.performClick()
                    else {
                        val center = bubble.x + dp(48) / 2f
                        dock =
                            when {
                                center < dp(40) -> "left"
                                center > root.width - dp(40) -> "right"
                                else -> ""
                            }
                        positionX = bubble.x / maxOf(1, root.width - dp(48))
                        positionY = bubble.y / maxOf(1, root.height - dp(48) - imeBottom)
                        expanded = startedCollapsed
                        store.put("bubble_x", positionX.toString())
                        store.put("bubble_y", positionY.toString())
                        store.put("bubble_dock", dock)
                        placeBubble()
                    }
                }
                MotionEvent.ACTION_CANCEL -> placeBubble()
            }
            true
        }
        root.addView(bubble, FrameLayout.LayoutParams(dp(44), dp(48)))
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeBubble() }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars =
                    insets.getInsets(
                        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                    )
                insetLeft = bars.left
                insetTop = bars.top
                insetRight = bars.right
                insetBottom = bars.bottom
                imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                layout()
                insets
            }
        }
        set(store.flag("fullscreen"), false)
    }

    init {
        initialize()
    }

    val isFullscreen
        get() = fullscreen

    fun toggle() = set(!fullscreen, true)

    fun set(value: Boolean, save: Boolean) {
        fullscreen = value
        expanded = false
        handler.removeCallbacks(hide)
        if (save) store.put("fullscreen", value)
        toolbar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        bars()
        layout()
        placeBubble()
    }

    fun refresh() {
        if (fullscreen != store.flag("fullscreen")) set(store.flag("fullscreen"), false)
        else {
            bars()
            layout()
        }
    }

    fun setVideo(value: Boolean) {
        video = value
        bars()
        layout()
        placeBubble()
    }

    fun hold() {
        holds++
        handler.removeCallbacks(hide)
    }

    fun release() {
        holds = maxOf(0, holds - 1)
        touch()
        bars()
    }

    fun touch() {
        handler.removeCallbacks(hide)
        if (fullscreen && holds == 0 && toolbar.isVisible)
            handler.postDelayed(hide, 5000)
    }

    fun pageTouched() {
        if (fullscreen && holds == 0) hideToolbar()
    }

    private fun hideToolbar() {
        if (!fullscreen || holds > 0) return
        handler.removeCallbacks(hide)
        toolbar.visibility = View.GONE
        expanded = false
        placeBubble()
    }

    fun bars(target: Window = activity.window) {
        val hideBars = fullscreen || video
        val night =
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 30) {
            target.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (hideBars) hide(WindowInsets.Type.systemBars())
                else show(WindowInsets.Type.systemBars())
                val light =
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                setSystemBarsAppearance(if (night) 0 else light, light)
            }
        } else {
            @Suppress("DEPRECATION")
            target.decorView.systemUiVisibility =
                if (hideBars) {
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                } else if (night) 0
                else
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun layout() {
        val fill = fullscreen || video
        val params = content.layoutParams as FrameLayout.LayoutParams
        val left = if (fill) 0 else insetLeft
        val right = if (fill) 0 else insetRight
        val top = if (fill) 0 else insetTop
        val toolbarHeight = activity.resources.getDimensionPixelSize(R.dimen.toolbar_height)
        val bottom = maxOf(if (fill) 0 else insetBottom, imeBottom) + if (fill) 0 else toolbarHeight
        if (
            params.leftMargin != left ||
                params.rightMargin != right ||
                params.topMargin != top ||
                params.bottomMargin != bottom
        ) {
            params.setMargins(left, top, right, bottom)
            content.layoutParams = params
        }
        val toolbarParams = toolbar.layoutParams as FrameLayout.LayoutParams
        val bottomPadding = if (fill) 0 else maxOf(0, insetBottom - imeBottom)
        toolbarParams.height = toolbarHeight + bottomPadding
        toolbarParams.bottomMargin = maxOf(if (fill) dp(12) else 0, imeBottom)
        toolbarParams.leftMargin = if (fill) dp(12) else 0
        toolbarParams.rightMargin = if (fill) dp(12) else 0
        toolbar.layoutParams = toolbarParams
        toolbar.setPadding(left, 0, right, bottomPadding)
        toolbar.background = Ui.background(activity, R.color.surface, if (fill) 18 else 0)
        if (video) toolbar.visibility = View.GONE
    }

    private fun placeBubble() {
        bubble.visibility =
            if (fullscreen && !video && toolbar.visibility != View.VISIBLE) View.VISIBLE
            else View.GONE
        val collapsed = dock.isNotEmpty() && !expanded
        val width = dp(if (collapsed) 10 else 48)
        val height = dp(48)
        val params = bubble.layoutParams as FrameLayout.LayoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            bubble.layoutParams = params
        }
        bubble.imageAlpha = if (collapsed) 0 else 210
        bubble.setColorFilter(activity.getColor(R.color.text))
        bubble.alpha = if (collapsed) 1f else .84f
        bubble.elevation = dp(if (collapsed) 0 else 8).toFloat()
        bubble.background =
            if (collapsed) {
                val capsule =
                    GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = width.toFloat()
                        setColor((activity.getColor(R.color.accent) and 0xffffff) or 0x30000000)
                        setStroke(
                            dp(1),
                            (activity.getColor(R.color.muted) and 0xffffff) or 0x70000000,
                        )
                    }
                InsetDrawable(
                    capsule,
                    if (dock == "left") -width else 0,
                    0,
                    if (dock == "right") -width else 0,
                    0,
                )
            } else Ui.background(activity, R.color.surface, 24)
        val padding = if (collapsed) 0 else dp(12)
        bubble.setPadding(padding, padding, padding, padding)
        val maxX = maxOf(0, root.width - width)
        val maxY = maxOf(0, root.height - height - imeBottom)
        val x =
            when (dock) {
                "left" -> 0f
                "right" -> maxX.toFloat()
                else -> positionX * maxX
            }
        bubble.x = clamp(x, 0, maxX)
        bubble.y = clamp(positionY * maxY, dp(12), maxOf(dp(12), maxY - dp(12)))
    }

    private fun dp(value: Int) = Ui.dp(activity, value)

    private fun clamp(value: Float, min: Int, max: Int) =
        value.coerceIn(min.toFloat(), maxOf(min, max).toFloat())

    fun destroy() {
        handler.removeCallbacksAndMessages(null)
    }
}
