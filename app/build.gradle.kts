plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.screengrab"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screengrab"
        minSdk = 24
        targetSdk = 34
        versionCode = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull() ?: 1
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
