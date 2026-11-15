import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.immichclient.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.immichclient.androidtv"
        minSdk = 26
        targetSdk = 35
        versionCode = 25
        versionName = "2.8.1"
        // MapLibre includes native code: 32-bit (NVIDIA Shield: abilist armeabi-v7a) and 64-bit for other boxes; not x86.
        // Each ABI adds ~8 MB to the APK.
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        // Only include the app's languages (otherwise ~80 translations from libraries end up in the APK)
        resourceConfigurations += listOf("en", "nl", "de", "fr", "es")
    }

    // Fixed release key, so a new version can be installed over an old one.
    // Keystore + passwords live outside the source tree: ~/.android/immichclient-release.properties (storeFile, storePassword,
    // keyAlias, keyPassword; chmod 600; the older ~/.android/fotos-release.properties is also still read) or the
    // environment variables IMMICHCLIENT_KEYSTORE / IMMICHCLIENT_KEYSTORE_PASSWORD / IMMICHCLIENT_KEY_ALIAS /
    // IMMICHCLIENT_KEY_PASSWORD. If everything is missing, fall back to the debug key.
    val releaseProps = Properties().apply {
        val home = System.getProperty("user.home")
        val f = listOf("immichclient-release.properties", "fotos-release.properties").map { File(home, ".android/$it") }.firstOrNull { it.isFile }
        f?.inputStream()?.use { load(it) }
    }
    fun releaseValue(name: String, env: String) = releaseProps.getProperty(name) ?: System.getenv("IMMICHCLIENT_$env") ?: System.getenv("FOTOS_$env")
    val releaseStore = releaseValue("storeFile", "KEYSTORE")?.let { File(it) }?.takeIf { it.isFile }
    if (releaseStore != null) signingConfigs.create("release") {
        storeFile = releaseStore
        storePassword = releaseValue("storePassword", "KEYSTORE_PASSWORD")
        keyAlias = releaseValue("keyAlias", "KEY_ALIAS") ?: "fotos"
        keyPassword = releaseValue("keyPassword", "KEY_PASSWORD")
    }

    buildTypes {
        // Always install the release build on the TV: Compose is noticeably slower in a debug build.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
    }
    buildFeatures { compose = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Images: memory and disk cache, OkHttp with the API key in the header
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QR code on the pairing screen (encoder only, ~0.5 MB)
    implementation("com.google.zxing:core:3.5.3")
    // World map: MapLibre (OpenGL) with Immich's vector tile style (tiles.immich.cloud).
    // 12.3.1 is the newest built with Kotlin 2.0; 13.x (Kotlin 2.2 metadata) does not compile with Kotlin 2.0.21.
    implementation("org.maplibre.gl:android-sdk:12.3.1")
    // Screensaver: Compose in a DreamService needs its own Lifecycle/SavedState owner (Dream.kt)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    // Set screensaver via the box's own network adb (ScreensaverSetup.kt); small, only okio
    implementation("dev.mobile:dadb:1.2.10")
    // Videos
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
}
