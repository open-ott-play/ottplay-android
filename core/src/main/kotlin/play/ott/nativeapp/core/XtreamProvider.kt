package play.ott.nativeapp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl

internal class XtreamProvider(private val http: ProviderHttp) {
    suspend fun load(config: SourceConfig): Catalog {
        val auth = api(config, null) as? JsonObject ?: throw ProviderException("Invalid Xtream account response")
        val account = auth["user_info"] as? JsonObject
        if (account?.string("auth") in setOf("0", "false")) throw ProviderException("Xtream rejected the username or password")
        val status = account?.string("status").orEmpty()
        if (status.isNotBlank() && !status.equals("active", true)) throw ProviderException("Xtream account is not active")
        val serverZone = (auth["server_info"] as? JsonObject)?.string("timezone").orEmpty().ifBlank { "UTC" }
        val entries = mutableListOf<MediaEntry>()
        val notes = mutableListOf<String>()
        listOf(Triple("live", "get_live_streams", MediaKind.LIVE), Triple("vod", "get_vod_streams", MediaKind.MOVIE),
            Triple("series", "get_series", MediaKind.SERIES)).forEach { (type, action, kind) ->
            val label = when (kind) { MediaKind.LIVE -> "ТВ"; MediaKind.MOVIE -> "Фильмы"; else -> "Сериалы" }
            // The legacy FOSS provider consumed live_streams/categories directly from player_api.php.
            // Prefer that complete result when present; standard Xtream servers expose action endpoints.
            val embeddedLive = if (kind == MediaKind.LIVE) auth["live_streams"] as? JsonArray else null
            val streams = embeddedLive ?: try { requireArray(api(config, action), "Xtream catalogue") }
            catch (error: ProviderException) {
                if (kind == MediaKind.LIVE || error.statusCode !in UNSUPPORTED) throw error
                notes += "$label: сервер не поддерживает этот раздел (HTTP ${error.statusCode}). Остальные разделы загружены."
                return@forEach
            }
            val categoryResponse = if (embeddedLive != null) (auth["categories"] as? JsonArray ?: JsonArray(emptyList()))
                else try { requireArray(api(config, "get_${type}_categories"), "Xtream categories") }
                catch (error: ProviderException) {
                    if (error.statusCode !in UNSUPPORTED) throw error
                    notes += "$label: сервер не предоставляет группы (HTTP ${error.statusCode}); записи доступны без групп провайдера."
                    JsonArray(emptyList())
                }
            val categories = categoryResponse.objects().associate { it.string("category_id") to it.string("category_name") }
            streams.objects().forEach item@ { item ->
                val id = item.string(if (kind == MediaKind.SERIES) "series_id" else "stream_id")
                if (id.isBlank()) return@item
                val name = item.string("name").ifBlank { "Untitled $type" }
                val direct = item.string("direct_source")
                val url = when {
                    kind == MediaKind.SERIES -> ""
                    direct.isNotBlank() -> resolveHttp(config.url, direct)
                    else -> streamUrl(config, if (kind == MediaKind.MOVIE) "movie" else "live", id,
                        extension(item.string("container_extension"), if (kind == MediaKind.LIVE) "m3u8" else "mp4"))
                }
                val archiveDays = item.string("tv_archive_duration").toDoubleOrNull() ?: 0.0
                val catchup = if (kind == MediaKind.LIVE && archiveDays > 0 && item.string("tv_archive") in setOf("1", "true"))
                    Catchup("xtream", archiveTemplate(config, id), archiveDays, serverZone) else null
                val epgId = if (kind == MediaKind.LIVE) item.string("epg_channel_id").ifBlank { id } else ""
                entries += MediaEntry(stableId(config.id, type, id), config.id, name, url, kind,
                    group = categories[item.string("category_id")].orEmpty().ifBlank { "Other" },
                    logo = resolveHttp(config.url, item.string("stream_icon").ifBlank { item.string("cover") }), epgId = epgId,
                    headers = config.headers, catchup = catchup, description = item.string("plot"), providerId = id)
                if (entries.size > 100_000) throw ProviderException("Xtream catalogue contains more than 100,000 entries")
            }
        }
        val epg = if (config.epgUrl.isNotBlank()) httpUrl(config.epgUrl).toString() else apiUrl(config, "xmltv.php").toString()
        return Catalog(entries.distinctBy { it.id }, listOf(epg), notes)
    }

    suspend fun episodes(config: SourceConfig, series: MediaEntry): List<MediaEntry> {
        val seriesId = series.providerId.ifBlank { throw ProviderException("Series identifier is missing") }
        val response = api(config, "get_series_info", mapOf("series_id" to seriesId)) as? JsonObject
            ?: throw ProviderException("Invalid Xtream series response")
        val episodes = response["episodes"]
        val result = mutableListOf<MediaEntry>()
        val seasons: List<Pair<String, JsonArray>> = when (episodes) {
            is JsonObject -> episodes.mapNotNull { (season, value) -> (value as? JsonArray)?.let { season to it } }
            is JsonArray -> listOf("0" to episodes)
            else -> throw ProviderException("Xtream returned no episode list")
        }
        seasons.forEach { (season, list) -> list.objects().forEach episode@ { item ->
            val id = item.string("id").ifBlank { item.string("stream_id") }
            if (id.isBlank()) return@episode
            val info = item["info"] as? JsonObject
            val direct = item.string("direct_source")
            val url = direct.takeIf(String::isNotBlank)?.let { resolveHttp(config.url, it) }
                ?: streamUrl(config, "series", id, extension(item.string("container_extension"), "mp4"))
            result += MediaEntry(
                id = stableId(config.id, "episode", id), sourceId = config.id,
                name = item.string("title").ifBlank { "Episode ${item.string("episode_num").ifBlank { id }}" },
                url = url, kind = MediaKind.EPISODE, group = series.name,
                logo = resolveHttp(config.url, info?.string("movie_image").orEmpty()).ifBlank { series.logo },
                headers = config.headers, description = info?.string("plot").orEmpty(),
                season = item.int("season") ?: season.toIntOrNull(), episode = item.int("episode_num"), providerId = id,
            )
            if (result.size > 100_000) throw ProviderException("Series contains too many episodes")
        } }
        return result.distinctBy { it.id }.sortedWith(compareBy<MediaEntry> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    private fun base(config: SourceConfig): HttpUrl {
        val input = httpUrl(config.url)
        val segments = input.pathSegments.filter(String::isNotBlank).toMutableList()
        if (segments.lastOrNull() in setOf("player_api.php", "get.php", "xmltv.php")) segments.removeAt(segments.lastIndex)
        return input.newBuilder().encodedPath("/").query(null).fragment(null).apply { segments.forEach { addPathSegment(it) } }.build()
    }

    private fun apiUrl(config: SourceConfig, file: String = "player_api.php", action: String? = null, params: Map<String, String> = emptyMap()): HttpUrl =
        base(config).newBuilder().addPathSegment(file).addQueryParameter("username", config.username)
            .addQueryParameter("password", config.password).apply {
                action?.let { addQueryParameter("action", it) }; params.forEach { (key, value) -> addQueryParameter(key, value) }
            }.build()

    private suspend fun api(config: SourceConfig, action: String?, params: Map<String, String> = emptyMap()): JsonElement =
        parseProviderJson(http.get(apiUrl(config, action = action, params = params).toString(), config.headers).text())

    private fun streamUrl(config: SourceConfig, type: String, id: String, ext: String): String = base(config).newBuilder()
        .addPathSegment(type).addPathSegment(config.username).addPathSegment(config.password).addPathSegment("$id.$ext").build().toString()

    private fun archiveTemplate(config: SourceConfig, id: String): String {
        val prefix = base(config).newBuilder().addPathSegment("timeshift").addPathSegment(config.username)
            .addPathSegment(config.password).build().toString().trimEnd('/')
        val encodedId = HttpUrl.Builder().scheme("https").host("template.invalid").addPathSegment("$id.ts").build().encodedPath
        return "$prefix/{durationMinutes}/{startDate}$encodedId"
    }

    private fun extension(candidate: String, default: String): String = candidate.takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: default

    companion object { private val UNSUPPORTED = setOf(404, 405, 501) }
}
