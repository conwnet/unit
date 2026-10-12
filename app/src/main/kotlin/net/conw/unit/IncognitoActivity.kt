package net.conw.unit

import net.conw.unit.privacy.PrivateSession

class IncognitoActivity : BrowserActivity() {
    override val isIncognito
        get() = true

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) PrivateSession.endProcess(this)
    }
}
