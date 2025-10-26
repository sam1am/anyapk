package com.anyapk.installer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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

        actionButton.setOnClickListener {
            showPairingDialog()
        }

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

            when (status) {
                AdbInstaller.ConnectionStatus.CONNECTED -> {
                    statusText.text = getString(R.string.status_ready)
                    actionButton.isEnabled = false
                    actionButton.text = getString(R.string.btn_connected)
                    testConnectionButton.visibility = Button.GONE
                    refreshButton.visibility = Button.GONE
                    selectApkButton.visibility = Button.VISIBLE
                }
                else -> {
                    // Show appropriate pairing instructions based on developer mode
                    statusText.text = if (isDeveloperModeEnabled) {
                        getString(R.string.status_needs_pairing)
                    } else {
                        getString(R.string.status_needs_dev_mode)
                    }
                    actionButton.isEnabled = true
                    actionButton.text = getString(R.string.btn_enter_code)
                    testConnectionButton.visibility = Button.GONE
                    selectApkButton.visibility = Button.GONE
                }
            }
        }
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
        val dialog = PairingDialogFragment()
        dialog.show(supportFragmentManager, "pairing")
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
}
