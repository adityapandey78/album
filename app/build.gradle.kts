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
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3") // grid thumbnails + memory cache
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0") // pinch zoom with tiled loading for 50 MP photos
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // On-device UI tests (gestures): ./gradlew connectedDebugAndroidTest
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0") // 3.5 (pulled in by Compose) breaks on Android 17
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
