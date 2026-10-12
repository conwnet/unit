package net.conw.unit.privacy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

class PrivateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        thread(name = "private-cleanup") {
            try {
                repeat(30) {
                    if (PrivateSession.cleanIfStopped(context)) return@thread
                    Thread.sleep(100)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                pending.finish()
            }
        }
    }
}
