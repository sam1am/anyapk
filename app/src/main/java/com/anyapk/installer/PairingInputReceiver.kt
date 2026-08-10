package com.anyapk.installer

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PairingInputReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "PairingInputReceiver"
        private const val REQUEST_RETURN_TO_APP = 3002
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PairingInputService.ACTION_PAIRING_INPUT) {
            return
        }

        val remoteInput = RemoteInput.getResultsFromIntent(intent)
        if (remoteInput != null) {
            val input = remoteInput.getCharSequence(PairingInputService.KEY_PAIRING_INPUT)?.toString()

            if (input.isNullOrEmpty()) {
                Toast.makeText(context, "Please enter code", Toast.LENGTH_SHORT).show()
                return
            }

            val portInt = intent.getIntExtra("PORT_EXTRA", -1)
            if (portInt == -1) return // Handle error

            val code = input.trim()

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
                    SettingsManager.setHasPairedBefore(context)
                    showSuccessNotification(context)
                    Toast.makeText(
                        context,
                        "Pairing successful!",
                        Toast.LENGTH_LONG
                    ).show()

                    // Stop the service
                    val serviceIntent = Intent(context, PairingInputService::class.java)
                    context.stopService(serviceIntent)

                    // The user is sitting in system Settings at this point — pull the
                    // app back to the foreground so they see the connected state.
                    returnToApp(context)
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

    /**
     * Brings MainActivity back to the front. CLEAR_TOP + SINGLE_TOP reuses the existing
     * instance (delivering onNewIntent, which triggers a status refresh in onResume) and
     * pops anything stacked above it, such as the in-app Settings screen.
     */
    private fun returnToApp(context: Context) {
        try {
            context.startActivity(mainActivityIntent(context))
        } catch (e: Exception) {
            // Background activity starts can be refused; the success notification is
            // tappable as a fallback.
            Log.w(TAG, "Could not bring the app to the foreground", e)
        }
    }

    private fun mainActivityIntent(context: Context): Intent {
        return Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_FROM_PAIRING, true)
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

        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_RETURN_TO_APP,
            mainActivityIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PairingInputService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pairing Successful!")
            .setContentText("Tap to return to anyapk")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent)
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
