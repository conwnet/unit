package net.conw.unit.browser

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import net.conw.unit.BrowserActivity
import net.conw.unit.data.BrowserStore

/** Private tab previews cross processes in memory, without launching an Activity or saving URLs. */
internal class TabSessions(private val activity: BrowserActivity) {
    data class Snapshot(val pages: List<BrowserStore.Page>, val selected: Int) {
        fun bundle() =
            Bundle().apply {
                putStringArrayList("urls", ArrayList(pages.map { it.url }))
                putStringArrayList("titles", ArrayList(pages.map { it.title }))
                putInt("selected", selected)
            }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var alive = true
    private val action = "${activity.packageName}.TABS"
    private val permission = "${activity.packageName}.permission.TABS"
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (
                    !alive ||
                        !isOrderedBroadcast ||
                        intent.getBooleanExtra("incognito", false) != activity.isIncognito
                )
                    return
                val snapshot =
                    perform(
                        requireNotNull(intent.getStringExtra("command")),
                        intent.getIntExtra("index", -1),
                    )
                setResultCode(Activity.RESULT_OK)
                setResultExtras(snapshot.bundle())
            }
        }

    init {
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(action),
            permission,
            handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun perform(command: String, index: Int): Snapshot {
        when (command) {
            "close" -> activity.closeTab(index)
            "close_all" -> activity.closeAll()
            "exit" -> {
                activity.finishBrowsing()
                return Snapshot(emptyList(), 0)
            }
        }
        return Snapshot(
            activity.tabs.map { BrowserStore.Page(it.url, it.title) },
            activity.selectedIndex,
        )
    }

    fun request(
        incognito: Boolean,
        command: String = "snapshot",
        index: Int = -1,
        callback: (Snapshot) -> Unit,
    ) {
        if (incognito == activity.isIncognito) {
            callback(perform(command, index))
            return
        }
        if (command == "snapshot" && !incognito) {
            callback(savedNormal())
            return
        }
        if (incognito && activity.store.get("private_count", "0") == "0") {
            callback(Snapshot(emptyList(), 0))
            return
        }
        val result =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (!alive) return
                    val data = getResultExtras(false)
                    if (resultCode == Activity.RESULT_OK && data != null) {
                        val urls = requireNotNull(data.getStringArrayList("urls"))
                        val titles = requireNotNull(data.getStringArrayList("titles"))
                        check(urls.size == titles.size)
                        callback(
                            Snapshot(
                                urls.mapIndexed { index, url ->
                                    BrowserStore.Page(url, titles[index])
                                },
                                data.getInt("selected"),
                            )
                        )
                    } else
                        callback(
                            if (incognito) Snapshot(emptyList(), 0)
                            else closeSavedNormal(command, index)
                        )
                }
            }
        // Ordered delivery reaches cached processes immediately on Android 14+.
        activity.sendOrderedBroadcast(
            Intent(action)
                .setPackage(activity.packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra("incognito", incognito)
                .putExtra("command", command)
                .putExtra("index", index),
            permission,
            result,
            handler,
            Activity.RESULT_CANCELED,
            null,
            null,
        )
    }

    private fun savedNormal(): Snapshot =
        activity.store.session().let { Snapshot(it.pages, it.selected) }

    /** A stopped normal process has only its saved session, so no WebViews need to be closed. */
    private fun closeSavedNormal(command: String, index: Int): Snapshot {
        val saved = savedNormal()
        if (command == "exit") return saved
        val pages = saved.pages.toMutableList()
        var selected = saved.selected
        if (command == "close_all") pages.clear()
        else if (command == "close" && index in pages.indices) {
            pages.removeAt(index)
            if (index < selected) selected--
        }
        if (pages.isEmpty()) {
            val home = activity.preferences.startUrl()
            pages.add(BrowserStore.Page(home, "Home"))
        }
        selected = selected.coerceIn(0, pages.lastIndex)
        activity.store.session(pages, selected)
        return Snapshot(pages, selected)
    }

    fun destroy() {
        alive = false
        activity.unregisterReceiver(receiver)
        handler.removeCallbacksAndMessages(null)
    }
}
