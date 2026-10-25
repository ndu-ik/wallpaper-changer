plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.wallpaperchanger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wallpaperchanger"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // WorkManager: periodic background watchdog that survives app/process death and reboots
    implementation("androidx.work:work-runtime:2.9.1")
    // Reads a photo's rotation tag so the cut is taken the right way round
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
