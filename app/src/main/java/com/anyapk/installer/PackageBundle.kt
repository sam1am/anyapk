package com.anyapk.installer

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Works out what the user actually handed us — a plain APK, a split bundle
 * (.apks/.apkm/.xapk), an app bundle, or a bare expansion file — and returns exactly
 * the pieces that belong on this device.
 *
 * Nothing is unpacked to disk. Split APKs and OBBs are streamed straight out of the
 * archive, so installing a 2 GB XAPK costs no more cache space than the file itself.
 */
object PackageBundle {
    private const val TAG = "PackageBundle"

    /** A file inside the package, opened lazily so nothing large is held in memory. */
    class Source(val name: String, val size: Long, private val opener: () -> InputStream) {
        fun open(): InputStream = opener()
    }

    /** An expansion file, and the package whose obb directory it belongs in. */
    class Obb(val packageName: String, val fileName: String, val source: Source)

    sealed class Payload {
        /** An ordinary single APK, already staged on disk. */
        data class Apk(val file: File) : Payload()

        /**
         * A base APK plus the splits that match this device, in install order, along
         * with any expansion files riding in the same archive. [dropped] names the
         * splits left out, purely so the user can be told.
         */
        class Split(val apks: List<Source>, val obbs: List<Obb>, val dropped: List<String>) : Payload()

        /** An expansion file with no APK to install alongside it. */
        class ObbOnly(val obb: Obb) : Payload()
    }

    /** Keeps the archive open for as long as [payload] is still being streamed. */
    class Opened(private val zip: ZipFile?, val payload: Payload) : Closeable {
        override fun close() {
            try {
                zip?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to close archive", e)
            }
        }
    }

    /** The file is something anyapk cannot install; the message explains why, to the user. */
    class UnsupportedException(message: String) : Exception(message)

    private val ABIS = setOf(
        "armeabi", "armeabi_v7a", "arm64_v8a", "x86", "x86_64", "mips", "mips64", "riscv64"
    )

    private val DENSITIES = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640
    )

    /** Resource qualifiers still use the pre-1989 ISO codes; device locales may not. */
    private val LANGUAGE_ALIASES = mapOf(
        "iw" to "he", "he" to "iw", "in" to "id", "id" to "in", "ji" to "yi", "yi" to "ji"
    )

    private val LOCALE_QUALIFIER = Regex("^[a-z]{2,3}(_[a-z0-9]{2,8})?$")
    private val SESSION_LOCALE_PREFIX = Regex("^b\\+")
    private val OBB_PATH = Regex("(?i)(?:^|/)Android/obb/([^/]+)/")
    private val OBB_NAME = Regex("(?i)^(?:main|patch)\\.\\d+\\.(.+)\\.obb$")

    private const val AAB_MESSAGE =
        "This is an Android App Bundle (.aab) — a build artifact for Google Play, not an " +
        "installable package. Play turns an .aab into APKs before it ever reaches a device, " +
        "and doing that requires bundletool and the developer's signing key on a computer.\n\n" +
        "Convert it first with:\nbundletool build-apks --mode=universal\n\n" +
        "then open the resulting .apks file here."

    /**
     * Inspects [staged] — a copy of whatever the user opened — and decides how to install
     * it. [displayName] is the original file name, which is the only way to recognise a
     * bare .obb (expansion files are themselves ZIPs).
     *
     * The result must be closed once its sources have been consumed.
     */
    fun open(context: Context, staged: File, displayName: String): Opened {
        if (displayName.endsWith(".obb", ignoreCase = true)) {
            val fileName = sanitizeFileName(displayName)
            val packageName = packageFromObbName(fileName) ?: throw UnsupportedException(
                "anyapk can't tell which app \"$displayName\" belongs to. Expansion files are " +
                "normally named main.<version>.<package>.obb — rename it to match, or install " +
                "the app's .xapk, which carries its expansion files with it."
            )
            val source = Source(fileName, staged.length()) { FileInputStream(staged) }
            return Opened(null, Payload.ObbOnly(Obb(packageName, fileName, source)))
        }

        val zip = try {
            ZipFile(staged)
        } catch (e: Exception) {
            Log.w(TAG, "Not a ZIP archive", e)
            throw UnsupportedException("\"$displayName\" isn't a readable APK or app bundle.")
        }

        var handOverZip = false
        try {
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()

            if (isAppBundle(entries)) throw UnsupportedException(AAB_MESSAGE)

            // A plain APK is the only archive with a manifest at its root; splits inside a
            // bundle sit one level down, so this check can't mistake one for the other.
            if (entries.any { it.name == "AndroidManifest.xml" }) {
                return Opened(null, Payload.Apk(staged))
            }

            val apkEntries = entries.filter {
                it.name.endsWith(".apk", ignoreCase = true) && !it.name.startsWith("META-INF/")
            }
            val obbEntries = entries.filter { it.name.endsWith(".obb", ignoreCase = true) }

            if (apkEntries.isEmpty() && obbEntries.isEmpty()) {
                throw UnsupportedException(
                    "\"$displayName\" is an archive, but there are no APKs inside it."
                )
            }

            val declaredPackage = readDeclaredPackage(zip, entries)
            val obbs = collectObbs(zip, obbEntries, declaredPackage)

            if (apkEntries.isEmpty()) {
                val only = obbs.singleOrNull() ?: throw UnsupportedException(
                    "\"$displayName\" holds expansion files but no APK. Install the app first, " +
                    "then open the expansion file on its own."
                )
                handOverZip = true
                return Opened(zip, Payload.ObbOnly(only))
            }

            val choice = selectSplits(context, apkEntries)
            handOverZip = true
            return Opened(
                zip,
                Payload.Split(choice.keep.map { sourceOf(zip, it) }, obbs, choice.dropped)
            )
        } finally {
            if (!handOverZip) {
                try {
                    zip.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to close archive", e)
                }
            }
        }
    }

    private fun isAppBundle(entries: List<ZipEntry>): Boolean {
        return entries.any { it.name == "BundleConfig.pb" || it.name == "base/manifest/AndroidManifest.xml" }
    }

    private fun sourceOf(zip: ZipFile, entry: ZipEntry): Source {
        return Source(entry.name.substringAfterLast('/'), entry.size) { zip.getInputStream(entry) }
    }

    // -- Split selection --------------------------------------------------------------

    internal enum class Kind { REQUIRED, ABI, DENSITY, LOCALE }

    private class Split(
        val entry: ZipEntry,
        val name: String,
        val kind: Kind,
        val qualifier: String?
    )

    private class Choice(val keep: List<ZipEntry>, val dropped: List<String>)

    /**
     * Picks the base APK, every feature split, and one config split per dimension. Playing
     * it safe on each axis: an unrecognised qualifier is kept rather than dropped, and if
     * no translation matches the device we ship all of them instead of none.
     */
    private fun selectSplits(context: Context, apks: List<ZipEntry>): Choice {
        val classified = apks.map { entry ->
            val name = entry.name.substringAfterLast('/')
            val qualifier = qualifierOf(name)?.replace('-', '_')?.lowercase(Locale.ROOT)
            Split(entry, name, kindOf(qualifier), qualifier)
        }

        val kept = ArrayList<Split>()
        val dropped = ArrayList<String>()

        // A lone APK is the base whatever it happens to be called: `MyApp-en.apk` reads
        // as a locale split, and refusing to install it would be worse than being wrong.
        // A lone APK is the base whatever it happens to be called: `MyApp-en.apk` reads
        // as a locale split, and refusing to install it would be worse than being wrong.
        val required = classified.filter { it.kind == Kind.REQUIRED }
            .ifEmpty { if (classified.size == 1) classified else emptyList() }
        if (required.isEmpty()) {
            throw UnsupportedException("This archive has config splits but no base APK to install.")
        }
        kept += required

        // Whatever was promoted to base above must not also be weighed as a config split,
        // or it would be written into the session twice under two names.
        val optional = classified.filter { candidate -> required.none { it === candidate } }

        val abiSplits = optional.filter { it.kind == Kind.ABI }
        if (abiSplits.isNotEmpty()) {
            val supported = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase(Locale.ROOT) }
            val chosen = supported.firstOrNull { abi -> abiSplits.any { it.qualifier == abi } }
                ?: throw UnsupportedException(
                    "This bundle only carries native code for " +
                    abiSplits.mapNotNull { it.qualifier }.distinct().joinToString(", ") +
                    ", and this device runs " + supported.joinToString(", ") + "."
                )
            abiSplits.forEach { if (it.qualifier == chosen) kept += it else dropped += it.name }
        }

        val densitySplits = optional.filter { it.kind == Kind.DENSITY }
        if (densitySplits.isNotEmpty()) {
            // nodpi/anydpi resources apply at every density, so they are never a choice.
            val (sized, universal) = densitySplits.partition { DENSITIES.containsKey(it.qualifier) }
            kept += universal

            val deviceDpi = context.resources.displayMetrics.densityDpi
            val dpiOf = { split: Split -> DENSITIES.getValue(split.qualifier!!) }
            // The nearest bucket at or above the screen's density, so nothing is upscaled;
            // failing that, the largest available.
            val best = sized.filter { dpiOf(it) >= deviceDpi }.minByOrNull(dpiOf)
                ?: sized.maxByOrNull(dpiOf)
            sized.forEach { if (it === best) kept += it else dropped += it.name }
        }

        val localeSplits = optional.filter { it.kind == Kind.LOCALE }
        if (localeSplits.isNotEmpty()) {
            val wanted = deviceLanguages(context)
            val anyMatch = localeSplits.any { it.qualifier!!.substringBefore('_') in wanted }
            if (anyMatch) {
                localeSplits.forEach {
                    if (it.qualifier!!.substringBefore('_') in wanted) kept += it else dropped += it.name
                }
            } else {
                kept += localeSplits
            }
        }

        // Base and feature splits first: install-write order is not significant to the
        // package manager, but it keeps failures legible in the logs.
        val ordered = kept.sortedBy { if (it.kind == Kind.REQUIRED) 0 else 1 }
        return Choice(ordered.map { it.entry }, dropped)
    }

    /**
     * Pulls the config qualifier out of a split's file name, covering both the
     * `split_config.arm64_v8a.apk` naming that Play and SAI use and bundletool's
     * `base-arm64_v8a.apk`. Returns null for a base or feature split.
     */
    internal fun qualifierOf(fileName: String): String? {
        val stem = fileName.removeSuffix(".apk").removeSuffix(".APK")
        val configAt = stem.lastIndexOf("config.")
        if (configAt >= 0) return stem.substring(configAt + "config.".length)
        val dashAt = stem.lastIndexOf('-')
        if (dashAt > 0) return stem.substring(dashAt + 1)
        return null
    }

    internal fun kindOf(qualifier: String?): Kind {
        if (qualifier == null) return Kind.REQUIRED
        return when {
            qualifier in ABIS -> Kind.ABI
            qualifier in DENSITIES || qualifier == "nodpi" || qualifier == "anydpi" -> Kind.DENSITY
            SESSION_LOCALE_PREFIX.containsMatchIn(qualifier) -> Kind.LOCALE
            LOCALE_QUALIFIER.matches(qualifier) -> Kind.LOCALE
            else -> Kind.REQUIRED
        }
    }

    private fun deviceLanguages(context: Context): Set<String> {
        // English earns a permanent seat: it is what almost every app falls back to when
        // a string is missing from the installed translation.
        val languages = linkedSetOf("en")
        val locales = context.resources.configuration.locales
        for (i in 0 until locales.size()) {
            val language = locales[i].language.lowercase(Locale.ROOT)
            languages += language
            LANGUAGE_ALIASES[language]?.let { languages += it }
        }
        return languages
    }

    // -- Expansion files --------------------------------------------------------------

    private fun collectObbs(zip: ZipFile, entries: List<ZipEntry>, declaredPackage: String?): List<Obb> {
        return entries.mapNotNull { entry ->
            val fileName = sanitizeFileName(entry.name.substringAfterLast('/'))
            val packageName = packageFromObbPath(entry.name)
                ?: packageFromObbName(fileName)
                ?: declaredPackage
            if (packageName == null) {
                Log.w(TAG, "Skipping ${entry.name}: cannot tell which package it belongs to")
                null
            } else {
                Obb(packageName, fileName, sourceOf(zip, entry))
            }
        }
    }

    internal fun packageFromObbPath(path: String): String? =
        OBB_PATH.find(path)?.groupValues?.get(1)

    internal fun packageFromObbName(fileName: String): String? =
        OBB_NAME.find(fileName)?.groupValues?.get(1)

    /** XAPK ships a `manifest.json`, APKM an `info.json`; both name the package. */
    private fun readDeclaredPackage(zip: ZipFile, entries: List<ZipEntry>): String? {
        val metadata = entries.firstOrNull {
            it.name.equals("manifest.json", ignoreCase = true) ||
                it.name.equals("info.json", ignoreCase = true)
        } ?: return null

        return try {
            val text = zip.getInputStream(metadata).bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            listOf("package_name", "packageName", "pname", "package")
                .firstOrNull { json.has(it) }
                ?.let { json.getString(it) }
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read ${metadata.name}", e)
            null
        }
    }

    /** Keeps names safe to drop into a shell command and an install-write argument. */
    fun sanitizeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifEmpty { "package" }
    }
}
