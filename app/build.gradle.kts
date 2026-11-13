plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.chargealert"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.chargealert"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
        if (!releaseStoreFile.isNullOrBlank()) {
            create("stableRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (!System.getenv("RELEASE_STORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("stableRelease")
            } else {
                // Temporary fallback for CI until a persistent keystore is added
                // to GitHub Actions secrets. This key is not suitable for upgrades
                // across ephemeral GitHub-hosted runners.
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
}
