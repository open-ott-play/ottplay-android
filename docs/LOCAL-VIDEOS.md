# Local videos

Local videos are a separate source type from an imported M3U playlist. Choose
**Open video folder** or **Open video files** on the welcome screen or in Sources.
Android's document picker grants access to the selected originals. A folder can
include subfolders; a file selection can include up to 128 video or audio files.
The source opens in Movies and begins playback with its first readable item.

The app stores source permissions and catalog metadata, not a second copy of the
media. Media3 reads and seeks the original `content://` document. A provider must
supply a seekable file descriptor; a pipe-only download provider is not staged
into a local copy. A USB drive, SD card, or document provider remains responsible
for keeping the selected original available. Android TV devices without a system
document picker display an explanatory error.

The first media candidate is persisted and published while enumeration continues.
Discovery reads metadata only; a listed row does not yet prove readability or
seekability. Automatic start checks at most two candidates concurrently until one passes
current access and seekability checks. Later metadata remains visible while an
earlier original is slow; losing checks are cancelled and retain their underlying
work until it drains. Each queued item is checked again when Media3 opens it,
and an unavailable item is skipped while playback is active. Background catalog
construction never opens every original. A cloud provider may still download or
cache an individual requested original when it is opened; the app does not
control that provider behavior.
The player service appends later items to its queue. **Shuffle** chooses a discovered
item and shuffles subsequent newly discovered batches without moving or replaying
an item already accepted into that queue. This is progressive shuffle, not a
uniform permutation of a folder whose total contents are still unknown. At the
end of the current queue, playback waits for further discovery; pausing prevents
a late item from automatically starting. A completed scan with no further items
ends playback normally. **Stop scanning** retires further discoveries while
leaving already accepted items available. Explicit Stop clears playback.

Refresh starts a new scan after the previous scan finishes. Repeated refreshes
reuse an in-flight scan. Source and item identities are stable for the same SAF
selection, so favorites and resume positions survive refresh and process restart.
Selecting the same file independently and through a folder creates distinct
source bindings. Removing one binding does not revoke the other selection's
permission. Re-select a source if Android or the provider revoked its grant.
New selections are refused before the conservative 128-grant budget would be
exceeded, including existing read-only and write-only grants. Re-selecting an
already persisted URI does not consume another slot.
Settings backups cannot transfer Android document grants; select local sources
again after moving to another installation.

## Ownership and limits

Discovery uses a shared dispatcher with at most four executing threads. Each
source scan can own four operations for folder enumeration or selected-file
metadata queries. Publication runs independently of those provider calls. At most four source scans can own work at once, so up
to sixteen operations may be queued, suspended, or executing across those scans.
Playback validation uses a separate dispatcher with two executing threads.
There is one live scan per source. Each scan visits at most 20,000 provider rows,
uses a bounded event channel, and commits batches of at most 64 items. The first
item commits immediately. URI, label and group sizes are bounded. Source access
is checked before original I/O and the repository checks the saved source binding
again before starting a selected item.

Cancellation retires publication immediately and sends Android cancellation
signals, including for a pending playback validation. Admitted durable writes
drain without holding the UI retirement lock; a cancelled write cannot publish. An uncooperative provider can keep an admitted call blocked; its owner
is retained until it drains and repeated requests do not spawn replacements for
that source. Playback validation has two independent demand slots so it cannot queue behind
blocked discovery workers. Playback then uses Media3's independent content reader. The operating
system and provider still arbitrate concurrent filesystem/network I/O: this
implementation does not promise a cross-provider bandwidth or latency guarantee,
and cannot bypass a blocked provider's own first query or file open.

## Validation

The host test suite includes:

- `LocalVideoAutoplayViewModelTest`: the actual import/dispatch path, a held first
  original with later ready metadata, Stop scanning cancellation, and Stop during
  the initial or final saved-source check; negative cases await the captured jobs
  after each gate is released.
- `LocalVideoScannerTest`: first item while nested folder calls remain blocked,
  publication only after persistence, first-file validation with all four discovery
  threads blocked, nonblocking cancellation during durable writes, retained
  cancelled demand I/O, late-callback cancellation, bounded refresh
  ownership, metadata-only discovery, unreadable-first selection, flush-deadline
  arrivals, and stable scoped identity.
- `LocalPlaybackQueueTest` and `LocalQueueOrderTest`: EOF waiting/resumption,
  pause, unreadable-item recovery, queue cancellation, stale source rejection,
  and append-only shuffle.
- `LocalCatalogPersistenceTest`: encrypted first-prefix reopen, incremental
  append without rewriting earlier blocks, and source isolation.
- `LocalVideoDocumentsTest`: a real test ContentProvider, direct ranged reads
  through the application's Media3 data-source factory, grant revocation and
  foreign-tree rejection, metadata-only discovery, selected-item revalidation,
  scan-free registration, grant-capacity checks and independent grant removal.

Run the existing hosted Android workflow to compile and execute these tests,
Android lint, and debug/release APK builds. Before release, run its phone and TV
instrumentation jobs and manually check a real SAF provider with a slow/removable
original, permission withdrawal, Activity recreation, background playback and
EOF arrival after a pause. Unit fixtures do not establish codec or device support.

The implementation uses Android's [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files),
[DocumentsContract](https://developer.android.com/reference/android/provider/DocumentsContract)
and Media3 [ContentDataSource](https://developer.android.com/reference/androidx/media3/datasource/ContentDataSource).
