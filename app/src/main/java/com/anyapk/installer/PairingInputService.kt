package com.anyapk.installer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class PairingInputService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adbMdns: AdbMdns? = null

    private val observer = Observer<Int> { port ->
        Log.i("PairingInputService", "Pairing service port: $port")
        if (port <= 0) return@Observer

        // Since the service could be killed before user finishing input,
        // we need to put the port into Intent
        val notification = createPairingNotification(port)

        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, createPairingNotification(0))
    }

    private fun createPairingNotification(port: Int): Notification {
        createNotificationChannel()

        // Create single RemoteInput for both code and port
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_INPUT)
            .setLabel("Code (e.g., 123456)")
            .build()

        // Create intent to handle the input
        val replyIntent = Intent(this, PairingInputReceiver::class.java).apply {
            action = ACTION_PAIRING_INPUT
            putExtra("PORT_EXTRA", port)
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
            .setContentTitle(if (port == 0) "Tap 'Pair device'"
                else "Enter Pairing Code")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Open Settings → Wireless Debugging → tap 'Pair device' to see the code.\n\nThen tap 'Reply' below and enter: CODE\n\nExample: 123456"))
            .setPriority(
                if (port == 0) NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_LOW)
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
        Log.d("PairingInputService",intent?.action.toString())
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start()}
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        adbMdns?.stop()
        serviceScope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "pairing_input_channel"
        const val NOTIFICATION_ID = 3001
        const val KEY_PAIRING_INPUT = "pairing_input"
        const val ACTION_PAIRING_INPUT = "com.anyapk.installer.PAIRING_INPUT"
    }
}
