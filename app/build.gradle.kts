// DeviceGuard MDM — Author: Sakthivel J
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing. Defaults to the debug keystore so release builds have the SAME signature as the
// installed app (updates keep working). To use your own key, create keystore.properties (git-ignored):
//   storeFile=D:/keys/deviceguard.jks
//   storePassword=...
//   keyAlias=...
//   keyPassword=...
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.devguard.mdm"
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
                storeFile = file(keystoreProps.getProperty("storeFile"))
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
