package net.conw.unit.data

import net.conw.unit.browser.UrlUtils

/** Persisted browsing preferences apply on the next page load. */
internal class BrowserPreferences(private val store: BrowserStore) {
    var searchEngine: String
        get() = store.get("search", "Bing")
        set(value) = store.put("search", value)

    var homePage: String
        get() = store.get("start_url", "")
        set(value) = store.put("start_url", value)

    var userAgent: String
        get() = store.get("user_agent", "")
        set(value) = store.put("user_agent", value)

    var languages: String
        get() = store.get("languages", "")
        set(value) = store.put("languages", value)

    var desktop: Boolean
        get() = store.flag("desktop")
        set(value) = store.put("desktop", value)

    var reader: Boolean
        get() = store.flag("reader")
        set(value) = store.put("reader", value)

    var playOn: Boolean
        get() = store.flag("play_on")
        set(value) = store.put("play_on", value)

    var textSize: Int
        get() = store.get("text_size", "100").toIntOrNull()?.coerceIn(TEXT_SIZE_RANGE) ?: 100
        set(value) = store.put("text_size", value.coerceIn(TEXT_SIZE_RANGE).toString())

    fun startUrl(incognito: Boolean = false): String {
        if (incognito) return UrlUtils.HOME
        val home = homePage
        return if (home.isBlank()) UrlUtils.HOME else UrlUtils.resolve(home, searchEngine)
    }

    companion object {
        val TEXT_SIZE_RANGE = 50..250
        val SEARCH_ENGINES = arrayOf("Bing", "Baidu", "Google")
    }
}
