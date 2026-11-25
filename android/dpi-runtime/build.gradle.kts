plugins {
    id("com.android.library")
}

android {
    namespace = "com.amurcanov.tgwsproxy.dpi"
    compileSdk = 35
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
        }
    }

    // Always registered: the native engine is required for any packaged APK, so
    // assemble*/bundle* fail loudly when the NDK/CMake toolchain is absent (Linux
    // CI has both). Pure JVM test tasks do not depend on the native build, so
    // `testDebugUnitTest` still runs on a workstation without the toolchain.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    // VpnPrepareContract (androidx.activity.result.contract.ActivityResultContract).
    implementation("androidx.activity:activity:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
