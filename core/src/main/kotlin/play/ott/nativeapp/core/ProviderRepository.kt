package play.ott.nativeapp.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient

/** Network/catalog operations run off the main thread; cancellation cancels the active HTTP call. */
class ProviderRepository(
    client: OkHttpClient = ProviderHttp.defaultClient(),
    transportPolicy: RemoteTransportPolicy = RemoteTransportPolicy.HTTP_COMPATIBLE,
) {
    private val http = ProviderHttp(client, transportPolicy)
    private val xtream = XtreamProvider(http)
    private val stalker = StalkerProvider(http)

    suspend fun load(config: SourceConfig): Catalog = withContext(Dispatchers.IO) {
        validate(config)
        when (config.kind) {
            SourceKind.M3U -> {
                val body = http.get(config.url, config.headers)
                M3uParser.parse(body.text(), config, body.url)
            }
            SourceKind.XTREAM -> xtream.load(config)
            SourceKind.STALKER -> stalker.load(config)
        }
    }

    suspend fun resolve(config: SourceConfig, entry: MediaEntry): PlaybackStream = withContext(Dispatchers.IO) {
        // A locally imported M3U has a content:// configuration URL; its individual streams
        // are still HTTP(S). NativeRepository owns opening local files, not this HTTP layer.
        if (config.kind != SourceKind.M3U) validate(config)
        if (entry.sourceId != config.id) throw ProviderException("This item belongs to another source")
        if (entry.kind == MediaKind.SERIES) throw ProviderException("Choose an episode before playing a series")
        val stream = if (config.kind == SourceKind.STALKER) stalker.resolve(config, entry) else {
            val url = http.checkedUrl(entry.url).toString()
            PlaybackStream(url, mimeType = inferMimeType(url))
        }
        http.checkedUrl(stream.url)
        stream.copy(headers = playbackHeaders(config, entry, stream.url))
    }

    suspend fun loadEpisodes(config: SourceConfig, series: MediaEntry): List<MediaEntry> = withContext(Dispatchers.IO) {
        validate(config)
        if (config.kind != SourceKind.XTREAM || series.kind != MediaKind.SERIES || series.sourceId != config.id)
            throw ProviderException("Episode lists require a series from this Xtream source")
        xtream.episodes(config, series)
    }

    fun epgSources(config: SourceConfig, catalog: Catalog): List<String> =
        play.ott.core.NativeGuideSources.urls(catalog.epgUrls, config.epgUrl, false, play.ott.core.NativeSourceFormat.ANDROID_RAW)

    suspend fun loadEpg(url: String, headers: Map<String, String> = emptyMap()): List<Programme> = withContext(Dispatchers.IO) {
        XmltvParser.parse(http.get(url, headers, MAX_EPG_BYTES).bytes)
    }

    private fun validate(config: SourceConfig) {
        if (config.id.isBlank()) throw ProviderException("Source identifier is required")
        http.checkedUrl(config.url)
        mergedHeaders(config.headers)
        if (config.kind == SourceKind.XTREAM && (config.username.isBlank() || config.password.isBlank()))
            throw ProviderException("Xtream username and password are required")
        if (config.kind == SourceKind.STALKER && !play.ott.core.StalkerProtocol.validMac(config.mac))
            throw ProviderException("Enter a MAC address in 00:1A:79:00:00:00 format")
    }
}

private val providerJson = Json { ignoreUnknownKeys = true }
internal fun parseProviderJson(text: String): JsonElement = try { providerJson.parseToJsonElement(text) }
    catch (_: Exception) { throw ProviderException("Provider returned invalid JSON") }
internal fun requireArray(data: JsonElement, context: String): JsonArray = data as? JsonArray
    ?: throw ProviderException("$context has an unexpected format")
internal fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()
internal fun JsonObject.int(key: String): Int? = string(key).toIntOrNull()
internal fun JsonArray.objects(): List<JsonObject> = filterIsInstance<JsonObject>()
