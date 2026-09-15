# OTT-play Native for Android

A standalone Android app for personal IPTV playlists, movies, and series. The interface uses Kotlin and Jetpack Compose; playback runs through Android Media3 / ExoPlayer. The app supports touch controls and Android TV remotes. The interface follows Android language preferences and falls back to English. Settings → Language can select a different language or return to System default; all 20 languages available in OTT-play FOSS are included offline.

This is a separate product with application ID `play.ott.foss.nativeapp`. It can be installed alongside `ottplay-foss`. Signed previews use the separate ID `play.ott.foss.nativeapp.preview`, with a stable signing identity configured independently from production. Neither running nor building the app requires the JavaScript player, WebView, Capacitor, or Node.js. The current project version is `0.2.1`; implementing a feature does not establish compatibility with every TV, codec, or provider service.

## Getting started

1. Download the APK from [signed preview v0.2.0-preview.2](https://github.com/open-ott-play/ottplay-android/releases/tag/v0.2.0-preview.2) and install it on Android 8.0 / API 26 or later. See the [migration guide](docs/MIGRATION.md#installing-the-signed-02-preview) to transfer settings from an earlier installation.
2. Choose the demo option. The APK includes an eight-second synthetic video that works offline without an account. Its audio track is silent.
3. Add your M3U playlist, select a local M3U file, or enter your Xtream or Stalker account details.
4. Open a channel, movie, or episode. Programme listings can be loaded from the source's or playlist's XMLTV URL.

The linked historical preview predates the 20-language interface. Use a build of the current source to exercise the language picker described here; the old preview is not evidence for that feature.

The app does not provide a subscription, a built-in commercial catalog, or permission to access sources. Use URLs and accounts you are authorized to access.

## Implemented features

- **M3U:** HTTPS downloads (HTTP is available in the separate full build) and selected-file imports, groups, names, logos, `tvg-id`, XMLTV, and standard HTTP headers. An HLS manifest is treated as one stream. Catch-up supports `default`, `append`, Flussonic, and recognized time placeholders; a fallback archive duration in hours can be set when the playlist has no catch-up tags.
- **Xtream:** separate APIs for live TV, movies, series, and episodes, plus the older combined `player_api.php` response containing `live_streams` and `categories`. Catch-up uses server time. Unsupported optional sections returning HTTP 404/405/501 are reported explicitly; authentication, network, and server errors are not treated as successful empty catalogs.
- **Stalker:** classic MAG handshake, profile, paginated channel lists, and `create_link`. The older FOSS JSON-RPC protocol uses an explicitly configured endpoint ending in `/api/`. These are different protocols; see the [migration guide](docs/MIGRATION.md).
- **Interface:** Compose layouts for touch and TV, search, groups, favorites, movies, seasons and episodes, programme listings, and available catch-up playback. TV receives focus at launch and restores it when returning from the player. The native player menu selects available audio tracks, subtitles, speed, and scaling; the remote's Menu key opens it.
- **Playback:** a Media3 `MediaSessionService`, system media-session controls, phone background playback, and explicit stop. System PiP is available on supported phones. Android TV pauses when leaving the app and does not offer video PiP. It displays the current stream, not a second channel playing simultaneously. The service saves movie positions on pause, seek, item changes, and every five seconds, including while the screen is closed; completed movies restart from the beginning.
- **Data:** a local SQLite catalog and EPG. Source settings, saved stream URLs, and EPG URLs use AES-GCM encryption with an Android Keystore key. Source data is excluded from Android backup. Selected sources can be exported and imported as JSON.
- **EPG refresh:** WorkManager requests periodic work every six hours on an unmetered network. Android may defer execution; the interval is not a guaranteed schedule. Channel-list refresh is a separate action in the app.

## Compatibility boundaries

Personal M3U playlists, Xtream, and the two Stalker protocols described above are implemented natively. The proprietary adapters, dealer/cloud activation, custom server commands, and portal-specific behavior of the older `ottplay-foss` project are not transferred automatically. Full behavioral compatibility with the old project is not claimed. See [PROVIDER-COMPATIBILITY.md](docs/PROVIDER-COMPATIBILITY.md) for migration examples using standard contracts.

Supported Kodi license properties become native Media3 configurations for Widevine, PlayReady, and ClearKey. License URLs and headers are isolated from stream requests; unknown license transformations are rejected explicitly. Compatibility depends on the device's DRM module, container, provider license, and decoders. Unit tests do not establish acceptance for paid services or hardware DRM. The precise contract is documented in [docs/DRM.md](docs/DRM.md).

Release and preview enforce HTTPS for remote sources, provider APIs, artwork, EPG, media segments and DRM, including redirects. A separate `full` build (`play.ott.foss.nativeapp.full`) permits HTTP for legacy personal sources; HTTP does not encrypt transmitted data. Debug also permits HTTP for local test fixtures. Settings exports are plain JSON containing source URLs and credentials. Treat an export as a file containing passwords. Local-storage encryption does not encrypt exported files.

The offline privacy policy is available before setup and in settings. The [public privacy policy](https://astral-oasis-sbqd.here.now/) and [Play submission pack](docs/PLAY-CONSOLE-SUBMISSION.md) document current data flows and remaining Console steps. Google Play submission and content rights require separate preparation. The signed preview was published through a dedicated workflow that verifies the version, certificate, and complete phone/TV test matrix. The certificate and future release procedure are documented in [docs/RELEASING.md](docs/RELEASING.md); the [publication report](docs/validation/native-0.2-preview-release-results.json) records the source commit and verified artifacts. Store approval and Android TV certification are not claimed.

## Building

Install JDK 17 and the Android SDK with platform 36, build-tools 36.0.0, and platform-tools. The Gradle Wrapper is included. Set the SDK location with `ANDROID_HOME` or a local `local.properties` file; do not commit personal paths or signing keys.

OkHttp is pinned to **5.4.0**, the latest release compatible with compile SDK 36. Version 5.5.0 requires API 37 and is excluded from Dependabot updates until that SDK is available and validated.

The project pins Kotlin **2.3.0**, Android Gradle Plugin **9.4.0**, and Gradle **9.7.1**. The Android module uses AGP's built-in Kotlin support; the Kotlin/JVM, Compose compiler and serialization plugins remain explicitly configured. The Wrapper sets `distributionSha256Sum` from the [official Gradle 9.7.1 all checksum](https://services.gradle.org/distributions/gradle-9.7.1-all.zip.sha256) to verify the downloaded distribution.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build uses R8 and resource shrinking:

```bash
./gradlew :app:assembleRelease
```

`./gradlew :app:bundleRelease` also builds an AAB. Without an explicit signing configuration, the APK output is `app/build/outputs/apk/release/app-release-unsigned.apk`. It is not an installable update. Keep the same signing key for future updates to an installed app.

To run instrumentation tests, start a dedicated emulator or connect a test device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

These tests exercise the app, media service, bundled-video decoding, Compose UI, Keystore, and storage. Use a test installation rather than a device with an active personal session.

## Code structure

`core/` is a Kotlin/JVM module containing source and catalog models, M3U, Xtream, Stalker, XMLTV, catch-up, cancellable HTTP requests, and legacy settings import. It has no Android UI dependency and contains no JavaScript.

`app/` contains the phone and TV Compose interface, Media3 player and media service, Android storage, Keystore, WorkManager, and file selection. Stream HTTP headers belong to each media item, so changing channels does not change the headers of an already-created media source.

## CI and validation

Version 0.2.1 passed 80 local JVM tests, 40 release/store/signing/CI-tool checks, and all 21 distinct instrumentation scenarios on each of the phone and TV emulators across a full run and targeted rerun. Release APK/AAB checks, actual release captures and the precise signing/device/Console boundaries are documented in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md).

Version 0.2 passed 68 local JVM tests, 16 Android API 35 tests, and 10 release-tooling checks. Coverage includes real ClearKey DRM, interaction with the actual Activity, and position persistence while the screen is closed. Details and hosted CI results are in [docs/VALIDATION-0.2.md](docs/VALIDATION-0.2.md). The first phone/TV matrix history is preserved in [docs/VALIDATION.md](docs/VALIDATION.md).

The [Android workflow](.github/workflows/android.yml) runs core and Android unit tests, lint, and debug/release APK builds on pushes and pull requests. APKs and reports are uploaded as run artifacts. [Dependabot](.github/dependabot.yml) maintains GitHub Actions and Gradle dependencies.

For manual workflow runs, `run_device_tests` enables two equally required jobs: phone API 35 (`google_apis`, `x86_64`, `pixel_7` profile) and Android TV API 36 (`android-tv`, `x86_64`, `tv_1080p` profile). The TV job uses a separate TV OS image; both jobs run the complete instrumentation suite and retain separate reports. Package `system-images;android-36;android-tv;x86_64`, revision 4, was verified in the stable `sdkmanager --list --channel=0` catalog. Both emulator jobs run automatically on pull requests and pushes to main; manual runs enable them with `run_device_tests=true`. The workflow defines the validation procedure; each run's reports establish its results. Host tests, emulator tests, and physical-device experience are different levels of evidence.

Migration from the older app is described in [docs/MIGRATION.md](docs/MIGRATION.md). Current checks and release captures are collected in [docs/VALIDATION-0.2.1.md](docs/VALIDATION-0.2.1.md) and [store](store/README.md).

Device jobs compile their APKs before booting Android and limit the remaining Gradle process to one worker and a 1536 MB heap. The Google APIs phone uses 3072 MB RAM and must sustain 30 seconds below the configured guest CPU/memory/I/O pressure thresholds within five minutes before tests start. The userdebug emulator's `su` reads those protected pressure files; the ADB daemon, app, and instrumentation retain their normal privileges. Pressure samples and system ANR reports are retained as diagnostics; a readiness timeout or failing test fails the job. The TV image retains 2048 MB RAM.

## Authoring language

Write code comments, documentation, commit messages, and pull-request text in English. Preserve UI translations, language choices, and external or provider data in their original languages.
