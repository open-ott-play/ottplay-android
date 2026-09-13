package play.ott.nativeapp.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.PlaybackStream

/** Main-thread coordinator. URL resolution may suspend; only the newest intent may commit. */
internal class ChannelNavigator(
    private val scope: CoroutineScope,
    private val currentId: () -> String?,
    private val loadChannels: suspend (String) -> List<MediaEntry>,
    private val resolve: suspend (MediaEntry) -> PlaybackStream,
    private val commit: (MediaEntry, PlaybackStream) -> Unit,
    private val changed: () -> Unit,
    private val failed: () -> Unit,
) {
    private var channels: List<MediaEntry> = emptyList()
    private var targetId: String? = null
    private var generation = 0L
    private var catalogGeneration = 0L
    private var loadJob: Job? = null
    private var resolveJob: Job? = null

    val available: Boolean
        get() = channels.size > 1 && channels.any { it.id == (targetId ?: currentId()) }

    fun currentItemChanged(id: String?) {
        cancelSwitch()
        loadJob?.cancel()
        val token = ++catalogGeneration
        if (id == null) {
            channels = emptyList()
            changed()
            return
        }
        channels = emptyList()
        changed()
        loadJob = scope.launch {
            try {
                val loaded = loadChannels(id).filter { it.kind == MediaKind.LIVE }.distinctBy { it.id }
                ensureActive()
                if (token == catalogGeneration && currentId() == id) {
                    channels = loaded.takeIf { entries -> entries.any { it.id == id } } ?: emptyList()
                    changed()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Playback remains available even when the local catalogue is unavailable. */ }
        }
    }

    fun step(direction: Int): Boolean {
        if (!available || direction == 0) return false
        val index = channels.indexOfFirst { it.id == (targetId ?: currentId()) }
        if (index < 0) return false
        val nextIndex = Math.floorMod(index + if (direction > 0) 1 else -1, channels.size)
        val entry = channels[nextIndex]
        resolveJob?.cancel()
        val token = ++generation
        val origin = currentId()
        // Advance the target now, rather than when the slow provider resolves its stream link.
        targetId = entry.id
        changed()
        resolveJob = scope.launch {
            try {
                val stream = resolve(entry)
                ensureActive()
                if (token != generation || currentId() != origin) return@launch
                commit(entry, stream)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) {
                    targetId = null
                    changed()
                    failed()
                }
            }
        }
        return true
    }

    /** Pause, Stop and a direct UI selection invalidate both cooperative and late callbacks. */
    fun cancelSwitch() {
        ++generation
        resolveJob?.cancel()
        resolveJob = null
        targetId = null
        changed()
    }

    fun close() {
        cancelSwitch()
        ++catalogGeneration
        loadJob?.cancel()
        channels = emptyList()
    }
}
