package play.ott.nativeapp.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/**
 * UI/service request contract. Credentials belong exclusively in requestMetadata (never title,
 * artist, mediaId, MediaMetadata.extras or log messages). Callers provide an opaque catalogue id.
 *
 * EXTRA_HEADERS: Bundle of String header-name -> String value, scoped to this media item.
 * EXTRA_IS_LIVE: Boolean, catalogue hint; the actual timeline remains authoritative for seeking.
 * EXTRA_START_POSITION_MS: Long, requested VOD offset; 0 means the default position for live.
 * EXTRA_ARTWORK: String, optional private artwork URL, reserved for authenticated image loading.
 * Remote artwork URLs are deliberately not published in MediaMetadata/lock-screen extras.
 */
@UnstableApi
object PlaybackItems {
    const val EXTRA_HEADERS = "play.ott.nativeapp.request_headers"
    const val EXTRA_IS_LIVE = "play.ott.nativeapp.is_live"
    const val EXTRA_START_POSITION_MS = "play.ott.nativeapp.start_position_ms"
    const val EXTRA_ARTWORK = "play.ott.nativeapp.artwork"

    fun build(
        id: String,
        title: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        artwork: String? = null,
        isLive: Boolean = false,
        startPositionMs: Long = 0L,
    ): MediaItem {
        require(id.isNotBlank()) { "A catalogue item id is required" }
        val uri = Uri.parse(RequestPolicy.requireStreamUrl(url))
        val extras = Bundle().apply {
            putBundle(EXTRA_HEADERS, headerBundle(RequestPolicy.headers(headers)))
            putBoolean(EXTRA_IS_LIVE, isLive)
            putLong(EXTRA_START_POSITION_MS, startPositionMs.coerceAtLeast(0L))
            if (!artwork.isNullOrBlank()) putString(EXTRA_ARTWORK, artwork)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMimeType(mimeType(uri))
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).setExtras(extras).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title.ifBlank { "OTT-play" }).build())
            .build()
    }

    fun startPositionMs(item: MediaItem): Long {
        val extras = item.requestMetadata.extras
        val position = extras?.getLong(EXTRA_START_POSITION_MS, 0L)?.coerceAtLeast(0L) ?: 0L
        return if (position == 0L && extras?.getBoolean(EXTRA_IS_LIVE, false) == true) C.TIME_UNSET else position
    }

    internal fun headers(item: MediaItem): Map<String, String> {
        val bundle = item.requestMetadata.extras?.getBundle(EXTRA_HEADERS) ?: return emptyMap()
        val values = bundle.keySet().associateWith { key ->
            requireNotNull(bundle.getString(key)) { "Invalid request header value" }
        }
        return RequestPolicy.headers(values)
    }

    /** Rebuild after controller IPC; do not depend on LocalConfiguration surviving serialization. */
    internal fun resolve(item: MediaItem): MediaItem {
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        requireNotNull(uri) { "A stream URL is required" }
        RequestPolicy.requireStreamUrl(uri.toString())
        val extras = Bundle(item.requestMetadata.extras ?: Bundle()).apply {
            putBundle(EXTRA_HEADERS, headerBundle(headers(item)))
        }
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(item.localConfiguration?.mimeType ?: mimeType(uri))
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).setExtras(extras).build())
            .build()
    }

    private fun headerBundle(headers: Map<String, String>): Bundle = Bundle().apply {
        headers.forEach { (key, value) -> putString(key, value) }
    }

    private fun mimeType(uri: Uri): String? = when {
        uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        uri.path.orEmpty().lowercase(Locale.ROOT).endsWith(".mpd") -> MimeTypes.APPLICATION_MPD
        else -> null
    }
}
