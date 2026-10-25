# Album

A fast, offline gallery app for Android, inspired by the OPPO and vivo galleries.
Built with Kotlin and Jetpack Compose. Your photos never leave the phone: the only network uses are
Settings → Updates, which talks to this GitHub repo's Releases, and the one-time describe-it search download
(Settings → Search), which fetches its model from the same Releases when you ask.

## Download

Get the latest APK from **[Releases](https://github.com/adityapandey78/album/releases/latest)**: open it on the phone, tap the `.apk`, and allow "Install unknown apps" when asked.

After that, update from inside the app: **Settings → Check for updates → Update**. Album downloads the new version itself and Android asks you to confirm. The first time, Android asks you to allow installs from Album.

> Upgrading from 0.1.0: uninstall it first. 0.2.0 is signed with the permanent release key, so Android won't install it over 0.1.0. This is a one-time step.

| Version | Phase | What's in it |
|---|---|---|
| 0.10.0 | 3 | People rebuilt: new on-phone face models (YuNet, EdgeFace-S) keep look-alike family members apart, "Same person?" suggestions instead of automatic merges, change a person's cover photo, a tidy row of five faces · Search finds more: labels down to 30% confidence, text read from any photo that has it, places at every level (area, city, district, state), places estimated for photos without GPS, WhatsApp dates from file names, "Top matches" first, and optional describe-it search (a one-time download, runs on the phone) · Clean up: exact copies and copies across the whole library (PDQ), similar shots without chaining, better blur and too dark or bright checks · For you split into Memories and Clean up · Settings: Updates, Diagnostics and About on pages of their own · new app icon (a stack of prints) whose sun follows your accent |
| 0.9.1 | 3 | Organising a big library keeps going after you leave Album (a quiet "Organising your photos" notification) instead of a slice per visit; the progress card shows overall progress, so a resumed pass no longer looks like a restart |
| 0.9.0 | 3 | Clean up: duplicates, similar shots with a best-shot pick, blurry photos, old screenshots, WhatsApp, big videos, swipe to clean, storage dashboard · Private safe: encrypted on the phone, opens with fingerprint or screen lock, originals removed only after a verified copy · trips and year recaps in For you, ID-card privacy guard, Memories home-screen widget · viewer: two-finger lossless rotate with Undo, Lens-style text selection, Edit with the app you choose · People and Places more accurate, a progress card while organising · Collections: top albums with See all, Recently deleted and Private safe at the bottom, no file paths |
| 0.7.1 | 2 | New design: Album design system (greyscale with one accent, Bricolage Grotesque, Figtree, Geist Mono, Material Symbols) · Library, Collections and For you tabs with a floating bar and search at the bottom · Appearance settings: Light, Dark (true black) or Dim, six accents, three looks, Grid, Justified or Mosaic · tab transitions (Depth by default) · Jump to a date sheet with heatmap · story-style memories (On this day, recaps) and a storage card · Edit opens Google Photos · copy text from a photo · Recently deleted at the bottom of Collections, with a switch to hide it · new app icon |
| 0.7.0 | 2 | Places, things and People (on-device face groups: name, merge, hide, "Not this person") in Explore · search by thing, place, person, year, month and words inside photos · release builds pre-compiled with a Baseline Profile for smooth 120/144 Hz scrolling · faster thumbnails · smaller download |
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

### Scroll performance

Scrolling needs a long timeline to measure, so seed the phone or emulator first (3,000 photos over two years, needs Pillow):

```bash
python3 tools/seed_library.py
```

Quick check, no build needed: open Album on Photos, then this flings the grid and drags the fast scroller with adb and
prints Android's own frame stats (`dumpsys gfxinfo`). At 144 Hz a frame has 6.9 ms.

```bash
tools/scroll_jank.sh
```

Full benchmark: frame timing (P50/P90/P99, overrun, janky frames) for flinging, a first fling while MediaProvider still
has to make thumbnails, and the fast scroller, on a release-like build. Results and a Perfetto trace per run land in
`benchmark/build/outputs/connected_android_test_additional_output/`. Debug and release-signed builds can't be installed
over each other, so uninstall Album first. The cold-thumbnail run deletes MediaProvider's thumbnail cache, which
Android rebuilds by itself.

```bash
./gradlew :benchmark:connectedBenchmarkReleaseAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

After changing startup, the grid or the viewer, regenerate the Baseline Profile (code ART compiles ahead of time instead
of interpreting it on the first scrolls) and commit `app/src/release/generated/baselineProfiles/`:

```bash
./gradlew :app:generateBaselineProfile
```

On an emulator, compare before and after on the same setup only: run it headless (`emulator -no-window`), since macOS
slows down a hidden emulator window, and give it 4 cores and the host GPU (`hw.cpu.ncore=4`, `-gpu host`).

Fonts (Bricolage Grotesque, Figtree, Geist Mono) are under the SIL Open Font License, see `licenses/`.
ML Kit telemetry is stripped by R8, so the "nothing leaves the phone" guarantee applies to release builds (the APKs in Releases), not debug builds.
Place names come from [GeoNames](https://www.geonames.org) under CC BY 4.0, see `licenses/geonames-CC-BY-4.0.txt`.
People finds faces with YuNet from [OpenCV Zoo](https://github.com/opencv/opencv_zoo) (MIT, see `licenses/yunet-MIT.txt`) and recognises them with EdgeFace-S by the [Idiap Research Institute](https://huggingface.co/Idiap/EdgeFace-S-GAMMA) under CC BY-NC-SA 4.0 (see `licenses/edgeface-CC-BY-NC-SA-4.0.txt`), so Album stays non-commercial while it ships this model.
Describe-it search downloads Meta's [Perception Encoder](https://github.com/facebookresearch/perception_models) PE-Core-S16-384 under Apache 2.0 (see `licenses/perception-encoder-Apache-2.0.txt`), converted by `tools/describe_convert.py`; its tokenizer vocabulary is OpenAI CLIP's, MIT (see `licenses/clip-bpe-vocabulary-MIT.txt`).
Clean up hashes photos with PDQ from Meta's [ThreatExchange](https://github.com/facebook/ThreatExchange/tree/main/pdq) under the BSD licence, see `licenses/pdq-BSD.txt`.
