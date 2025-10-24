package com.anyapk.installer

import android.content.Context
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*

object AdbInstaller {

    private const val LOCALHOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555

    enum class ConnectionStatus {
        NOT_CONNECTED,
        CONNECTED,
        NEEDS_PAIRING,
        ERROR
    }

    // Keep track of connection state without constantly reconnecting
    @Volatile
    private var lastConnectionCheck: Long = 0
    @Volatile
    private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING
    private const val CONNECTION_CHECK_CACHE_MS = 2000 // Cache for 2 seconds

    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {
        // Use cached status if recent (unless forced)
        val now = System.currentTimeMillis()
        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CHECK_CACHE_MS) {
            return lastConnectionStatus
        }

        var stream: AdbStream? = null
        val status = try {
            val manager = AdbConnectionManager.getInstance(context)

            // Try to auto-connect using service discovery (works after pairing)
            if (!manager.autoConnect(context, 3000)) {
                ConnectionStatus.NEEDS_PAIRING
            } else {
                // Actually test the connection with a simple command
                try {
                    stream = manager.openStream("shell:echo test")
                    val buffer = ByteArray(128)
                    val bytesRead = stream.openInputStream().read(buffer)
                    stream.close()

                    // If we got a response, we're connected and authorized
                    if (bytesRead > 0) {
                        ConnectionStatus.CONNECTED
                    } else {
                        ConnectionStatus.NEEDS_PAIRING
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    try {
                        stream?.close()
                    } catch (ex: Exception) {
                        ex.printStackTrace()
                    }
                    // Don't close manager here - let it be reused
                    ConnectionStatus.NEEDS_PAIRING
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionStatus.NEEDS_PAIRING
        }

        lastConnectionCheck = now
        lastConnectionStatus = status
        return status
    }

    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)
            // Pair with the device
            manager.pair(LOCALHOST, pairingPort, pairingCode)
            Result.success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun testConnection(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)

            // Connect to local ADB - this should trigger authorization prompt
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Could not connect to ADB. Make sure wireless debugging is enabled."))
            }

            // Try to execute a simple command to verify authorization
            stream = manager.openStream("shell:echo test")
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(128)
            var bytesRead: Int

            // Read with timeout
            var totalWait = 0
            while (totalWait < 5000) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                        break
                    }
                }
                kotlinx.coroutines.delay(100)
                totalWait += 100
            }

            stream.close()
            manager.close()

            if (output.contains("test")) {
                Result.success(true)
            } else {
                Result.failure(Exception("Connection test failed. Did you authorize the prompt?"))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(Exception("Authorization required. Check for 'Allow USB debugging?' prompt and tap 'Always allow'."))
        }
    }

    suspend fun install(context: Context, apkPath: String): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        var manager: io.github.muntashirakon.adb.AbsAdbConnectionManager? = null
        try {
            // Invalidate cache before install attempt
            lastConnectionCheck = 0

            // Create a NEW manager instance for this install to avoid stale connections
            manager = object : io.github.muntashirakon.adb.AbsAdbConnectionManager() {
                private val delegate = AdbConnectionManager.getInstance(context)

                override fun getPrivateKey() = delegate.getPrivateKey()
                override fun getCertificate() = delegate.getCertificate()
                override fun getDeviceName() = delegate.getDeviceName()
            }
            manager.setApi(android.os.Build.VERSION.SDK_INT)

            // Connect to local ADB using auto-discovery
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Failed to connect to ADB. Make sure wireless debugging is enabled and you've paired."))
            }

            // Use proper install protocol - stream the APK data
            val apkFile = java.io.File(apkPath)
            val apkSize = apkFile.length()

            // Open install stream with size
            stream = manager.openStream("exec:cmd package install -S $apkSize")

            // Stream the APK data
            val outputStream = stream.openOutputStream()
            java.io.FileInputStream(apkFile).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }

            // Read the response
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)
            var bytesRead: Int
            var totalWait = 0
            val maxWait = 30000 // 30 seconds for install

            // Read with timeout
            while (totalWait < maxWait) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer)
                    if (bytesRead > 0) {
                        output.append(String(buffer, 0, bytesRead))
                    }
                    if (bytesRead == -1) break
                } else {
                    kotlinx.coroutines.delay(100)
                    totalWait += 100
                    // Check if we got a complete response
                    val currentOutput = output.toString()
                    if (currentOutput.contains("Success") || currentOutput.contains("Failure")) {
                        break
                    }
                }
            }

            val result = output.toString().trim()
            stream.close()

            // Check for success
            if (result.contains("Success", ignoreCase = true)) {
                // Update cache to show we're still connected
                lastConnectionCheck = System.currentTimeMillis()
                lastConnectionStatus = ConnectionStatus.CONNECTED
                Result.success("Installation successful")
            } else {
                Result.failure(Exception(result.ifEmpty { "Unknown error" }))
            }

        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            try {
                manager?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(e)
        } finally {
            try {
                manager?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
