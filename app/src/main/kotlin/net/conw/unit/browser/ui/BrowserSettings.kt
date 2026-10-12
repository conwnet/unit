package net.conw.unit.browser.ui

import android.text.InputFilter
import android.text.InputType
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.UrlUtils
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.ui.Ui

internal class BrowserSettings(private val activity: BrowserActivity) {
    private val preferences = activity.preferences
    private val sheet = BrowserSheet(activity, R.string.settings)
    private val list = Ui.column(activity).apply { setPadding(0, 0, 0, Ui.dp(activity, 8)) }

    fun show() {
        sheet.body.addView(
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            }
        )
        render()
        sheet.show()
    }

    private fun render() {
        list.removeAllViews()
        val browsing = Ui.section(activity, list, R.string.settings_browsing)
        browsing.addView(
            Ui.row(
                activity,
                activity.getString(R.string.search_engine),
                preferences.searchEngine,
                icon = R.drawable.ic_find,
                click = ::chooseSearchEngine,
            )
        )
        browsing.addView(
            Ui.row(
                activity,
                activity.getString(R.string.home_page),
                preferences.homePage.ifEmpty { activity.getString(R.string.default_home) },
                icon = R.drawable.ic_home,
                click = ::editHomePage,
            )
        )
        browsing.addView(
            Ui.row(
                activity,
                activity.getString(R.string.user_agent),
                preferences.userAgent.ifEmpty { activity.getString(R.string.default_user_agent) },
                icon = R.drawable.ic_desktop,
                click = ::editUserAgent,
            )
        )
        browsing.addView(
            Ui.row(
                activity,
                activity.getString(R.string.language),
                preferences.languages.ifEmpty { activity.getString(R.string.system_languages) },
                icon = R.drawable.ic_language,
            ) {
                activity.languages.show(activity, ::render)
            }
        )
        val privacy = Ui.section(activity, list, R.string.settings_privacy)
        privacy.addView(
            Ui.row(
                activity,
                activity.getString(R.string.clear_permissions),
                activity.getString(R.string.permissions_hint),
                icon = R.drawable.ic_erase,
                click = ::clearPermissions,
            )
        )
        val application = Ui.section(activity, list, R.string.settings_application)
        application.addView(
            Ui.row(
                activity,
                activity.getString(R.string.about),
                activity.getString(R.string.project_url),
                icon = R.mipmap.ic_launcher,
                iconTint = null,
            ) {
                sheet.dialog.dismiss()
                activity.newTab(activity.getString(R.string.project_url), true)
            }
        )
    }

    private fun chooseSearchEngine() {
        val engines = BrowserPreferences.SEARCH_ENGINES
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(R.string.search_engine)
                .setItems(engines) { _, which ->
                    preferences.searchEngine = engines[which]
                    activity.message(R.string.search_updated)
                    render()
                }
                .create()
        )
    }

    private fun editHomePage() {
        val input = Ui.textInput(activity, R.string.home_url_hint, preferences.homePage, uri = true)
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(R.string.home_page)
                .setMessage(R.string.home_page_message)
                .setView(
                    Ui.form(activity).apply {
                        addView(Ui.input(activity, input, R.string.home_url_hint))
                    }
                )
                .setPositiveButton(R.string.save) { _, _ ->
                    val value = input.text.toString().trim()
                    preferences.homePage =
                        if (value.isEmpty()) ""
                        else UrlUtils.resolve(value, preferences.searchEngine)
                    activity.message(R.string.home_saved)
                    render()
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
    }

    private fun editUserAgent() {
        val field =
            Ui.textInput(activity, R.string.custom_user_agent, preferences.userAgent).apply {
                id = R.id.user_agent_input
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(2048))
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
        val input = Ui.input(activity, field, R.string.custom_user_agent)
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.user_agent)
                .setMessage(R.string.user_agent_message)
                .setView(
                    Ui.form(activity).apply {
                        addView(input)
                    }
                )
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        activity.showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = field.text.toString().trim()
            if (value.any { it !in ' '..'~' }) {
                input.error = activity.getString(R.string.user_agent_error)
            } else {
                preferences.userAgent = value
                activity.message(R.string.user_agent_saved)
                render()
                dialog.dismiss()
            }
        }
    }

    private fun clearPermissions() {
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(R.string.clear_permissions_title)
                .setPositiveButton(R.string.clear) { _, _ ->
                    activity.sitePermissions.clear()
                    activity.message(R.string.permissions_cleared)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
    }
}
