package com.anyapk.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers which release asset an update installs.
 *
 * Releases carry one APK per ABI plus a universal fallback, in both a main and a `-dev`
 * flavour, so a release page holds eight APKs that all look alike. Picking the wrong one
 * hands the user an APK their device cannot run, or silently moves them between
 * channels; either way the failure only shows up after the download.
 */
class UpdateCheckerAssetTest {

    private val release = listOf(
        "anyapk-0.0.6-arm64-v8a.apk",
        "anyapk-0.0.6-armeabi-v7a.apk",
        "anyapk-0.0.6-x86_64.apk",
        "anyapk-0.0.6-universal.apk",
        "anyapk-dev-0.0.6-arm64-v8a.apk",
        "anyapk-dev-0.0.6-armeabi-v7a.apk",
        "anyapk-dev-0.0.6-x86_64.apk",
        "anyapk-dev-0.0.6-universal.apk",
    )

    private fun pick(version: String, vararg abis: String) =
        UpdateChecker.selectAsset(release, version, abis.toList())

    // -- The device's own ABI order decides ------------------------------------------

    @Test
    fun `64-bit arm device takes the arm64 build`() {
        assertEquals("anyapk-0.0.6-arm64-v8a.apk", pick("0.0.5", "arm64-v8a", "armeabi-v7a"))
    }

    @Test
    fun `32-bit only device takes the v7a build`() {
        assertEquals("anyapk-0.0.6-armeabi-v7a.apk", pick("0.0.5", "armeabi-v7a"))
    }

    @Test
    fun `x86_64 device is not handed an arm build`() {
        assertEquals("anyapk-0.0.6-x86_64.apk", pick("0.0.5", "x86_64", "x86"))
    }

    /** armeabi-v7a is listed first only because the emulator reports it first. */
    @Test
    fun `abi preference order is honoured over list order`() {
        assertEquals("anyapk-0.0.6-armeabi-v7a.apk", pick("0.0.5", "armeabi-v7a", "arm64-v8a"))
    }

    // -- Channels must not cross ------------------------------------------------------

    @Test
    fun `dev build stays on the dev channel`() {
        assertEquals("anyapk-dev-0.0.6-arm64-v8a.apk", pick("0.0.5-dev", "arm64-v8a"))
    }

    @Test
    fun `release build is never handed a dev asset`() {
        val devOnly = release.filter { it.contains("-dev-") }
        assertNull(UpdateChecker.selectAsset(devOnly, "0.0.5", listOf("arm64-v8a")))
    }

    // -- Fallbacks --------------------------------------------------------------------

    @Test
    fun `unknown abi falls back to universal rather than a wrong arch`() {
        assertEquals("anyapk-0.0.6-universal.apk", pick("0.0.5", "riscv64"))
    }

    /** Older releases predate the split, so their single asset must still be found. */
    @Test
    fun `a release with one unsuffixed apk still resolves`() {
        val old = listOf("anyapk-0.0.5.apk")
        assertEquals("anyapk-0.0.5.apk", UpdateChecker.selectAsset(old, "0.0.4", listOf("arm64-v8a")))
    }

    @Test
    fun `a release with no apks resolves to nothing`() {
        assertNull(UpdateChecker.selectAsset(emptyList(), "0.0.5", listOf("arm64-v8a")))
    }
}
