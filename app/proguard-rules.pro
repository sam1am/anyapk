# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep LibADB classes
-keep class io.github.muntashirakon.adb.** { *; }

# Keep sun security classes for certificate generation
-keep class sun.security.** { *; }
-dontwarn sun.security.**
