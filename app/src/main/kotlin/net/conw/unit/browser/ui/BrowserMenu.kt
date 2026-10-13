package net.conw.unit.browser.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.doOnLayout
import kotlin.math.abs
import kotlin.math.roundToInt
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.browser.page.BrowserPageTools
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.proxy.ProxyDialog
import net.conw.unit.ui.Ui

internal class BrowserMenu(private val activity: BrowserActivity, private val root: FrameLayout) {
    private val popup =
        BrowserPopup(activity, root, anchor = activity.toolbar.view.findViewById(R.id.more))
    private var page = 0

    fun show() {
        activity.proxyNotice.dismiss()
        if (dismiss()) return
        activity.toolbar.cancelEditing()
        activity.dismissPopups()
        activity.current()?.web?.clearFocus()
        activity
            .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(root.windowToken, 0)
        val width = minOf(dp(560), root.width - dp(24))
        val columns =
            when {
                width >= dp(330) -> 5
                width >= dp(270) -> 4
                else -> 3
            }
        val pageWidth = width - dp(16)
        val panel =
            Ui.column(activity).apply {
                id = R.id.menu_panel
                setPadding(dp(8), dp(8), dp(8), dp(8))
                accessibilityPaneTitle = activity.getString(R.string.more)
            }
        val pages = actions().chunked(columns * 2)
        val pager = MenuPager(activity, pageWidth, pages.size)
        val strip = LinearLayout(activity)
        pages.forEach { items ->
            val page = Ui.column(activity)
            repeat(2) { row ->
                val line = LinearLayout(activity)
                repeat(columns) { column ->
                    val item = items.getOrNull(row * columns + column)
                    line.addView(
                        if (item == null) View(activity) else cell(item),
                        LinearLayout.LayoutParams(0, dp(if (row == 0) 72 else 64), 1f),
                    )
                }
                page.addView(line)
            }
            strip.addView(page, LinearLayout.LayoutParams(pageWidth, -2))
        }
        pager.addView(strip)
        panel.addView(pager, LinearLayout.LayoutParams(-1, dp(136)))
        if (pages.size > 1) {
            val indicators = LinearLayout(activity).apply { gravity = Gravity.CENTER }
            val dots =
                pages.indices.map { index ->
                    FrameLayout(activity).apply {
                        contentDescription = activity.getString(R.string.menu_page, index + 1)
                        isFocusable = true
                        setOnClickListener { pager.showPage(index) }
                        addView(
                            View(activity).apply {
                                background = Ui.background(activity, R.color.accent, 3)
                            },
                            FrameLayout.LayoutParams(dp(6), dp(6), Gravity.CENTER),
                        )
                        indicators.addView(this, LinearLayout.LayoutParams(dp(28), dp(16)))
                    }
                }
            pager.pageChanged = { selected ->
                page = selected
                dots.forEachIndexed { index, dot ->
                    dot.alpha = if (index == selected) 1f else .22f
                }
            }
            panel.addView(indicators)
        }
        pager.doOnLayout { pager.showPage(page, smooth = false) }
        popup.show(panel, width)
    }

    fun dismiss(): Boolean = popup.dismiss()

    private fun cell(action: Action): View =
        Ui.column(activity).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(3), dp(10), dp(3), dp(6))
            val color = if (action.active == true) R.color.accent else R.color.text
            addView(
                ImageView(activity).apply {
                    setImageResource(action.icon)
                    imageTintList =
                        android.content.res.ColorStateList.valueOf(activity.getColor(color))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(23), dp(23)),
            )
            addView(
                Ui.text(activity, action.label, 12, color).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    includeFontPadding = false
                    setAutoSizeTextTypeUniformWithConfiguration(
                        10,
                        12,
                        1,
                        TypedValue.COMPLEX_UNIT_SP,
                    )
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(-1, dp(20)).apply { topMargin = dp(6) },
            )
            background =
                Ui.ripple(
                    activity,
                    if (action.active == true) R.color.surface_variant else R.color.surface,
                    12,
                )
            contentDescription = activity.getString(action.label)
            if (android.os.Build.VERSION.SDK_INT >= 30 && action.active != null)
                stateDescription =
                    activity.getString(if (action.active) R.string.on else R.string.off)
            isEnabled = action.enabled
            alpha = if (isEnabled) 1f else .3f
            isFocusable = isEnabled
            setOnClickListener {
                dismiss()
                action.run()
            }
        }

    private fun actions(): List<Action> {
        val fullscreen = activity.fullscreen.isFullscreen
        val webpage = UrlUtils.isWeb(activity.currentUrl())
        val loading = activity.current()?.web?.progress?.let { it < 100 } == true
        return listOf(
            Action(R.string.bookmarks, R.drawable.ic_bookmark) { activity.dialogs.pages(true) },
            Action(R.string.history, R.drawable.ic_history, enabled = !activity.isIncognito) {
                activity.dialogs.pages(false)
            },
            Action(R.string.downloads, R.drawable.ic_download) { activity.downloads.show() },
            Action(
                R.string.desktop,
                R.drawable.ic_desktop,
                active = activity.preferences.desktop,
            ) {
                activity.preferences.desktop = !activity.preferences.desktop
                activity.current()?.reload()
            },
            Action(R.string.zoom, R.drawable.ic_zoom_in, enabled = webpage && !loading) {
                activity.zoom.show()
            },
            Action(R.string.proxy, R.drawable.ic_proxy, active = activity.proxy.enabled) {
                ProxyDialog(activity).show()
            },
            Action(
                R.string.fullscreen,
                if (fullscreen) R.drawable.ic_exit_fullscreen else R.drawable.ic_fullscreen,
                active = fullscreen,
            ) {
                activity.fullscreen.toggle()
            },
            Action(R.string.find, R.drawable.ic_find, enabled = webpage) {
                activity.toolbar.find()
            },
            Action(
                if (loading) R.string.stop else R.string.reload,
                if (loading) R.drawable.ic_close else R.drawable.ic_reload,
            ) {
                activity.current()?.let { if (loading) it.web?.stopLoading() else it.reload() }
            },
            Action(R.string.settings, R.drawable.ic_settings) { BrowserSettings(activity).show() },
            Action(R.string.scan, R.drawable.ic_scan) { activity.requests.scan() },
            Action(
                R.string.reader,
                R.drawable.ic_reader,
                active = activity.reader.enabled,
                enabled = !loading,
            ) {
                activity.reader.toggle()
            },
            Action(
                R.string.theme,
                when (activity.preferences.theme) {
                    BrowserPreferences.Theme.SYSTEM -> R.drawable.ic_theme
                    BrowserPreferences.Theme.LIGHT -> R.drawable.ic_theme_light
                    BrowserPreferences.Theme.DARK -> R.drawable.ic_theme_dark
                },
            ) {
                chooseTheme()
            },
            Action(
                R.string.play_on,
                R.drawable.ic_play_on,
                active = activity.preferences.playOn,
            ) {
                activity.togglePlayOn()
            },
            Action(
                R.string.text,
                R.drawable.ic_text_size,
                enabled = webpage && !loading,
            ) {
                activity.textSize.show()
            },
            Action(R.string.debug, R.drawable.ic_debug, enabled = activity.current()?.web != null) {
                BrowserPageTools(activity).debug()
            },
            Action(
                R.string.export,
                R.drawable.ic_pdf,
                enabled =
                    webpage &&
                        activity.current()?.isHome != true &&
                        !loading &&
                        !activity.pdf.isBusy,
            ) {
                activity.pdf.export()
            },
            Action(R.string.share, R.drawable.ic_share, enabled = webpage) {
                activity.requests.sharePage()
            },
            Action(R.string.exit, R.drawable.ic_exit) { activity.exit() },
        )
    }

    private fun chooseTheme() {
        val themes = BrowserPreferences.Theme.entries
        val labels =
            themes.map { theme ->
                activity.getString(
                    when (theme) {
                        BrowserPreferences.Theme.SYSTEM -> R.string.theme_system
                        BrowserPreferences.Theme.LIGHT -> R.string.theme_light
                        BrowserPreferences.Theme.DARK -> R.string.theme_dark
                    }
                )
            }
        val adapter =
            ArrayAdapter(
                Ui.dialogContext(activity),
                android.R.layout.simple_list_item_single_choice,
                labels,
            )
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(R.string.theme)
                .setSingleChoiceItems(adapter, activity.preferences.theme.ordinal) { dialog, which ->
                    activity.preferences.theme = themes[which]
                    dialog.dismiss()
                    activity.syncTheme()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
    }

    private data class Action(
        val label: Int,
        val icon: Int,
        val active: Boolean? = null,
        val enabled: Boolean = true,
        val run: () -> Unit,
    )

    private fun dp(value: Int) = Ui.dp(activity, value)
}

@SuppressLint("ViewConstructor") // Created only by BrowserMenu; it is not an XML-inflated view.
private class MenuPager(
    activity: BrowserActivity,
    private val pageWidth: Int,
    private val pageCount: Int,
) : HorizontalScrollView(activity) {
    var pageChanged: ((Int) -> Unit)? = null
    private var page = 0
    private var startPage = 0
    private var handledFling = false
    private val minimumFling = ViewConfiguration.get(activity).scaledMinimumFlingVelocity
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    fun showPage(index: Int, smooth: Boolean = true) {
        page = index.coerceIn(0, pageCount - 1)
        if (smooth) smoothScrollTo(page * pageWidth, 0) else scrollTo(page * pageWidth, 0)
        pageChanged?.invoke(page)
    }

    override fun fling(velocityX: Int) {
        handledFling = true
        val target =
            if (abs(velocityX) >= minimumFling) startPage + if (velocityX > 0) 1 else -1
            else (scrollX.toFloat() / pageWidth).roundToInt()
        showPage(target)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            startPage = page
            handledFling = false
            downX = event.x
            downY = event.y
        }
        val handled = super.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP && !handledFling)
            showPage((scrollX.toFloat() / pageWidth).roundToInt())
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) showPage(page)
        if (
            event.actionMasked == MotionEvent.ACTION_UP &&
                abs(event.x - downX) < touchSlop &&
                abs(event.y - downY) < touchSlop
        )
            performClick()
        return handled
    }

    override fun performClick(): Boolean = super.performClick()

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> {
                showPage(page + 1)
                return true
            }
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> {
                showPage(page - 1)
                return true
            }
        }
        return super.performAccessibilityAction(action, arguments)
    }
}
