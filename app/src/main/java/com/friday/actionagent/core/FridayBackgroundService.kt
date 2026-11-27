package com.friday.actionagent.core

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
import androidx.core.content.ContextCompat
import com.friday.actionagent.MainActivity
import com.friday.actionagent.R
import com.friday.actionagent.engines.offline.EmbeddedOfflineBrain

class FridayBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "friday_live_channel"
        private const val NOTIF_ID = 4041

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FridayBackgroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FridayBackgroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "FRIDAY Always-On AI", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val brain = EmbeddedOfflineBrain.loadedModelName.ifBlank { "Auto-Connected" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FRIDAY Connected • $brain")
            .setContentText("Offline AI Brain & Background Voice Active")
            .setSmallIcon(R.drawable.ic_friday_logo)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
