package net.conw.unit.browser.ui

import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import net.conw.unit.BrowserActivity
import net.conw.unit.BuildConfig
import net.conw.unit.R
import net.conw.unit.browser.BrowserLanguages
import net.conw.unit.browser.HomePage
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
        val home = preferences.homePage
        list.removeAllViews()
        list.addView(
            Ui.row(
                activity,
                activity.getString(R.string.search_engine),
                preferences.searchEngine,
                icon = R.drawable.ic_find,
                click = ::chooseSearchEngine,
            )
        )
        list.addView(
            Ui.row(
                activity,
                activity.getString(R.string.home_page),
                if (HomePage.isHtml(home)) activity.getString(R.string.home_page_html)
                else home.ifBlank { activity.getString(R.string.default_home) },
                icon = R.drawable.ic_home,
                click = ::editHomePage,
            )
        )
        list.addView(
            Ui.row(
                activity,
                activity.getString(R.string.user_agent),
                preferences.userAgent.ifEmpty { activity.getString(R.string.default_user_agent) },
                icon = R.drawable.ic_desktop,
                click = ::editUserAgent,
            )
        )
        list.addView(
            View(activity).apply {
                setBackgroundColor(activity.getColor(R.color.divider))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(-1, Ui.dp(activity, 1)).apply {
                marginStart = Ui.dp(activity, 12)
                marginEnd = Ui.dp(activity, 12)
                topMargin = Ui.dp(activity, 6)
                bottomMargin = Ui.dp(activity, 6)
            },
        )
        list.addView(
            Ui.row(
                activity,
                activity.getString(R.string.languages),
                BrowserLanguages.appLanguageName(activity, preferences.appLanguage),
                icon = R.drawable.ic_language,
            ) {
                BrowserLanguagesDialog(activity).show(::render)
            }
        )
        list.addView(
            Ui.row(
                activity,
                activity.getString(R.string.clear_browsing_data),
                activity.getString(R.string.clear_browsing_data_hint),
                icon = R.drawable.ic_delete,
                click = { BrowserDataDialog(activity).show() },
            )
        )
        list.addView(
            Ui.row(
                activity,
                "${activity.getString(R.string.app_name)} ${BuildConfig.VERSION_NAME}",
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
        val input =
            Ui.textInput(activity, R.string.home_page_hint, preferences.homePage, uri = true).apply {
                setSingleLine(false)
                minLines = 4
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
            }
        activity.showDialog(
            Ui.dialog(activity)
                .setTitle(R.string.home_page)
                .setView(
                    Ui.form(activity).apply {
                        addView(
                            Ui.input(activity, input, R.string.home_page_hint).apply {
                                isExpandedHintEnabled = false
                                placeholderText = activity.getString(R.string.home_page_placeholder)
                            }
                        )
                    }
                )
                .setPositiveButton(R.string.save) { _, _ ->
                    val value = input.text.toString().trim()
                    preferences.homePage =
                        if (value.isEmpty() || HomePage.isHtml(value)) value
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
}
