// Register in AndroidManifest.xml as shown in the comment below.
// Add inside the <application> tag:
//
//   <service
//       android:name=".voice.RtsVoiceForegroundService"
//       android:foregroundServiceType="microphone"
//       android:exported="false" />
//
// Also ensure the following permissions are declared before <application>:
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

package com.trika.roadtosale.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class RtsVoiceForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "rts_voice_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.trika.roadtosale.voice.START"
        const val ACTION_STOP = "com.trika.roadtosale.voice.STOP"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Road to Sale — Microphone",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the microphone active during a coaching session"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tap notification → bring app to foreground
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Road to Sale")
            .setContentText("Mic active — coaching session in progress")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
