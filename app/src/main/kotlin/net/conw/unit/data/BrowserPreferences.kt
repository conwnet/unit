package net.conw.unit.data

import android.content.pm.ActivityInfo
import net.conw.unit.browser.HomePage
import net.conw.unit.browser.UrlUtils

/** Persisted browser settings shared by regular and private browsing. */
internal class BrowserPreferences(private val store: BrowserStore) {
    enum class Theme {
        SYSTEM,
        LIGHT,
        DARK,
    }

    enum class AppLanguage(val tag: String) {
        SYSTEM(""),
        ENGLISH("en"),
    }

    var searchEngine: String
        get() = store.get("search", "Bing")
        set(value) = store.put("search", value)

    var homePage: String
        get() = store.get("home_page", "")
        set(value) = store.put("home_page", value)

    var userAgent: String
        get() = store.get("user_agent", "")
        set(value) = store.put("user_agent", value)

    var appLanguage: AppLanguage
        get() {
            val saved = store.get("app_language")
            return AppLanguage.entries.firstOrNull { it.tag == saved } ?: AppLanguage.SYSTEM
        }
        set(value) = store.put("app_language", value.tag)

    var websiteLanguages: String
        get() = store.get("languages", "")
        set(value) = store.put("languages", value)

    var desktop: Boolean
        get() = store.flag("desktop")
        set(value) = store.put("desktop", value)

    var reader: Boolean
        get() = store.flag("reader")
        set(value) = store.put("reader", value)

    var theme: Theme
        get() {
            val saved = store.get("theme")
            return Theme.entries.firstOrNull { it.name == saved } ?: Theme.SYSTEM
        }
        set(value) = store.put("theme", value.name)

    var orientation: Int
        get() =
            store.get("orientation")?.toIntOrNull()
                ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        set(value) = store.put("orientation", value.toString())

    var playOn: Boolean
        get() = store.flag("play_on")
        set(value) = store.put("play_on", value)

    var textSize: Int
        get() = store.get("text_size", "100").toIntOrNull()?.coerceIn(TEXT_SIZE_RANGE) ?: 100
        set(value) = store.put("text_size", value.coerceIn(TEXT_SIZE_RANGE).toString())

    fun startUrl(incognito: Boolean = false): String {
        if (incognito) return UrlUtils.HOME
        val home = homePage
        return if (home.isBlank() || HomePage.isHtml(home)) UrlUtils.HOME
        else UrlUtils.resolve(home, searchEngine)
    }

    companion object {
        val TEXT_SIZE_RANGE = 50..250
        val SEARCH_ENGINES = arrayOf("Bing", "Baidu", "Google")
    }
}
