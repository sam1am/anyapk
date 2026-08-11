package com.anyapk.installer

import android.content.Context
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.*
import java.io.InputStream

object AdbInstaller {

    private const val LOCALHOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555
    private const val SHELL_READ_TIMEOUT_MS = 3000

    /** Long enough for a slow commit on a big app; short enough to not hang forever. */
    private const val COMMAND_TIMEOUT_MS = 60_000L
    private const val COMMIT_TIMEOUT_MS = 300_000L
    private const val TRANSFER_TIMEOUT_MS = 300_000L

    /** Wireless debugging pairing codes are always exactly six digits. */
    private val PAIRING_CODE_PATTERN = Regex("\\d{6}")

    /** A real pairing handshake completes in well under a second on the loopback. */
    private const val PAIRING_TIMEOUT_MS = 20_000L

    private const val RETRY_HINT =
        "Tap \"Pair device with pairing code\" again for a fresh code, then reply with the new one."

    /**
     * Hosts pairing attempts that may outlive the caller. Detached on purpose — see [pair].
     */
    private val pairingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Gets the target IP address from settings, falling back to localhost
     */
    private fun getTargetIp(context: Context): String {
        return SettingsManager.getTargetIpAddress(context) ?: LOCALHOST
    }

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

    /**
     * Pairs with the local wireless debugging service.
     *
     * The handshake underneath is a blocking socket read with no timeout of its own. A
     * wrong code makes adbd tear the session down mid-handshake, and the read then never
     * returns — which is why a mistyped code used to wedge the pairing notification until
     * the device was rebooted. Everything here exists to guarantee this returns.
     */
    suspend fun pair(context: Context, pairingCode: String, pairingPort: Int): Result<Boolean> {
        val code = pairingCode.trim()
        if (!PAIRING_CODE_PATTERN.matches(code)) {
            return Result.failure(
                Exception("That is not a pairing code. Enter the 6 digits shown in the \"Pair device with pairing code\" dialog.")
            )
        }
        if (pairingPort !in 1..65535) {
            return Result.failure(
                Exception("No pairing port found yet. Tap \"Pair device with pairing code\" in Wireless debugging, then try again.")
            )
        }

        val targetIp = getTargetIp(context)

        // Deliberately not a child of the caller's job. Cancelling a coroutine cannot
        // interrupt a thread parked in a blocking socket read, so on timeout the only
        // option is to abandon this thread and let it unwind whenever the socket
        // finally errors out. Awaiting it would defeat the timeout entirely.
        val attempt = pairingScope.async {
            AdbConnectionManager.getInstance(context).pair(targetIp, pairingPort, code)
        }

        val paired = try {
            withTimeoutOrNull(PAIRING_TIMEOUT_MS) { attempt.await() }
        } catch (e: CancellationException) {
            // The caller went away (the service was stopped). Must not be reported as a
            // pairing failure, or the handler would run against a dead service.
            attempt.cancel()
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            attempt.cancel()
            // The underlying messages are adbd protocol detail; keep the cause for the
            // log and show the user something they can act on.
            return Result.failure(Exception("Pairing failed. $RETRY_HINT", e))
        }

        return when (paired) {
            // pair() reports a rejected code by returning false rather than throwing;
            // ignoring the return value used to report a bad code as a success.
            true -> Result.success(true)
            false -> Result.failure(Exception("The device rejected that code. $RETRY_HINT"))
            null -> {
                attempt.cancel()
                Result.failure(Exception("Pairing timed out. $RETRY_HINT"))
            }
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

    /**
     * Runs a shell command over the existing ADB connection and returns whatever it
     * printed. Silent commands simply return an empty string once the read window
     * elapses, so callers that need certainty should verify the effect separately
     * rather than trusting the output.
     */
    suspend fun runShellCommand(context: Context, command: String): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        return@withContext try {
            val manager = AdbConnectionManager.getInstance(context)

            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Could not connect to ADB."))
            }

            stream = manager.openStream("shell:$command")
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)

            var totalWait = 0
            while (totalWait < SHELL_READ_TIMEOUT_MS) {
                if (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead <= 0) break
                    output.append(String(buffer, 0, bytesRead))
                } else {
                    kotlinx.coroutines.delay(100)
                    totalWait += 100
                }
            }

            // Leave the shared connection open; the caller is usually mid-flow.
            stream.close()

            Result.success(output.toString().trim())
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                stream?.close()
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
            Result.failure(e)
        }
    }

    suspend fun install(context: Context, apkPath: String): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        var manager: AbsAdbConnectionManager? = null
        try {
            // Invalidate cache before install attempt
            lastConnectionCheck = 0

            // Create a NEW manager instance for this install to avoid stale connections
            manager = createManager(context)

            // Connect to local ADB using auto-discovery
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Failed to connect to ADB. Make sure wireless debugging is enabled and you've paired."))
            }

            // Use proper install protocol - stream the APK data
            val apkFile = java.io.File(apkPath)
            val apkSize = apkFile.length()

            // Open install stream with size. `-d` allows downgrading to an older
            // versionCode; for fresh installs and upgrades it's a no-op.
            stream = manager.openStream("exec:cmd package install -d ${bypassFlag()}-S $apkSize")

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

            // Read the response with blocking reads. `inputStream.available()` is
            // not reliable on ADB piped streams, so the previous polling approach
            // often missed the response even when the install actually succeeded.
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val readBuffer = ByteArray(1024)

            kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                kotlinx.coroutines.runInterruptible {
                    while (true) {
                        val n = try {
                            inputStream.read(readBuffer)
                        } catch (e: java.io.IOException) {
                            -1
                        }
                        if (n <= 0) break
                        output.append(String(readBuffer, 0, n))
                        val current = output.toString()
                        if (current.contains("Success", ignoreCase = true) ||
                            current.contains("Failure", ignoreCase = true)) {
                            break
                        }
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

    /**
     * Installs a set of APKs — a base plus its splits — as one atomic session, the same
     * way `adb install-multiple` does: create a session, stream each piece into it, then
     * commit. A session that is not committed is abandoned so it doesn't sit around
     * holding disk space on the device.
     *
     * [sources] must start with the base APK. [onProgress] is called on the main thread.
     */
    suspend fun installMultiple(
        context: Context,
        sources: List<PackageBundle.Source>,
        onProgress: suspend (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) {
            return@withContext Result.failure(Exception("No APKs to install"))
        }

        lastConnectionCheck = 0
        var manager: AbsAdbConnectionManager? = null
        var sessionId: String? = null

        try {
            manager = createManager(context)
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Failed to connect to ADB. Make sure wireless debugging is enabled and you've paired."))
            }

            val totalSize = sources.sumOf { it.size }
            val created = runExec(manager, "cmd package install-create -r -d ${bypassFlag()}-S $totalSize")
            sessionId = SESSION_ID.find(created)?.groupValues?.get(1)
                ?: return@withContext Result.failure(
                    Exception(created.ifEmpty { "The package manager refused to open an install session" })
                )

            sources.forEachIndexed { index, source ->
                // The split name only has to be unique within the session; adb uses the
                // same index-prefixed form, which keeps duplicate file names apart.
                val splitName = "${index}_${PackageBundle.sanitizeFileName(source.name)}"
                val label = "${index + 1}/${sources.size} · ${source.name}"

                val written = manager.openStream(
                    "exec:cmd package install-write -S ${source.size} $sessionId $splitName -"
                ).use { stream ->
                    val output = stream.openOutputStream()
                    source.open().use { input ->
                        copyWithProgress(input, output, source.size) { percent ->
                            onMain { onProgress("Sending $label — $percent%") }
                        }
                    }
                    output.flush()
                    readResponse(stream, TRANSFER_TIMEOUT_MS)
                }

                if (written.contains("Failure", ignoreCase = true)) {
                    throw Exception("Could not stage ${source.name}: $written")
                }
            }

            onMain { onProgress("Committing install…") }
            val committed = runExec(manager, "cmd package install-commit $sessionId", COMMIT_TIMEOUT_MS)

            if (committed.contains("Success", ignoreCase = true)) {
                sessionId = null // committed sessions must not be abandoned
                lastConnectionCheck = System.currentTimeMillis()
                lastConnectionStatus = ConnectionStatus.CONNECTED
                Result.success("Installation successful")
            } else {
                Result.failure(Exception(committed.ifEmpty { "Install was not confirmed by the package manager" }))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            val orphaned = sessionId
            if (orphaned != null && manager != null) {
                try {
                    runExec(manager, "cmd package install-abandon $orphaned", 10_000L)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                manager?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Copies an expansion file into `/sdcard/Android/obb/<package>/`. The app itself can't
     * write there — scoped storage walls off other packages' obb directories even with
     * all-files access — so this goes over ADB, where the shell user still can.
     */
    // The path is resolved by the remote shell, not by this process, so the usual
    // Environment.getExternalStorageDirectory() advice doesn't apply.
    @android.annotation.SuppressLint("SdCardPath")
    suspend fun pushObb(
        context: Context,
        obb: PackageBundle.Obb,
        onProgress: suspend (String) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        val packageName = obb.packageName
        if (!VALID_PACKAGE.matches(packageName)) {
            return@withContext Result.failure(Exception("\"$packageName\" is not a valid package name"))
        }

        val directory = "/sdcard/Android/obb/$packageName"
        val remotePath = "$directory/${obb.fileName}"
        var manager: AbsAdbConnectionManager? = null

        try {
            manager = createManager(context)
            if (!manager.autoConnect(context, 10000)) {
                return@withContext Result.failure(Exception("Failed to connect to ADB."))
            }

            // `head -c` stops after exactly this many bytes and exits, which is what ends
            // the command — the ADB protocol has no way to half-close a stream, so `cat`
            // would simply block forever waiting on an EOF that never arrives. The marker
            // is how we learn the shell got that far at all.
            manager.openStream(
                "exec:mkdir -p $directory && head -c ${obb.source.size} > $remotePath && echo $PUSH_MARKER"
            ).use { stream ->
                val output = stream.openOutputStream()
                obb.source.open().use { input ->
                    copyWithProgress(input, output, obb.source.size) { percent ->
                        onMain { onProgress("Copying ${obb.fileName} — $percent%") }
                    }
                }
                output.flush()
                // Whatever the shell says here is advisory; the size check below decides.
                readResponse(stream, TRANSFER_TIMEOUT_MS) { it.contains(PUSH_MARKER) }
            }

            val reported = runExec(manager, "stat -c %s $remotePath", 15_000L) { it.contains("\n") }
                .trim().lines().lastOrNull()?.trim()?.toLongOrNull()

            if (reported == obb.source.size) {
                Result.success(remotePath)
            } else {
                Result.failure(
                    Exception(
                        "Copied ${reported ?: 0} of ${obb.source.size} bytes to $remotePath"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        } finally {
            try {
                manager?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // -- Shared plumbing ------------------------------------------------------------------

    private val SESSION_ID = Regex("\\[(\\d+)]")
    private val VALID_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Printed by the remote shell once a pushed file is fully written. */
    private const val PUSH_MARKER = "ANYAPK_PUSH_DONE"

    /**
     * A fresh manager per operation. Reusing one across installs picks up stale
     * connections, which fail in ways that look like a pairing problem.
     */
    private fun createManager(context: Context): AbsAdbConnectionManager {
        val manager = object : AbsAdbConnectionManager() {
            private val delegate = AdbConnectionManager.getInstance(context)

            override fun getPrivateKey() = delegate.getPrivateKey()
            override fun getCertificate() = delegate.getCertificate()
            override fun getDeviceName() = delegate.getDeviceName()
        }
        manager.setApi(android.os.Build.VERSION.SDK_INT)
        return manager
    }

    /**
     * On Android 14+ (API 34) the package manager rejects APKs whose targetSdk is below
     * 23 unless this flag is set. Older Android versions don't recognize the flag, so
     * only add it when needed.
     */
    private fun bypassFlag(): String {
        return if (android.os.Build.VERSION.SDK_INT >= 34) "--bypass-low-target-sdk-block " else ""
    }

    private suspend fun runExec(
        manager: AbsAdbConnectionManager,
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        isComplete: ((String) -> Boolean)? = null
    ): String {
        return manager.openStream("exec:$command").use { stream ->
            if (isComplete == null) readResponse(stream, timeoutMs)
            else readResponse(stream, timeoutMs, isComplete)
        }
    }

    /**
     * Reads a command's output until it says how it went, or the timeout elapses.
     * `available()` is not reliable on ADB piped streams, so this blocks on read and
     * leans on the timeout instead of polling.
     */
    private suspend fun readResponse(
        stream: AdbStream,
        timeoutMs: Long,
        isComplete: (String) -> Boolean = { it.contains("Success", ignoreCase = true) || it.contains("Failure", ignoreCase = true) }
    ): String {
        val output = StringBuilder()
        val input = stream.openInputStream()
        val buffer = ByteArray(1024)

        withTimeoutOrNull(timeoutMs) {
            runInterruptible {
                while (true) {
                    val read = try {
                        input.read(buffer)
                    } catch (e: java.io.IOException) {
                        -1
                    }
                    if (read <= 0) break
                    output.append(String(buffer, 0, read))
                    if (isComplete(output.toString())) break
                }
            }
        }

        return output.toString().trim()
    }

    private suspend fun copyWithProgress(
        input: InputStream,
        output: java.io.OutputStream,
        total: Long,
        onPercent: suspend (Int) -> Unit
    ) {
        val buffer = ByteArray(64 * 1024)
        var sent = 0L
        var lastReported = -1

        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            sent += read

            if (total > 0) {
                val percent = ((sent * 100) / total).toInt()
                if (percent != lastReported) {
                    lastReported = percent
                    onPercent(percent)
                }
            }
            // Long transfers must stay cancellable when the user backs out.
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun onMain(block: suspend () -> Unit) = withContext(Dispatchers.Main) { block() }
}
