package net.conw.unit.browser.page

import android.webkit.ConsoleMessage

/** Per-tab logs remain in memory and are released with the tab. */
internal class PageConsole {
    enum class Kind { RESULT, LOG, DEBUG, TIP, WARNING, ERROR }

    data class Entry(val id: Long, val kind: Kind, val text: String, val source: String = "")

    private val lines = ArrayDeque<Entry>()
    private var sequence = 0L
    var changed: (() -> Unit)? = null
    var generation = 0
        private set

    fun record(message: ConsoleMessage) {
        val source = message.sourceId().substringAfterLast('/').take(100)
        val location = if (source.isEmpty()) "" else "$source:${message.lineNumber()}"
        append(Kind.valueOf(message.messageLevel().name), message.message(), location)
    }

    fun result(text: String) = append(Kind.RESULT, text)

    private fun append(kind: Kind, text: String, source: String = "") {
        if (lines.size == 200) lines.removeFirst()
        lines.addLast(Entry(++sequence, kind, text.take(4000), source))
        changed?.invoke()
    }

    fun clear() {
        generation++
        lines.clear()
        changed?.invoke()
    }

    fun entries(): List<Entry> = lines.toList()

    fun text(): String = lines.joinToString("\n\n") { entry ->
        val prefix = if (entry.kind == Kind.RESULT) "→" else "[${entry.kind}]"
        val location = if (entry.source.isEmpty()) "" else " · ${entry.source}"
        "$prefix ${entry.text}$location"
    }
}
