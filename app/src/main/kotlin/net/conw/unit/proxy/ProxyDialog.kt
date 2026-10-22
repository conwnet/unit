package net.conw.unit.proxy

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.TextViewCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import net.conw.unit.BrowserActivity
import net.conw.unit.R
import net.conw.unit.ui.Ui

/** Edits a draft; only Apply changes the shared proxy or its enabled state. */
internal class ProxyDialog(private val activity: BrowserActivity) {
    @SuppressLint("SetTextI18n") // Protocol ports use ASCII digits regardless of the device locale.
    fun show() {
        val proxy = activity.proxy
        val saved =
            try {
                proxy.settings()
            } catch (_: Exception) {
                activity.message(R.string.proxy_saved_error)
                return
            }
        lateinit var dialog: AlertDialog
        val box =
            Ui.column(activity).apply {
                setPadding(dp(24), dp(16), dp(24), dp(8))
                isFocusableInTouchMode = true
            }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            Ui.text(activity, R.string.proxy, 22, R.color.text).apply {
                typeface =
                    android.graphics.Typeface.create(
                        "sans-serif-medium",
                        android.graphics.Typeface.NORMAL,
                    )
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        header.addView(
            Ui.iconButton(
                activity,
                R.drawable.ic_close,
                activity.getString(R.string.close_panel, "Proxy"),
            ) {
                dialog.dismiss()
            }
        )
        box.addView(header)
        val enabled =
            MaterialSwitch(Ui.dialogContext(activity)).apply {
                id = R.id.proxy_enabled
                text = activity.getString(R.string.enabled)
                setTextColor(activity.getColor(R.color.text))
                textSize = 15f
                isChecked = saved.enabled
                minimumHeight = dp(56)
            }
        box.addView(enabled)
        val content = Ui.column(activity)
        var selectedType = saved.type
        val type =
            MaterialAutoCompleteTextView(Ui.dialogContext(activity)).apply {
                id = R.id.proxy_type
                inputType = InputType.TYPE_NULL
                setSimpleItems(ProxySettings.Type.entries.map { it.name }.toTypedArray())
                setText(saved.type.name, false)
                setTextColor(activity.getColor(R.color.text))
                contentDescription = activity.getString(R.string.proxy_type)
                minimumHeight = dp(56)
            }
        content.addView(
            Ui.input(activity, type, R.string.proxy_type).apply {
                endIconMode = TextInputLayout.END_ICON_DROPDOWN_MENU
            }
        )
        fun input(label: Int, id: Int, value: String, mode: Int): Pair<TextInputLayout, EditText> {
            val field =
                Ui.editText(activity).apply {
                    this.id = id
                    inputType = mode
                    typeface =
                        android.graphics.Typeface.create(
                            "sans-serif",
                            android.graphics.Typeface.NORMAL,
                        )
                    setSingleLine()
                    setText(value)
                    contentDescription = activity.getString(label)
                }
            return Ui.input(activity, field, label) to field
        }
        val (hostBox, host) =
            input(
                R.string.proxy_host,
                R.id.proxy_host,
                saved.host,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            )
        val (portBox, port) =
            input(
                R.string.proxy_port,
                R.id.proxy_port,
                saved.port.toString(),
                InputType.TYPE_CLASS_NUMBER,
            )
        port.filters = arrayOf(InputFilter.LengthFilter(5))
        content.addView(
            LinearLayout(activity).apply {
                addView(hostBox, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    portBox,
                    LinearLayout.LayoutParams(dp(88), -2).apply { leftMargin = dp(12) },
                )
            }
        )
        val auth = Ui.column(activity)
        val (userBox, username) =
            input(
                R.string.proxy_username,
                R.id.proxy_username,
                saved.username,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            )
        val (passBox, password) =
            input(
                R.string.proxy_password,
                R.id.proxy_password,
                saved.password,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
        auth.addView(userBox)
        passBox.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        auth.addView(passBox)
        val authSection = Ui.column(activity).apply {
            addView(sectionToggle(R.string.proxy_authentication, auth))
            addView(auth)
        }
        content.addView(authSection)
        val bypass = Ui.editText(activity).apply {
            id = R.id.proxy_bypass
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(false)
            minLines = 2
            maxLines = 5
            gravity = Gravity.TOP or Gravity.START
            setText(saved.bypass.joinToString("\n"))
            contentDescription = activity.getString(R.string.proxy_bypass)
        }
        val bypassBox = Ui.input(activity, bypass, R.string.proxy_bypass).apply {
            helperText = activity.getString(R.string.proxy_bypass_hint)
        }
        val bypassToggle = sectionToggle(R.string.proxy_bypass, bypassBox)
        content.addView(bypassToggle)
        content.addView(bypassBox)
        box.addView(
            ScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                addView(content)
            },
            LinearLayout.LayoutParams(-1, -2, 1f),
        )
        authSection.visibility = if (selectedType == ProxySettings.Type.SOCKS5) View.GONE else View.VISIBLE
        type.setOnItemClickListener { _, _, position, _ ->
            val selected = ProxySettings.Type.entries[position]
            if (port.text.toString().toIntOrNull() == selectedType.defaultPort)
                port.setText(selected.defaultPort.toString())
            selectedType = selected
            authSection.visibility = if (selected == ProxySettings.Type.SOCKS5) View.GONE else View.VISIBLE
        }
        dialog = Ui.dialog(activity).setView(box).setPositiveButton(R.string.apply, null).create()
        activity.showDialog(dialog)
        box.requestFocus()
        @Suppress("DEPRECATION")
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            hostBox.error = null
            portBox.error = null
            bypassBox.error = null
            val normalized = ProxySettings.canonicalHost(host.text.toString())
            val number = port.text.toString().toIntOrNull()
            if (normalized == null && (enabled.isChecked || host.text.isNotBlank())) {
                hostBox.error = activity.getString(R.string.proxy_host_error)
                host.requestFocus()
                return@setOnClickListener
            }
            if (number == null || number !in 1..65535) {
                portBox.error = activity.getString(R.string.proxy_port_error)
                port.requestFocus()
                return@setOnClickListener
            }
            val rules = ProxyBypass.parseList(bypass.text.toString())
            if (rules == null) {
                if (bypassBox.visibility != View.VISIBLE) bypassToggle.performClick()
                bypassBox.error = activity.getString(R.string.proxy_bypass_error)
                bypass.requestFocus()
                return@setOnClickListener
            }
            val selected = selectedType
            val credentials = selected != ProxySettings.Type.SOCKS5
            val next =
                ProxySettings(
                    enabled.isChecked,
                    selected,
                    normalized.orEmpty(),
                    number,
                    if (credentials) username.text.toString() else "",
                    if (credentials) password.text.toString() else "",
                    rules,
                )
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.isEnabled = false
            proxy.save(next) { success ->
                button.isEnabled = true
                if (activity.isDestroyed || activity.isFinishing) return@save
                if (success) {
                    dialog.dismiss()
                    activity.proxyNotice.show(next.enabled)
                } else
                    activity.message(proxy.error ?: activity.getString(R.string.proxy_apply_error))
            }
        }
    }

    private fun sectionToggle(label: Int, body: View) =
        Ui.text(activity, label, 15, R.color.text).apply {
            body.visibility = View.GONE
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.dialogRipple(activity)
            isFocusable = true
            setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_down, 0)
            TextViewCompat.setCompoundDrawableTintList(
                this, ColorStateList.valueOf(activity.getColor(R.color.muted)),
            )
            setOnClickListener {
                val expanded = body.visibility != View.VISIBLE
                body.visibility = if (expanded) View.VISIBLE else View.GONE
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    0, 0, if (expanded) R.drawable.ic_up else R.drawable.ic_down, 0,
                )
            }
        }

    private fun dp(value: Int) = Ui.dp(activity, value)
}
