# Album

A fast, fully offline gallery app for Android, inspired by the OPPO and vivo galleries.
Built with Kotlin and Jetpack Compose. It has no internet permission, so photos and data never leave the phone.

## Download

Latest APK: [releases/album-v0.1.0.apk](https://github.com/adityapandey78/album/raw/main/releases/album-v0.1.0.apk)

To install on the phone: open the link above in Chrome, download, tap the file, and allow "Install unknown apps" for Chrome when asked.

| Version | Phase | What's in it |
|---|---|---|
| 0.1.0 | 0 | App shell, 3 switchable designs, local crash logs (Settings → Diagnostics) |

## Build

Needs Android Studio (for its SDK and bundled Java).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
```

Fonts (Inter, Outfit, Plus Jakarta Sans) are under the SIL Open Font License, see `licenses/`.
