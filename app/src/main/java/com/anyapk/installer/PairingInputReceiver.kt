package com.anyapk.installer

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PairingInputReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingInputService.ACTION_PAIRING_INPUT) {
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val input = remoteInput.getCharSequence(PairingInputService.KEY_PAIRING_INPUT)?.toString()

            if (input.isNullOrEmpty()) {
                Toast.makeText(context, "Please enter code and port", Toast.LENGTH_SHORT).show()
                return
            }

            // Parse input format: "CODE PORT" (space-separated)
            val parts = input.trim().split("\\s+".toRegex())
            if (parts.size != 2) {
                Toast.makeText(
                    context,
                    "Invalid format. Use: CODE PORT (e.g., 123456 37829)",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val code = parts[0]
            val portInt = parts[1].toIntOrNull()

            if (portInt == null || portInt <= 0) {
                Toast.makeText(context, "Invalid port number", Toast.LENGTH_SHORT).show()
                return
            }

            // Show progress notification
            showProgressNotification(context)

            // Perform pairing
            scope.launch {
                val result = AdbInstaller.pair(context, code, portInt)

                result.onSuccess {
                    showSuccessNotification(context)
                    Toast.makeText(
                        context,
                        "Pairing successful!",
                        Toast.LENGTH_LONG
                    ).show()

                    // Stop the service
                    val serviceIntent = Intent(context, PairingInputService::class.java)
                    context.stopService(serviceIntent)
                }

                result.onFailure { error ->
                    showErrorNotification(context, error.message ?: "Unknown error")
                    Toast.makeText(
                        context,
                        "Pairing failed: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showProgressNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing...")
            .setContentText("Connecting to device")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        notificationManager.notify(PairingInputService.NOTIFICATION_ID, notification)
    }

    private fun showSuccessNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing Successful!")
            .setContentText("Device paired successfully")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(PairingInputService.NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(context: Context, error: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Pairing Failed")
            .setContentText(error)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(PairingInputService.NOTIFICATION_ID, notification)
    }
}
