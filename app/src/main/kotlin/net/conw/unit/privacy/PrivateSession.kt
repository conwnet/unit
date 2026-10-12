package net.conw.unit.privacy

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import net.conw.unit.BrowserActivity
import net.conw.unit.data.BrowserStore

/** A process-lifetime file lock prevents cleanup while the private WebView is alive. */
internal object PrivateSession {
    private lateinit var lockFile: RandomAccessFile
    private lateinit var lifetimeLock: FileLock

    fun prepare(context: Context) {
        try {
            lockFile = RandomAccessFile(File(context.filesDir, "incognito.lock"), "rw")
            lifetimeLock = lockFile.channel.lock()
            if (!deleteProfile(context))
                throw IOException("Could not clear the old incognito session")
        } catch (error: IOException) {
            // Fail closed rather than expose a previous private session.
            throw IllegalStateException("Cannot start a clean incognito session", error)
        }
    }

    fun cleanIfStopped(context: Context): Boolean =
        try {
            RandomAccessFile(File(context.filesDir, "incognito.lock"), "rw").use { file ->
                file.channel.tryLock()?.use {
                    deleteProfile(context).also { clean ->
                        if (clean) BrowserStore(context).use { it.put("private_count", "0") }
                    }
                } ?: false
            }
        } catch (_: IOException) {
            false
        } catch (_: OverlappingFileLockException) {
            false
        }

    private fun deleteProfile(context: Context): Boolean {
        // Chromium suffix directory names are implementation details, isolated here.
        val directories =
            listOf(
                File(context.applicationInfo.dataDir, "app_webview_incognito"),
                File(context.cacheDir, "WebView_incognito"),
                File(context.cacheDir, "webview_incognito"),
            )
        return directories.map { !it.exists() || it.deleteRecursively() }.all { it }
    }

    fun finish(activity: BrowserActivity) {
        activity.destroyAllTabs()
        activity.startActivity(
            Intent(activity, BrowserActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
        )
        activity.finishAndRemoveTask()
    }

    fun endProcess(context: Context) {
        context.sendBroadcast(Intent(context, PrivateCleanupReceiver::class.java))
        // Finish removing the activity window before terminating its isolated process.
        Handler(Looper.getMainLooper()).post { Process.killProcess(Process.myPid()) }
    }
}
