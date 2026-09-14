# Version 0.2.1 — Google Play preparation

Checked on 2026-09-13, versionCode 3, against the changes in this report's commit on top of `9bd64d44c4f02c299f02aeb65c84b361bac92c31`. This records local validation. GitHub workflow results must be checked separately for the intended release commit.

## Implemented changes

- Release and preview enforce HTTPS through provider, media, redirect, artwork, EPG and DRM requests. The separate `.full` application retains HTTP compatibility. Source credentials retain their origin through parsing, redirects, serialization, provider resolution and media playback; explicit per-entry headers retain their own origin.
- Android TV has a separate launcher Activity without PiP. A service-owned visibility gate prevents hidden TV playback, including a late controller command after the Activity is destroyed. Phone PiP and background controls remain available.
- An offline privacy policy is reachable before source setup and from settings, including by TV remote. The public policy, listing copy, graphics, real release screenshots and foreground-service review video are prepared.
- CI runs the complete phone/TV instrumentation matrix on pull requests and main pushes. Store validation and release lint are included. Upload-key setup has an explicit, tested preparation tool; no production key was generated during this work.

## Host results

- **80 JVM tests passed:** 41 core and 39 app tests. Tests include HTTPS rejection and redirect handling, credential provenance across cache/resolve/playback, per-entry header overrides, DRM request isolation, and provider behavior.
- **31 Python tests passed**, including release preflight, store-asset validation and upload-signing preparation.
- Release lint: **0 errors, 16 warnings**. Remaining warnings concern dependency update availability and resource density coverage; this is not a warning-free lint claim.
- Debug, optimized release APK/AAB, Full APK and instrumentation APK assembled successfully. The final launcher artwork from main was integrated and the application artifacts rebuilt.
- `actionlint`, `git diff --check` and all **19 store-asset checks** passed. Five phone and three TV screenshots satisfy the checked dimensions, encoding and aspect-ratio requirements.

Reproduction:

```sh
./gradlew :core:test :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease :app:bundleRelease :app:assembleFull :app:assembleDebugAndroidTest
python3 -m unittest discover -s scripts/tests -v
python3 scripts/validate-store-assets.py --require-screenshots
actionlint
```

## Device results

Dedicated arm64 emulators used Android 15 / API 35 (phone, default image) and Android TV 16 / API 36 (android-tv image). Both reported 4096-byte memory pages. The TV checks used a TV system image.

The initial full instrumentation run executed **21 tests per device**, with 20 passing and one failure in the Home-screen test helper on each device. That helper incorrectly compared an accessibility active window with the focused launcher window. After correcting Home resolution and focused-window inspection, the entire affected `RealActivityPlaybackInstrumentedTest` class passed **2/2 on phone and 2/2 on TV**. Thus all 21 distinct scenarios passed per platform across the full run and targeted rerun; this is not a claim that the final complete suite was rerun locally in a single invocation. CI runs the final complete suite on x86_64 phone and TV images.

Coverage includes actual Activity interactions, phone PiP, TV Home pause, rejection of late hidden playback and playback after Activity destruction, privacy access with Back/D-pad, encrypted storage migration, source refresh, HLS and platform ClearKey playback using local fixtures.

The optimized release APK was separately signed with the standard Android SDK debug certificate solely for isolated device checks. Its demo played on both devices. On TV, media-session state changed from `PLAYING (3)` to `PAUSED (2)` after Home. On phone, actual playback, Home/PiP and user pause were recorded. The release screenshots and video come from this optimized application, not mockups. [Capture provenance](../store/capture-provenance.json) binds their dimensions and hashes to the tested APK/AAB.

## Bundle and public materials

`bundletool 1.18.3 validate` accepted the unsigned release AAB. Its manifest contains minSdk 26, targetSdk 36, cleartext disabled, phone PiP enabled and TV PiP disabled. All eight packaged native libraries have 16 KB-compatible ELF load-segment alignment, and the bundle requests `PAGE_ALIGNMENT_16K`.

This is static bundle evidence. The local runtime used 4 KB pages; physical 16 KB devices, delivered split-APK behavior, paid-provider acceptance, hardware Widevine/PlayReady, physical remote/decoder behavior and Play pre-launch/TV certification remain separate checks.

The [public privacy policy](https://astral-oasis-sbqd.here.now/) and [review video page](https://astral-oasis-sbqd.here.now/review/) were read back without authentication. The downloaded MP4 matched the repository SHA-256; browser metadata reported a ready video, 1080-pixel width and 18.279933-second duration. The site is saved to the publisher's existing hosting account. [Submission draft](PLAY-CONSOLE-SUBMISSION.md) contains reviewer instructions, proposed data disclosures and foreground-service answers.

## Publication boundary

The authenticated Play Console currently shows the alvit Personal account with no apps. Google is reviewing previously uploaded identity documents; contact-phone verification follows. **Create app is disabled until account verification completes.** No app record, Play upload, app-content submission or production release was created.

The candidate AAB is unsigned. Production upload signing must be configured using the intended backed-up key, then the actual signed artifact and complete CI matrix must be verified. Data safety choices, age/content questionnaires, any production-access testing requirements, review access and Play App Signing must be completed in the available Console. Technical checks and published supporting materials do not establish Google Play approval.

Local logs and detailed binary inspection are retained under `analysis/ottplay-android-google-play-fixes-20260913` in the parent workspace. Publicly shareable capture hashes are committed in `store/capture-provenance.json`; no provider credentials or upload keys are included.
