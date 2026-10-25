// Frame timing benchmarks and the Baseline Profile generator, run against a release-like build of :app. See README.
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.aditya.album.benchmark"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Emulator numbers are only good for before/after comparisons on the same setup; on a phone this does nothing
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    targetProjectPath = ":app"
}

dependencies {
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
}
