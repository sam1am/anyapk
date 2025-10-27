package com.anyapk.installer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PairingInputService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createPairingNotification())
    }

    private fun createPairingNotification(): Notification {
        createNotificationChannel()

        // Create single RemoteInput for both code and port
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_INPUT)
            .setLabel("Code and Port (e.g., 123456 37829)")
            .build()

        // Create intent to handle the input
        val replyIntent = Intent(this, PairingInputReceiver::class.java).apply {
            action = ACTION_PAIRING_INPUT
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        // Create notification with reply action
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Enter Pairing Code")
            .setContentText("Format: CODE PORT (e.g., 123456 37829)")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Open Settings → Wireless Debugging to see the code and port.\n\nThen tap 'Reply' below and enter: CODE PORT\n\nExample: 123456 37829"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_input_add,
                    "Reply",
                    replyPendingIntent
                )
                    .addRemoteInput(remoteInput)
                    .build()
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Pairing Input",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Enter pairing code directly from notification"
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "pairing_input_channel"
        const val NOTIFICATION_ID = 3001
        const val KEY_PAIRING_INPUT = "pairing_input"
        const val ACTION_PAIRING_INPUT = "com.anyapk.installer.PAIRING_INPUT"
    }
}
