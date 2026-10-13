package net.conw.unit.browser.page

import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.isGone
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.BrowserTab
import net.conw.unit.browser.ui.BrowserSheet
import net.conw.unit.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal class BrowserDebug(private val activity: BrowserActivity) {
    fun show() {
        val tab = activity.current() ?: return
        val web = tab.web ?: return
        Panel(tab, web).show()
    }

    private inner class Panel(private val tab: BrowserTab, private val web: WebView) {
        private val sheet = BrowserSheet(activity, R.string.debug_tools)
        private val page =
            Ui.text(activity, pageTitle(), 12, R.color.muted).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setPadding(dp(2), 0, dp(2), dp(12))
            }
        private val input = createInput()
        private val status =
            Ui.text(activity, R.string.debug_ready, 12, R.color.muted).apply {
                id = R.id.debug_status
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        private val run = createRunButton()
        private val copyCode =
            Ui.iconButton(activity, R.drawable.ic_copy, R.string.copy_script) {
                Ui.copy(activity, R.string.javascript_hint, input.text.toString())
            }
        private val outputHeader = header(R.string.console)
        private val count = outputHeader.getChildAt(0) as TextView
        private val adapter = ConsoleAdapter()
        private val output =
            ListView(activity).apply {
                id = R.id.debug_console
                this.adapter = this@Panel.adapter
                divider = null
                selector = Color.TRANSPARENT.toDrawable()
                isVerticalScrollBarEnabled = true
                transcriptMode = AbsListView.TRANSCRIPT_MODE_NORMAL
                itemsCanFocus = true
                clipToPadding = false
            }
        private val copyOutput =
            Ui.iconButton(activity, R.drawable.ic_copy, R.string.copy_console) {
                Ui.copy(activity, R.string.console, tab.console.text())
            }
        private val clearOutput =
            Ui.iconButton(activity, R.drawable.ic_delete, R.string.clear_console) {
                tab.console.clear()
            }
        private var running = false
        private var scheduled = false
        private var generation = tab.console.generation
        private val render = Runnable { refreshConsole() }

        fun show() {
            sheet.actions.addView(
                Ui.iconButton(activity, R.drawable.ic_source, R.string.view_source) {
                    BrowserPageTools(activity).source()
                }
            )
            sheet.body.setPadding(dp(16), 0, dp(16), 0)
            sheet.body.addView(createContent(), FrameLayout.LayoutParams(-1, -1))
            sheet.footer.setPadding(dp(18), dp(12), dp(16), dp(16))
            sheet.footer.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            sheet.footer.addView(run, LinearLayout.LayoutParams(-2, dp(48)))
            bindActions()
            refreshConsole()
            sheet.show()
            if (adapter.count > 0) output.setSelection(adapter.count - 1)
        }

        private fun createInput() =
            Ui.editText(activity).apply {
                id = R.id.debug_input
                setSingleLine(false)
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                typeface = Typeface.MONOSPACE
                textSize = 14f
                includeFontPadding = false
                setLineSpacing(dp(4).toFloat(), 1f)
                setHorizontallyScrolling(false)
                gravity = Gravity.TOP or Gravity.START
                hint = activity.getString(R.string.javascript_placeholder)
                contentDescription = activity.getString(R.string.javascript_hint)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                background = null
                minimumHeight = 0
                setPadding(dp(14), dp(8), dp(14), dp(12))
            }

        private fun createRunButton() =
            MaterialButton(Ui.dialogContext(activity)).apply {
                id = R.id.debug_run
                setText(R.string.debug_run)
                contentDescription = activity.getString(R.string.run_script)
                isAllCaps = false
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(activity.getColor(R.color.surface))
                backgroundTintList = ColorStateList.valueOf(activity.getColor(R.color.accent))
                setIconResource(R.drawable.ic_run)
                iconTint = ColorStateList.valueOf(activity.getColor(R.color.surface))
                iconSize = dp(18)
                iconPadding = dp(8)
                cornerRadius = dp(12)
                insetTop = 0
                insetBottom = 0
                minimumHeight = dp(48)
                minWidth = dp(112)
            }

        private fun createEditor(): LinearLayout {
            val editor = card(R.color.surface_variant)
            val codeHeader = header(R.string.javascript_hint)
            codeHeader.addView(copyCode)
            codeHeader.addView(
                Ui.iconButton(activity, R.drawable.ic_paste, R.string.paste_script, ::pasteCode)
            )
            editor.addView(codeHeader)
            editor.addView(input, LinearLayout.LayoutParams(-1, 0, 1f))
            input.setOnFocusChangeListener { _, focused ->
                editor.background =
                    Ui.background(activity, R.color.surface_variant, 16).apply {
                        setStroke(
                            dp(1),
                            activity.getColor(if (focused) R.color.accent else R.color.divider),
                        )
                    }
            }
            return editor
        }

        private fun createConsole(): LinearLayout {
            val console = card(R.color.background)
            val empty =
                Ui.empty(
                        activity,
                        activity.getString(R.string.no_console_messages),
                        activity.getString(R.string.debug_empty_hint),
                        R.drawable.ic_debug,
                    )
                    .apply {
                        minimumHeight = 0
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
            val content =
                FrameLayout(activity).apply {
                    addView(output, FrameLayout.LayoutParams(-1, -1))
                    addView(empty, FrameLayout.LayoutParams(-1, -1))
                }
            output.emptyView = empty
            outputHeader.addView(copyOutput)
            outputHeader.addView(clearOutput)
            console.addView(outputHeader)
            console.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
            return console
        }

        private fun createContent(): DebugColumn {
            val editor = createEditor()
            val console = createConsole()
            val workspace =
                Ui.column(activity).apply {
                    addView(
                        editor,
                        LinearLayout.LayoutParams(-1, 0, .43f).apply { bottomMargin = dp(12) },
                    )
                    addView(console, LinearLayout.LayoutParams(-1, 0, .57f))
                }
            return DebugColumn().apply {
                addView(page)
                addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
                var layoutMode = -1
                configure = { width, height ->
                    val wide = width >= dp(480)
                    val compact = height < dp(240)
                    val mode = if (wide) if (compact) 3 else 2 else if (compact) 1 else 0
                    if (mode != layoutMode) {
                        layoutMode = mode
                        page.isGone = compact
                        console.isGone = mode == 1
                        workspace.orientation =
                            if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                        editor.layoutParams =
                            if (wide)
                                LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(12) }
                            else
                                LinearLayout.LayoutParams(-1, 0, if (compact) 1f else .43f).apply {
                                    bottomMargin = if (compact) 0 else dp(12)
                                }
                        console.layoutParams =
                            if (wide) LinearLayout.LayoutParams(0, -1, 1f)
                            else LinearLayout.LayoutParams(-1, 0, .57f)
                    }
                }
            }
        }

        private fun bindActions() {
            input.doAfterTextChanged {
                if (!running) status.setText(R.string.debug_ready)
                updateActions()
            }
            run.setOnClickListener { runScript() }
            tab.console.changed = {
                if (!scheduled) {
                    scheduled = true
                    output.postDelayed(render, 100)
                }
            }
            sheet.onDismiss = {
                tab.console.changed = null
                output.removeCallbacks(render)
            }
        }

        private fun pasteCode() {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
            if (text.isNullOrEmpty()) {
                activity.message(R.string.clipboard_no_text)
                return
            }
            val start = input.selectionStart.coerceAtLeast(0)
            val end = input.selectionEnd.coerceAtLeast(0)
            input.text?.replace(minOf(start, end), maxOf(start, end), text)
            input.requestFocus()
        }

        private fun runScript() {
            val code = input.text.toString()
            if (code.isBlank() || running || tab.web !== web) return
            running = true
            status.setText(R.string.debug_running)
            updateActions()
            activity.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(input.windowToken, 0)
            input.clearFocus()
            val currentGeneration = tab.console.generation
            tab.evaluate(code) { result ->
                if (currentGeneration == tab.console.generation) {
                    tab.console.result(readResult(result))
                    if (sheet.dialog.isShowing) {
                        running = false
                        status.setText(R.string.debug_finished)
                        updateActions()
                    }
                }
            }
        }

        private fun refreshConsole() {
            scheduled = false
            if (generation != tab.console.generation) {
                generation = tab.console.generation
                running = false
                status.setText(R.string.debug_ready)
            }
            val entries = tab.console.entries()
            adapter.submit(entries)
            count.text = activity.getString(R.string.debug_console_count, entries.size)
            enabled(copyOutput, entries.isNotEmpty())
            enabled(clearOutput, entries.isNotEmpty())
            page.text = pageTitle()
            updateActions()
        }

        private fun updateActions() {
            val hasCode = !input.text.isNullOrBlank()
            enabled(copyCode, hasCode)
            enabled(run, hasCode && !running && tab.web === web)
        }

        private fun enabled(view: View, value: Boolean) {
            view.isEnabled = value
            view.alpha = if (value) 1f else .38f
        }

        private fun pageTitle() =
            if (tab.isHome) activity.getString(R.string.home) else tab.url
    }

    private inner class DebugColumn : LinearLayout(activity) {
        var configure: ((Int, Int) -> Unit)? = null

        init {
            orientation = VERTICAL
            isFocusableInTouchMode = true
        }

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            configure?.invoke(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
            super.onMeasure(widthSpec, heightSpec)
        }
    }

    private fun card(color: Int) =
        Ui.column(activity).apply {
            background =
                Ui.background(activity, color, 16).apply {
                    setStroke(dp(1), activity.getColor(R.color.divider))
                }
            clipToOutline = true
        }

    private fun header(title: Int) =
        LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(2), dp(4), dp(2))
            addView(
                Ui.text(activity, title, 14, R.color.text).apply {
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }

    private inner class ConsoleAdapter : BaseAdapter() {
        private var entries = emptyList<PageConsole.Entry>()

        fun submit(value: List<PageConsole.Entry>) {
            if (entries == value) return
            entries = value
            notifyDataSetChanged()
        }

        override fun getCount() = entries.size

        override fun getItem(position: Int) = entries[position]

        override fun getItemId(position: Int) = entries[position].id

        override fun hasStableIds() = true

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
            ((recycled as? ConsoleRow) ?: ConsoleRow()).apply { bind(getItem(position)) }
    }

    private inner class ConsoleRow : LinearLayout(activity) {
        private val badge = Ui.text(activity, "", 11, R.color.accent)
        private val source = Ui.text(activity, "", 10, R.color.muted)
        private val message = Ui.text(activity, "", 13, R.color.text)

        init {
            orientation = VERTICAL
            setPadding(dp(14), dp(12), dp(14), 0)
            badge.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            badge.includeFontPadding = false
            badge.setPadding(dp(8), dp(4), dp(8), dp(4))
            source.typeface = Typeface.MONOSPACE
            source.maxLines = 1
            source.ellipsize = TextUtils.TruncateAt.START
            val metadata = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            metadata.addView(badge)
            metadata.addView(source, LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(metadata)
            message.typeface = Typeface.MONOSPACE
            message.includeFontPadding = false
            message.setLineSpacing(dp(3).toFloat(), 1f)
            message.setTextIsSelectable(true)
            message.setPadding(0, dp(8), 0, dp(12))
            addView(message, LayoutParams(-1, -2))
            addView(
                View(activity).apply { setBackgroundColor(activity.getColor(R.color.divider)) },
                LayoutParams(-1, dp(1)),
            )
        }

        fun bind(entry: PageConsole.Entry) {
            val (label, color) =
                when (entry.kind) {
                    PageConsole.Kind.RESULT -> R.string.debug_result to R.color.accent
                    PageConsole.Kind.WARNING -> R.string.debug_warning to R.color.debug_warning
                    PageConsole.Kind.ERROR -> R.string.debug_error to R.color.debug_error
                    PageConsole.Kind.LOG -> R.string.debug_log to R.color.muted
                    PageConsole.Kind.DEBUG -> R.string.debug_message to R.color.muted
                    PageConsole.Kind.TIP -> R.string.debug_info to R.color.accent
                }
            val tint = activity.getColor(color)
            badge.setText(label)
            badge.setTextColor(tint)
            badge.background =
                Ui.background(activity, R.color.surface_variant, 6).apply {
                    setColor(ColorUtils.setAlphaComponent(tint, 24))
                }
            source.text = entry.source
            if (message.text.toString() != entry.text)
                message.setText(entry.text, TextView.BufferType.SPANNABLE)
            message.isClickable = true
            message.isLongClickable = true
            message.setTextColor(
                activity.getColor(
                    if (entry.kind == PageConsole.Kind.ERROR) R.color.debug_error else R.color.text
                )
            )
        }
    }

    private fun readResult(value: String): String = runCatching {
        when (val parsed = JSONTokener(value).nextValue()) {
            is JSONObject -> parsed.toString(2)
            is JSONArray -> parsed.toString(2)
            else -> parsed.toString()
        }
    }
        .getOrDefault(value)

    private fun dp(value: Int) = Ui.dp(activity, value)
}
