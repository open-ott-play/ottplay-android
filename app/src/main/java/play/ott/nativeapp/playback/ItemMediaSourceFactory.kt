package play.ott.nativeapp.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import okhttp3.OkHttpClient

/**
 * A new HTTP factory for each source. HLS manifests, segments and keys (and DASH resources)
 * keep the same immutable header snapshot while a later playlist item is being prepared.
 * Sharing one mutable setDefaultRequestProperties across the playlist would leak credentials.
 */
@UnstableApi
internal class ItemMediaSourceFactory(context: Context, private val client: OkHttpClient) : MediaSource.Factory {
    private val context = context.applicationContext
    private var drmProvider: DrmSessionManagerProvider? = null
    private var errorPolicy: LoadErrorHandlingPolicy? = null

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val resolved = PlaybackItems.resolve(mediaItem)
        PlaybackItems.requireSupported(context, resolved)
        val http = httpFactoryFor(resolved)
        val factory = DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        val config = PlaybackItems.drm(resolved)
        if (drmProvider != null) {
            factory.setDrmSessionManagerProvider(requireNotNull(drmProvider))
        } else if (config != null) {
            val builder = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(PlaybackItems.uuid(config.scheme), FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(config.multiSession)
                .setPlayClearSamplesWithoutKeys(false)
            errorPolicy?.let(builder::setLoadErrorHandlingPolicy)
            val manager = builder.build(ScopedDrmCallback(config, client))
            factory.setDrmSessionManagerProvider { manager }
        }
        errorPolicy?.let(factory::setLoadErrorHandlingPolicy)
        return factory.createMediaSource(resolved)
    }

    internal fun httpFactoryFor(item: MediaItem): OkHttpDataSource.Factory = OkHttpDataSource.Factory(client)
        .setUserAgent("OTT-play-Android/1.0")
        .setDefaultRequestProperties(PlaybackItems.headers(item))

    override fun getSupportedTypes(): IntArray = DefaultMediaSourceFactory(context).supportedTypes

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory = apply {
        drmProvider = drmSessionManagerProvider
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory = apply {
        errorPolicy = loadErrorHandlingPolicy
    }
}
