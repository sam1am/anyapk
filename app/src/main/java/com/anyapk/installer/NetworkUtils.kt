package com.anyapk.installer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utilities for network operations and IP address handling
 */
object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Gets the device's local IP address (WiFi preferred)
     * @return IP address as string or null if not available
     */
    fun getLocalIpAddress(context: Context): String? {
        try {
            // Try WiFi first (most common for wireless ADB)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null && wifiManager.isWifiEnabled) {
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        (ipInt shr 8) and 0xff,
                        (ipInt shr 16) and 0xff,
                        (ipInt shr 24) and 0xff
                    )
                }
            }

            // Fallback: enumerate network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()

                // Skip loopback and non-active interfaces
                if (networkInterface.isLoopback || !networkInterface.isUp) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Only want IPv4 addresses
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }

        return null
    }

    /**
     * Validates an IPv4 address
     * @param ip The IP address string to validate
     * @return true if valid IPv4 address, false otherwise
     */
    fun isValidIpAddress(ip: String?): Boolean {
        if (ip.isNullOrBlank()) {
            return false
        }

        val parts = ip.split(".")

        // Must have exactly 4 parts
        if (parts.size != 4) {
            return false
        }

        // Each part must be a number between 0-255
        for (part in parts) {
            try {
                val num = part.toInt()
                if (num < 0 || num > 255) {
                    return false
                }

                // Check for leading zeros (except for "0" itself)
                if (part.length > 1 && part.startsWith("0")) {
                    return false
                }
            } catch (e: NumberFormatException) {
                return false
            }
        }

        return true
    }

    /**
     * Checks if WiFi is currently connected
     */
    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Gets a human-readable network status
     */
    fun getNetworkStatus(context: Context): String {
        if (!isWifiConnected(context)) {
            return "No WiFi connection"
        }

        val ip = getLocalIpAddress(context)
        return if (ip != null) {
            "Connected - IP: $ip"
        } else {
            "WiFi connected but no IP address"
        }
    }
}
