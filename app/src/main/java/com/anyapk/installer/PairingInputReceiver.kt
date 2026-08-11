package com.anyapk.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

/**
 * Relays the notification's inline reply and Cancel action to [PairingInputService].
 *
 * Nothing long-running happens here. A receiver's process can be killed the moment
 * onReceive returns, so the pairing itself lives in the service, which keeps the process
 * alive and owns its own teardown.
 */
class PairingInputReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PairingInputService.ACTION_PAIRING_INPUT -> handleCode(context, intent)
            PairingInputService.ACTION_PAIRING_CANCEL -> handleCancel(context)
        }
    }

    private fun handleCode(context: Context, intent: Intent) {
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val input = remoteInput
            .getCharSequence(PairingInputService.KEY_PAIRING_INPUT)
            ?.toString()
            ?.trim()
            .orEmpty()

        if (input.isEmpty()) {
            Toast.makeText(context, "Enter the 6-digit pairing code", Toast.LENGTH_SHORT).show()
            return
        }

        // Validation proper lives in AdbInstaller.pair so the service can render the
        // message in the notification; here we only avoid waking it for nothing.
        val forward = Intent(context, PairingInputService::class.java).apply {
            action = PairingInputService.ACTION_SUBMIT_CODE
            putExtra(PairingInputService.EXTRA_CODE, input)
            putExtra(
                PairingInputService.EXTRA_PORT,
                intent.getIntExtra(PairingInputService.EXTRA_PORT, 0)
            )
        }

        // startForegroundService, not startService: the app is in the background (the
        // user is in system Settings) when this fires.
        ContextCompat.startForegroundService(context, forward)
    }

    private fun handleCancel(context: Context) {
        Log.i(TAG, "Pairing cancelled from the notification")
        context.stopService(Intent(context, PairingInputService::class.java))
    }

    companion object {
        private const val TAG = "PairingInputReceiver"
    }
}
