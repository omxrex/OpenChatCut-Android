plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.openchatcut.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openchatcut.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-android"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    // Real video engine: FFmpegKit full GPL build with libx264/libass/xfade/freetype.
    // API is the upstream com.arthenica.ffmpegkit API; only groupId changed.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
