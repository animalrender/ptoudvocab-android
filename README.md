# ProudVocab for Android

An Android port of [ProudVocab](https://github.com/melonityhub/proudvocab): vocabulary
builder with CEFR-coloured word chips, Google Translate, dictionary look-ups, SRS
review, games, archive and Anki/JSON export, plus a local video player with SRT and
dual Persian subtitles.

The app is a native shell (Kotlin + WebView) around the desktop renderer, bundled
under `app/src/main/assets/web/`.

## Requirements

- Android 9 (API 28) or newer. Built and checked for Poco X3 Pro (Android 11+, 1080×2400).
- Permissions: `INTERNET` only. Videos and subtitles are opened through the system
  file picker (Storage Access Framework), so the app never asks for storage access.

## What changed from the desktop build

- **All licensing removed.** Every option is unlocked. The license client, checkout,
  premium modals and the license server guard are gone.
- **Google Drive sync is hidden.** Google OAuth cannot run inside a WebView. Local
  JSON backup and restore stay available and unlocked.
- **Local videos only.** YouTube, Netflix and Prime integrations are not part of the
  desktop port either.
- **HEVC and MKV depend on the WebView's codecs.** MP4 (H.264 + AAC) is the reliable format.
- **Sibling subtitle discovery** (automatically finding `movie.srt` next to `movie.mp4`)
  is not available for single-file picks, because the picker does not grant folder access.
  Use "Open folder" to load a whole folder, or pick the subtitle file manually.

## Project layout

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/proudvocab/android/MainActivity.kt` | WebView host, fullscreen, file pickers, intents, bridge dispatch |
| `.../PvNativeBridge.kt` | `window.PvNative` (JavaScript interface) |
| `.../AppRequestHandler.kt` | Serves `assets/web` and streams picked videos with HTTP Range |
| `.../VirtualPaths.kt` | Encodes content URIs into renderer-friendly virtual paths |
| `.../DocumentAccess.kt` | SAF queries: file info, bytes (64 MB cap), folder listing |
| `.../NetFetcher.kt` | HTTPS-only `fetch()` for translation and dictionary requests |
| `.../AppStore.kt` | Renderer state persisted as JSON in app-private storage |
| `app/src/main/assets/web/` | Renderer (side panel, subtitles, player) |
| `scripts/strip-license.py` | CI gate: no license/premium references, all JS parses |

## Build

```bash
gradle :app:assembleDebug          # Gradle 8.10.x, JDK 17, Android SDK 35
```

Open the folder in Android Studio (Ladybug or newer) to run it on a device. The
project has no Gradle wrapper; the CI workflows pin Gradle 8.10.2 explicitly.

## Continuous integration

- `.github/workflows/android-build.yml` runs on every push and pull request. It checks
  the renderer, then builds the debug and release APKs and uploads them as artifacts.
- `.github/workflows/android-release.yml` runs on `v*` tags, or manually with a version.
  It signs the release APK, verifies the signature (minSdk 28) and publishes a GitHub Release.

## Release signing

The keystore is never committed. The release workflow reads four repository secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release-keystore.p12` (PKCS#12) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | key alias (`proudvocab` for the current key) |
| `ANDROID_KEY_PASSWORD` | key password (same as the keystore password for this key) |

If a secret is missing, the release workflow stops with an error that names it.
Keep the keystore backed up. Losing it means existing installs can never be updated.
