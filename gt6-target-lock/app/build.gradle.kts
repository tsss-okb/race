plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tsss.gt6lock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tsss.gt6lock.v12"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"
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
