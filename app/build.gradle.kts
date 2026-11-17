plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing info comes from environment variables (set by the Release workflow from GitHub secrets).
val ksPath: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.comadrez.mailapp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.comadrez.mailapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.1"
        manifestPlaceholders["appLabel"] = "Driftbox"
    }
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "driftbox"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Debug builds install next to the release app instead of replacing it.
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Driftbox (debug)"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    packaging {
        resources {
            excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
}
