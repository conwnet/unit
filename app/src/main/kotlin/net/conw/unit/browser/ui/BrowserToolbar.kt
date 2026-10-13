package net.conw.unit.browser.ui

import android.content.res.ColorStateList
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.toDrawable
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.BrowserTab
import net.conw.unit.browser.TabPicker
import net.conw.unit.browser.UrlUtils
import net.conw.unit.ui.Ui

internal class BrowserToolbar(private val activity: BrowserActivity, val view: View) {
    private enum class Mode {
        NAVIGATION,
        ADDRESS,
        FIND,
    }

    private var mode = Mode.NAVIGATION
    private val navigation = view.findViewById<View>(R.id.navigation_row)
    private val editor = view.findViewById<View>(R.id.address_row)
    private val finder = view.findViewById<View>(R.id.find_row)
    private val address = view.findViewById<TextView>(R.id.address)
    private val input = view.findViewById<BrowserInput>(R.id.address_input)
    private val findInput = view.findViewById<BrowserInput>(R.id.find_input)
    private val findCount = view.findViewById<TextView>(R.id.find_count)
    private val previousMatch = view.findViewById<ImageButton>(R.id.find_previous)
    private val nextMatch = view.findViewById<ImageButton>(R.id.find_next)
    private var findWeb: WebView? = null
    private val search = Runnable { findWeb?.findAllAsync(findInput.text.toString()) }
    private val tabs = view.findViewById<View>(R.id.tabs)
    private val count = view.findViewById<TextView>(R.id.tab_count)
    private val back = view.findViewById<ImageButton>(R.id.back)
    private val forward = view.findViewById<ImageButton>(R.id.forward)
    private val keyboard = activity.getSystemService(InputMethodManager::class.java)
    private val cancelBack =
        if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { cancelEditing() } else null

    init {
        view.clipToOutline = true
        back.setOnClickListener {
            activity.dismissPopups()
            activity.current()?.back()
        }
        forward.setOnClickListener {
            activity.dismissPopups()
            activity.current()?.forward()
        }
        address.setOnClickListener { edit() }
        tabs.setOnClickListener { TabPicker(activity).show() }
        view.findViewById<View>(R.id.more).setOnClickListener { activity.menu.show() }
        view.findViewById<View>(R.id.cancel_address).setOnClickListener { cancelEditing() }
        view.findViewById<View>(R.id.clear_address).setOnClickListener { input.text.clear() }
        view.findViewById<View>(R.id.go_address).setOnClickListener { submit() }
        view.findViewById<View>(R.id.close_find).setOnClickListener { cancelEditing() }
        previousMatch.setOnClickListener { findWeb?.findNext(false) }
        nextMatch.setOnClickListener { findWeb?.findNext(true) }
        input.cancel = ::cancelEditing
        findInput.cancel = ::cancelEditing
        input.setOnEditorActionListener { _, action, event ->
            if (
                action == EditorInfo.IME_ACTION_GO ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            ) {
                submit()
                true
            } else false
        }
        findInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_NEXT) {
                findWeb?.findNext(true)
                true
            } else false
        }
        findInput.textChanged = { text ->
            if (mode == Mode.FIND) {
                findInput.removeCallbacks(search)
                matches(0, 0)
                if (text.isEmpty())
                    findWeb?.let {
                        it.findAllAsync("")
                        it.clearMatches()
                    }
                else findInput.postDelayed(search, 120)
            }
        }
        matches(0, 0)
    }

    private fun begin(next: Mode, row: View, field: BrowserInput) {
        activity.dismissPopups()
        cancelEditing()
        mode = next
        if (Build.VERSION.SDK_INT >= 33)
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                requireNotNull(cancelBack),
            )
        activity.fullscreen.hold()
        navigation.visibility = View.GONE
        row.visibility = View.VISIBLE
        field.requestFocus()
        field.post {
            if (mode == next) keyboard.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun edit() {
        input.setText(if (activity.current()?.isHome == true) "" else activity.currentUrl())
        begin(Mode.ADDRESS, editor, input)
        input.selectAll()
    }

    fun find() {
        val web = activity.current()?.takeUnless { it.isHome }?.web ?: return
        begin(Mode.FIND, finder, findInput)
        findWeb = web
        findInput.text.clear()
        matches(0, 0)
        web.setFindListener { ordinal, total, done ->
            if (mode == Mode.FIND && findWeb === web && done) {
                if (findInput.text.isEmpty()) matches(0, 0)
                else matches(if (total == 0) 0 else ordinal + 1, total)
            }
        }
    }

    private fun matches(current: Int, total: Int) {
        findCount.text = activity.getString(R.string.match_count, current, total)
        findCount.contentDescription =
            activity.resources.getQuantityString(R.plurals.match_description, total, current, total)
        listOf(previousMatch, nextMatch).forEach {
            it.isEnabled = total > 0
            it.alpha = if (total > 0) 1f else .28f
        }
    }

    private fun submit() {
        val value = input.text.toString()
        activity.navigate(value)
    }

    fun cancelEditing(): Boolean {
        if (mode == Mode.NAVIGATION) return false
        val field = if (mode == Mode.FIND) findInput else input
        mode = Mode.NAVIGATION
        if (Build.VERSION.SDK_INT >= 33)
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(
                requireNotNull(cancelBack)
            )
        findInput.removeCallbacks(search)
        findWeb?.let {
            it.setFindListener(null)
            it.findAllAsync("")
            it.clearMatches()
        }
        findWeb = null
        keyboard.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        editor.visibility = View.GONE
        finder.visibility = View.GONE
        navigation.visibility = View.VISIBLE
        activity.fullscreen.release()
        return true
    }

    fun pageStarted(tab: BrowserTab) {
        if (mode == Mode.FIND && findWeb === tab.web) cancelEditing()
    }

    fun pageTouched() {
        if (mode == Mode.ADDRESS) {
            cancelEditing()
        } else if (mode == Mode.FIND) {
            keyboard.hideSoftInputFromWindow(view.windowToken, 0)
            findInput.clearFocus()
        }
    }

    val isEditing
        get() = mode != Mode.NAVIGATION

    fun render() {
        val tab = activity.current() ?: return
        back.isEnabled = tab.web?.canGoBack() == true
        forward.isEnabled = tab.web?.canGoForward() == true
        back.alpha = if (back.isEnabled) 1f else .28f
        forward.alpha = if (forward.isEnabled) 1f else .28f
        address.text =
            if (tab.isHome) {
                activity.getString(if (activity.isIncognito) R.string.incognito else R.string.home)
            } else tab.title.ifBlank { UrlUtils.host(tab.url) }
        val icon =
            tab.favicon?.let { it.toDrawable(activity.resources) }
                ?: if (tab.isHome) AppCompatResources.getDrawable(activity, R.mipmap.ic_launcher) else null
        icon?.setBounds(0, 0, Ui.dp(activity, 18), Ui.dp(activity, 18))
        address.setCompoundDrawablesRelative(icon, null, null, null)
        address.compoundDrawablePadding = Ui.dp(activity, 6)
        address.setTextColor(
            activity.getColor(if (activity.isIncognito) R.color.accent else R.color.text)
        )
        count.text = if (activity.tabs.size > 99) "99+" else activity.tabs.size.toString()
        count.setTextColor(
            activity.getColor(if (activity.isIncognito) R.color.accent else R.color.text)
        )
        tabs.contentDescription =
            activity.getString(
                if (activity.isIncognito) R.string.private_tabs_description
                else R.string.tabs_description,
                activity.tabs.size,
            )
    }

    fun applyTheme() {
        address.background = Ui.background(activity, R.color.surface_variant, 9)
        listOf(input, findInput).forEach {
            it.setTextColor(activity.getColor(R.color.text))
            it.setHintTextColor(activity.getColor(R.color.muted))
        }
        findCount.setTextColor(activity.getColor(R.color.muted))
        listOf(
                R.id.back,
                R.id.forward,
                R.id.more,
                R.id.cancel_address,
                R.id.clear_address,
                R.id.close_find,
                R.id.find_previous,
                R.id.find_next,
            )
            .forEach { id ->
                view.findViewById<ImageButton>(id).imageTintList =
                    ColorStateList.valueOf(activity.getColor(R.color.text))
            }
        view.findViewById<ImageView>(R.id.tab_icon).imageTintList =
            ColorStateList.valueOf(activity.getColor(R.color.text))
        view.findViewById<ImageButton>(R.id.go_address).imageTintList =
            ColorStateList.valueOf(activity.getColor(R.color.accent))
        render()
    }
}
