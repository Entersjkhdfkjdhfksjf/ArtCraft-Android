plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.artcraft.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.artcraft.launcher"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionCode=<run number> -PversionName=<tag>; local builds use the defaults.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
    }

    // One shared key for every build (local + CI) so a newer APK installs over an older one and keeps
    // the apps you installed. This is a sideloading key, not a Play Store release key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
// No third-party dependencies on purpose: the launcher uses only the Android framework.
