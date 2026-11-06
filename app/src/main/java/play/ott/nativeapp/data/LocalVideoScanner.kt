package play.ott.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig

data class LocalVideoSnapshot(
    val sourceId: String,
    val generation: Long,
    val entries: List<MediaEntry> = emptyList(),
    val scanning: Boolean = true,
    val cancelled: Boolean = false,
    val failed: Boolean = false,
)

/** Cancellation retires publications immediately; admitted provider calls still drain. */
internal class LocalVideoOwner {
    private val lock = Any()
    private var retired = false
    private val callbacks = mutableSetOf<() -> Unit>()
    private val visited = AtomicInteger()
    val cancelled: Boolean get() = synchronized(lock) { retired }
    fun check() { synchronized(lock) { if (retired) throw CancellationException("Local scan cancelled") } }
    fun visit() { check(); check(visited.incrementAndGet() <= 20_000) { "Local source exceeds the document limit" } }
    fun <T> publish(action: () -> T): T = synchronized(lock) { check(); action() }
    // The caller retains this owner while admitted durable I/O drains. Do not
    // hold the publication lock across SQLite/Keystore: UI retirement must return
    // immediately even if storage is stuck. A cancelled write cannot publish.
    fun <T> persist(action: () -> T): T {
        check()
        val result = action()
        check()
        return result
    }
    fun onCancel(action: () -> Unit): () -> Unit {
        val now = synchronized(lock) { if (retired) true else { callbacks.add(action); false } }
        if (now) action()
        return { synchronized(lock) { callbacks.remove(action) }; Unit }
    }
    fun cancel() {
        val actions = synchronized(lock) {
            if (retired) return
            retired = true
            callbacks.toList().also { callbacks.clear() }
        }
        actions.forEach { runCatching(it) }
    }
}

internal data class LocalDocument(val uri: String, val name: String, val mime: String, val directory: Boolean, val group: String)

internal interface LocalVideoDocuments {
    suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit)
    suspend fun document(source: SourceConfig, uri: String, owner: LocalVideoOwner): LocalDocument?
    suspend fun readable(source: SourceConfig, document: LocalDocument, owner: LocalVideoOwner): Boolean
}

internal fun localVideoId(sourceId: String, uri: String): String = "local-video:" +
    MessageDigest.getInstance("SHA-256").digest((sourceId + "\u0000" + uri).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Four owned operations include directory enumeration and file validation together. */
internal class LocalVideoScanner(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val documents: LocalVideoDocuments,
    private val persist: suspend (SourceConfig, List<MediaEntry>, Boolean, LocalVideoOwner) -> Unit,
) {
    internal class Scan(val owner: LocalVideoOwner, val state: StateFlow<LocalVideoSnapshot>) {
        fun cancel() = owner.cancel()
    }
    private sealed interface Work {
        data class Folder(val uri: String, val group: String) : Work
        data class SelectedFile(val uri: String) : Work
        data class File(val document: LocalDocument) : Work
    }
    private sealed interface Event {
        data class Document(val document: LocalDocument) : Event
        data class Ready(val entry: MediaEntry) : Event
        data class Done(val failed: Boolean, val discovery: Boolean) : Event
    }

    fun start(source: SourceConfig, generation: Long): Scan {
        val owner = LocalVideoOwner()
        val state = MutableStateFlow(LocalVideoSnapshot(source.id, generation))
        scope.launch {
            val events = Channel<Event>(64)
            val pending = ArrayDeque<Work>()
            val discovered = hashSetOf<String>()
            val published = linkedMapOf<String, MediaEntry>()
            val batch = arrayListOf<MediaEntry>()
            var active = 0
            var discovery = 0
            var failed = false
            var persisted = false
            if (source.localFolder) pending.add(Work.Folder(source.url, ""))
            else source.localDocuments.ifEmpty { listOf(source.url) }.forEach { pending.add(Work.SelectedFile(it)) }
            suspend fun flush() {
                if (batch.isEmpty() || owner.cancelled) return
                val ready = batch.toList()
                persist(source, ready, !persisted, owner)
                owner.publish {
                    persisted = true
                    ready.forEach { published[it.id] = it }
                    batch.clear()
                    state.value = LocalVideoSnapshot(source.id, generation, published.values.toList())
                }
            }
            fun launch(work: Work) {
                active++
                val finding = work !is Work.File
                if (finding) discovery++
                scope.launch(dispatcher) {
                    var problem = false
                    try {
                        owner.check()
                        when (work) {
                            is Work.Folder -> documents.folder(source, work.uri, work.group, owner) { events.send(Event.Document(it)) }
                            is Work.SelectedFile -> documents.document(source, work.uri, owner)?.let { events.send(Event.Document(it)) }
                            is Work.File -> if (documents.readable(source, work.document, owner)) {
                                owner.check()
                                val item = work.document
                                events.send(Event.Ready(MediaEntry(localVideoId(source.id, item.uri), source.id, item.name,
                                    item.uri, MediaKind.MOVIE, group = item.group, mimeType = item.mime, headerOrigins = emptyMap())))
                            } else problem = true
                        }
                    } catch (_: CancellationException) { }
                    catch (_: Exception) { problem = true }
                    finally { events.send(Event.Done(problem, finding)) }
                }
            }
            try {
                while (active > 0 || pending.isNotEmpty()) {
                    if (owner.cancelled) pending.clear()
                    while (!owner.cancelled && active < 4 && pending.isNotEmpty()) {
                        // Keep one slot for a ready file even when nested
                        // directory providers stop responding mid-enumeration.
                        val next = pending.firstOrNull { discovery < 3 || it is Work.File } ?: break
                        pending.remove(next)
                        launch(next)
                    }
                    if (active == 0) break
                    when (val event = withTimeoutOrNull(250) { events.receive() }) {
                        is Event.Document -> if (!owner.cancelled && discovered.add(event.document.uri)) {
                            if (discovered.size > 20_000) { failed = true; owner.cancel() }
                            else if (event.document.directory) pending.addFirst(Work.Folder(event.document.uri, event.document.group))
                            else pending.addFirst(Work.File(event.document))
                        }
                        is Event.Ready -> if (!owner.cancelled && !published.containsKey(event.entry.id)) {
                            batch.add(event.entry)
                            if (published.isEmpty() || batch.size >= 64) flush()
                        }
                        is Event.Done -> { active--; if (event.discovery) discovery--; failed = failed || event.failed }
                        null -> flush()
                    }
                }
                flush()
                if (!owner.cancelled && !persisted) persist(source, emptyList(), true, owner)
            } catch (_: Exception) {
                failed = true
                owner.cancel()
                // Workers may be inside a non-interruptible provider call. Keep
                // receiving their completions rather than dropping their owner.
                while (active > 0) if (events.receive() is Event.Done) active--
            } finally {
                state.value = LocalVideoSnapshot(source.id, generation, published.values.toList(),
                    scanning = false, cancelled = owner.cancelled, failed = failed)
                events.close()
            }
        }
        return Scan(owner, state)
    }
}
