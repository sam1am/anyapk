package com.anyapk.installer

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.anyapk.installer.databinding.ActivityInstallBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class InstallActivity : AppCompatActivity() {


    private var _binding: ActivityInstallBinding? = null
    private val binding get() = _binding!!

    private lateinit var apkUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        _binding = ActivityInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Get APK from intent
        apkUri = intent.data ?: run {
            Toast.makeText(this, getString(R.string.error_no_apk), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fileName = apkUri.lastPathSegment ?: "Unknown APK"
        binding.apply {
            infoText.text = getString(R.string.install_ready, fileName)

            installButton.setOnClickListener {
                installApk()
            }
        }
    }

    private fun installApk() {
        // Don't check status here - just try to install and handle errors
        // The status check in onCreate already did a basic check

        lifecycleScope.launch {
            try {
                // Copy APK to accessible location
                val tempFile = File(cacheDir, "temp_install.apk")
                contentResolver.openInputStream(apkUri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Install using ADB
                binding.apply {
                    installButton.isEnabled = false
                    infoText.text = getString(R.string.installing)
                }

                val result = AdbInstaller.install(this@InstallActivity, tempFile.absolutePath)

                result.onSuccess { message ->
                    Toast.makeText(this@InstallActivity, getString(R.string.install_success), Toast.LENGTH_LONG).show()
                    tempFile.delete()
                    finish()
                }

                result.onFailure { error ->
                    val errorMsg = error.message ?: "Unknown error"
                    Toast.makeText(this@InstallActivity, getString(R.string.install_failed, errorMsg), Toast.LENGTH_LONG).show()
                    binding.apply {
                        installButton.isEnabled = true
                        infoText.text = getString(R.string.install_failed, errorMsg)
                    }
                    tempFile.delete()
                }

            } catch (e: Exception) {
                Toast.makeText(this@InstallActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.installButton.isEnabled = true
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }
}
