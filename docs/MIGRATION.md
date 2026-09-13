# Migrating from ottplay-foss

The native app is installed separately as `play.ott.foss.nativeapp`. The older Full and Play apps use different IDs, so Android does not automatically transfer their sandbox, cookies, settings, or passwords. Keep the old installation until you have checked the sources you need.

APK/AAB building and publication were removed from the `ottplay-foss` main branch in [PR #452](https://github.com/open-ott-play/ottplay-foss/pull/452), commit `8fd0762fc587154705293ac034b8fae312014569`. Android builds now live in this repository. The old Android bridges remain there only as compatibility-test fixtures; iOS, web, desktop, and server builds continue in `ottplay-foss`.

## Transferring sources directly

- For M3U, open the option to add a source by URL and copy your playlist URL, XMLTV URL, and HTTP headers as needed. Select local files again through Android's file picker. Absolute HTTP(S) stream URLs inside the selected file are supported; relative links require an accessible HTTP(S) base playlist URL.
- For Xtream, copy the server URL, username, and password. The app reads standard action endpoints and the older combined live `player_api.php` response. Live TV availability does not imply movies or series are available; unsupported sections are reported separately.
- For classic Stalker / MAG, enter a `/c/` portal or an explicit `server/load.php` / `portal.php` endpoint and the MAC address registered with your provider. A `/c/` URL is normalized to `server/load.php` in the same portal directory. Protocol support does not guarantee compatibility with every provider's device and authentication checks.
- The older FOSS Stalker provider uses a different protocol: JSON-RPC `handshake` / `get_channels`. Enter its complete endpoint, such as `https://portal.example/stalker_portal/api/`. Native import of an older configuration preserves this protocol choice.

M3U headers from `#EXTVLCOPT`, `#EXTHTTP`, Kodi `stream_headers`, and the `|Header=Value` URL suffix apply to stream requests. Unsupported control characters and reserved headers are rejected. Supported Kodi DRM-license properties become native Media3 configurations. Unknown formats and request/response transformations are rejected; [DRM.md](DRM.md) documents the contract and device limitations.

## What an older backup contains

The standard `exportSettings()` command in `ottplay-foss` produces a version-1 envelope containing `settings`, `favoritesArray`, `parentalArray`, and an export timestamp. This file **does not contain account configurations or playlist URLs**. Import cannot restore missing credentials and explains this in a notice.

If you already have a separate JSON dump of the old storage, these keys are supported:

- `m3um3uArr` or `m3uArr`: an object with an `M3Us` array; the importer reads `www` and `name`. The object `{"M3Us": [...]}` is also accepted directly.
- `xtreamxtream_data` or `xtream_data`: `server`, `username`, and `password`.
- `stalkerstalker_data` or `stalker_data`: `portal` and `mac`. The session `token` is not transferred.

Each value may be a JSON object or a string containing JSON. Supported containers are `localStorage`, `storage`, and `values`. Import reads data; it does not execute scripts or contact the listed URLs until the source is refreshed.

Old local paths do not become accessible to the new app; select the file again. `rechours` is imported as a fallback archive duration, with the playlist's own catch-up tags taking precedence. The source editor exposes this duration in hours for playlists without catch-up metadata. The old JSON media library's `medUrl` is not converted to M3U; import reports this limitation. Old numeric favorite and blocked-channel IDs do not match the new IDs, so those selections must be rebuilt.

## Exporting from the new app

The new JSON export contains sources, favorites, the selected source, the background-playback setting, and movie/episode positions. It includes passwords and token-bearing URLs in plain text. Android's local storage is protected with Keystore, but the export is a separate file: do not publish it, and remove unneeded copies after migration.

Import validates the contents before writing and merges sources by ID. Changing a source configuration clears its previous cache. Local-file sources are skipped with a notice: select the M3U file again and grant Android access. This does not prevent network accounts in the same backup from being restored. Invalid values are checked before writing, so a malformed playback position cannot clear a source's cache. Older sources-only JSON leaves the newer user preferences unchanged.

## Checks before everyday use

Check every source you rely on: catalog loading, several channels in different formats, programme listings, catch-up, a movie, and an episode. Check remote or touch controls, pause, background playback, returning to the app, and explicit stop. If you use PiP, test it on the intended device.

The bundled synthetic video checks local playback without an account or network. Its silent audio track does not establish audible background playback. Use an authorized source with sound to check that behavior.

The native project does not port the full set of branded adapters, activation codes, dealer/cloud features or nonstandard extensions from the old JavaScript player. Widevine/PlayReady and DRM license acquisition are not implemented. A successful build or demo playback does not confirm these capabilities.

## Installing the signed 0.2 preview

Preview uses `play.ott.foss.nativeapp.preview`, separate from the debug/early 0.1 APK and future production app. To migrate, export settings from the previous installation and import them into preview. Passwords are in plaintext in the exported JSON; delete the unnecessary copy after migration. The same configured signing key is retained across subsequent previews; a new key is not generated on every CI run. Release procedure and required key configuration: [RELEASING.md](RELEASING.md).
