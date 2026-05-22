package com.anyapk.installer

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app settings using SharedPreferences
 */
object SettingsManager {
    private const val PREFS_NAME = "anyapk_settings"

    // Setting keys
    private const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
    private const val KEY_USE_DEVICE_IP = "use_device_ip"
    private const val KEY_CUSTOM_IP = "custom_ip"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Auto-update setting
    var isAutoUpdateEnabled: (Context) -> Boolean = { context ->
        getPrefs(context).getBoolean(KEY_AUTO_UPDATE_ENABLED, true) // Enabled by default
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()
    }

    // IP address settings
    fun shouldUseDeviceIp(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_USE_DEVICE_IP, true) // Use device IP by default
    }

    fun setShouldUseDeviceIp(context: Context, useDeviceIp: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_USE_DEVICE_IP, useDeviceIp).apply()
    }

    fun getCustomIp(context: Context): String {
        return getPrefs(context).getString(KEY_CUSTOM_IP, "") ?: ""
    }

    fun setCustomIp(context: Context, ip: String) {
        getPrefs(context).edit().putString(KEY_CUSTOM_IP, ip).apply()
    }

    /**
     * Gets the IP address to use for ADB connections
     * Returns device IP if enabled, otherwise returns custom IP
     * @return IP address or null if not available/configured
     */
    fun getTargetIpAddress(context: Context): String? {
        return if (shouldUseDeviceIp(context)) {
            // Use device's local IP
            NetworkUtils.getLocalIpAddress(context)
        } else {
            // Use custom IP (if valid)
            val customIp = getCustomIp(context)
            if (NetworkUtils.isValidIpAddress(customIp)) {
                customIp
            } else {
                null
            }
        }
    }

    /**
     * Gets a summary of current settings for display
     */
    fun getSettingsSummary(context: Context): String {
        val autoUpdate = if (isAutoUpdateEnabled(context)) "Enabled" else "Disabled"
        val ipMode = if (shouldUseDeviceIp(context)) {
            val deviceIp = NetworkUtils.getLocalIpAddress(context)
            "Device IP ($deviceIp)"
        } else {
            "Custom IP (${getCustomIp(context)})"
        }

        return "Auto-update: $autoUpdate\nIP Mode: $ipMode"
    }
}
