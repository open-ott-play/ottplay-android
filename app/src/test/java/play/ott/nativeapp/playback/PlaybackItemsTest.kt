package play.ott.nativeapp.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class PlaybackItemsTest {
    @Test fun `HTTP credentials are source scoped and are absent from public metadata`() {
        val input = mutableMapOf("Authorization" to "Bearer first")
        val first = PlaybackItems.build("first", "Channel one", "https://host.test/one.m3u8?secret=one", input)
        input["Authorization"] = "Bearer second"
        val second = PlaybackItems.build("second", "Channel two", "https://host.test/two.mpd?secret=two", input)
        assertEquals("Bearer first", PlaybackItems.headers(first)["Authorization"])
        assertEquals("Bearer second", PlaybackItems.headers(second)["Authorization"])
        assertNull(first.mediaMetadata.extras)
        assertNull(first.mediaMetadata.artworkUri)
        assertEquals("Channel one", first.mediaMetadata.title.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, first.localConfiguration?.mimeType)
        assertEquals(MimeTypes.APPLICATION_MPD, second.localConfiguration?.mimeType)
    }

    @Test fun `live playback defaults to live edge while VOD retains requested offset`() {
        val live = PlaybackItems.build("live", "Live", "https://host.test/live.m3u8", isLive = true)
        val vod = PlaybackItems.build("vod", "Movie", "https://host.test/vod.mp4", startPositionMs = 12_345L)
        val negative = PlaybackItems.build("vod", "Movie", "https://host.test/vod.mp4", startPositionMs = -5L)
        assertEquals(C.TIME_UNSET, PlaybackItems.startPositionMs(live))
        assertEquals(12_345L, PlaybackItems.startPositionMs(vod))
        assertEquals(0L, PlaybackItems.startPositionMs(negative))
    }

    @Test fun `requests missing local configuration after IPC are rebuilt with detached headers`() {
        val headers = Bundle().apply { putString("Referer", "https://portal.test/") }
        val extras = Bundle().apply { putBundle(PlaybackItems.EXTRA_HEADERS, headers) }
        val incoming = MediaItem.Builder().setMediaId("channel")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse("https://stream.test/live.m3u8"))
                .setExtras(extras).build())
            .build()
        assertNull(incoming.localConfiguration)
        val resolved = PlaybackItems.resolve(incoming)
        headers.putString("Referer", "https://changed.test/")
        assertEquals(Uri.parse("https://stream.test/live.m3u8"), resolved.localConfiguration?.uri)
        assertEquals("https://portal.test/", PlaybackItems.headers(resolved)["Referer"])
    }

    @Test fun `explicit stop cancels play intent and clears media but pause does not`() {
        val calls = mutableListOf<String>()
        val delegate = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "setPlayWhenReady" -> calls.add("playWhenReady=${args!![0]}")
                "stop", "clearMediaItems", "pause" -> calls.add(method.name)
            }
            null
        } as Player
        val player = StopClearsPlaylistPlayer(delegate)
        player.pause()
        assertEquals(listOf("pause"), calls)
        calls.clear()
        player.stop()
        assertEquals(listOf("playWhenReady=false", "stop", "clearMediaItems"), calls)
        assertTrue(calls.none { it == "release" })
    }
}
