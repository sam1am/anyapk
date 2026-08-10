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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class InstallActivity : AppCompatActivity() {

    private lateinit var apkUri: Uri
    private lateinit var infoText: TextView
    private lateinit var installButton: Button

    /** What stands between the user and a working install. */
    private sealed class Blocker {
        /** Something the user has to do; [action] opens where they do it. */
        data class NeedsUser(
            val title: String,
            val message: String,
            val actionLabel: String,
            val action: () -> Unit
        ) : Blocker()

        object None : Blocker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_install)

        infoText = findViewById(R.id.infoText)
        installButton = findViewById(R.id.installButton)

        // Get APK from intent
        apkUri = intent.data ?: run {
            Toast.makeText(this, getString(R.string.error_no_apk), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = apkUri.lastPathSegment ?: "Unknown APK"
        infoText.text = getString(R.string.install_ready, fileName)

        installButton.setOnClickListener {
            installApk()
        }
    }

    private fun installApk() {
        lifecycleScope.launch {
            installButton.isEnabled = false

            when (val blocker = preflight()) {
                is Blocker.NeedsUser -> {
                    installButton.isEnabled = true
                    showBlockerDialog(blocker)
                }
                Blocker.None -> performInstall()
            }
        }
    }

    /**
     * Checks everything the install depends on before touching the APK, and quietly
     * switches wireless debugging back on when it can. Returns what still blocks the
     * install, or [Blocker.None] when the way is clear.
     */
    private suspend fun preflight(): Blocker {
        if (!isDeveloperOptionsEnabled()) {
            return Blocker.NeedsUser(
                title = "Developer Options Required",
                message = "anyapk installs APKs over wireless debugging, which lives in " +
                    "Developer Options.\n\nOpen Settings → About Phone and tap \"Build Number\" " +
                    "seven times, then try again.",
                actionLabel = "Open Settings",
                action = { openSettings() }
            )
        }

        if (!hasNotificationPermission()) {
            return Blocker.NeedsUser(
                title = "Notification Permission Required",
                message = "anyapk needs notification permission to pair with wireless " +
                    "debugging — the pairing code is entered by replying to a notification." +
                    "\n\nOpen anyapk to grant it.",
                actionLabel = "Open anyapk",
                action = { openMainActivity() }
            )
        }

        if (!SettingsManager.hasPairedBefore(this)) {
            return Blocker.NeedsUser(
                title = "Setup Not Finished",
                message = "anyapk hasn't been paired with wireless debugging yet.\n\n" +
                    "Open anyapk and follow the setup steps, then try this install again.",
                actionLabel = "Open anyapk",
                action = { openMainActivity() }
            )
        }

        if (!WirelessDebugging.isEnabled(this)) {
            infoText.text = "Enabling wireless debugging…"

            if (WirelessDebugging.enable(this)) {
                Toast.makeText(this, "Wireless debugging enabled", Toast.LENGTH_SHORT).show()
            } else {
                return Blocker.NeedsUser(
                    title = "Wireless Debugging Is Off",
                    message = "anyapk could not enable wireless debugging from here.\n\n" +
                        "Please turn it on manually in Developer Options → Wireless debugging, " +
                        "then try again.",
                    actionLabel = "Open Developer Options",
                    action = { openDeveloperOptions() }
                )
            }
        }

        return Blocker.None
    }

    private suspend fun performInstall() {
        var tempFile: File? = null
        try {
            // Copy APK to accessible location
            val file = File(cacheDir, "temp_install.apk")
            tempFile = file
            contentResolver.openInputStream(apkUri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }

            // Install using ADB
            infoText.text = getString(R.string.installing)

            val result = AdbInstaller.install(this@InstallActivity, file.absolutePath)

            result.onSuccess {
                Toast.makeText(this, getString(R.string.install_success), Toast.LENGTH_LONG).show()
                file.delete()
                finish()
            }

            result.onFailure { error ->
                val errorMsg = error.message ?: "Unknown error"
                Toast.makeText(this, getString(R.string.install_failed, errorMsg), Toast.LENGTH_LONG).show()
                installButton.isEnabled = true
                infoText.text = getString(R.string.install_failed, errorMsg)
                file.delete()
            }

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            installButton.isEnabled = true
            tempFile?.delete()
            e.printStackTrace()
        }
    }

    private fun showBlockerDialog(blocker: Blocker.NeedsUser) {
        infoText.text = getString(R.string.install_ready, apkUri.lastPathSegment ?: "Unknown APK")

        AlertDialog.Builder(this)
            .setTitle(blocker.title)
            .setMessage(blocker.message)
            .setPositiveButton(blocker.actionLabel) { _, _ -> blocker.action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun isDeveloperOptionsEnabled(): Boolean {
        return try {
            Settings.Global.getInt(
                contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
        } catch (e: Exception) {
            // If we can't tell, assume it's on rather than blocking a valid install.
            true
        }
    }

    private fun openMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun openDeveloperOptions() {
        if (!startSettingsIntent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) {
            openSettings()
        }
    }

    private fun openSettings() {
        if (!startSettingsIntent(Settings.ACTION_SETTINGS)) {
            Toast.makeText(this, "Please open Settings manually", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startSettingsIntent(action: String): Boolean {
        return try {
            startActivity(Intent(action))
            true
        } catch (e: Exception) {
            false
        }
    }
}
