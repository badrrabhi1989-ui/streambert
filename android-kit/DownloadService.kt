package com.truelockmc.streambert

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps an in-progress download alive when the screen turns off. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra("text") ?: "Downloading…"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Streambert")
            .setContentText(text)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(70, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(70, notification)
        }
        return START_NOT_STICKY
    }
}
