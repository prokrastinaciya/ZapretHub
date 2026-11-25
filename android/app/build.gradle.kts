import java.util.Properties
import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}
val coreLock = rootProject.file("upstream/flowseal.lock.json")
val coreVersion = if (coreLock.exists()) (JsonSlurper().parse(coreLock) as Map<*, *>)["core_version"].toString().removePrefix("v") else "1.11.4"
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String) = System.getenv(env) ?: localProps.getProperty(prop)
val releaseKey = signingValue("TGWS_KEYSTORE_FILE", "KEYSTORE_FILE")
val testKeystore = providers.gradleProperty("testKeystore").orNull

android {
    namespace = "com.amurcanov.tgwsproxy"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.prokrastinaciya.zaprethub"
        minSdk = 24
        targetSdk = 35
        versionCode = providers.gradleProperty("appVersionCode").orElse("100").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("0.1.0").get()
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
        // Android and Windows releases share a repository. The upstream updater
        // follows a different release format; download this APK from GitHub Releases.
        buildConfigField("boolean", "APP_UPDATES_ENABLED", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }
    flavorDimensions.add("arch")
    productFlavors {
        create("arm64") {
            dimension = "arch"
            ndk.abiFilters.add("arm64-v8a")
        }
        create("emulator") {
            dimension = "arch"
            ndk.abiFilters.add("x86_64")
        }
    }
    signingConfigs {
        getByName("debug") {
            if (testKeystore != null) {
                storeFile = file(testKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            if (releaseKey != null) {
                storeFile = file(releaseKey)
                storePassword = signingValue("TGWS_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("TGWS_KEY_ALIAS", "KEY_ALIAS")
                keyPassword = signingValue("TGWS_KEY_PASSWORD", "KEY_PASSWORD")
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
    // Old local builds may leave Rust binaries here; the Python-based application never ships them.
    sourceSets.getByName("main").jniLibs.setSrcDirs(emptyList<String>())
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/licenseAssets").get().asFile)
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/dpiTestAssets").get().asFile)
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Compose UI tests run under Robolectric in the JVM unit-test task, so
        // string/drawable/font resources must be visible to the Robolectric sandbox.
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
chaquopy {
    defaultConfig {
        version = "3.13"
        (providers.gradleProperty("buildPython").orNull ?: localProps.getProperty("buildPython"))?.let { buildPython(it) }
        pip {
            install("-r", "requirements-android.txt")
        }
    }
    sourceSets {
        getByName("main") {
            srcDir("src/main/python")
            // Include only the proxy package. The locked tree includes the Android AES loader.
            srcDir(rootProject.file("upstream/flowseal"))
            exclude("tests/**", "LICENSE", "*.json", "*.md")
        }
    }
}
dependencies {
    implementation(project(":dpi-runtime"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.appcompat:appcompat:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Compose UI tests on the JVM (Task 4: DualModuleControlsTest).
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // Declares androidx.activity.ComponentActivity in the debug manifest so
    // Robolectric can resolve the activity createComposeRule() launches.
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val licenseNotices by tasks.registering(Sync::class) {
    from(rootProject.file("LICENSE")) { rename { "ANDROID-GPL-3.0.txt" } }
    from(rootProject.file("NOTICE.md"))
    from(rootProject.file("upstream/flowseal/LICENSE")) { rename { "Flowseal-MIT.txt" } }
    val byeByeDpi = rootProject.file("upstream/byebyedpi")
    val hevTunnel = byeByeDpi.resolve("app/src/main/jni/hev-socks5-tunnel")
    from(byeByeDpi.resolve("LICENSE")) { rename { "ByeByeDPI-GPL-3.0.txt" } }
    from(byeByeDpi.resolve("app/src/main/cpp/byedpi/LICENSE")) { rename { "ByeDPI-MIT.txt" } }
    from(hevTunnel.resolve("LICENSE")) { rename { "HEV-SOCKS5-Tunnel-MIT.txt" } }
    from(hevTunnel.resolve("src/core/LICENSE")) { rename { "HEV-SOCKS5-Core-MIT.txt" } }
    from(hevTunnel.resolve("third-part/hev-task-system/LICENSE")) { rename { "HEV-Task-System-MIT.txt" } }
    from(hevTunnel.resolve("third-part/lwip/LICENSE")) { rename { "lwIP-BSD-3-Clause.txt" } }
    from(hevTunnel.resolve("third-part/yaml/License")) { rename { "libYAML-MIT.txt" } }
    from(rootProject.file("licenses/fonts"))
    into(layout.buildDirectory.dir("generated/licenseAssets/licenses"))
}
val dpiStrategies by tasks.registering(Sync::class) {
    from(rootProject.file("upstream/byebyedpi/app/src/main/assets")) {
        include("proxytest_strategies.list")
    }
    into(layout.buildDirectory.dir("generated/dpiTestAssets"))
}
tasks.named("preBuild") { dependsOn(licenseNotices, dpiStrategies) }
