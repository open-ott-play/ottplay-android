# Moving a provider to the native app

The native app accepts M3U playlists, Xtream credentials and supported Stalker
portal contracts. It does not execute the original application's 48 provider
JavaScript adapters. A provider name alone therefore does not establish feature
parity: obtain the full playlist URL or server/login contract supplied for your
subscription. Use the source type that matches that contract.

Several old adapters already use these standard entry points:

- **cbilling and sharavoz:** both construct Xtream `player_api.php` requests from
  a server, username and password and fall back to a `get.php` playlist. Use
  **Xtream** with those server credentials, or **M3U** with the full playlist URL.
  Evidence: [cbilling adapter](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/cbilling/prov.js#L155)
  and [sharavoz adapter](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/sharavoz/prov.js#L153).
- **tvteam:** use **M3U** with the complete provider playlist URL ending in
  `playlist.m3u8`. The old branded screen also expands a shortened value; the
  native source editor expects the complete URL. Evidence:
  [tvteam adapter](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/tvteam/prov.js#L32).
- **antifriz:** the adapter loads a `/playlist/<key>.m3u8` endpoint. Use **M3U**
  with the complete URL provided by the service. The old adapter also derives
  tokenized channel/archive URLs; archive parity depends on equivalent supported
  catchup metadata being supplied. Evidence:
  [playlist loading](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/antifriz/prov.js#L246)
  and [stream selection](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/antifriz/prov.js#L43).

These paths are derived from repository source, not live tests of a provider's
current endpoints, subscription or regional access. The examples do not imply
that account activation, device registration, server selection, provider EPG,
VOD or archive behavior has been reproduced for every brand.

For example, **ottclub** uses custom `/api/channel_now` and `/api/channel/<id>`
responses for its catalog and EPG. Its eventual media URL is HLS, but an HLS
decoder does not implement those catalog or account contracts. The native app
does not currently implement that branded API; use a provider-supplied supported
playlist/portal if available. Evidence: [catalog](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/ottclub/prov.js#L144),
[EPG](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/ottclub/prov.js#L159)
and [stream URL](https://github.com/open-ott-play/ottplay-foss/blob/8fd0762fc587154705293ac034b8fae312014569/prov/ottclub/prov.js#L42).

For protected content, the playlist must also provide one of the explicitly
supported [native DRM contracts](DRM.md). Provider-specific license challenge
transformations and undocumented proprietary APIs are not inferred or executed.
