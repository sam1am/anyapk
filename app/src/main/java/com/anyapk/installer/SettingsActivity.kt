package com.anyapk.installer

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var autoUpdateSwitch: SwitchMaterial
    private lateinit var useDeviceIpSwitch: SwitchMaterial
    private lateinit var customIpLayout: TextInputLayout
    private lateinit var customIpInput: TextInputEditText
    private lateinit var deviceIpLabel: TextView
    private lateinit var networkStatusText: TextView
    private lateinit var ipValidationError: TextView
    private lateinit var checkUpdateButton: Button
    private lateinit var saveButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Enable back button in action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        initViews()
        loadSettings()
        setupListeners()
        updateNetworkInfo()
    }

    private fun initViews() {
        autoUpdateSwitch = findViewById(R.id.autoUpdateSwitch)
        useDeviceIpSwitch = findViewById(R.id.useDeviceIpSwitch)
        customIpLayout = findViewById(R.id.customIpLayout)
        customIpInput = findViewById(R.id.customIpInput)
        deviceIpLabel = findViewById(R.id.deviceIpLabel)
        networkStatusText = findViewById(R.id.networkStatusText)
        ipValidationError = findViewById(R.id.ipValidationError)
        checkUpdateButton = findViewById(R.id.checkUpdateButton)
        saveButton = findViewById(R.id.saveButton)
    }

    private fun loadSettings() {
        // Load auto-update setting
        autoUpdateSwitch.isChecked = SettingsManager.isAutoUpdateEnabled(this)

        // Load IP settings
        val useDeviceIp = SettingsManager.shouldUseDeviceIp(this)
        useDeviceIpSwitch.isChecked = useDeviceIp

        // Load custom IP
        val customIp = SettingsManager.getCustomIp(this)
        customIpInput.setText(customIp)

        // Update UI based on IP mode
        updateIpInputState(useDeviceIp)
    }

    private fun setupListeners() {
        // Auto-update switch
        autoUpdateSwitch.setOnCheckedChangeListener { _, _ ->
            // No immediate action needed, will save on button press
        }

        // Use device IP switch
        useDeviceIpSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateIpInputState(isChecked)
            validateCurrentIp()
        }

        // Custom IP input - validate on text change
        customIpInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateCurrentIp()
            }
        })

        // Check for updates button
        checkUpdateButton.setOnClickListener {
            checkForUpdates()
        }

        // Save button
        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun updateIpInputState(useDeviceIp: Boolean) {
        // Enable/disable custom IP input based on checkbox
        customIpLayout.isEnabled = !useDeviceIp
        customIpInput.isEnabled = !useDeviceIp

        // Update switch text to show current device IP
        val deviceIp = NetworkUtils.getLocalIpAddress(this)
        if (deviceIp != null) {
            useDeviceIpSwitch.text = "Use Device Local IP: $deviceIp"
            deviceIpLabel.text = "Detected IP: $deviceIp"
        } else {
            useDeviceIpSwitch.text = "Use Device Local IP: (not detected)"
            deviceIpLabel.text = "No IP address detected. Make sure WiFi is connected."
            deviceIpLabel.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun updateNetworkInfo() {
        val status = NetworkUtils.getNetworkStatus(this)
        networkStatusText.text = "Network: $status"

        if (!NetworkUtils.isWifiConnected(this)) {
            networkStatusText.setTextColor(getColor(android.R.color.holo_orange_dark))
        }
    }

    private fun validateCurrentIp(): Boolean {
        // If using device IP, always valid
        if (useDeviceIpSwitch.isChecked) {
            ipValidationError.visibility = View.GONE
            customIpLayout.error = null
            return true
        }

        // Validate custom IP
        val customIp = customIpInput.text.toString().trim()

        if (customIp.isEmpty()) {
            ipValidationError.text = "Custom IP cannot be empty when device IP is disabled"
            ipValidationError.visibility = View.VISIBLE
            customIpLayout.error = " " // Show error state
            return false
        }

        if (!NetworkUtils.isValidIpAddress(customIp)) {
            ipValidationError.text = "Invalid IP address format. Example: 192.168.1.100"
            ipValidationError.visibility = View.VISIBLE
            customIpLayout.error = " " // Show error state
            return false
        }

        // Valid
        ipValidationError.visibility = View.GONE
        customIpLayout.error = null
        return true
    }

    private fun saveSettings() {
        // Validate IP configuration
        if (!validateCurrentIp()) {
            Toast.makeText(this, "Please fix the IP address configuration", Toast.LENGTH_SHORT).show()
            return
        }

        // Save auto-update setting
        SettingsManager.setAutoUpdateEnabled(this, autoUpdateSwitch.isChecked)

        // Save IP settings
        SettingsManager.setShouldUseDeviceIp(this, useDeviceIpSwitch.isChecked)

        // Save custom IP (even if not currently used)
        val customIp = customIpInput.text.toString().trim()
        SettingsManager.setCustomIp(this, customIp)

        // Show confirmation
        Toast.makeText(this, "Settings saved successfully", Toast.LENGTH_SHORT).show()

        // Close activity
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        // Refresh network info when returning to settings
        updateNetworkInfo()
        updateIpInputState(useDeviceIpSwitch.isChecked)
    }

    private fun checkForUpdates() {
        checkUpdateButton.isEnabled = false
        checkUpdateButton.text = "Checking..."

        lifecycleScope.launch {
            val updateInfo = UpdateChecker.checkForUpdate(this@SettingsActivity)

            if (updateInfo != null) {
                showUpdateDialog(updateInfo)
            } else {
                Toast.makeText(
                    this@SettingsActivity,
                    "You're running the latest version!",
                    Toast.LENGTH_SHORT
                ).show()
            }

            checkUpdateButton.isEnabled = true
            checkUpdateButton.text = "Check for Updates"
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
                this@SettingsActivity,
                updateInfo.downloadUrl,
                updateInfo.versionName
            ) { progress ->
                progressDialog.setMessage("Downloading version ${updateInfo.versionName}...\n$progress%")
            }

            progressDialog.dismiss()

            result.onSuccess { message ->
                // Show a toast before the app closes
                Toast.makeText(
                    this@SettingsActivity,
                    "Installing update via ADB...\nApp will restart shortly.",
                    Toast.LENGTH_LONG
                ).show()
                // Note: App will be killed by Android during the update process
            }

            result.onFailure { error ->
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Update Failed")
                    .setMessage("Failed to install update: ${error.message}\n\nMake sure ADB is connected and authorized.")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
}
