# Security design and verification

## Scope and trust boundaries

The project provides the native Kotlin/Compose Android and Android TV player.

Untrusted playlists, guide metadata, provider URLs and settings imports enter a native media application. Source settings use Android Keystore-backed AES-GCM, while manual JSON exports are unencrypted and may contain secrets. Release/preview builds require HTTPS; the separate Full sideload build permits legacy HTTP. Scope provider headers to their intended requests and never forward DRM license headers to provisioning endpoints.

## Source and operating documentation

- [docs/privacy-policy.md](../docs/privacy-policy.md)
- [docs/MIGRATION.md](../docs/MIGRATION.md)
- [app/src/main/AndroidManifest.xml](../app/src/main/AndroidManifest.xml)
- [README.md](../README.md)

## Regression evidence

- [app/src/test/java/play/ott/nativeapp/playback/RequestPolicyTest.kt](../app/src/test/java/play/ott/nativeapp/playback/RequestPolicyTest.kt)
- [app/src/test/java/play/ott/nativeapp/playback/ScopedDrmCallbackTest.kt](../app/src/test/java/play/ott/nativeapp/playback/ScopedDrmCallbackTest.kt)
- [app/src/test/java/play/ott/nativeapp/data/SettingsBackupPolicyTest.kt](../app/src/test/java/play/ott/nativeapp/data/SettingsBackupPolicyTest.kt)

Run the documented commands in [CONTRIBUTING.md](../CONTRIBUTING.md) and the
[CI workflow](../.github/workflows/android.yml). Preserve negative tests for rejected inputs,
unavailable dependencies, authorization failures and cancellation. A passing
test run describes its fixtures and environment; it does not certify every
upstream service, hardware model or production deployment.

## Remaining security assessment

Review every distribution variant against transport/cryptography criteria and record exact device/emulator evidence. Signed release metadata and privacy-policy behavior must match the released APK.

Report new issues through [SECURITY.md](../SECURITY.md). An OpenSSF assessment
records evidence and applicability; it is not a guarantee that a system is safe.
