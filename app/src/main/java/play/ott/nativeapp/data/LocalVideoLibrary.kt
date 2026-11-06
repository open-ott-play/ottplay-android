package play.ott.nativeapp.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

/** Application-owned scans survive Activity recreation and are shared with playback. */
class LocalVideoLibrary internal constructor(
    private val scope: CoroutineScope,
    private val documents: LocalVideoDocuments,
    private val dispatcher: CoroutineDispatcher,
    private val demandDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2),
    persist: suspend (SourceConfig, List<MediaEntry>, Boolean, LocalVideoOwner) -> Unit,
) {
    internal constructor(context: Context, scope: CoroutineScope,
        persist: suspend (SourceConfig, List<MediaEntry>, Boolean, LocalVideoOwner) -> Unit,
    ) : this(scope, AndroidLocalVideoDocuments(context), Dispatchers.IO.limitedParallelism(4), persist = persist)
    private val scanner = LocalVideoScanner(scope, dispatcher, documents, persist)
    private data class Binding(val source: SourceConfig, val scan: LocalVideoScanner.Scan, val public: MutableStateFlow<LocalVideoSnapshot>)
    private val scans = mutableMapOf<String, Binding>()
    private var generation = 0L

    @Synchronized fun scan(source: SourceConfig, refresh: Boolean = false): StateFlow<LocalVideoSnapshot> {
        require(source.kind == SourceKind.LOCAL)
        val previous = scans[source.id]
        previous?.let {
            // Do not accumulate replacement jobs behind a provider which has
            // ignored CancellationSignal. A later retry can start after drain.
            if (it.scan.state.value.scanning && (it.scan.owner.cancelled || it.source != source)) {
                cancel(source.id)
                return it.public
            }
            if (it.source == source && !it.scan.owner.cancelled && (it.scan.state.value.scanning || !refresh)) return it.public
            it.scan.cancel()
        }
        if (scans.values.count { it.scan.state.value.scanning } >= 4) {
            return MutableStateFlow(LocalVideoSnapshot(source.id, generation, scanning = false, failed = true))
        }
        val started = scanner.start(source, ++generation)
        // Existing playback observes refreshes through the same source stream.
        // A retired worker can never replace a newer generation's publication.
        val public = previous?.public ?: MutableStateFlow(started.state.value)
        public.value = started.state.value
        val binding = Binding(source, started, public)
        scans[source.id] = binding
        scope.launch {
            started.state.first { snapshot ->
                synchronized(this@LocalVideoLibrary) {
                    if (scans[source.id] === binding && !public.value.cancelled) public.value = snapshot
                }
                !snapshot.scanning
            }
        }
        return public
    }

    @Synchronized fun cancel(sourceId: String) {
        scans[sourceId]?.let {
            it.scan.cancel()
            it.public.value = it.public.value.copy(scanning = false, cancelled = true)
        }
    }
    @Synchronized fun forget(sourceId: String) {
        cancel(sourceId)
        val binding = scans[sourceId] ?: return
        scope.launch {
            binding.scan.state.first { !it.scanning }
            synchronized(this@LocalVideoLibrary) { if (scans[sourceId] === binding) scans.remove(sourceId) }
        }
    }

    // A published item must not queue behind unrelated blocked discovery calls.
    // Media3 also has its own loader; demand validation has a separate fixed bound.
    suspend fun validate(source: SourceConfig, entry: MediaEntry): Boolean = suspendCancellableCoroutine { continuation ->
        require(source.kind == SourceKind.LOCAL && entry.sourceId == source.id && entry.id == localVideoId(source.id, entry.url))
        val owner = LocalVideoOwner()
        // This application-owned job retains a blocked provider call after the
        // requesting UI is cancelled, while queued work can be removed at once.
        val work = scope.launch(demandDispatcher) {
            try {
                owner.check()
                val actual = documents.document(source, entry.url, owner)
                val readable = actual != null && !actual.directory && documents.readable(source, actual, owner)
                owner.check()
                if (continuation.isActive) continuation.resume(readable)
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
        continuation.invokeOnCancellation { owner.cancel(); work.cancel() }
    }
}
