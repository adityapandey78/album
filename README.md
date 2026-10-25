# Album

A fast, offline gallery app for Android, inspired by the OPPO and vivo galleries.
Built with Kotlin and Jetpack Compose. Your photos never leave the phone: the only network use is
Settings → Check for updates, which talks to this GitHub repo's Releases.

## Download

Get the latest APK from **[Releases](https://github.com/adityapandey78/album/releases/latest)**: open it on the phone, tap the `.apk`, and allow "Install unknown apps" when asked.

After that, update from inside the app: **Settings → Check for updates → Update**. Album downloads the new version itself and Android asks you to confirm. The first time, Android asks you to allow installs from Album.

> Upgrading from 0.1.0: uninstall it first. 0.2.0 is signed with the permanent release key, so Android won't install it over 0.1.0. This is a one-time step.

| Version | Phase | What's in it |
|---|---|---|
| 0.5.1 | 1 | Updates download and install inside Album again (progress bar, checksum checked, Android confirms); dark mode text fix on Settings and other overlay screens |
| 0.5.0 | 1 | Albums: new, rename, delete, pin, sort, hide from Photos · move or copy photos between albums (dates and location kept, name clashes renamed, never overwritten) · collections: one photo in many albums without copies · calendar view with day thumbnails, tap a day to jump there · viewer ⋮ menu: slideshow, lossless rotate (changes 2 bytes, never re-saves the photo), motion photos (Google format), Use as wallpaper or contact |
| 0.4.0 | 1 + 2 base | Fast scrollbar with date bubble, drag to select, select a day or all, remembered zoom level · photos fly out of and back into the grid, swipe down to close, swipe up for details · Favourites, Videos, Recently deleted (restore, delete forever), Undo, optional "no pop-ups" mode · open photos from other apps · on-device index for places (offline GeoNames) and things (ML Kit), shown in a later version |
| 0.3.1 | 1 | Updates download through the browser, so Play Protect no longer flags Album |
| 0.3.0 | 1 | Your real photos and videos: timeline with pinch zoom (2 to 7 columns, month view), albums, full-screen viewer with deep zoom, video player, share, favourite, delete to trash, details, multi-select |
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
ML Kit telemetry is stripped by R8, so the "nothing leaves the phone" guarantee applies to release builds (the APKs in Releases), not debug builds.
Place names come from [GeoNames](https://www.geonames.org) under CC BY 4.0, see `licenses/geonames-CC-BY-4.0.txt`.
