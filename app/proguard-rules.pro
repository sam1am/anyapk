# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep LibADB classes
-keep class io.github.muntashirakon.adb.** { *; }

# Keep sun security classes for certificate generation.
#
# sun-security-android repackages these under android.sun.security, NOT sun.security --
# the rule has to name the real package or it silently matches nothing. Keeping them is
# not optional: OIDMap, CertificateExtensions and X509Key resolve extension classes by
# Class.forName on their fully-qualified names, so obfuscating them breaks the ADB
# certificate generation in AdbConnectionManager at runtime, not at build time.
-keep class android.sun.security.** { *; }
-dontwarn android.sun.security.**

# Conscrypt ships its own keep rules, but its -dontwarn list misses these two. Both are
# platform-internal classes that only its pre-API-29 compatibility shims reference, so
# they are absent at compile time and R8 fails the build without this.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
