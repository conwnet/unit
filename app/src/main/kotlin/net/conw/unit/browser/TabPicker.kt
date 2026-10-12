package net.conw.unit.browser

import android.graphics.Typeface
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.ui.BrowserSheet
import net.conw.unit.ui.Ui

/** Changing groups only changes this list; choosing or creating a tab activates its profile. */
internal class TabPicker(private val activity: BrowserActivity) {
    private val sheet = BrowserSheet(activity, R.string.tabs)
    private var group = activity.isIncognito
    private var generation = 0
    private var snapshot = TabSessions.Snapshot(emptyList(), 0)
    private var loading = false
    private val list = Ui.list(activity)
    private val normal = mode(activity.getString(R.string.tabs))
    private val incognito = mode(activity.getString(R.string.incognito))
    private val home =
        Ui.iconButton(activity, R.drawable.ic_home, R.string.home) {
            sheet.dialog.dismiss()
            activity.goHome()
        }
    private val closeAll =
        Ui.iconButton(activity, R.drawable.ic_close_tabs, R.string.close_all) { confirmCloseAll() }
    private val adapter =
        object : BaseAdapter() {
            override fun getCount() = maxOf(1, snapshot.pages.size)

            override fun getItem(index: Int) = snapshot.pages.getOrNull(index)

            override fun getItemId(index: Int) = index.toLong()

            override fun getView(index: Int, recycled: View?, parent: ViewGroup): View {
                val page =
                    snapshot.pages.getOrNull(index)
                        ?: return Ui.empty(
                            activity,
                            activity.getString(
                                if (loading) R.string.loading_tabs
                                else if (group) R.string.no_private_tabs else R.string.no_tabs
                            ),
                            activity.getString(R.string.new_tab_hint),
                            R.drawable.ic_tabs,
                        )
                val row =
                    LinearLayout(activity).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        background =
                            Ui.background(activity, R.color.surface, 16).apply {
                                if (index == snapshot.selected)
                                    setStroke(dp(1), activity.getColor(R.color.accent))
                            }
                        setPadding(dp(1), dp(1), dp(6), dp(1))
                    }
                row.addView(
                    Ui.row(
                        activity,
                        page.title.ifBlank { activity.getString(R.string.new_tab) },
                        if (page.url == UrlUtils.HOME) activity.getString(R.string.home)
                        else UrlUtils.host(page.url),
                        icon =
                            if (index == snapshot.selected) R.drawable.ic_check
                            else R.drawable.ic_tabs,
                    ) {
                        sheet.dialog.dismiss()
                        activity.activateTab(group, index)
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                row.addView(
                    Ui.iconButton(
                        activity,
                        R.drawable.ic_close,
                        activity.getString(R.string.close_tab, index + 1),
                    ) {
                        change("close", index)
                    },
                    LinearLayout.LayoutParams(dp(44), dp(48)),
                )
                return row
            }
        }

    fun show() {
        val column = Ui.column(activity)
        val modes =
            LinearLayout(activity).apply {
                background = Ui.background(activity, R.color.surface_variant, 12)
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(normal, LinearLayout.LayoutParams(0, dp(36), 1f))
                addView(incognito, LinearLayout.LayoutParams(0, dp(36), 1f))
            }
        column.addView(
            modes,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            },
        )
        list.adapter = adapter
        list.setPadding(0, 0, 0, dp(8))
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        sheet.body.addView(column)
        sheet.footer.addView(home)
        sheet.footer.addView(closeAll)
        sheet.footer.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        sheet.footer.addView(
            Ui.iconButton(activity, R.drawable.ic_new_tab, R.string.new_tab) {
                sheet.dialog.dismiss()
                activity.activateTab(group)
            }
        )
        normal.setOnClickListener { preview(false) }
        incognito.setOnClickListener { preview(true) }
        sheet.onDismiss = {
            generation++
            snapshot = TabSessions.Snapshot(emptyList(), 0)
            secure(false)
        }
        sheet.show()
        preview(group)
    }

    private fun preview(incognito: Boolean) {
        group = incognito
        secure(group)
        styleMode(normal, !group)
        styleMode(this.incognito, group)
        home.isEnabled = group == activity.isIncognito
        home.alpha = if (home.isEnabled) 1f else .3f
        loading = true
        snapshot = TabSessions.Snapshot(emptyList(), 0)
        render()
        val version = ++generation
        activity.tabSessions.request(group) { result ->
            if (sheet.dialog.isShowing && version == generation) {
                loading = false
                snapshot = result
                render()
            }
        }
    }

    private fun change(command: String, index: Int = -1) {
        val version = generation
        activity.tabSessions.request(group, command, index) { result ->
            if (sheet.dialog.isShowing && version == generation) {
                snapshot = result
                render()
            }
        }
    }

    private fun confirmCloseAll() {
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(if (group) R.string.close_private_title else R.string.close_tabs_title)
                .setMessage(
                    if (group) R.string.close_private_message else R.string.close_tabs_message
                )
                .setPositiveButton(R.string.close) { _, _ -> change("close_all") }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
    }

    private fun render() {
        adapter.notifyDataSetChanged()
        closeAll.isEnabled = snapshot.pages.isNotEmpty()
        closeAll.alpha = if (closeAll.isEnabled) 1f else .3f
    }

    private fun secure(privatePreview: Boolean) {
        val flag = WindowManager.LayoutParams.FLAG_SECURE
        if (activity.isIncognito || privatePreview) {
            sheet.dialog.window?.addFlags(flag)
            activity.window.addFlags(flag)
        } else {
            sheet.dialog.window?.clearFlags(flag)
            activity.window.clearFlags(flag)
        }
    }

    private fun mode(label: String) =
        Ui.text(activity, label, 14, R.color.muted).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            isFocusable = true
        }

    private fun styleMode(view: View, selected: Boolean) {
        view.isSelected = selected
        view.isEnabled = !selected
        view.background =
            Ui.ripple(activity, if (selected) R.color.surface else R.color.surface_variant, 9)
        (view as android.widget.TextView).setTextColor(
            activity.getColor(if (selected) R.color.accent else R.color.muted)
        )
        if (Build.VERSION.SDK_INT >= 30)
            view.stateDescription = if (selected) activity.getString(R.string.selected) else null
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
