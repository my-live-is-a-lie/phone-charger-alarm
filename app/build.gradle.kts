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

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so CI produces an installable APK.
            // For Play Store distribution, replace with a release keystore.
            signingConfig = signingConfigs.getByName("debug")
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
