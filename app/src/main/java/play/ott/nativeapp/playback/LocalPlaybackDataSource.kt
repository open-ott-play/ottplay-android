package play.ott.nativeapp.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.SourceKind
import play.ott.nativeapp.data.NativeRepository
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Validation runs on Media3's loader, only when that item actually needs bytes. */
@UnstableApi
internal class LocalPlaybackDataSource(
    private val delegate: DataSource,
    private val original: Uri,
    private val validate: () -> Unit,
) : DataSource by delegate {
    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.uri != original) throw IOException("Local media cannot open a different document")
        try { validate() }
        catch (failure: Exception) { throw IOException("Local video unavailable", failure) }
        return delegate.open(dataSpec)
    }
}

/** Called by the loader; interrupting that loader cancels the owned validation request. */
@UnstableApi
internal fun validateLocalPlayback(repository: NativeRepository, item: MediaItem) = runBlocking {
    val extras = requireNotNull(item.requestMetadata.extras)
    val sourceId = requireNotNull(extras.getString(LocalPlaybackQueue.EXTRA_SOURCE))
    val entry = Json.decodeFromString<MediaEntry>(requireNotNull(extras.getString("play.ott.nativeapp.entry")))
    require(entry.sourceId == sourceId && entry.id == item.mediaId && entry.url == item.localConfiguration?.uri.toString())
    val source = repository.sources().first { it.id == sourceId && it.kind == SourceKind.LOCAL }
    repository.stream(source, entry)
    Unit
}
