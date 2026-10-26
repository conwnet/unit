package net.conw.unit.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import net.conw.unit.BrowserActivity
import net.conw.unit.IncognitoActivity
import net.conw.unit.R

/** Keeps WebView playback available while PlayOn is enabled. */
open class PlayOnService : Service() {
    override fun getResources(): Resources = application.resources

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.play_on),
                NotificationManager.IMPORTANCE_LOW,
            )
        )
        val private = this is PrivatePlayOnService
        val id = if (private) 3 else 2
        val open = PendingIntent.getActivity(
            this,
            id,
            Intent(this, if (private) IncognitoActivity::class.java else BrowserActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play_on)
            .setContentTitle(getString(R.string.play_on))
            .setContentText(getString(R.string.play_on_enabled))
            .setContentIntent(open)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this,
            id,
            notification,
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "play_on"

        internal fun intent(activity: BrowserActivity) = Intent(
            activity,
            if (activity.isIncognito) PrivatePlayOnService::class.java
            else PlayOnService::class.java,
        )

        internal fun stop(context: Context) {
            context.stopService(Intent(context, PlayOnService::class.java))
            context.stopService(Intent(context, PrivatePlayOnService::class.java))
        }
    }
}

class PrivatePlayOnService : PlayOnService()
