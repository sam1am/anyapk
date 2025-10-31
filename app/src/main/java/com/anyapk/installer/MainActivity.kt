package com.anyapk.installer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
            selectApkLauncher.launch("application/vnd.android.package-archive")
        }
    }

    override fun onResume() {
        super.onResume()
        checkStatus()
    }

    private fun checkStatus() {
        lifecycleScope.launch {
            val status = withContext(Dispatchers.IO) {
                AdbInstaller.getConnectionStatus(this@MainActivity)
            }

            val isDeveloperModeEnabled = isDeveloperOptionsEnabled()
            val hasNotificationPermission = checkNotificationPermission()

            when (status) {
                AdbInstaller.ConnectionStatus.CONNECTED -> {
                    showConnectedState()
                }
                else -> {
                    showSetupChecklist(isDeveloperModeEnabled, hasNotificationPermission)
                }
            }
        }
    }

    private fun checkNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // On older Android versions, notification permission is auto-granted
            true
        }
    }

    private fun showConnectedState() {
        statusText.text = "✅ Ready to Install APKs\n\nYou're all set! Open any APK file and select anyapk to install."
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
            append("$step2 Step 2: Grant Notification Permission\n")
            if (!notificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                append("   • Required to enter pairing codes\n")
                append("   • Tap button below to grant\n\n")
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
            !notificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                actionButton.text = "Grant Notification Permission"
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(
                    this,
                    "✅ Notification permission granted!",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Notification permission is required for pairing. Please enable it in Settings.",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Refresh status to update checklist
            checkStatus()
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

    companion object {
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
