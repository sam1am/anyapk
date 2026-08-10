package com.anyapk.installer

import com.anyapk.installer.PackageBundle.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Covers the naming heuristics that decide which pieces of a bundle get installed.
 * Every producer names its splits differently, and a split misread as "required" is
 * installed needlessly while one misread as a config split is silently dropped.
 */
class PackageBundleTest {

    private fun kindOf(fileName: String): Kind =
        PackageBundle.kindOf(PackageBundle.qualifierOf(fileName)?.replace('-', '_')?.lowercase(Locale.ROOT))

    // -- Base and feature splits must always survive selection ------------------------

    @Test
    fun `base apks are required`() {
        assertEquals(Kind.REQUIRED, kindOf("base.apk"))
        assertEquals(Kind.REQUIRED, kindOf("base-master.apk"))
        assertEquals(Kind.REQUIRED, kindOf("com.example.app.apk"))
        assertEquals(Kind.REQUIRED, kindOf("split_dynamic_feature.apk"))
    }

    @Test
    fun `a version suffix is not mistaken for a qualifier`() {
        assertEquals(Kind.REQUIRED, kindOf("com.example.app_1.2.3.apk"))
        assertEquals(Kind.REQUIRED, kindOf("MyApp-release-1.apk"))
    }

    // -- Config splits, in both common naming schemes ---------------------------------

    @Test
    fun `play and SAI style config splits are classified`() {
        assertEquals(Kind.ABI, kindOf("split_config.arm64_v8a.apk"))
        assertEquals(Kind.ABI, kindOf("config.armeabi_v7a.apk"))
        assertEquals(Kind.DENSITY, kindOf("split_config.xxhdpi.apk"))
        assertEquals(Kind.LOCALE, kindOf("split_config.en.apk"))
        assertEquals(Kind.LOCALE, kindOf("split_config.zh_CN.apk"))
    }

    @Test
    fun `bundletool style config splits are classified`() {
        assertEquals(Kind.ABI, kindOf("base-arm64_v8a.apk"))
        assertEquals(Kind.ABI, kindOf("base-x86_64.apk"))
        assertEquals(Kind.DENSITY, kindOf("base-xhdpi.apk"))
        assertEquals(Kind.LOCALE, kindOf("base-de.apk"))
    }

    @Test
    fun `a feature module's own config split is classified by its qualifier`() {
        assertEquals(Kind.LOCALE, kindOf("split_dynamic_feature.config.fr.apk"))
        assertEquals(Kind.ABI, kindOf("split_dynamic_feature.config.arm64_v8a.apk"))
    }

    @Test
    fun `density-independent splits are kept apart from sized ones`() {
        assertEquals(Kind.DENSITY, kindOf("split_config.nodpi.apk"))
        assertEquals(Kind.DENSITY, kindOf("split_config.anydpi.apk"))
    }

    @Test
    fun `an unrecognised qualifier is kept rather than dropped`() {
        assertEquals(Kind.REQUIRED, kindOf("split_config.something_new.apk"))
    }

    // -- Qualifier extraction ---------------------------------------------------------

    @Test
    fun `qualifiers are extracted from the end of the name`() {
        assertEquals("arm64_v8a", PackageBundle.qualifierOf("split_config.arm64_v8a.apk"))
        assertEquals("master", PackageBundle.qualifierOf("base-master.apk"))
        assertNull(PackageBundle.qualifierOf("base.apk"))
    }

    // -- Expansion files --------------------------------------------------------------

    @Test
    fun `obb package comes from the archive path when there is one`() {
        assertEquals(
            "com.example.game",
            PackageBundle.packageFromObbPath("Android/obb/com.example.game/main.1.com.example.game.obb")
        )
        assertEquals(
            "com.example.game",
            PackageBundle.packageFromObbPath("XAPK/Android/obb/com.example.game/patch.2.obb")
        )
        assertNull(PackageBundle.packageFromObbPath("assets/data.obb"))
    }

    @Test
    fun `obb package falls back to the conventional file name`() {
        assertEquals("com.example.game", PackageBundle.packageFromObbName("main.1234.com.example.game.obb"))
        assertEquals("com.example.game", PackageBundle.packageFromObbName("patch.7.com.example.game.obb"))
        assertNull(PackageBundle.packageFromObbName("expansion.obb"))
    }

    @Test
    fun `remote names are stripped of anything the shell would reinterpret`() {
        assertEquals("main.1.com.example.obb", PackageBundle.sanitizeFileName("main.1.com.example.obb"))
        assertEquals("bad_name_.apk", PackageBundle.sanitizeFileName("bad name;.apk"))
        assertEquals("file.apk", PackageBundle.sanitizeFileName("dir/sub/file.apk"))
    }
}
