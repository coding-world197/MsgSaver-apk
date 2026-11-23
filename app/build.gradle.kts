plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Release signing: keys come from environment variables (GitHub Secrets).
// If they are missing, the release build falls back to the debug key so the build never fails.
val ksFile: java.io.File? = System.getenv("KEYSTORE_PATH")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.example.msgsaver"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.msgsaver"
        minSdk = 29
        targetSdk = 34
        // IMPORTANT: increase versionCode by 1 for every update you release
        versionCode = 10
        versionName = "1.9"
    }
    signingConfigs {
        if (ksFile != null) {
            create("release") {
                storeFile = ksFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
}
