plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.test") version "9.4.1" apply false // :benchmark
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("androidx.baselineprofile") version "1.5.0" apply false // generates app/src/release/generated/baselineProfiles
}
