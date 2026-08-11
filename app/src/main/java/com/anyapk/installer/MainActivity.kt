package com.anyapk.installer

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var actionButton: Button
    private lateinit var refreshButton: Button
    private lateinit var testConnectionButton: Button
    private lateinit var selectApkButton: Button

    private val selectApkLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            // Launch InstallActivity with the selected APK
            val intent = Intent(this, InstallActivity::class.java).apply {
                data = it
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        actionButton = findViewById(R.id.actionButton)
        refreshButton = findViewById(R.id.refreshButton)
        testConnectionButton = findViewById(R.id.testConnectionButton)
        selectApkButton = findViewById(R.id.selectApkButton)

        refreshButton.setOnClickListener {
            checkStatus()
        }

        testConnectionButton.setOnClickListener {
            testConnection()
        }

        selectApkButton.setOnClickListener {
            // Anything but a plain APK arrives with a MIME type the picker won't map back
            // to a bundle, so the filter has to stay open and let PackageBundle decide.
            selectApkLauncher.launch("*/*")
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep the newest intent so onResume can see EXTRA_FROM_PAIRING.
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()

        // Coming straight back from a successful pairing: skip the cached connection
        // status, which is still pre-pairing and would show the checklist again.
        val fromPairing = intent?.getBooleanExtra(EXTRA_FROM_PAIRING, false) == true
        if (fromPairing) {
            intent.removeExtra(EXTRA_FROM_PAIRING)
        }

        checkStatus(forceCheck = fromPairing)
    }

    /**
     * Checks for updates once per app session, and only once ADB is connected —
     * installing an update goes through ADB, so offering it earlier would just fail.
     */
    private fun checkForUpdatesInBackground() {
        if (updatePromptShown) return
        if (!SettingsManager.isAutoUpdateEnabled(this)) return

        updatePromptShown = true

        lifecycleScope.launch {
            // Small delay to not interfere with status check
            kotlinx.coroutines.delay(1000)

            val updateInfo = UpdateChecker.checkForUpdate(this@MainActivity)
            if (updateInfo != null) {
                showUpdateDialog(updateInfo)
            } else {
                // Nothing to offer — allow a later resume to check again.
                updatePromptShown = false
            }
        }
    }

    private fun checkStatus(forceCheck: Boolean = false) {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                AdbInstaller.getConnectionStatus(this@MainActivity, forceCheck)
            }

            val isDeveloperModeEnabled = isDeveloperOptionsEnabled()
            val hasNotificationPermission = checkNotificationPermission()

            when (status) {
                AdbInstaller.ConnectionStatus.CONNECTED -> {
                    showConnectedState()
                    // Covers the paths that bypass the service's own teardown, e.g. the
                    // user pairing by hand in Settings while the prompt is still up.
                    stopPairingService()
                    // A working connection is the only chance to pick up the permission
                    // that lets anyapk re-enable wireless debugging on its own later.
                    SettingsManager.setHasPairedBefore(this@MainActivity)
                    acquireWirelessDebuggingPermission()
                    checkForUpdatesInBackground()
                }
                else -> {
                    showSetupChecklist(isDeveloperModeEnabled, hasNotificationPermission)
                }
            }
        }
    }

    /**
     * Grants anyapk WRITE_SECURE_SETTINGS through the live ADB connection, once per
     * session. Failure is not worth reporting: it only costs the convenience of
     * auto-enabling wireless debugging later, and the user can still do it by hand.
     */
    private fun acquireWirelessDebuggingPermission() {
        if (permissionGrantAttempted) return
        if (WirelessDebugging.canToggle(this)) return

        permissionGrantAttempted = true

        lifecycleScope.launch {
            WirelessDebugging.tryAcquireTogglePermission(this@MainActivity)
        }
    }

    /**
     * anyapk targets API 30, so POST_NOTIFICATIONS is granted implicitly at install even
     * on Android 13+ and a runtime permission request would show nothing. What
     * actually matters is whether the user has since switched notifications off, which is
     * exactly what this reports.
     */
    private fun checkNotificationPermission(): Boolean {
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
    }

    private fun showConnectedState() {
        statusText.text = "✅ Ready to Install\n\nYou're all set! Open any APK, APKS, APKM or XAPK file and select anyapk to install."
        actionButton.isEnabled = false
        actionButton.text = getString(R.string.btn_connected)
        testConnectionButton.visibility = Button.GONE
        refreshButton.visibility = Button.GONE
        selectApkButton.visibility = Button.VISIBLE
    }

    private fun showSetupChecklist(devModeEnabled: Boolean, notificationPermission: Boolean) {
        val step1 = if (devModeEnabled) "✅" else "⬜"
        val step2 = if (notificationPermission) "✅" else "⬜"
        val step3 = if (devModeEnabled && notificationPermission) "⬜" else "⚪"

        val message = buildString {
            append("Setup Progress:\n\n")

            // Step 1: Developer Options
            append("$step1 Step 1: Enable Developer Options\n")
            if (!devModeEnabled) {
                append("   • Open Settings → About Phone\n")
                append("   • Tap \"Build Number\" 7 times\n\n")
            } else {
                append("   Complete!\n\n")
            }

            // Step 2: Notification Permission
            append("$step2 Step 2: Enable Notifications\n")
            if (!notificationPermission) {
                append("   • Required to enter pairing codes\n")
                append("   • Tap below to open Settings\n\n")
            } else {
                append("   Complete!\n\n")
            }

            // Step 3: Pairing
            append("$step3 Step 3: Pair with Wireless ADB\n")
            if (devModeEnabled && notificationPermission) {
                append("   • Tap \"Start Pairing\" below\n")
                append("   • Enter code from Settings notification\n")
            } else {
                append("   Complete previous steps first\n")
            }
        }

        statusText.text = message

        // Configure button based on current step
        when {
            !notificationPermission -> {
                actionButton.text = "Enable Notifications"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    requestNotificationPermission()
                }
            }
            devModeEnabled && notificationPermission -> {
                actionButton.text = "Start Pairing"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    showPairingDialog()
                }
            }
            !devModeEnabled -> {
                actionButton.text = "Open Settings"
                actionButton.isEnabled = true
                actionButton.setOnClickListener {
                    try {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Please open Settings manually", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        testConnectionButton.visibility = Button.GONE
        selectApkButton.visibility = Button.GONE
    }

    private fun isDeveloperOptionsEnabled(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(
                contentResolver,
                android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            // If we can't determine, assume it's enabled to avoid confusion
            true
        }
    }

    private fun stopPairingService() {
        stopService(Intent(this, PairingInputService::class.java))
    }

    private fun showPairingDialog() {
        // Start pairing input service with RemoteInput notification
        val serviceIntent = Intent(this, PairingInputService::class.java)
        startService(serviceIntent)

        // Try to open Developer Options directly
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // If that fails, just open main settings
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                startActivity(intent)
            } catch (ex: Exception) {
                Toast.makeText(
                    this,
                    "Please open Settings → Developer Options → Wireless Debugging manually",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Sends the user to the system notification settings for anyapk. There is no runtime
     * prompt to show at this targetSdk — see [checkNotificationPermission] — so toggling
     * it back on has to happen in Settings. onResume re-runs the checklist on return.
     */
    private fun requestNotificationPermission() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null))
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Please enable notifications for anyapk in Settings",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun refreshStatus() {
        checkStatus()
    }

    fun showTestConnectionButton() {
        testConnectionButton.visibility = Button.VISIBLE
        statusText.text = "⚠️ Authorization Required\n\nTap 'Test Connection' below to trigger the USB debugging authorization prompt. Make sure to check 'Always allow' and tap 'Allow'."
    }

    private fun testConnection() {
        testConnectionButton.isEnabled = false
        testConnectionButton.text = "Testing..."

        lifecycleScope.launch {
            val result = AdbInstaller.testConnection(this@MainActivity)

            result.onSuccess {
                Toast.makeText(this@MainActivity, "✅ Connection authorized! You can now install APKs.", Toast.LENGTH_LONG).show()
                refreshStatus()
            }

            result.onFailure { error ->
                Toast.makeText(this@MainActivity, "❌ Authorization failed: ${error.message}\n\nMake sure you tapped 'Always allow' on the prompt.", Toast.LENGTH_LONG).show()
                testConnectionButton.isEnabled = true
                testConnectionButton.text = "Test Connection"
            }
        }
    }

    private fun showUpdateDialog(updateInfo: UpdateChecker.UpdateInfo) {
        val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
        val message = buildString {
            append("A new version is available!\n\n")
            append("Current: $currentVersion\n")
            append("Latest: ${updateInfo.versionName}\n\n")
            if (updateInfo.releaseNotes.isNotBlank()) {
                append("What's new:\n")
                append(updateInfo.releaseNotes.take(200))
                if (updateInfo.releaseNotes.length > 200) {
                    append("...")
                }
                append("\n\n")
            }
            append("Note: The app will close during the update and restart with the new version.")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update Available")
            .setMessage(message)
            .setPositiveButton("Download & Install") { _, _ ->
                downloadAndInstallUpdate(updateInfo)
            }
            .setNegativeButton("Not Now", null)
            .setCancelable(true)
            .show()
    }

    private fun downloadAndInstallUpdate(updateInfo: UpdateChecker.UpdateInfo) {
        val progressDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Downloading Update")
            .setMessage("Downloading version ${updateInfo.versionName}...\n0%")
            .setCancelable(false)
            .create()

        progressDialog.show()

        lifecycleScope.launch {
            val result = UpdateManager.downloadAndInstall(
                this@MainActivity,
                updateInfo.downloadUrl,
                updateInfo.versionName
            ) { progress ->
                progressDialog.setMessage("Downloading version ${updateInfo.versionName}...\n$progress%")
            }

            progressDialog.dismiss()

            result.onSuccess { message ->
                // Show a toast before the app closes
                Toast.makeText(
                    this@MainActivity,
                    "Installing update via ADB...\nApp will restart shortly.",
                    Toast.LENGTH_LONG
                ).show()
                // Note: App will be killed by Android during the update process
            }

            result.onFailure { error ->
                androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("Update Failed")
                    .setMessage("Failed to install update: ${error.message}\n\nMake sure ADB is connected and authorized.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    companion object {
        /** Set by [PairingInputService] when it brings the app forward after pairing. */
        const val EXTRA_FROM_PAIRING = "com.anyapk.installer.FROM_PAIRING"

        // Process-wide so the prompt survives activity recreation and is shown
        // at most once per app session.
        private var updatePromptShown = false

        // Likewise, only try the self-grant once per session.
        private var permissionGrantAttempted = false
    }
}
