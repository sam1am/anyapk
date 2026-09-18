package com.anyapk.installer

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * Tells whether the user has unlocked Developer Options — as far as an app is allowed
 * to know.
 *
 * Since Android 17 QPR1 the system reports DEVELOPMENT_SETTINGS_ENABLED (and ADB_ENABLED)
 * as 0 to every third-party app, whatever the real value. A 0 there is therefore no
 * answer at all, and treating it as "off" strands the user on setup step 1 with no way
 * to reach pairing. On those releases the check gets out of the way instead: if
 * Developer Options really are locked, pairing simply won't find a service, and the
 * instructions for unlocking them are still on screen.
 */
object DeveloperOptions {
    // Build.VERSION_CODES has no name for this at our compileSdk.
    private const val ANDROID_17 = 37

    /** Whether the system still gives apps a truthful answer about Developer Options. */
    val isReadable: Boolean
        get() = Build.VERSION.SDK_INT < ANDROID_17

    fun isEnabled(context: Context): Boolean {
        return try {
            val reported = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0
            ) == 1
            reported || !isReadable
        } catch (e: Exception) {
            // If we can't tell, assume it's on rather than blocking a valid setup.
            true
        }
    }
}
