# Documentation history

On 2026-09-13, the owner requested removal of assistant-authored Russian documentation from Git history. The rewrite translated 21 historical Markdown blobs across five files, preserving the facts, commands, hashes and validation boundaries recorded at each point in time.

All seven branch refs and the preview tag were updated atomically with explicit old-SHA leases. A fresh GitHub mirror confirmed that all 20 advertised refs, including the six pull-request heads and six regenerated merge refs, reach only the rewritten history. No original commit was reachable through those refs. The pre-rewrite backup and complete commit map are retained locally outside this repository.

Every non-Markdown file is byte-identical across each corresponding pair of commits. This includes app code, UI translations, language settings, test data, screenshots, raw validation reports and build configuration. The current main file tree was also identical immediately before and after the rewrite; this note and the release-reference clarification were added afterward.

## Published release provenance

The APK and AAB in `v0.2.0-preview.2` were built from original commit `f29a4eb17abb237e5cb58b2fd6a102012a0260fe`. The equivalent source revision in the rewritten history is [7b0eb46](https://github.com/open-ott-play/ottplay-android/commit/7b0eb46b785a00c308b066318eca4e61e3236378), which is now the release tag target. Their application code and build inputs match; only historical documentation changed.

The four uploaded release assets, including the signed binaries, original manifest and checksums, are preserved. Historical CI runs and raw validation reports retain their actual original commit IDs; they are not presented as tests run on a rewritten commit. Future releases still require a successful phone/TV matrix on their exact source commit.

## GitHub retention boundary

Rewriting advertised Git refs does not guarantee erasure of cached commit pages, force-push events, or copies held elsewhere. These are outside the Git history exposed by a fresh clone. GitHub documents that cached views may remain and that its sensitive-data removal process does not cover non-sensitive data such as documentation language. See [GitHub's history-removal guidance](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository).
