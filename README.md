# OTT-play Native for Android

A standalone Android app for personal IPTV playlists, movies, and series. The interface uses Kotlin and Jetpack Compose; playback runs through Android Media3 / ExoPlayer. The app supports touch controls and Android TV remotes.

This is a separate product with application ID `play.ott.foss.nativeapp`. It can be installed alongside `ottplay-foss`. The JavaScript player, WebView, Capacitor and Node.js are not required to run or build it. The project version is `0.1.0`; the presence of a feature in the code does not mean that every TV, codec or provider service has been tested.

## Getting started

1. Install the debug APK on Android 8.0 / API 26 or later.
2. Choose the demo option. The APK includes an eight-second synthetic video that works offline without an account. Its audio track is silent.
3. Add your M3U playlist, select a local M3U file, or enter your Xtream or Stalker account details.
4. Open a channel, movie, or episode. Programme listings can be loaded from the source's or playlist's XMLTV URL.

The app does not provide a subscription, a built-in commercial catalog, or permission to access sources. Use URLs and accounts you are authorized to access.

## Implemented features

- **M3U:** HTTP(S) loading and import of a selected file, groups, names, logos, `tvg-id`, XMLTV and standard HTTP headers. An HLS manifest is handled as a single stream. The `default`, `append` and Flussonic catchup templates and known time substitutions are supported.
- **Xtream:** separate APIs for live TV, movies, series, and episodes, plus the older combined `player_api.php` response containing `live_streams` and `categories`. Catch-up uses server time. Unsupported optional sections returning HTTP 404/405/501 are reported explicitly; authentication, network, and server errors are not treated as successful empty catalogs.
- **Stalker:** classic MAG handshake, profile, paginated channel lists, and `create_link`. The older FOSS JSON-RPC protocol uses an explicitly configured endpoint ending in `/api/`. These are different protocols; see the [migration guide](docs/MIGRATION.md).
- **Interface:** Compose layouts for touch and TV, search, groups, favorites, movies, seasons and episodes, the program guide and access to available catchup programs.
- **Playback:** Media3 `MediaSessionService`, control through the system media session, background playback and a separate stop action. System PiP is used on devices that support it. This is PiP for the current stream, not a second channel playing simultaneously.
- **Data:** a local SQLite catalog and EPG. Source settings, saved stream URLs, and EPG URLs use AES-GCM encryption with an Android Keystore key. Source data is excluded from Android backup. Selected sources can be exported and imported as JSON.
- **EPG refresh:** WorkManager requests periodic work every six hours on an unmetered network. Android may defer execution; the interval is not a guaranteed schedule. Channel-list refresh is a separate action in the app.

## Compatibility boundaries

User-provided M3U and Xtream sources and the two described Stalker protocols are implemented natively. The branded and proprietary adapters from the old `ottplay-foss`, dealer/cloud activation, specific server commands and individual portal behavior have not all been ported automatically. This is not a claim of complete behavioral compatibility with the old project.

Kodi DRM license directives are not imported: the catalog displays a notice about them. Widevine/PlayReady configuration, DRM license acquisition and paid protected services are not implemented here. Playback of unencrypted HLS, DASH and files also depends on the format and the device's decoders. The presence of the Media3 library does not guarantee support for every stream.

HTTP is allowed for personal sources and does not encrypt transmitted data. Settings exports are plain JSON containing source URLs and credentials. Treat an export as a file containing passwords. Local-storage encryption does not encrypt exported files.

Preparing for publication on Google Play, release signing and obtaining rights to the content used are separate steps. This repository does not claim store approval or Android TV certification.

## Building

Install JDK 17 and the Android SDK with platform 36, build-tools 36.0.0, and platform-tools. The Gradle Wrapper is included. Set the SDK location with `ANDROID_HOME` or a local `local.properties` file; do not commit personal paths or signing keys.

The project pins Kotlin **2.3.0**, Android Gradle Plugin **8.13.2**, and Gradle **8.14.3**. AGP 8.13.2 includes R8 8.13.19 with Kotlin 2.3 support: [official release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes). The Wrapper sets `distributionSha256Sum` from the [official Gradle 8.14.3 all checksum](https://services.gradle.org/distributions/gradle-8.14.3-all.zip.sha256) to verify the downloaded distribution.

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The release build uses R8 and resource shrinking:

```bash
./gradlew :app:assembleRelease
```

Without custom signing configuration, the result is `app/build/outputs/apk/release/app-release-unsigned.apk`. It cannot be installed as a ready-to-use update. Retaining the same signing key is necessary for future updates to the installed application.

To run instrumentation tests, start a dedicated emulator or connect a test device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

These tests exercise the app, media service, bundled-video decoding, Compose UI, Keystore, and storage. Use a test installation rather than a device with an active personal session.

## Code structure

`core/` is a Kotlin/JVM module containing source and catalog models, M3U, Xtream, Stalker, XMLTV, catch-up, cancellable HTTP requests, and legacy settings import. It has no Android UI dependency and contains no JavaScript.

`app/` contains the phone and TV Compose interface, Media3 player and media service, Android storage, Keystore, WorkManager, and file selection. Stream HTTP headers belong to each media item, so changing channels does not change the headers of an already-created media source.

## CI and validation

The [Android workflow](.github/workflows/android.yml) runs core and Android unit tests, lint, and debug/release APK builds on pushes and pull requests. APKs and reports are uploaded as run artifacts. [Dependabot](.github/dependabot.yml) maintains GitHub Actions and Gradle dependencies.

For manual workflow runs, `run_device_tests` enables two equally required jobs: phone API 35 (`google_apis`, `x86_64`, `pixel_7` profile) and Android TV API 36 (`android-tv`, `x86_64`, `tv_1080p` profile). The TV job uses a separate TV OS image; both jobs run the complete instrumentation suite and retain separate reports. Package `system-images;android-36;android-tv;x86_64`, revision 4, was verified in the stable `sdkmanager --list --channel=0` catalog. Emulator jobs are disabled by default. The workflow defines the validation procedure; each run's reports establish its results. Host tests, emulator tests, and physical-device experience are different levels of evidence.

Migration from the old application is described in [docs/MIGRATION.md](docs/MIGRATION.md). Completed checks, results and screenshots of the actual Android interface are collected in [docs/VALIDATION.md](docs/VALIDATION.md).
