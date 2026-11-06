package play.ott.nativeapp.playback

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.data.LocalVideoSnapshot

@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class LocalPlaybackQueueTest {
    private val source = SourceConfig("local", "Local", SourceKind.LOCAL, "content://video/root", localFolder = true)
    private fun entry(id: String) = MediaEntry(id, source.id, id, "content://video/$id")
    private fun firstItem() = PlaybackItems.build("first", "First", "content://video/first").let { item ->
        item.buildUpon().setRequestMetadata(item.requestMetadata.buildUpon().setExtras(Bundle().apply {
            putString(LocalPlaybackQueue.EXTRA_SOURCE, source.id)
        }).build()).build()
    }
    private class EndedPlayer(player: Player) : ForwardingPlayer(player) {
        var ended = true
        var ready = true
        var advances = 0
        var prepares = 0
        override fun getPlaybackState() = if (ended) Player.STATE_ENDED else Player.STATE_READY
        override fun getPlayWhenReady() = ready
        override fun seekToNextMediaItem() { advances++; ended = false; super.seekToNextMediaItem() }
        override fun prepare() { prepares++ }
    }

    @Test fun eofWaitsForLatePublicationAndResumesOnceWithoutReplayingPrefix() = runTest {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        exo.setMediaItem(firstItem())
        val player = EndedPlayer(exo)
        val snapshots = MutableStateFlow(LocalVideoSnapshot(source.id, 1, listOf(entry("first"))))
        val queue = LocalPlaybackQueue(player, backgroundScope, { source }, { snapshots })
        try {
            runCurrent()
            assertEquals(0, player.advances)
            snapshots.value = snapshots.value.copy(entries = listOf(entry("first"), entry("second")))
            runCurrent(); shadowOf(Looper.getMainLooper()).idle(); runCurrent()
            assertEquals(1, player.advances)
            assertEquals(1, player.prepares)
            assertEquals(2, exo.mediaItemCount)
            assertEquals("second", exo.currentMediaItem?.mediaId)
            snapshots.value = snapshots.value.copy(scanning = false)
            runCurrent()
            assertEquals(2, exo.mediaItemCount)
            assertEquals(1, player.advances)
        } finally { queue.close(); exo.release() }
    }

    @Test fun pausePreventsLateEofAdvanceAndClosedQueueRejectsLaterRows() = runTest {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        exo.setMediaItem(firstItem())
        val player = EndedPlayer(exo).apply { ready = false }
        val snapshots = MutableStateFlow(LocalVideoSnapshot(source.id, 1, listOf(entry("first"))))
        val queue = LocalPlaybackQueue(player, backgroundScope, { source }, { snapshots })
        try {
            runCurrent()
            snapshots.value = snapshots.value.copy(entries = listOf(entry("first"), entry("second")))
            runCurrent()
            assertEquals(0, player.advances)
            player.ready = true
            queue.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            assertEquals(1, player.advances)
            queue.close()
            snapshots.value = snapshots.value.copy(entries = snapshots.value.entries + entry("late"))
            runCurrent()
            assertEquals(2, exo.mediaItemCount)
        } finally { queue.close(); exo.release() }
    }

    @Test fun changedSourceCannotAppendAStaleScan() = runTest {
        val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
        exo.setMediaItem(firstItem())
        val snapshots = MutableStateFlow(LocalVideoSnapshot(source.id, 1, listOf(entry("first"))))
        var current = source
        val queue = LocalPlaybackQueue(exo, backgroundScope, { current }, { snapshots })
        try {
            runCurrent()
            current = source.copy(url = "content://other/root")
            snapshots.value = snapshots.value.copy(entries = listOf(entry("first"), entry("stale")))
            runCurrent()
            assertEquals(1, exo.mediaItemCount)
        } finally { queue.close(); exo.release() }
    }
}
