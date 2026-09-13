# Migrating from ottplay-foss

The native app is installed separately as `play.ott.foss.nativeapp`. The older Full and Play apps use different IDs, so Android does not automatically transfer their sandbox, cookies, settings, or passwords. Keep the old installation until you have checked the sources you need.

## Transferring sources directly

- For M3U, open the option to add a source by URL and copy your playlist URL, XMLTV URL, and HTTP headers as needed. Select local files again through Android's file picker. Absolute HTTP(S) stream URLs inside the selected file are supported; relative links require an accessible HTTP(S) base playlist URL.
- For Xtream, copy the server URL, username, and password. The app reads standard action endpoints and the older combined live `player_api.php` response. Live TV availability does not imply movies or series are available; unsupported sections are reported separately.
- For classic Stalker / MAG, enter a `/c/` portal or an explicit `server/load.php` / `portal.php` endpoint and the MAC address registered with your provider. A `/c/` URL is normalized to `server/load.php` in the same portal directory. Protocol support does not guarantee compatibility with every provider's device and authentication checks.
- The older FOSS Stalker provider uses a different protocol: JSON-RPC `handshake` / `get_channels`. Enter its complete endpoint, such as `https://portal.example/stalker_portal/api/`. Native import of an older configuration preserves this protocol choice.

M3U headers from `#EXTVLCOPT`, `#EXTHTTP`, Kodi `stream_headers` and the URL suffix `|Header=Value` apply to the stream. Unsupported control characters and reserved headers are rejected. Kodi DRM license parameters are not converted into Widevine configuration; a notice is displayed when they are present.

## What an older backup contains

The standard `exportSettings()` command in `ottplay-foss` produces a version-1 envelope containing `settings`, `favoritesArray`, `parentalArray`, and an export timestamp. This file **does not contain account configurations or playlist URLs**. Import cannot restore missing credentials and explains this in a notice.

If you already have a separate JSON dump of the old storage, these keys are supported:

- `m3um3uArr` or `m3uArr`: an object with an `M3Us` array; the importer reads `www` and `name`. The object `{"M3Us": [...]}` is also accepted directly.
- `xtreamxtream_data` or `xtream_data`: `server`, `username`, and `password`.
- `stalkerstalker_data` or `stalker_data`: `portal` and `mac`. The session `token` is not transferred.

Each value may be a JSON object or a string containing JSON. Supported containers are `localStorage`, `storage`, and `values`. Import reads data; it does not execute scripts or contact the listed URLs until the source is refreshed.

Old local paths do not become accessible to the new application: the file must be selected again. `rechours` as a separate catchup-depth override and `medUrl` from the old media library are not transferred; the import lists these limitations. Catchup in the new M3U player uses the playlist's own catchup tags. Old numeric IDs for favorite and blocked channels do not match the new IDs; those selections must be recreated.

## Exporting from the new app

The new JSON export includes sources and supported user settings. It contains plaintext passwords and URLs with tokens. Local Android storage is protected by Keystore, but the export is a separate file: do not publish it, and delete unnecessary copies after migration.

Import validates the contents before writing and merges sources by their IDs. Changing a source configuration invalidates its previous cache. For a file source on another device, select the M3U file again and grant Android access.

## Checks before everyday use

Check every source you rely on: catalog loading, several channels in different formats, programme listings, catch-up, a movie, and an episode. Check remote or touch controls, pause, background playback, returning to the app, and explicit stop. If you use PiP, test it on the intended device.

The bundled synthetic video checks local playback without an account or network. Its silent audio track does not establish audible background playback. Use an authorized source with sound to check that behavior.

The native project does not port the full set of branded adapters, activation codes, dealer/cloud features or nonstandard extensions from the old JavaScript player. Widevine/PlayReady and DRM license acquisition are not implemented. A successful build or demo playback does not confirm these capabilities.
