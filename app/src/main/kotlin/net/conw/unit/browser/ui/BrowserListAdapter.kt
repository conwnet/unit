package net.conw.unit.browser.ui

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

internal class BrowserListAdapter<T>(
    private val activity: BrowserActivity,
    private val emptyTitle: Int,
    private val emptyHint: Int,
    private val searchText: (T) -> String,
    private val row: (T) -> View,
    private val emptyIcon: Int = R.drawable.ic_find,
) : BaseAdapter() {
    private var source = emptyList<T>()
    private var query = ""
    private var visible = emptyList<T>()

    fun submit(items: List<T>) {
        if (source == items) return
        source = items.toList()
        filter(query)
    }

    fun filter(text: String) {
        query = text.trim()
        visible =
            if (query.isEmpty()) source
            else source.filter { searchText(it).contains(query, ignoreCase = true) }
        notifyDataSetChanged()
    }

    override fun getCount() = maxOf(1, visible.size)

    override fun getItem(position: Int) = visible.getOrNull(position)

    override fun getItemId(position: Int) = position.toLong()

    override fun isEnabled(position: Int) = position in visible.indices

    override fun getView(position: Int, recycled: View?, parent: ViewGroup): View =
        getItem(position)?.let(row)
            ?: Ui.empty(
                activity,
                activity.getString(if (query.isEmpty()) emptyTitle else R.string.no_matches),
                activity.getString(if (query.isEmpty()) emptyHint else R.string.try_search),
                if (query.isEmpty()) emptyIcon else R.drawable.ic_find,
            )
}
