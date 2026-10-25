# Album

A fast, offline gallery app for Android, inspired by the OPPO and vivo galleries.
Built with Kotlin and Jetpack Compose. Your photos never leave the phone: the only network use is
Settings → Check for updates, which talks to this GitHub repo's Releases.

## Download

Get the latest APK from **[Releases](https://github.com/adityapandey78/album/releases/latest)**: open it on the phone, tap the `.apk`, and allow "Install unknown apps" when asked.

After that, update from inside the app: **Settings → Check for updates**.

> Upgrading from 0.1.0: uninstall it first. 0.2.0 is signed with the permanent release key, so Android won't install it over 0.1.0. This is a one-time step.

| Version | Phase | What's in it |
|---|---|---|
| 0.2.0 | 0 | In-app updater (GitHub Releases, SHA-256 checked), permanent signing key |
| 0.1.0 | 0 | App shell, 3 switchable designs, local crash logs (Settings → Diagnostics) |

Every APK is also kept in [`releases/`](releases/).

## Build

Needs Android Studio (for its SDK and bundled Java). Release signing reads `ALBUM_STORE_FILE` and `ALBUM_STORE_PASSWORD`
from `~/.gradle/gradle.properties`; without them the release APK is built unsigned.

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleRelease
```

Fonts (Inter, Outfit, Plus Jakarta Sans) are under the SIL Open Font License, see `licenses/`.
