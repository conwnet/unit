package net.conw.unit.browser

import android.content.Context
import android.content.res.Resources
import android.os.LocaleList
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatMultiAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import java.util.Locale
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.data.BrowserPreferences
import net.conw.unit.data.BrowserStore
import net.conw.unit.ui.Ui

/** WebView reads the process locale list; native UI resources remain English. */
internal class BrowserLanguages(context: Context) {
    private val preferences = BrowserPreferences(BrowserStore(context.applicationContext))

    fun sync(): Boolean {
        val saved = preferences.languages
        val locales =
            if (saved.isEmpty()) Resources.getSystem().configuration.locales
            else LocaleList.forLanguageTags(saved)
        if (locales.isEmpty || LocaleList.getDefault() == locales) return false
        LocaleList.setDefault(locales)
        return true
    }

    fun header(): String {
        sync()
        val locales = LocaleList.getDefault()
        return (0 until minOf(locales.size(), 10)).joinToString(",") { index ->
            locales[index].toLanguageTag() + if (index == 0) "" else ";q=0.${10 - index}"
        }
    }

    fun show(activity: BrowserActivity, onSaved: () -> Unit = {}) {
        val presets =
            listOf(
                Preset(activity.getString(R.string.english), "en-US"),
                Preset(activity.getString(R.string.chinese), "zh-CN"),
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
                    id = R.id.language_input
                    setSingleLine(true)
                    textSize = 16f
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    hint = "en-US, zh-CN"
                    contentDescription = activity.getString(R.string.language)
                    setTextColor(activity.getColor(R.color.text))
                    setHintTextColor(activity.getColor(R.color.muted))
                    background = null
                    setPadding(
                        dp(activity, 16),
                        dp(activity, 16),
                        dp(activity, 16),
                        dp(activity, 16),
                    )
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
                                    text = getItem(position)?.label
                                    textSize = 15f
                                    setTextColor(activity.getColor(R.color.text))
                                    setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
                                    minHeight = dp(activity, 48)
                                }
                        }
                    )
                    setDropDownBackgroundDrawable(Ui.background(activity, R.color.surface, 14))
                    dropDownVerticalOffset = dp(activity, 4)
                    setText(preferences.languages)
                }
        val row =
            Ui.input(activity, field, R.string.language).apply {
                isHintEnabled = false
                field.hint = "en-US, zh-CN"
                endIconMode = TextInputLayout.END_ICON_CUSTOM
                setEndIconDrawable(R.drawable.ic_close)
                endIconContentDescription = activity.getString(R.string.clear_languages)
                setEndIconOnClickListener { field.text.clear() }
                isEndIconVisible = field.length() > 0
            }
        field.textChanged = {
            row.isEndIconVisible = field.length() > 0
            row.error = null
        }
        val column =
            Ui.column(activity).apply {
                setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 8))
                isFocusableInTouchMode = true
                addView(row)
                addView(
                    Ui.text(activity, R.string.language_hint, 12, R.color.muted).apply {
                        setPadding(0, dp(activity, 8), 0, 0)
                    }
                )
            }
        val dialog =
            Ui.dialog(activity)
                .setTitle(R.string.language)
                .setView(column)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        activity.showDialog(dialog) { field.dismissDropDown() }
        fun suggest() {
            field.post {
                if (dialog.isShowing) {
                    field.dropDownWidth = row.width
                    field.showDropDown()
                }
            }
        }
        field.setOnClickListener { suggest() }
        field.setOnFocusChangeListener { _, focused -> if (focused) suggest() }
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val normalized = runCatching { normalize(field.text.toString()) }.getOrNull()
            if (normalized == null)
                row.error = activity.getString(R.string.invalid_languages)
            else {
                preferences.languages = normalized
                activity.syncLanguages()
                onSaved()
                dialog.dismiss()
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

    companion object {
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

        private fun dp(context: Context, value: Int) = Ui.dp(context, value)
    }
}
