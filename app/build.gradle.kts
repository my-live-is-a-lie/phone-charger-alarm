plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.chargealert"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.chargealert"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Release keystore comes from GitHub Secrets in CI:
            // RELEASE_KEYSTORE_BASE64 is decoded to this file by the workflow,
            // and the passwords/alias arrive as env vars.
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: "${rootDir}/phone-charger-alarm-upload-key.jks"
            storeFile = file(keystorePath)
            storePassword = System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "upload"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    // Material 3 stable 1.4.0 made the Expressive APIs internal, so the code
    // (MaterialExpressiveTheme, MotionScheme, wavy indicators, ToggleButton,
    // LargeFlexibleTopAppBar) needs the 1.5.0 line. Pin it explicitly; the
    // BOM still manages every other Compose artifact.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
}
