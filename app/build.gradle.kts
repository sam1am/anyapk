import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Load keystore properties
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.anyapk.installer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anyapk.installer"
        minSdk = 30  // Android 11 - Required for Wireless Debugging
        // Android 11 behaviours. anyapk is sideloaded, so there is no Play targetSdk
        // floor to satisfy, and staying here keeps the pairing foreground service off
        // the API 34 typed-FGS rules (which need a declared type plus a matching
        // permission). Raising this means revisiting PairingInputService and the
        // AndroidManifest service entry together.
        targetSdk = 30
        versionCode = 5
        versionName = "0.0.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProperties.getProperty("storeFile") ?: "anyapk-release.keystore")
            storePassword = keystoreProperties.getProperty("storePassword")
            keyAlias = keystoreProperties.getProperty("keyAlias")
            keyPassword = keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // anyapk is released as a sideloaded APK on GitHub and could not be listed on
        // Play regardless (it requests WRITE_SECURE_SETTINGS and installs packages), so
        // the Play targetSdk floor does not apply. See the targetSdk comment above.
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // LibADB Android
    implementation("com.github.MuntashirAkon:libadb-android:3.1.0")

    // Custom Conscrypt (recommended for local ADB)
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // For key generation
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
