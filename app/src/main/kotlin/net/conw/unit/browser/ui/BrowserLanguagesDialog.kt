package net.conw.unit.browser.ui

import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.MultiAutoCompleteTextView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatMultiAutoCompleteTextView
import androidx.core.database.sqlite.transaction
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.browser.BrowserLanguages
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.ui.Ui

internal class BrowserLanguagesDialog(private val activity: BrowserActivity) {
    private val preferences = activity.preferences

    fun show(onSaved: () -> Unit) {
        val appLanguages = BrowserPreferences.AppLanguage.entries
        var appLanguage = preferences.appLanguage
        val language =
            MaterialAutoCompleteTextView(Ui.dialogContext(activity)).apply {
                id = R.id.app_language_select
                inputType = InputType.TYPE_NULL
                setSimpleItems(
                    appLanguages.map { BrowserLanguages.appLanguageName(activity, it) }.toTypedArray()
                )
                setText(BrowserLanguages.appLanguageName(activity, appLanguage), false)
                textSize = 16f
                setTextColor(activity.getColor(R.color.text))
                contentDescription = activity.getString(R.string.app_language)
                setOnItemClickListener { _, _, position, _ ->
                    appLanguage = appLanguages[position]
                }
            }
        val presets =
            listOf(
                Preset(activity.getString(R.string.english), "en-US"),
                Preset(activity.getString(R.string.chinese), "zh-CN"),
                Preset(activity.getString(R.string.spanish), "es-ES"),
                Preset(activity.getString(R.string.french), "fr-FR"),
            )
        val field =
            object : AppCompatMultiAutoCompleteTextView(Ui.dialogContext(activity)) {
                    var textChanged: (() -> Unit)? = null

                    override fun enoughToFilter() = true

                    override fun convertSelectionToString(selectedItem: Any?): CharSequence =
                        (selectedItem as? Preset)?.tag
                            ?: super.convertSelectionToString(selectedItem)

                    override fun onTextChanged(
                        text: CharSequence?,
                        start: Int,
                        before: Int,
                        count: Int,
                    ) {
                        super.onTextChanged(text, start, before, count)
                        textChanged?.invoke()
                    }
                }
                .apply {
                    id = R.id.website_languages_input
                    setSingleLine(true)
                    textSize = 16f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    contentDescription = activity.getString(R.string.website_languages)
                    setTextColor(activity.getColor(R.color.text))
                    setHintTextColor(activity.getColor(R.color.muted))
                    background = null
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    setTokenizer(
                        object : MultiAutoCompleteTextView.CommaTokenizer() {
                            override fun terminateToken(text: CharSequence): CharSequence = text
                        }
                    )
                    setAdapter(
                        object :
                            ArrayAdapter<Preset>(
                                activity,
                                android.R.layout.simple_dropdown_item_1line,
                                presets,
                            ) {
                            override fun getView(
                                position: Int,
                                convertView: View?,
                                parent: ViewGroup,
                            ): View =
                                (super.getView(position, convertView, parent) as TextView).apply {
                                    text = getItem(position)?.let { "${it.label} [${it.tag}]" }
                                    textSize = 15f
                                    setTextColor(activity.getColor(R.color.text))
                                    setPadding(dp(14), 0, dp(14), 0)
                                    minHeight = dp(48)
                                }
                        }
                    )
                    setDropDownBackgroundDrawable(Ui.background(activity, R.color.surface, 14))
                    dropDownVerticalOffset = dp(4)
                    setText(preferences.websiteLanguages)
                }
        val input =
            Ui.input(activity, field, R.string.website_languages).apply {
                isHintEnabled = false
                field.hint = "en-US, zh-CN"
                endIconMode = TextInputLayout.END_ICON_CUSTOM
                setEndIconDrawable(R.drawable.ic_close)
                endIconContentDescription = activity.getString(R.string.clear_website_languages)
                setEndIconOnClickListener { field.text.clear() }
                isEndIconVisible = field.length() > 0
            }
        field.textChanged = {
            input.isEndIconVisible = field.length() > 0
            input.error = null
        }
        val form =
            Ui.form(activity).apply {
                isFocusableInTouchMode = true
                addView(
                    Ui.input(activity, language, R.string.app_language).apply {
                        endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
                    }
                )
                addView(Ui.text(activity, R.string.app_language_hint, 12, R.color.muted))
                addView(
                    Ui.text(activity, R.string.website_languages, 15, R.color.text).apply {
                        setPadding(0, dp(20), 0, dp(8))
                    }
                )
                addView(input)
                addView(Ui.text(activity, R.string.website_languages_hint, 12, R.color.muted))
            }
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.languages)
                .setView(
                    ScrollView(activity).apply {
                        isVerticalScrollBarEnabled = false
                        addView(form)
                    }
                )
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        activity.showDialog(dialog) {
            language.dismissDropDown()
            field.dismissDropDown()
        }
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        fun suggest() {
            field.post {
                if (dialog.isShowing) {
                    field.dropDownWidth = input.width
                    field.showDropDown()
                }
            }
        }
        field.setOnClickListener { suggest() }
        field.setOnFocusChangeListener { _, focused -> if (focused) suggest() }
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val normalized = runCatching {
                BrowserLanguages.normalize(field.text.toString())
            }.getOrNull()
            if (normalized == null)
                input.error = activity.getString(R.string.invalid_website_languages)
            else {
                activity.store.writableDatabase.transaction {
                    preferences.appLanguage = appLanguage
                    preferences.websiteLanguages = normalized
                }
                dialog.dismiss()
                activity.syncLanguages()
                onSaved()
            }
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                save.performClick()
                true
            } else false
        }
    }

    private data class Preset(val label: String, val tag: String) {
        override fun toString() = "$label $tag"
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
