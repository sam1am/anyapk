package com.anyapk.installer

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for app updates from GitHub releases
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"

    // TODO: Replace with your actual GitHub repo (username/repo-name)
    private const val GITHUB_REPO = "sam1am/anyapk"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val releaseNotes: String,
        val publishedAt: String
    )

    /**
     * Checks if a new version is available on GitHub
     * @return UpdateInfo if an update is available, null otherwise
     */
    suspend fun checkForUpdate(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val currentVersionName = getCurrentVersionName(context)

            Log.d(TAG, "Checking for updates. Current version: $currentVersionName")

            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection

            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Failed to check for updates. Response code: $responseCode")
                return@withContext null
            }

            val response = BufferedReader(InputStreamReader(connection.inputStream)).use {
                it.readText()
            }

            val json = JSONObject(response)
            val latestVersionName = json.getString("tag_name").removePrefix("v")

            Log.d(TAG, "Latest version on GitHub: $latestVersionName")

            // Find the APK download URL from assets
            val assets = json.getJSONArray("assets")
            val apkNames = mutableListOf<String>()
            val urlsByName = mutableMapOf<String, String>()

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkNames += name
                    urlsByName[name] = asset.getString("browser_download_url")
                }
            }

            val apkUrl = selectAsset(apkNames, currentVersionName, Build.SUPPORTED_ABIS.toList())
                ?.let { urlsByName[it] }

            if (apkUrl == null) {
                Log.e(TAG, "No APK file found in latest release")
                return@withContext null
            }

            // Compare the version *names*. The installed versionCode cannot be used here:
            // release builds derive it from the tag count, which drifts from the 0.0.N
            // version name, so a matching version would still look out of date.
            if (compareVersions(latestVersionName, currentVersionName) > 0) {
                val releaseNotes = json.optString("body", "No release notes available")
                val publishedAt = json.optString("published_at", "")

                Log.d(TAG, "Update available! $currentVersionName -> $latestVersionName")

                return@withContext UpdateInfo(
                    versionName = latestVersionName,
                    downloadUrl = apkUrl,
                    releaseNotes = releaseNotes,
                    publishedAt = publishedAt
                )
            } else {
                Log.d(TAG, "App is up to date")
                return@withContext null
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            return@withContext null
        }
    }

    /**
     * Picks the release asset this device should install.
     *
     * Releases carry one APK per ABI plus a universal fallback, and both a main and a
     * `-dev` build of each, so "the first .apk in the list" would happily hand an arm64
     * phone the x86_64 build or cross a user between channels. Assets are named
     * `anyapk[-dev]-<version>-<abi>.apk`.
     *
     * [abis] is [Build.SUPPORTED_ABIS], the device's own preference order, so a 64-bit
     * phone takes the arm64 build and falls back to the 32-bit one it can also run.
     * Universal is the last resort.
     */
    internal fun selectAsset(
        names: List<String>,
        currentVersionName: String,
        abis: List<String>
    ): String? {
        val wantDev = currentVersionName.contains("-dev", ignoreCase = true)
        val channel = names.filter { it.contains("-dev-", ignoreCase = true) == wantDev }
            .ifEmpty { return null }

        for (abi in abis) {
            channel.firstOrNull { it.endsWith("-$abi.apk", ignoreCase = true) }?.let { return it }
        }
        return channel.firstOrNull { it.endsWith("-universal.apk", ignoreCase = true) }
            ?: channel.first()
    }

    private fun getCurrentVersionName(context: Context): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    }

    /**
     * Compares two dotted version names numerically, segment by segment.
     * Any pre-release suffix ("0.0.10-dev") is ignored, so a dev build of a
     * version is treated as equal to the release of the same version.
     *
     * @return a positive number if [a] is newer than [b], 0 if equal, negative if older
     */
    internal fun compareVersions(a: String, b: String): Int {
        val partsA = versionParts(a)
        val partsB = versionParts(b)

        for (i in 0 until maxOf(partsA.size, partsB.size)) {
            val diff = (partsA.getOrNull(i) ?: 0) - (partsB.getOrNull(i) ?: 0)
            if (diff != 0) return diff
        }
        return 0
    }

    private fun versionParts(versionName: String): List<Int> {
        return versionName
            .substringBefore('-')
            .substringBefore('+')
            .trim()
            .split('.')
            .map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
