plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp") // Room's code generator
    id("androidx.baselineprofile") // ships the profile from :benchmark so grid and viewer code is precompiled, not JIT-compiled mid-scroll
}

android {
    namespace = "com.aditya.album"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aditya.album"
        minSdk = 33 // Android 13+: READ_MEDIA_* permissions and AGSL shaders, no legacy branches
        targetSdk = 37 // Android 17
        versionCode = 17
        versionName = "1.1.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 64-bit ARM only: the phone and the emulator. Each extra ABI would add another 11 MB ML Kit library.
        ndk { abiFilters += "arm64-v8a" }
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
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures { compose = true }

    // ML Kit and LiteRT native libraries are ~35 MB uncompressed. Compressed, every update download is much smaller;
    // Android unpacks them once at install (a little more storage), with no cost at runtime.
    packaging { jniLibs { useLegacyPackaging = true } }
    // The licence texts in licenses/ go in the app too: Apache 2.0 and BSD ask for a copy with every binary
    sourceSets["main"].assets.srcDir("../licenses")
}

// Smart index schemas, kept in git so later tables (faces, text, embeddings) can ship as Room auto-migrations.
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3") // grid thumbnails + memory cache
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0") // pinch zoom with tiled loading for 50 MP photos
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.room:room-runtime:2.8.5") // smart index database (labels, places), read as Flows
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime:2.12.0") // indexing as resumable background work that waits out low battery
    implementation("androidx.glance:glance-appwidget:1.2.0") // the Memories home-screen widget
    implementation("androidx.exifinterface:exifinterface:1.4.2") // GPS from EXIF, including HEIF photos
    // On-device labels; model bundled in the APK, nothing downloaded. Version pinned: proguard-rules.pro strips its telemetry by obfuscated name.
    implementation("com.google.mlkit:image-labeling:17.0.9")
    // On-device text in screenshots and documents, for search; Latin model bundled. Pinned for the same proguard reason.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Clean up: eyes open and smiles for the best shot; bundled like the labeller. Pinned for the same proguard rule reason.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // QR codes and barcodes in a photo, on demand in the viewer; model bundled like the others. Pinned for the same proguard rule reason.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // People: runs the bundled YuNet and EdgeFace-S models (assets/faces) on the CPU; describe-it search runs its
    // downloaded model the same way. No Play services.
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Profile generator; Compose already brings androidx.profileinstaller, which installs the profile on sideloaded APKs
    baselineProfile(project(":benchmark"))
    testImplementation("junit:junit:4.13.2")
    // On-device UI tests (gestures): ./gradlew connectedDebugAndroidTest
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0") // 3.5 (pulled in by Compose) breaks on Android 17
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
