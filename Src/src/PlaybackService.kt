package com.zeko.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.media3.exoplayer.ExoPlayer

object PlaybackHolder {
    @Volatile var player: ExoPlayer? = null
    @Volatile var title: String = ""
}

class PlaybackService : Service() {

    companion object {
        const val CHANNEL = "zeko_playback"
        const val ACTION_TOGGLE = "com.zeko.player.TOGGLE"
        const val ACTION_STOP = "com.zeko.player.STOP"
        const val NOTIF_ID = 7
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = PlaybackHolder.player
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_TOGGLE -> {
                if (p.isPlaying) p.pause() else p.play()
            }
            ACTION_STOP -> {
                p.pause()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        val n = buildNotification(p.isPlaying)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, n)
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(playing: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            flags
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            flags
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            flags
        )

        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else Notification.Builder(this)

        val toggleIcon = Icon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)

        b.setSmallIcon(android.R.drawable.ic_media_play)
        b.setContentTitle(if (PlaybackHolder.title.isBlank()) "Zeko Player" else PlaybackHolder.title)
        b.setContentText(if (playing) "Playing in background" else "Paused")
        b.setContentIntent(open)
        b.setOngoing(true)
        b.addAction(
            Notification.Action.Builder(toggleIcon, if (playing) "Pause" else "Play", toggle).build()
        )
        b.addAction(Notification.Action.Builder(stopIcon, "Stop", stop).build())
        return b.build()
    }
}
