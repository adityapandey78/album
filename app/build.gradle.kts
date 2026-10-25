plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aditya.album"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aditya.album"
        minSdk = 33 // Android 13+: READ_MEDIA_* permissions and AGSL shaders, no legacy branches
        targetSdk = 37 // Android 17
        versionCode = 2
        versionName = "0.2.0"
    }

    // Permanent release key. Path and password live in ~/.gradle/gradle.properties, never in git.
    // Without them the release APK is built unsigned (not installable) instead of silently using another key.
    val storeFile = providers.gradleProperty("ALBUM_STORE_FILE").orNull
    if (storeFile != null) {
        signingConfigs.create("release") {
            this.storeFile = file(storeFile)
            storePassword = providers.gradleProperty("ALBUM_STORE_PASSWORD").get()
            keyAlias = "album"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
