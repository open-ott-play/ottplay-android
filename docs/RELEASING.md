# Signed builds and publication

The version is stored in `version.properties`: currently `0.2.0`, `versionCode=2`. Increase the version code before every new published preview or production release. The workflow does not move an existing tag, replace a release or overwrite assets.

The two channels use different Android package IDs and certificates:

- **Preview:** `play.ott.foss.nativeapp.preview`, an R8-optimized APK and a signed AAB; tag `v0.2.0-preview.2`. The final tag component is the `versionCode`. This is a GitHub prerelease for testing, with no store submission.
- **Production:** `play.ott.foss.nativeapp`, APK and AAB signed with an explicitly supplied production/upload key; tag `v0.2.0`. Missing production secrets stop the workflow. Preview/debug key fallback and unsigned APK publication are prohibited.

The preview installs alongside the earlier 0.1.0 app. Because its package ID differs, Android does not transfer the database or Keystore automatically: export sources from the old version and import the file into the preview. The export contains plaintext credentials; keep it local. Later previews can update an existing preview when the same signing key is retained and `versionCode` increases.

## Stable preview certificate

`scripts/configure-preview-signing.py` creates a **preview-only** RSA key and stores the keystore and password JSON outside the repository with permissions `0600`. Repeated calls reuse the existing key and check its fingerprint; an incomplete file pair causes an error instead of silent key rotation. The Android SDK debug key is not used. Creating the key and uploading it to GitHub require the owner's explicit authorization.

```bash
python3 scripts/configure-preview-signing.py \
  --keystore "$HOME/.android/ottplay-native-preview.keystore" \
  --credentials "$HOME/.android/ottplay-native-preview-signing.json" \
  --repository open-ott-play/ottplay-android
```

With `--repository`, the script passes values to `gh secret set` through stdin. Console output contains only backup paths and the public certificate SHA-256. Do not put the private key, passwords or credentials JSON in Git, artifacts, messages or logs. Keep a protected backup outside this computer: losing the key prevents updates to the installed preview.

Five repository secrets are required with the `PREVIEW_SIGNING_` prefix: `KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `CERT_SHA256`. Production uses the same five suffixes with the `RELEASE_SIGNING_` prefix. SHA-256 is 64 hexadecimal characters without colons. The owner supplies the production key; this project does not generate it or configure a Google Play account.

## Local builds

Use JDK 17, Android SDK platform 36 and build-tools 36.0.0. The Wrapper pins Gradle and verifies the distribution SHA-256.

```bash
python3 scripts/release.py build --channel preview \
  --credentials "$HOME/.android/ottplay-native-preview-signing.json"
```

For an existing production key, securely set `OTTPLAY_KEYSTORE` (an absolute path), `OTTPLAY_STORE_PASSWORD`, `OTTPLAY_KEY_ALIAS`, `OTTPLAY_KEY_PASSWORD` and `OTTPLAY_EXPECTED_CERT_SHA256`, then run `python3 scripts/release.py build --channel production`. Passwords are not passed as process arguments. A JSON file with these field names and permissions `0600` is also accepted through `--credentials`.

The command runs core/app unit tests, lint for the selected variant, and `assemblePreview`/`bundlePreview` or `assembleRelease`/`bundleRelease`. It verifies the APK with `apksigner`, compares its signer with the expected certificate, checks the APK package ID/versionCode/versionName with `aapt2`, then verifies the AAB with `jarsigner -verify -strict` and checks its certificate fingerprint. Any failure stops the release. Ordinary host CI may still build an unsigned release to check compilation; that is not a publishable artifact for this channel.

Output is written to `build/distributions/<tag>/`: signed `.apk` and `.aab` files, a `release-manifest.json` containing the commit/package/version/fingerprint/sizes/hashes, and `SHA256SUMS`. Install the APK with `adb install -r <apk>`; an AAB cannot be installed directly. Check a downloaded set with `shasum -a 256 -c SHA256SUMS` on macOS or `sha256sum -c SHA256SUMS` on Linux.

## Releasing through GitHub Actions

1. Commit the new version and all changes to `main`. Run **Android native** with `run_device_tests=true` and wait for successful host, phone API 35 and real Android TV API 36 jobs on that exact commit.
2. Confirm that the five signing secrets for the selected channel are configured. Run **Signed Android release** manually on `main` and select `preview` or `production`.
3. The workflow checks a clean checkout, matching SHA, an unused tag, increasing version/code and a successful full matrix on the same commit. It then repeats host tests/lint, builds and verifies both signatures, and produces checksums.
4. The key is decoded only into a private temporary runner directory and removed in an `always()` step. Configuration cache is disabled for signed builds. Artifacts contain release files and reports, not the keystore. The build job has read-only repository access.
5. A separate publish job with `contents: write` downloads only this run's verified assets; it receives no signing secrets. Checks are repeated before publication. A new Git ref is created atomically, a draft release is created with all assets, and the release is then published. A preview is marked as a prerelease and does not become the latest production release.

The script enforces append-only behaviour within its pipeline. It does not change GitHub repository rules or prevent an administrator from manually changing a tag or release. The owner can enable GitHub immutable releases or tag rules for that protection. If upload/publication stops after creating the tag or draft, another run refuses to overwrite it: investigate the cause, then use a new version or explicitly manage the unfinished draft manually.

Signing an APK and AAB does not publish them to Google Play. With Play App Signing, the AAB is signed with an upload key and Google signs user APKs with the app signing key; an APK signed with the upload key may not update an app installed from Play. Store policies, Play Console, ownership of the production identity and physical-device validation remain separate steps. See [Android app signing](https://developer.android.com/studio/publish/app-signing) and the [GitHub Releases API](https://docs.github.com/en/rest/releases/releases).

Release tooling checks: `python3 -m unittest discover -s scripts/tests` and `actionlint .github/workflows/release.yml`. They verify rejection of missing signing configuration, production fallback, reused tags/codes and a missing successful full device matrix. Each signed build also verifies the actual signatures.
