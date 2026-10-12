import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.duckets.dashboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.duckets.dashboard"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        // App and bridge version independently (bridge: BRIDGE_VERSION in
        // bridge/dashboard_bridge.py). Bump each only when it changes.
        versionName = "1.4.0"

        // Default bridge address. Override per-machine (never committed) by
        // putting dashboard.bridgeUrl=<url> in android/local.properties.
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val bridgeUrl = localProps.getProperty("dashboard.bridgeUrl")
            ?: "http://192.168.1.100:8901"
        val escapedBridgeUrl = bridgeUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "DEFAULT_BRIDGE_URL", "\"$escapedBridgeUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Without this, assembleRelease produced an UNSIGNED apk that
            // Android refuses to install. Personal sideload builds sign with
            // the debug key; provide a real keystore via RELEASE_STORE_FILE /
            // RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
            // environment variables for a properly signed release.
            val ksFile = System.getenv("RELEASE_STORE_FILE")
            if (ksFile != null) {
                signingConfig = signingConfigs.create("releaseKs") {
                    storeFile = file(ksFile)
                    storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                    keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                    keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }
    buildFeatures {
        compose = true
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

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-tooling-preview")
}
