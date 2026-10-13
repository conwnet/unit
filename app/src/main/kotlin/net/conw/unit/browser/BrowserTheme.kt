package net.conw.unit.browser

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.data.BrowserStore

/** Applies themes to platform activities and AppCompat dialogs without recreating browser tabs. */
internal object BrowserTheme {
    fun wrap(context: Context): Context {
        val theme = BrowserStore(context).use { BrowserPreferences(it).theme }
        applyDialogTheme(theme)
        // Separate resources preserve the application configuration used by Follow system.
        return context.createConfigurationContext(
            Configuration().apply { uiMode = nightMode(context, theme) }
        )
    }

    @Suppress("DEPRECATION")
    fun apply(context: Context, theme: BrowserPreferences.Theme): Int {
        applyDialogTheme(theme)
        val mode = nightMode(context, theme)
        val config = Configuration(context.resources.configuration)
        if (config.uiMode and Configuration.UI_MODE_NIGHT_MASK != mode) {
            config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or mode
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        return mode
    }

    private fun applyDialogTheme(theme: BrowserPreferences.Theme) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                BrowserPreferences.Theme.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                BrowserPreferences.Theme.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                BrowserPreferences.Theme.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    private fun nightMode(context: Context, theme: BrowserPreferences.Theme): Int =
        when (theme) {
            BrowserPreferences.Theme.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            BrowserPreferences.Theme.DARK -> Configuration.UI_MODE_NIGHT_YES
            BrowserPreferences.Theme.SYSTEM ->
                if (
                    context.applicationContext.resources.configuration.uiMode and
                        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                ) Configuration.UI_MODE_NIGHT_YES
                else Configuration.UI_MODE_NIGHT_NO
        }
}
