package net.conw.unit.browser

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import java.util.Locale
import net.conw.unit.R
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.data.BrowserStore

/** UI resources use the app language; WebView reads the process locale list. */
internal object BrowserLanguages {
    fun wrap(context: Context): Context {
        val language = BrowserStore(context).use { BrowserPreferences(it).appLanguage }
        return context.createConfigurationContext(
            Configuration().apply {
                uiMode = context.resources.configuration.uiMode
                setLocales(appLocales(language))
            }
        )
    }

    fun sync(context: Context, preferences: BrowserPreferences): Boolean {
        applyAppLanguage(context.applicationContext, preferences.appLanguage)
        applyAppLanguage(context, preferences.appLanguage)
        val locales = websiteLocales(preferences.websiteLanguages)
        if (locales.isEmpty || LocaleList.getDefault() == locales) return false
        LocaleList.setDefault(locales)
        return true
    }

    fun header(preferences: BrowserPreferences): String {
        val locales = websiteLocales(preferences.websiteLanguages)
        return (0 until minOf(locales.size(), 10)).joinToString(",") { index ->
            locales[index].toLanguageTag() + if (index == 0) "" else ";q=0.${10 - index}"
        }
    }

    fun appLanguageName(context: Context, language: BrowserPreferences.AppLanguage): String =
        if (language == BrowserPreferences.AppLanguage.SYSTEM)
            context.getString(R.string.language_system)
        else Locale.forLanguageTag(language.tag).let { it.getDisplayLanguage(it) }

    fun normalize(value: String): String {
        if (value.isBlank()) return ""
        val entries = value.split(',')
        require(entries.size <= 10)
        return entries
            .map { entry ->
                val tag = entry.trim()
                require(tag.isNotEmpty() && tag.length <= 64)
                val locale = Locale.Builder().setLanguageTag(tag).build()
                require(locale.language.isNotEmpty() && locale.language != "und")
                locale.toLanguageTag()
            }
            .distinct()
            .joinToString(",")
    }

    @Suppress("DEPRECATION")
    private fun applyAppLanguage(context: Context, language: BrowserPreferences.AppLanguage) {
        val locales = appLocales(language)
        if (context.resources.configuration.locales == locales) return
        val config = Configuration(context.resources.configuration).apply { setLocales(locales) }
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    private fun appLocales(language: BrowserPreferences.AppLanguage): LocaleList =
        if (language == BrowserPreferences.AppLanguage.SYSTEM)
            Resources.getSystem().configuration.locales
        else LocaleList.forLanguageTags(language.tag)

    private fun websiteLocales(value: String): LocaleList =
        if (value.isEmpty()) Resources.getSystem().configuration.locales
        else LocaleList.forLanguageTags(value)
}
