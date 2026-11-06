package play.ott.nativeapp.playback

import org.junit.Assert.*
import org.junit.Test
import play.ott.nativeapp.core.MediaEntry
import kotlin.random.Random

class LocalQueueOrderTest {
    private fun item(id: String) = MediaEntry(id, "s", id, "content://videos/$id")
    @Test fun lateBatchesNeverReplayAnAcceptedItem() {
        val order = LocalQueueOrder("first", true, Random(7))
        val firstBatch = listOf("first", "a", "b", "c").map(::item)
        val accepted = order.append(firstBatch)
        assertEquals(setOf("a", "b", "c"), accepted.map { it.id }.toSet())
        assertTrue(order.append(firstBatch).isEmpty())
        assertEquals(listOf("d"), order.append(firstBatch + item("d")).map { it.id })
        assertEquals(setOf("a", "b", "c"), accepted.map { it.id }.toSet())
    }
    @Test fun sequentialPlaybackPreservesPublishedOrder() {
        val order = LocalQueueOrder("first", false)
        assertEquals(listOf("b", "a"), order.append(listOf("first", "b", "a", "b").map(::item)).map { it.id })
    }
}
