# Android documentation

Reference material for OTT-play Native for Android, the standalone Kotlin/Compose
IPTV player (`play.ott.foss.nativeapp`) with an Android TV remote interface.

## Using the app

- [Provider configuration](PROVIDER-COMPATIBILITY.md) — the M3U, Xtream and Stalker source types and what each one needs.
- [Migrating from ottplay-foss](MIGRATION.md) — the separate package ID, what an older backup contains and how sources are re-entered.
- [Local videos](LOCAL-VIDEOS.md) — the separate source type that reads originals through Android's document picker.
- [Native DRM import](DRM.md) — the Kodi M3U license properties that become Media3 configurations, and which are rejected.
- [Persistent audio and subtitle preferences](TRACK-PREFERENCES.md) — how a chosen track survives a replay or a restart.

### Persistent track preferences in other languages

- [العربية](TRACK-PREFERENCES.ar.md) · [বাংলা](TRACK-PREFERENCES.bn.md) · [Čeština](TRACK-PREFERENCES.cs.md) · [Deutsch](TRACK-PREFERENCES.de.md) · [Español](TRACK-PREFERENCES.es.md) · [فارسی](TRACK-PREFERENCES.fa.md) · [Français](TRACK-PREFERENCES.fr.md) · [עברית](TRACK-PREFERENCES.he.md) · [हिन्दी](TRACK-PREFERENCES.hi.md) · [Bahasa Indonesia](TRACK-PREFERENCES.id.md) · [Italiano](TRACK-PREFERENCES.it.md) · [日本語](TRACK-PREFERENCES.ja.md) · [한국어](TRACK-PREFERENCES.ko.md) · [Nederlands](TRACK-PREFERENCES.nl.md) · [Polski](TRACK-PREFERENCES.pl.md) · [Português](TRACK-PREFERENCES.pt.md) · [Русский](TRACK-PREFERENCES.ru.md) · [ไทย](TRACK-PREFERENCES.th.md) · [Türkçe](TRACK-PREFERENCES.tr.md) · [Українська](TRACK-PREFERENCES.uk.md) · [Tiếng Việt](TRACK-PREFERENCES.vi.md) · [简体中文](TRACK-PREFERENCES.zh-Hans.md) · [繁體中文](TRACK-PREFERENCES.zh-Hant.md)

## Build, test and release

- [Validation](VALIDATION.md) — the host checks and device matrix to run against the exact source revision.
- [CI phone rendering budget](CI-EMULATOR.md) — which changes skip the host and emulator jobs, and what a skipped job does not prove.
- [CI runner selection](CI-RUNNERS.md) — the `CI_RUNNER_MODE` variable and the three ARC scale sets used by trusted main runs.
- [Signed builds and publication](RELEASING.md) — preview, stable and production identities, tags and signing keys.
- [Offline parser fuzz assessment](FUZZING.md) — the release-candidate parser targets and the assertions they check.
- [Android 0.2.1 preview, build 8](releases/0.2.1-preview.8.md) — the dependency and Gradle 9.8.2 update for that candidate.
- [Android 0.2.1 preview, build 9](releases/0.2.1-preview.9.md) — the verified Core 0.1.4 provenance manifest merged for that candidate.
- [Android 0.2.1 preview, build 11](releases/0.2.1-preview.11.md) — the OkHttp 5.5.1 alignment and updated parser-fuzz catalog.

## Store and privacy

- [Google Play submission draft](PLAY-CONSOLE-SUBMISSION.md) — the filled working draft for package `play.ott.foss.nativeapp`, and the Console status it records.
- [Privacy publication preparation](PRIVACY-PREPARATION.md) — the offline policy screen, the published public copy and the steps still required before submission.
- [Privacy Policy — OTT-play Native for Android](privacy-policy.md) — the public policy text, last updated 2026-09-13.
- [Android icon](BRANDING.md) — how the launcher icon background is generated from the bundled demo video.

## Security and images

- [Security design and verification](security-design.md) — trust boundaries, the HTTPS certificate policy and how each control is checked.
- [Documentation screenshots](screenshots/README.md) — the store captures used by the README hero, their provenance and what they do not show.
