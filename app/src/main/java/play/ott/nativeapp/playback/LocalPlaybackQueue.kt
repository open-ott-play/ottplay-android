package play.ott.nativeapp.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.data.LocalVideoSnapshot
import play.ott.nativeapp.data.NativeRepository

/** The service owns local queue growth, including when the Activity is absent. */
@UnstableApi
internal class LocalPlaybackQueue(
    private val player: Player,
    private val scope: CoroutineScope,
    private val source: suspend (String) -> SourceConfig?,
    private val scan: (SourceConfig) -> StateFlow<LocalVideoSnapshot>,
) : Player.Listener {
    constructor(player: Player, repository: NativeRepository, scope: CoroutineScope) : this(
        player, scope, { id -> repository.sources().firstOrNull { it.id == id } }, { repository.localVideos.scan(it) },
    )
    private var job: Job? = null
    private var sourceId: String? = null
    private var generation = 0L
    private var advancing = false
    private var failedItem = false

    init { player.addListener(this); changed(player.currentMediaItem) }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = changed(mediaItem)
    override fun onPlaybackStateChanged(playbackState: Int) { advanceFromEnd() }
    override fun onPlayerError(error: PlaybackException) { failedItem = true; advanceFromEnd() }
    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (playWhenReady) advanceFromEnd() }

    private fun changed(item: MediaItem?) {
        failedItem = false
        val selected = item?.requestMetadata?.extras?.getString(EXTRA_SOURCE)
        // Automatic progression belongs to the same accepted queue. Explicit
        // replacement resets it, even when the source itself did not change.
        if (selected != null && selected == sourceId && player.mediaItemCount > 1) return
        job?.cancel(); job = null
        val owner = ++generation
        sourceId = selected
        if (selected == null || item == null) return
        val shuffle = item.requestMetadata.extras?.getBoolean(EXTRA_SHUFFLE) == true
        val order = LocalQueueOrder(item.mediaId, shuffle)
        job = scope.launch {
          try {
            val bound = source(selected)?.takeIf { it.kind == SourceKind.LOCAL } ?: return@launch
            scan(bound).takeWhile { snapshot ->
                val valid = generation == owner && sourceId == selected &&
                    source(selected) == bound && snapshot.sourceId == selected
                if (!valid) return@takeWhile false
                val additions = order.append(snapshot.entries).map { mediaItem(it, shuffle) }
                if (additions.isNotEmpty()) {
                    player.addMediaItems(additions)
                    advanceFromEnd()
                }
                !snapshot.cancelled
            }.collect { }
          } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { /* Keep the already accepted item playable; a new selection retries. */ }
        }
    }

    private fun advanceFromEnd() {
        if (advancing || sourceId == null || (!failedItem && player.playbackState != Player.STATE_ENDED) || !player.playWhenReady || !player.hasNextMediaItem()) return
        advancing = true
        failedItem = false
        try {
            player.seekToNextMediaItem()
            player.prepare()
        } finally { advancing = false }
    }

    private fun mediaItem(entry: MediaEntry, shuffle: Boolean): MediaItem {
        val item = PlaybackItems.build(entry.id, entry.name, entry.url, mimeType = entry.mimeType)
        val extras = Bundle(item.requestMetadata.extras ?: Bundle()).apply {
            putString(EXTRA_SOURCE, entry.sourceId)
            putBoolean(EXTRA_SHUFFLE, shuffle)
            putString("play.ott.nativeapp.entry", Json.encodeToString(entry))
        }
        return item.buildUpon().setRequestMetadata(item.requestMetadata.buildUpon().setExtras(extras).build()).build()
    }

    fun close() { generation++; job?.cancel(); player.removeListener(this); sourceId = null }

    companion object {
        const val EXTRA_SOURCE = "play.ott.nativeapp.local_source"
        const val EXTRA_SHUFFLE = "play.ott.nativeapp.local_shuffle"
    }
}
