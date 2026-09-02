plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tsss.gt6lock"
    compileSdk = 35

    defaultConfig {
        // Unique install id for the camera-fix build so it installs even if an older
        // debug APK with another signing key is still on the phone.
        applicationId = "com.tsss.gt6lock.v03"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
        externalNativeBuild {
            cmake { cppFlags += listOf("-O3", "-ffast-math", "-fno-exceptions", "-fno-rtti") }
        }
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
