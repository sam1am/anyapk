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
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives the "enter pairing code" notification: watches mDNS for the pairing port,
 * accepts the code through an inline reply, and performs the pairing.
 *
 * This service owns the whole pairing attempt rather than handing it to the broadcast
 * receiver, so that every outcome — success, rejection, timeout, the user walking away —
 * ends with the service stopped and the notification gone. Earlier only a successful pair
 * stopped it, which left the notification pinned indefinitely.
 */
class PairingInputService : Service() {

    private enum class State { WAITING_FOR_PORT, READY, PAIRING, FAILED }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var adbMdns: AdbMdns? = null
    private var idleTimeoutJob: Job? = null
    private var pairingJob: Job? = null

    private var state = State.WAITING_FOR_PORT
    private var pairingPort = 0
    private var failureMessage: String? = null

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private val observer = Observer<Int> { port ->
        Log.i(TAG, "Pairing service port: $port")
        // AdbMdns reports a lost service as -1; only a live port is actionable.
        if (port <= 0) return@Observer
        if (state == State.PAIRING) return@Observer

        pairingPort = port
        state = State.READY
        failureMessage = null
        updateNotification()
        // The port appears the moment the user opens the pair dialog, which is when
        // their window to actually type the code starts.
        startIdleTimeout()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")

        // Re-asserted on every start so a delivery via startForegroundService always
        // satisfies the five-second startForeground contract, whichever path got here.
        startForeground(NOTIFICATION_ID, buildNotification())
        startDiscovery()

        if (intent?.action == ACTION_SUBMIT_CODE) {
            submitCode(
                code = intent.getStringExtra(EXTRA_CODE).orEmpty(),
                port = intent.getIntExtra(EXTRA_PORT, 0)
            )
        } else {
            startIdleTimeout()
        }

        // Not START_STICKY: a system restart would hand us a null intent with no user
        // waiting on the other end and silently re-pin the notification. That was the
        // reason force-quitting the app did not get rid of it.
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        // Guarded: each onStartCommand used to allocate another AdbMdns and leak the
        // previous discovery registration.
        if (adbMdns != null) return
        adbMdns = AdbMdns(this, AdbMdns.TLS_PAIRING, observer).apply { start() }
    }

    /**
     * Stops the service if the user never gets around to entering a code. Restarted
     * whenever they make progress, so an engaged user is never cut off mid-attempt.
     */
    private fun startIdleTimeout() {
        idleTimeoutJob?.cancel()
        idleTimeoutJob = serviceScope.launch {
            delay(IDLE_TIMEOUT_MS)
            Log.i(TAG, "No pairing code entered within the idle window; stopping")
            stopSelf()
        }
    }

    private fun submitCode(code: String, port: Int) {
        if (pairingJob?.isActive == true) {
            Log.d(TAG, "Ignoring code submission; a pairing attempt is already running")
            return
        }

        // The attempt is time-boxed by AdbInstaller.pair, so the idle timer would only
        // race it. It is restarted below if the attempt fails.
        idleTimeoutJob?.cancel()
        state = State.PAIRING
        failureMessage = null
        updateNotification()

        pairingJob = serviceScope.launch {
            // Prefer the port the notification was built with, falling back to the most
            // recent discovery in case the extras went stale.
            val effectivePort = if (port > 0) port else pairingPort

            AdbInstaller.pair(this@PairingInputService, code, effectivePort)
                .onSuccess {
                    SettingsManager.setHasPairedBefore(this@PairingInputService)
                    showResultNotification(
                        title = "Pairing successful",
                        text = "Tap to return to anyapk",
                        icon = android.R.drawable.ic_dialog_info,
                        tappable = true
                    )
                    Toast.makeText(
                        this@PairingInputService,
                        "Pairing successful!",
                        Toast.LENGTH_LONG
                    ).show()
                    returnToApp()
                    stopSelf()
                }
                .onFailure { error ->
                    val message = error.message ?: "Pairing failed."
                    Log.w(TAG, "Pairing failed: $message", error)

                    // Stay up so the user can retry with a fresh code — but back under
                    // the idle timeout, so a walk-away still tears everything down.
                    state = State.FAILED
                    failureMessage = message
                    updateNotification()
                    Toast.makeText(this@PairingInputService, message, Toast.LENGTH_LONG).show()
                    startIdleTimeout()
                }
        }
    }

    /**
     * Brings MainActivity back to the front. The user is sitting in system Settings at
     * this point, so without this they would not see the connected state. Background
     * activity starts can be refused; the success notification is tappable as a fallback.
     */
    private fun returnToApp() {
        try {
            startActivity(mainActivityIntent())
        } catch (e: Exception) {
            Log.w(TAG, "Could not bring the app to the foreground", e)
        }
    }

    private fun mainActivityIntent(): Intent {
        return Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(MainActivity.EXTRA_FROM_PAIRING, true)
        }
    }

    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val title = when (state) {
            State.WAITING_FOR_PORT -> "Waiting for the pairing dialog"
            State.READY -> "Enter pairing code"
            State.PAIRING -> "Pairing…"
            State.FAILED -> "Pairing failed"
        }

        val summary = when (state) {
            State.WAITING_FOR_PORT ->
                "Open Wireless debugging and tap \"Pair device with pairing code\"."
            State.READY -> "Tap Reply and enter the 6-digit code."
            State.PAIRING -> "Talking to the device."
            State.FAILED -> failureMessage ?: "Something went wrong."
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (state == State.FAILED) android.R.drawable.ic_dialog_alert
                else android.R.drawable.ic_dialog_info
            )
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(openAppPendingIntent())
            // Pre-O only; from O onwards the channel's importance governs. The old code
            // toggled priority per state, which had no effect at all on this minSdk.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Port discovery can fire repeatedly; without this every update re-alerts.
            .setOnlyAlertOnce(true)
            // Un-swipeable only while an attempt is genuinely in flight. Leaving this on
            // permanently is what made the notification feel un-dismissable.
            .setOngoing(state == State.PAIRING)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent()
            )

        if (state == State.READY || state == State.FAILED) {
            val remoteInput = RemoteInput.Builder(KEY_PAIRING_INPUT)
                .setLabel("6-digit code")
                .build()

            // The port has to ride along in the intent: the service can be killed
            // between building this and the user replying.
            val replyIntent = Intent(this, PairingInputReceiver::class.java).apply {
                action = ACTION_PAIRING_INPUT
                putExtra(EXTRA_PORT, pairingPort)
            }

            val replyPendingIntent = PendingIntent.getBroadcast(
                this,
                REQUEST_REPLY,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            builder.addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_input_add,
                    if (state == State.FAILED) "Retry" else "Reply",
                    replyPendingIntent
                )
                    .addRemoteInput(remoteInput)
                    .build()
            )
        }

        return builder.build()
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        ),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        REQUEST_CANCEL,
        Intent(this, PairingInputReceiver::class.java).setAction(ACTION_PAIRING_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * Posted under its own id so that tearing down the foreground service — which
     * removes [NOTIFICATION_ID] — does not take the result with it.
     */
    private fun showResultNotification(
        title: String,
        text: String,
        icon: Int,
        tappable: Boolean
    ) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        if (tappable) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    this,
                    REQUEST_RETURN_TO_APP,
                    mainActivityIntent(),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        notificationManager.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // The original channel was created at IMPORTANCE_HIGH, so every port update
        // peeked as a heads-up and the notification sat permanently expanded. A
        // channel's importance is immutable once created, so correcting it means
        // retiring the old id.
        notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID)

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Device pairing",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Enter the wireless debugging pairing code"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        adbMdns?.stop()
        adbMdns = null
        serviceScope.cancel()

        // Belt and braces: the platform drops the foreground notification on destroy,
        // but some OEM shells (ColorOS among them) have been seen keeping a stale entry
        // around. Cancelling explicitly is what actually guarantees it disappears.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        notificationManager.cancel(NOTIFICATION_ID)
    }

    companion object {
        private const val TAG = "PairingInputService"

        /** Retired; deleted on startup so it stops showing up in notification settings. */
        private const val LEGACY_CHANNEL_ID = "pairing_input_channel"

        private const val REQUEST_REPLY = 0
        private const val REQUEST_CANCEL = 1
        private const val REQUEST_OPEN_APP = 2
        private const val REQUEST_RETURN_TO_APP = 3

        /** Long enough to walk to Settings and read a code; short enough to self-clean. */
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L

        const val CHANNEL_ID = "pairing_input_v2"
        const val NOTIFICATION_ID = 3001
        const val RESULT_NOTIFICATION_ID = 3003
        const val KEY_PAIRING_INPUT = "pairing_input"
        const val ACTION_PAIRING_INPUT = "com.anyapk.installer.PAIRING_INPUT"
        const val ACTION_PAIRING_CANCEL = "com.anyapk.installer.PAIRING_CANCEL"
        const val ACTION_SUBMIT_CODE = "com.anyapk.installer.SUBMIT_PAIRING_CODE"
        const val EXTRA_CODE = "pairing_code"
        const val EXTRA_PORT = "PORT_EXTRA"
    }
}
