plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes VERSION_CODE (the workflow run number) so every release is newer than the last.
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
// CI passes the release keystore through secrets; local builds fall back to the debug key.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.gever.notepro"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.gever.notepro"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "0.1.$ciVersionCode"
        // Tablet only needs arm64. `-Pemu` adds x86_64 for local emulator testing.
        ndk { abiFilters += if (project.hasProperty("emu")) listOf("arm64-v8a", "x86_64") else listOf("arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "notepro"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val ink = "1.0.0"
    implementation("androidx.ink:ink-authoring:$ink")
    implementation("androidx.ink:ink-brush:$ink")
    implementation("androidx.ink:ink-geometry:$ink")
    implementation("androidx.ink:ink-rendering:$ink")
    implementation("androidx.ink:ink-strokes:$ink")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.input:input-motionprediction:1.0.0")
}
