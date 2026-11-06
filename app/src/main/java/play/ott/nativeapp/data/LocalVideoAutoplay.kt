package play.ott.nativeapp.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.ArrayDeque
import play.ott.nativeapp.core.MediaEntry

private sealed interface LocalStartEvent {
    data class Catalog(val value: LocalVideoSnapshot) : LocalStartEvent
    data class Checked(val entry: MediaEntry, val available: Boolean) : LocalStartEvent
}

/** Only automatic start races candidates; exact user selections stay single-file requests. */
internal suspend fun firstPlayableLocalVideo(
    snapshots: StateFlow<LocalVideoSnapshot>,
    validate: suspend (MediaEntry) -> Boolean,
): MediaEntry? = coroutineScope {
    val events = Channel<LocalStartEvent>(4)
    val checks = mutableListOf<Job>()
    val observed = launch { snapshots.collect { events.send(LocalStartEvent.Catalog(it)) } }
    val seen = hashSetOf<String>()
    val pending = ArrayDeque<MediaEntry>()
    var active = 0
    var complete = false
    try {
        while (true) {
            when (val event = events.receive()) {
                is LocalStartEvent.Catalog -> {
                    if (event.value.cancelled) return@coroutineScope null
                    complete = !event.value.scanning
                    event.value.entries.filter { seen.add(it.id) }.forEach { pending.addLast(it) }
                }
                is LocalStartEvent.Checked -> {
                    active--
                    if (event.available) return@coroutineScope event.entry
                }
            }
            while (active < 2 && pending.isNotEmpty()) {
                val entry = pending.removeFirst()
                active++
                checks += launch {
                    val available = try { validate(entry) }
                    catch (cancelled: CancellationException) { currentCoroutineContext().ensureActive(); false }
                    catch (_: Exception) { false }
                    events.send(LocalStartEvent.Checked(entry, available))
                }
            }
            if (complete && active == 0 && pending.isEmpty()) return@coroutineScope null
        }
        @Suppress("UNREACHABLE_CODE") null
    } finally {
        observed.cancel()
        checks.forEach { it.cancel() }
        events.cancel()
    }
}
