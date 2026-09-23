package com.kurdistan.iptv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.session.MediaStyleNotificationHelper

/**
 * Radio mode, and nothing else.
 *
 * The player stays where it has always been - inside MainActivity. This
 * service only holds one notification while the app is away from the screen,
 * which is what tells Android the sound is playing on purpose and must not be
 * cut off. It starts when the app goes to the background with radio mode on,
 * and stops the moment the app comes back or radio mode is turned off.
 */
class RadioService : Service() {

    companion object {
        const val CHANNEL = "kiptv_radio"
        const val ID = 7301
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, ID, build(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
        } catch (e: Exception) {
            /* the system refused it: the sound may stop early, but nothing crashes */
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val c = NotificationChannel(CHANNEL, getString(R.string.radio_channel),
            NotificationManager.IMPORTANCE_LOW)
        c.setShowBadge(false)
        nm.createNotificationChannel(c)
    }

    private fun build(): Notification {
        channel()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags)

        val title = MainActivity.nowTitle.ifBlank { getString(R.string.app_name) }
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_kiptv)
            .setContentTitle(title)
            .setContentText(getString(R.string.radio_playing))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        /* the play / pause the system already knows about, when there is a session */
        try {
            MainActivity.liveSession?.let { b.setStyle(MediaStyleNotificationHelper.MediaStyle(it)) }
        } catch (e: Exception) { /* a plain notification does the job too */ }
        return b.build()
    }

    override fun onDestroy() {
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
        super.onDestroy()
    }
}
