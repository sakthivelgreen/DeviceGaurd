// DeviceGuard MDM — Author: Sakthivel J
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing. The app's key lives in keys/ (git-ignored), described by keystore.properties (git-ignored):
//   storeFile=keys/deviceguard.keystore      (relative to MDM_App/, or an absolute path)
//   storePassword=...
//   keyAlias=androiddebugkey
//   keyPassword=...
// When it exists, BOTH debug and release builds use it, so every build has the same signature as the
// installed app (fingerprint SHA-256 E8:92:FC:E3:…:4C:DE:E2). Without it, builds fall back to the PC's
// default debug keystore (~/.android/debug.keystore). Back up the keystore + keystore.properties: a lost
// key means the installed Device Owner app can never be updated again.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.devguard.mdm"
    buildFeatures { buildConfig = true } // BuildConfig.APPLICATION_ID in ADB command texts (D-05)
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.devguard.mdm"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "2.0"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile")) // relative to MDM_App/
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            } else {
                val debug = getByName("debug")
                storeFile = debug.storeFile
                storePassword = debug.storePassword
                keyAlias = debug.keyAlias
                keyPassword = debug.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            // Same key as release when keystore.properties exists (one key file to back up).
            if (keystoreProps.getProperty("storeFile") != null) signingConfig = signingConfigs.getByName("release")
            // testOnly = ADB escape hatch (dpm remove-active-admin). Debug builds keep it.
            manifestPlaceholders["testOnly"] = "true"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            // Release builds can be installed by QR provisioning and the auto-updater (no testOnly).
            manifestPlaceholders["testOnly"] = "false"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.zxing:core:3.5.3") // QR codes (authenticator setup, provisioning)
}
