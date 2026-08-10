package com.anyapk.installer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class InstallActivity : AppCompatActivity() {

    private lateinit var apkUri: Uri
    private lateinit var infoText: TextView
    private lateinit var installButton: Button

    /** The original file name, which is the only way to recognise a bare .obb. */
    private lateinit var displayName: String

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

        displayName = resolveDisplayName(apkUri)
        infoText.text = getString(R.string.install_ready, displayName)

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
        val staged = File(cacheDir, "temp_install.bin")
        try {
            infoText.text = getString(R.string.reading_package)
            withContext(Dispatchers.IO) {
                contentResolver.openInputStream(apkUri)?.use { input ->
                    FileOutputStream(staged).use { output -> input.copyTo(output) }
                } ?: throw IOException("Could not read the selected file")
            }

            val opened = withContext(Dispatchers.IO) {
                PackageBundle.open(this@InstallActivity, staged, displayName)
            }

            opened.use {
                when (val payload = it.payload) {
                    is PackageBundle.Payload.Apk -> installSingle(payload.file)
                    is PackageBundle.Payload.Split -> installSplits(payload)
                    is PackageBundle.Payload.ObbOnly ->
                        pushExpansionFiles(listOf(payload.obb), appInstalled = false)
                }
            }
        } catch (e: PackageBundle.UnsupportedException) {
            showUnsupported(e.message ?: "This file can't be installed.")
        } catch (e: Exception) {
            e.printStackTrace()
            reportFailure(e.message ?: "Unknown error")
        } finally {
            staged.delete()
        }
    }

    private suspend fun installSingle(apk: File) {
        infoText.text = getString(R.string.installing)
        finishWith(AdbInstaller.install(this, apk.absolutePath))
    }

    private suspend fun installSplits(payload: PackageBundle.Payload.Split) {
        if (payload.dropped.isNotEmpty()) {
            Log.i(TAG, "Skipping splits this device doesn't need: ${payload.dropped.joinToString(", ")}")
        }

        val total = payload.apks.size + payload.dropped.size
        infoText.text = getString(R.string.installing_parts, payload.apks.size, total)

        val result = AdbInstaller.installMultiple(this, payload.apks) { progress ->
            infoText.text = progress
        }

        result.onFailure {
            reportFailure(it.message ?: "Unknown error")
            return
        }

        if (payload.obbs.isNotEmpty()) {
            pushExpansionFiles(payload.obbs, appInstalled = true)
            return
        }

        finishWith(result)
    }

    /**
     * Copies expansion files into place, after the app itself is installed when they came
     * from the same archive. The app is already usable by then, so a failure here is
     * reported without pretending the whole install came apart.
     */
    private suspend fun pushExpansionFiles(obbs: List<PackageBundle.Obb>, appInstalled: Boolean) {
        val failures = mutableListOf<String>()

        for (obb in obbs) {
            infoText.text = getString(R.string.copying_expansion, obb.fileName)
            AdbInstaller.pushObb(this, obb) { progress ->
                infoText.text = progress
            }.onFailure { failures += "${obb.fileName}: ${it.message}" }
        }

        if (failures.isEmpty()) {
            val message =
                if (appInstalled) getString(R.string.install_success)
                else getString(R.string.expansion_success, obbs.first().packageName)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        } else {
            val preface =
                if (appInstalled) "The app is installed, but its expansion files did not copy:\n"
                else "The expansion file did not copy:\n"
            reportFailure(preface + failures.joinToString("\n"))
        }
    }

    private fun finishWith(result: Result<String>) {
        result.onSuccess {
            Toast.makeText(this, getString(R.string.install_success), Toast.LENGTH_LONG).show()
            finish()
        }
        result.onFailure { reportFailure(it.message ?: "Unknown error") }
    }

    private fun reportFailure(message: String) {
        Toast.makeText(this, getString(R.string.install_failed, message), Toast.LENGTH_LONG).show()
        infoText.text = getString(R.string.install_failed, message)
        installButton.isEnabled = true
    }

    private fun showUnsupported(message: String) {
        infoText.text = getString(R.string.install_ready, displayName)
        installButton.isEnabled = true

        AlertDialog.Builder(this)
            .setTitle(R.string.unsupported_title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * The file name as the user knows it. [Uri.getLastPathSegment] on a document URI is
     * usually an opaque id, and the extension is what tells a bare .obb apart from the
     * ZIP archive it technically is.
     */
    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst() && cursor.columnCount > 0) {
                            cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { return it }
                        }
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Unknown package"
    }

    companion object {
        private const val TAG = "InstallActivity"
    }

    private fun showBlockerDialog(blocker: Blocker.NeedsUser) {
        infoText.text = getString(R.string.install_ready, displayName)

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
