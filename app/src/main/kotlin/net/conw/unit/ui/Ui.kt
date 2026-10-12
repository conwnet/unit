package net.conw.unit.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.drawable.toDrawable
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlin.math.roundToInt
import net.conw.unit.BrowserActivity
import net.conw.unit.R

internal object Ui {
    const val EMPTY_MIN_HEIGHT_DP = 192

    fun copy(context: Context, label: Int, value: String, confirmation: Int = R.string.copied) {
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(context.getString(label), value))
        Toast.makeText(context, confirmation, Toast.LENGTH_SHORT).show()
    }

    fun form(context: Context) =
        column(context).apply {
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8))
        }

    fun textInput(context: Context, label: Int, value: String, uri: Boolean = false) =
        editText(context).apply {
            inputType =
                if (uri)
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_URI
                else
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            contentDescription = context.getString(label)
            setText(value)
        }

    fun dialogContext(context: Context) = ContextThemeWrapper(context, R.style.Theme_Unit_Dialog)

    fun dialog(context: Context) =
        MaterialAlertDialogBuilder(dialogContext(context))
            .setBackgroundInsetStart(0)
            .setBackgroundInsetEnd(0)
            .setBackgroundInsetTop(0)
            .setBackgroundInsetBottom(0)

    fun actions(
        activity: BrowserActivity,
        title: String,
        labels: List<Int>,
        selected: (Int) -> Unit,
    ) {
        activity.showDialog(
            dialog(activity)
                .setTitle(title)
                .setItems(labels.map(activity::getString).toTypedArray()) { _, index ->
                    selected(labels[index])
                }
                .create()
        )
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun background(context: Context, color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(context.getColor(color))
            cornerRadius = dp(context, radius).toFloat()
        }

    fun text(context: Context, value: String, size: Int, color: Int) =
        TextView(context).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(context.getColor(color))
        }

    fun text(context: Context, value: Int, size: Int, color: Int) =
        text(context, context.getString(value), size, color)

    fun column(context: Context) =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun iconButton(context: Context, icon: Int, label: String, click: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            contentDescription = label
            tooltipText = label
            isFocusable = true
            layoutParams =
                LinearLayout.LayoutParams(dp(context, 44), dp(context, 44)).apply {
                    marginStart = dp(context, 4)
                }
            imageTintList = ColorStateList.valueOf(context.getColor(R.color.muted))
            background = dialogRipple(context)
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setOnClickListener { click() }
        }

    fun iconButton(context: Context, icon: Int, label: Int, click: () -> Unit) =
        iconButton(context, icon, context.getString(label), click)

    fun ripple(context: Context, color: Int, radius: Int) =
        RippleDrawable(
            ColorStateList.valueOf((context.getColor(R.color.accent) and 0xffffff) or 0x18000000),
            background(context, color, radius),
            background(context, color, radius),
        )

    fun dialogRipple(context: Context, radius: Int = 12) =
        RippleDrawable(
            ColorStateList.valueOf(context.getColor(R.color.ripple)),
            null,
            background(context, R.color.surface, radius),
        )

    fun list(context: Context) =
        ListView(context).apply {
            divider = context.getColor(R.color.divider).toDrawable()
            dividerHeight = dp(context, 1)
            selector = dialogRipple(context)
            setDrawSelectorOnTop(true)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }

    fun row(
        context: Context,
        title: String,
        subtitle: String? = null,
        icon: Int? = null,
        iconTint: Int? = R.color.accent,
        click: (() -> Unit)? = null,
    ): LinearLayout =
        column(context).apply {
            minimumHeight = dp(context, 64)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
            val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            if (icon != null) {
                line.addView(
                    ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList =
                            iconTint?.let { ColorStateList.valueOf(context.getColor(it)) }
                        setPadding(
                            dp(context, 6),
                            dp(context, 6),
                            dp(context, 6),
                            dp(context, 6),
                        )
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                        marginEnd = dp(context, 12)
                    },
                )
            }
            val labels = column(context)
            labels.addView(
                text(context, title, 15, R.color.text).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    includeFontPadding = false
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                }
            )
            if (!subtitle.isNullOrEmpty())
                labels.addView(
                    text(context, subtitle, 13, R.color.muted).apply {
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        setPadding(0, dp(context, 5), 0, 0)
                    }
                )
            line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            if (click != null) {
                line.addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_chevron)
                        imageTintList = ColorStateList.valueOf(context.getColor(R.color.muted))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)).apply {
                        marginStart = dp(context, 8)
                    },
                )
            }
            addView(line)
            background = dialogRipple(context)
            if (click != null) {
                setOnClickListener { click() }
                isFocusable = true
            }
        }

    fun section(context: Context, parent: LinearLayout, title: Int): LinearLayout {
        parent.addView(
            text(context, title, 12, R.color.muted).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(context, 12), dp(context, 14), 0, dp(context, 6))
            }
        )
        return column(context).apply {
            parent.addView(this)
        }
    }

    fun empty(context: Context, title: String, hint: String, icon: Int): View =
        column(context).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(context, EMPTY_MIN_HEIGHT_DP)
            setPadding(dp(context, 24), dp(context, 28), dp(context, 24), dp(context, 28))
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(context.getColor(R.color.muted))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LinearLayout.LayoutParams(dp(context, 32), dp(context, 32)).apply {
                    bottomMargin = dp(context, 16)
                },
            )
            addView(text(context, title, 16, R.color.text).apply { gravity = Gravity.CENTER })
            addView(
                text(context, hint, 13, R.color.muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(context, 6), 0, 0)
                }
            )
        }

    fun editText(context: Context) =
        TextInputEditText(dialogContext(context)).apply {
            textSize = 16f
            setTextColor(context.getColor(R.color.text))
            setHintTextColor(context.getColor(R.color.muted))
            setSingleLine(true)
        }

    fun input(context: Context, field: EditText, label: Int): TextInputLayout =
        TextInputLayout(dialogContext(context)).apply {
            hint = context.getString(label)
            field.hint = null
            field.background = null
            field.minimumHeight = dp(context, 56)
            field.setPadding(dp(context, 14), dp(context, 16), dp(context, 14), dp(context, 16))
            addView(field, LinearLayout.LayoutParams(-1, -2))
            layoutParams =
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
        }

    fun listHeight(context: Context, count: Int, rowDp: Int): Int {
        val available = dp(context, maxOf(72, context.resources.configuration.screenHeightDp - 200))
        return minOf(available, dp(context, 400), dp(context, maxOf(1, count) * rowDp))
    }

    fun styleDialog(dialog: AlertDialog) {
        val window = dialog.window ?: return
        val context = dialog.context
        dialog.listView?.let { list ->
            list.adapter?.let { list.layoutParams.height = listHeight(context, it.count, 52) }
            list.isVerticalScrollBarEnabled = false
            list.selector = dialogRipple(context)
            list.setDrawSelectorOnTop(true)
            list.requestLayout()
        }
        window.decorView.clipToOutline = true
        val width =
            minOf(dp(context, 520), context.resources.displayMetrics.widthPixels - dp(context, 40))
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
            .forEach { button ->
                dialog.getButton(button)?.apply {
                    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                        it.marginStart = dp(context, 8)
                        layoutParams = it
                    }
                }
            }
    }
}
