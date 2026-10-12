package net.conw.unit.browser.ui

import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.data.BrowserStore
import net.conw.unit.ui.Ui

internal class BrowserDialogs(private val activity: BrowserActivity) {
    fun bookmark(url: String, title: String?, editing: Boolean = false, onSaved: () -> Unit = {}) {
        if (!UrlUtils.isWeb(url)) {
            activity.message(R.string.bookmark_open_page)
            return
        }
        val box = Ui.form(activity)
        val name = Ui.textInput(activity, R.string.title, title ?: url)
        val address = Ui.textInput(activity, R.string.address, url, uri = true)
        box.addView(Ui.input(activity, name, R.string.title))
        box.addView(Ui.input(activity, address, R.string.address))
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(R.string.save_bookmark)
                .setView(box)
                .setPositiveButton(R.string.save) { _, _ ->
                    val updated = address.text.toString().trim()
                    if (!UrlUtils.isWeb(updated)) activity.message(R.string.invalid_address)
                    else {
                        if (editing && url != updated) activity.store.deletePage(true, url)
                        activity.store.savePage(true, updated, name.text.toString())
                        activity.message(R.string.bookmark_saved)
                        activity.refreshHome()
                        onSaved()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
    }

    fun pages(bookmarks: Boolean) {
        val sheet = BrowserSheet(activity, if (bookmarks) R.string.bookmarks else R.string.history)
        val adapter =
            BrowserListAdapter<BrowserStore.Page>(
                activity,
                if (bookmarks) R.string.no_bookmarks else R.string.no_history,
                if (bookmarks) R.string.bookmark_hint else R.string.history_hint,
                searchText = { "${it.title}\n${it.url}" },
                row = {
                    Ui.row(
                        activity,
                        it.title,
                        it.url,
                        icon = if (bookmarks) R.drawable.ic_bookmark else R.drawable.ic_history,
                    )
                },
                emptyIcon = if (bookmarks) R.drawable.ic_bookmark else R.drawable.ic_history,
            )
        fun refresh() {
            adapter.submit(activity.store.pages(bookmarks))
            if (bookmarks) activity.refreshHome()
        }
        val list = sheet.list(adapter)
        sheet.search(adapter::filter)
        if (bookmarks)
            sheet.actions.addView(
                Ui.iconButton(activity, R.drawable.ic_new_tab, R.string.add_bookmark) {
                        val url = activity.currentUrl()
                        if (UrlUtils.isWeb(url)) {
                            activity.store.savePage(true, url, activity.current()?.title)
                            refresh()
                            activity.message(R.string.bookmark_saved)
                        }
                    }
                    .apply {
                        isEnabled = UrlUtils.isWeb(activity.currentUrl())
                        alpha = if (isEnabled) 1f else .3f
                    }
            )
        else
            sheet.actions.addView(
                Ui.iconButton(activity, R.drawable.ic_delete, R.string.clear_history) {
                    activity.showDialog(
                        Ui.dialog(activity)
                            .setTitle(R.string.clear_history_title)
                            .setMessage(R.string.clear_history_message)
                            .setPositiveButton(R.string.clear) { _, _ ->
                                activity.store.deletePage(false, null)
                                refresh()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .create()
                    )
                }
            )
        list.setOnItemClickListener { _, _, index, _ ->
            adapter.getItem(index)?.let {
                sheet.dialog.dismiss()
                activity.navigate(it.url)
            }
        }
        list.setOnItemLongClickListener { _, _, position, _ ->
            val page = adapter.getItem(position)
            if (page == null) false
            else {
                val actions =
                    if (bookmarks) listOf(R.string.open_new_tab, R.string.edit, R.string.delete)
                    else listOf(R.string.open_new_tab, R.string.delete)
                Ui.actions(activity, page.title, actions) { action ->
                    when (action) {
                        R.string.open_new_tab -> {
                            sheet.dialog.dismiss()
                            activity.newTab(page.url, true)
                        }
                        R.string.edit -> bookmark(page.url, page.title, true, ::refresh)
                        R.string.delete -> {
                            activity.store.deletePage(bookmarks, page.url)
                            refresh()
                        }
                    }
                }
                true
            }
        }
        refresh()
        sheet.show(compact = true)
    }

    fun pageContext(link: String?, image: String?) {
        val labels = mutableListOf<Int>()
        if (UrlUtils.isWeb(link ?: image)) labels.add(R.string.open_new_tab)
        if (link != null)
            labels.addAll(
                listOf(
                    R.string.copy_link,
                    R.string.save_bookmark,
                )
            )
        if (image != null) labels.add(R.string.save_image)
        if (labels.isEmpty()) return
        val title = UrlUtils.host(link ?: image).ifEmpty { activity.getString(R.string.image) }
        Ui.actions(activity, title, labels) { action ->
            val url = requireNotNull(if (action == R.string.save_image) image else link ?: image)
            when (action) {
                R.string.open_new_tab -> activity.newTab(url, true)
                R.string.copy_link ->
                    Ui.copy(activity, R.string.copy_link, url, R.string.link_copied)
                R.string.save_bookmark -> bookmark(url, UrlUtils.host(url))
                R.string.save_image ->
                    activity.downloads.request(url, activity.userAgent(), null, null, image = true)
            }
        }
    }
}
