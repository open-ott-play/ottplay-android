package play.ott.nativeapp.playback

import play.ott.nativeapp.core.MediaEntry
import kotlin.random.Random

/** Accepted order is append-only; late discovery cannot replay or move an earlier item. */
internal class LocalQueueOrder(firstId: String, private val shuffle: Boolean, private val random: Random = Random.Default) {
    private val accepted = hashSetOf(firstId)
    fun append(entries: List<MediaEntry>): List<MediaEntry> {
        val fresh = entries.filter { accepted.add(it.id) }
        return if (shuffle) fresh.shuffled(random) else fresh
    }
}
