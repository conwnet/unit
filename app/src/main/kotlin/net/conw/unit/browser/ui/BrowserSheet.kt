package net.conw.unit.browser.ui

import android.app.Dialog
import android.database.DataSetObserver
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ListView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isEmpty
import androidx.core.view.isGone
import androidx.core.view.isVisible
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

internal class BrowserSheet(private val activity: BrowserActivity, titleId: Int) {
    val dialog = Dialog(activity)
    val body = FrameLayout(activity)
    val actions = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    val footer =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
    var onDismiss: (() -> Unit)? = null
    private val title = activity.getString(titleId)
    private val heading =
        Ui.text(activity, title, 22, R.color.text).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }
    private val header =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(12))
        }
    private var searchRow: LinearLayout? = null
    private var searchInput: BrowserInput? = null
    private var compact = false
    private var listAdapter: ListAdapter? = null
    private val resizeContent = Runnable { if (dialog.isShowing && compact) resize() }
    private val contentObserver =
        object : DataSetObserver() {
            override fun onChanged() {
                if (!dialog.isShowing || !compact) return
                body.removeCallbacks(resizeContent)
                body.post(resizeContent)
            }

            override fun onInvalidated() = onChanged()
        }
    private var visibleHeight = 0
    private val visibleFrame = Rect()
    private val visibleResize =
        ViewTreeObserver.OnGlobalLayoutListener {
            activity.window.decorView.getWindowVisibleDisplayFrame(visibleFrame)
            val height = visibleFrame.height()
            if (dialog.isShowing && height > 0 && height != visibleHeight) {
                visibleHeight = height
                resize()
            }
        }
    private val keyboard = activity.getSystemService(InputMethodManager::class.java)
    private val searchBack =
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { hideSearch() } else null

    init {
        dialog.setOwnerActivity(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column =
            Ui.column(activity).apply {
                id = R.id.sheet
                background = Ui.background(activity, R.color.surface, 24)
                clipToOutline = true
                accessibilityPaneTitle = title
            }
        header.addView(heading, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(actions)
        header.addView(
            Ui.iconButton(
                activity,
                R.drawable.ic_close,
                activity.getString(R.string.close_panel, title),
            ) {
                dialog.dismiss()
            }
        )
        body.setPadding(dp(12), dp(8), dp(12), dp(12))
        body.setBackgroundColor(activity.getColor(R.color.surface))
        column.addView(header)
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(footer)
        dialog.setContentView(column)
        column.viewTreeObserver.addOnGlobalLayoutListener(visibleResize)
        dialog.window?.apply {
            setBackgroundDrawable(Ui.background(activity, R.color.surface, 24))
            setDimAmount(.28f)
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun list(adapter: ListAdapter): ListView {
        listAdapter = adapter
        return Ui.list(activity).apply {
            this.adapter = adapter
            body.addView(this)
        }
    }

    fun search(onQuery: (String) -> Unit) {
        val field =
            BrowserInput(activity).apply {
                id = R.id.panel_search_input
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                setSingleLine(true)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                hint = activity.getString(R.string.search_panel, title)
                contentDescription = hint
                background = null
                setTextColor(activity.getColor(R.color.text))
                setHintTextColor(activity.getColor(R.color.muted))
                setPadding(dp(8), 0, dp(4), 0)
                cancel = ::hideSearch
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEARCH) {
                        keyboard.hideSoftInputFromWindow(windowToken, 0)
                        true
                    } else false
                }
            }
        val clear =
            Ui.iconButton(activity, R.drawable.ic_erase, R.string.clear_search) {
                    field.text.clear()
                }
                .apply {
                    isEnabled = false
                    alpha = .3f
                }
        field.textChanged = { query ->
            clear.isEnabled = query.isNotEmpty()
            clear.alpha = if (clear.isEnabled) 1f else .3f
            onQuery(query)
        }
        val row =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background =
                    Ui.background(activity, R.color.surface, 14).apply {
                        setStroke(dp(1), activity.getColor(R.color.divider))
                    }
                setPadding(dp(12), 0, dp(4), 0)
                visibility = View.GONE
                addView(
                    ImageView(activity).apply {
                        setImageResource(R.drawable.ic_find)
                        imageTintList =
                            android.content.res.ColorStateList.valueOf(
                                activity.getColor(R.color.muted)
                            )
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(22), dp(22)),
                )
                addView(field, LinearLayout.LayoutParams(0, dp(44), 1f))
                addView(clear)
            }
        searchInput = field
        searchRow = row
        header.addView(row, 0, LinearLayout.LayoutParams(0, dp(44), 1f))
        actions.addView(
            Ui.iconButton(
                activity,
                R.drawable.ic_find,
                activity.getString(R.string.search_panel, title),
            ) {
                heading.visibility = View.GONE
                actions.visibility = View.GONE
                row.visibility = View.VISIBLE
                if (Build.VERSION.SDK_INT >= 33)
                    dialog.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        requireNotNull(searchBack),
                    )
                field.requestFocus()
                field.post {
                    if (dialog.isShowing)
                        keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
                }
            },
            0,
        )
    }

    private fun hideSearch(): Boolean {
        val row = searchRow?.takeIf { it.isVisible } ?: return false
        if (Build.VERSION.SDK_INT >= 33)
            dialog.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
                requireNotNull(searchBack)
            )
        searchInput?.let {
            keyboard.hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
            it.text.clear()
        }
        row.visibility = View.GONE
        heading.visibility = View.VISIBLE
        actions.visibility = View.VISIBLE
        return true
    }

    fun show(compact: Boolean = false) {
        this.compact = compact
        body.minimumHeight =
            if (compact) dp(Ui.EMPTY_MIN_HEIGHT_DP) + body.paddingTop + body.paddingBottom else 0
        footer.visibility = if (footer.isEmpty()) View.GONE else View.VISIBLE
        listAdapter?.registerDataSetObserver(contentObserver)
        val observer = activity.window.decorView.viewTreeObserver
        observer.addOnGlobalLayoutListener(visibleResize)
        activity.showDialog(dialog) {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(visibleResize)
            listAdapter?.unregisterDataSetObserver(contentObserver)
            body.removeCallbacks(resizeContent)
            hideSearch()
            onDismiss?.invoke()
        }
        resize()
    }

    private fun resize() {
        val decor = activity.window.decorView
        val width: Int
        val available: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = activity.windowManager.currentWindowMetrics
            val bars =
                metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            width = metrics.bounds.width() - bars.left - bars.right
            available = metrics.bounds.height() - bars.top - bars.bottom
        } else {
            width = decor.width
            val bars = ViewCompat.getRootWindowInsets(decor)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars())
            available =
                activity.resources.displayMetrics.heightPixels -
                    (bars?.top ?: 0) -
                    (bars?.bottom ?: 0)
        }
        val usableHeight = minOf(available, visibleHeight.takeIf { it > 0 } ?: available)
        val maximum = minOf(dp(640), (usableHeight * .86f).toInt())
        val panelWidth = minOf(dp(640), width - dp(32))
        val height =
            if (!compact) maximum
            else {
                val widthSpec =
                    View.MeasureSpec.makeMeasureSpec(panelWidth, View.MeasureSpec.EXACTLY)
                val natural = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                header.measure(widthSpec, natural)
                val footerHeight =
                    if (footer.isGone) 0
                    else {
                        footer.measure(widthSpec, natural)
                        footer.measuredHeight
                    }
                val fixedHeight = header.measuredHeight + footerHeight
                body.measure(
                    widthSpec,
                    View.MeasureSpec.makeMeasureSpec(
                        maxOf(0, maximum - fixedHeight),
                        View.MeasureSpec.AT_MOST,
                    ),
                )
                minOf(maximum, fixedHeight + body.measuredHeight)
            }
        if (compact) body.requestLayout()
        dialog.window?.setLayout(panelWidth, height)
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
