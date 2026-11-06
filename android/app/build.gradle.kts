plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ro.superauz"
    compileSdk = 34

    defaultConfig {
        applicationId = "ro.superauz"
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("vcode") as String? ?: "1").toInt()
        versionName = "1.0." + versionCode
    }

    // Stable key so every build can update the previous install in place.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
