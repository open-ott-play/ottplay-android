# Native version 0.1.0 validation

Source code: `8eed56f52fe5a90829354a1fe0ff1de625931709`. Local validation was performed on 13 September 2026 UTC. Machine-readable results: [validation/local-results.json](validation/local-results.json).

## Local validation

JDK 17, Gradle 8.14.3, AGP 8.13.2, Kotlin 2.3.0, compile/target SDK 36. A dedicated AOSP Android 15 / API 35 arm64 emulator used the Pixel 6 profile.

```sh
./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:assembleRelease :app:lintDebug
```

The command completed successfully. There were 25 core tests, 19 Android unit/Robolectric tests and 11 instrumented Android tests: 55 total, with no failures or skips. Lint reported 0 errors, 26 warnings and 1 hint, mostly dependency updates and KTX suggestions, plus the TV vector banner and the intentionally exported MediaSessionService that validates controllers. No lint baseline hides errors. AGP was updated to a version compatible with Kotlin 2.3, removing the R8 warnings about incompatible Kotlin metadata.

The instrumented checks use the real Android runtime:

- Keystore AES-GCM, fresh nonces, rejection of modified ciphertext without deleting the damaged file, and no plaintext credentials in the vault/SQLite/WAL.
- Atomic import validation, isolation between two sources, exact EPG ID matching and invalidation of a changed catalogue.
- A 3,000-entry catalogue is stored in 24 encrypted blocks; failure during the next refresh rolls back blocks already written. One debug-emulator measurement recorded a 450 ms write and a 382 ms read. This is not a physical set-top box performance estimate.
- Android SAX: XMLTV, gzip, timezones and rejection of DOCTYPE/external entities.
- Real ExoPlayer, SurfaceView and MP4 decoding; Activity/controller destruction, reconnection to the same player, and pause/resume/stop.
- HLS master/media manifests and TS segments from a loopback HTTP server; separate Authorization headers for streams A/B, decoding A -> B -> A.
- Compose onboarding, demo selection, returning from the player, the source editor and D-pad/focus in a TV configuration. This last local check by itself does not constitute a run on TV OS.

## App verification through the system interface

The bundled source was selected through the real MainActivity, its catalogue was opened and the synthetic clip was played. Going to the home screen put the Activity into `mode=pinned`; Android reported `mLastReportedPictureInPictureMode=true`. A decoded frame was visible in settled PiP. The system media Play command resumed the stream, and the media session reported PLAYING.

[Catalogue screen](screenshots/phone-library.png) · [Video in system PiP](screenshots/phone-pip.png). These historical screenshots retain the app's actual localization.

The optimized release APK was built with R8 and resource shrinking. A separate installable copy was signed with the local Android SDK test certificate, leaving the unsigned release separate. APK v2/v3 signatures were verified. This copy was installed over the debug version: the stored encrypted catalogue was read, MainActivity opened, the clip decoded, and system Pause stopped playback at 3560 ms without an error. [Release player frame](screenshots/phone-release-player.png). One cold launch measured with `am start -W` took 1051 ms; this is a single emulator observation, not an SLA. This certificate is for preview testing, not a store release. SHA-256 and size are recorded in the JSON linked above.

## GitHub and validation limits

[The first complete workflow](https://github.com/open-ott-play/ottplay-android/actions/runs/34742174407) successfully completed Linux host checks and built the APK. Both emulator jobs stopped before the tests started: Android Emulator required 7372,80 MB for userdata, while the runner had 6836,73 MB remaining for the phone and 2072,44 MB for TV. This is not an instrumentation test result. For the rerun, userdata size has been capped and unused Android NDK/CMake installations have been removed from the temporary runner to free space. Results for phone API 35 and actual TV API 36 will be recorded from the rerun reports.

Physical televisions, set-top boxes and phones; real user IPTV accounts; DRM; hardware HEVC/AC3 paths; HDMI/audio passthrough; and store certification were not tested. Successful H264/AAC playback does not establish those cases. Proprietary adapters from the original app are not claimed as ported; [MIGRATION.md](MIGRATION.md) defines the migration scope.
