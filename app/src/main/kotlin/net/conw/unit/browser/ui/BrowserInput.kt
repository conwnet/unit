package net.conw.unit.browser.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.widget.EditText

/** Native inputs share live text updates and pre-IME Back handling on Android 9–12. */
@SuppressLint("AppCompatCustomView") // The browser toolbar uses the platform Material theme.
class BrowserInput(context: Context, attributes: AttributeSet? = null) :
    EditText(context, attributes) {
    var cancel: (() -> Boolean)? = null
    var textChanged: ((String) -> Unit)? = null

    override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
        super.onTextChanged(text, start, before, count)
        textChanged?.invoke(text?.toString().orEmpty())
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (
            keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP &&
                cancel?.invoke() == true
        )
            return true
        return super.onKeyPreIme(keyCode, event)
    }
}
