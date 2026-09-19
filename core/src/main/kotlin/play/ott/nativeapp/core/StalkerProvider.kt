package play.ott.nativeapp.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl

/** Classic MAG protocol, plus the existing FOSS dialect when the explicit URL ends in /api/. */
internal class StalkerProvider(private val http: ProviderHttp) {
    private val sessions = ConcurrentHashMap<SourceConfig, String>()
    private val sessionMutex = Mutex()

    suspend fun load(config: SourceConfig): Catalog = if (isRpc(config)) loadRpc(config) else loadClassic(config)

    suspend fun resolve(config: SourceConfig, entry: MediaEntry): PlaybackStream {
        if (isRpc(config)) {
            val url = httpUrl(entry.url).toString()
            return PlaybackStream(url, mergedHeaders(config.headers, entry.headers), inferMimeType(url))
        }
        val data = call(config, "itv", "create_link", mapOf("cmd" to entry.url, "series" to "0",
            "forced_storage" to "0", "disable_ad" to "0", "download" to "0"))
        val cmd = (data as? JsonObject)?.string("cmd").orEmpty().ifBlank { (data as? JsonPrimitive)?.content.orEmpty() }
        val link = cmd.trim().replace(Regex("^(?:ffmpeg|ffrt|auto)\\s+", RegexOption.IGNORE_CASE), "").trim()
        val url = resolveHttp(endpoint(config).toString(), link).takeIf(String::isNotBlank)
            ?: throw ProviderException("Portal did not return a playable link")
        if (httpUrl(url).host in setOf("localhost", "127.0.0.1", "::1"))
            throw ProviderException("Portal returned an unresolved local playback command")
        // API bearer tokens are never copied into an unrelated streaming-host request.
        return PlaybackStream(url, mergedHeaders(config.headers, entry.headers), inferMimeType(url))
    }

    private fun endpoint(config: SourceConfig): HttpUrl {
        val input = httpUrl(config.url)
        val parts = input.pathSegments.filter(String::isNotBlank).toMutableList()
        if (parts.lastOrNull()?.endsWith(".php", true) == true) return input.newBuilder().query(null).fragment(null).build()
        if (parts.lastOrNull().equals("c", true)) parts.removeAt(parts.lastIndex)
        return input.newBuilder().encodedPath("/").query(null).fragment(null).apply {
            parts.forEach { addPathSegment(it) }; addPathSegment("server"); addPathSegment("load.php")
        }.build()
    }

    private suspend fun token(config: SourceConfig): String = sessionMutex.withLock {
        sessions[config]?.let { return@withLock it }
        val data = raw(config, "stb", "handshake", mapOf("token" to ""), null) as? JsonObject
        val token = data?.string("token").orEmpty()
        if (token.isBlank()) throw ProviderException("Stalker handshake did not return an access token")
        if (sessions.size >= 100) sessions.clear()
        sessions[config] = token
        token
    }

    private suspend fun call(config: SourceConfig, type: String, action: String, params: Map<String, String> = emptyMap()): JsonElement {
        val token = token(config)
        return try { raw(config, type, action, params, token) } catch (error: ProviderException) {
            if (error.statusCode !in setOf(401, 403)) throw error
            sessions.remove(config, token)
            raw(config, type, action, params, token(config))
        }
    }

    private suspend fun raw(config: SourceConfig, type: String, action: String, params: Map<String, String>, token: String?): JsonElement {
        val url = endpoint(config).newBuilder().addQueryParameter("type", type).addQueryParameter("action", action)
            .addQueryParameter("JsHttpRequest", "1-xml").apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val cookie = "mac=${config.mac.uppercase()}; stb_lang=en; timezone=UTC"
        val headers = mergedHeaders(config.headers, mapOf("Cookie" to cookie, "X-User-Agent" to "Model: MAG250; Link: Ethernet"),
            if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap())
        val response = parseProviderJson(http.get(url.toString(), headers).text()) as? JsonObject
            ?: throw ProviderException("Invalid Stalker response")
        val data = response["js"] ?: response["result"] ?: throw ProviderException("Stalker response has no result")
        if (data is JsonNull) throw ProviderException("Stalker portal returned no result")
        if (data is JsonObject && data.string("error").isNotBlank()) throw ProviderException("Stalker portal rejected the request")
        return data
    }

    private suspend fun loadClassic(config: SourceConfig): Catalog {
        token(config)
        // Some portals establish their session while retrieving this profile.
        call(config, "stb", "get_profile", mapOf("hd" to "1"))
        val genres = requireArray(call(config, "itv", "get_genres"), "Stalker genres").objects()
            .associate { it.string("id") to it.string("title").ifBlank { it.string("name") } }
        val entries = linkedMapOf<String, MediaEntry>()
        var page = 1
        var expectedTotal: Int? = null
        while (page <= 1000) {
            val data = call(config, "itv", "get_ordered_list", mapOf("genre" to "*", "p" to page.toString(),
                "fav" to "0", "sortby" to "number", "hd" to "0"))
            val obj = data as? JsonObject
            val rows = if (data is JsonArray) data else obj?.get("data") as? JsonArray
                ?: throw ProviderException("Invalid Stalker channel page")
            val total = obj?.int("total_items")
            if (total != null && total > 100_000) throw ProviderException("Portal contains more than 100,000 channels")
            if (total != null && total >= 0) expectedTotal = total
            if (rows.isEmpty()) {
                if (expectedTotal != null && entries.size < expectedTotal) throw ProviderException("Portal channel pagination ended before all channels arrived")
                break
            }
            val before = entries.size
            rows.objects().forEach { channel ->
                val id = channel.string("id").ifBlank { channel.string("ch_id") }
                val cmd = channel.string("cmd").ifBlank { channel.string("url") }
                if (id.isNotBlank() && cmd.isNotBlank()) {
                    val key = stableId(config.id, "stalker", id)
                    entries[key] = MediaEntry(key, config.id, channel.string("name").ifBlank { "Channel $id" }, cmd,
                        group = genres[channel.string("tv_genre_id")].orEmpty().ifBlank { channel.string("genre") }.ifBlank { "Other" },
                        logo = resolveHttp(endpoint(config).toString(), channel.string("logo")),
                        epgId = channel.string("xmltv_id").ifBlank { id }, headers = config.headers, providerId = id,
                        headerOrigins = headerOrigins(config.headers, config.url),
                        nameMessage = if (channel.string("name").isBlank()) CoreMessage(CoreMessageKey.CHANNEL, listOf(id)) else null,
                        groupMessage = if (genres[channel.string("tv_genre_id")].isNullOrBlank() && channel.string("genre").isBlank()) CoreMessage(CoreMessageKey.OTHER_GROUP) else null)
                }
            }
            if (expectedTotal != null && entries.size >= expectedTotal) break
            if (entries.size == before) throw ProviderException("Portal repeated a channel page; refusing an incomplete catalogue")
            val pageSize = obj?.int("max_page_items")
            if (expectedTotal == null && (data is JsonArray || (pageSize != null && rows.size < pageSize))) break
            page++
        }
        if (page > 1000) throw ProviderException("Portal exceeds the channel pagination limit")
        return Catalog(entries.values.toList(), epgUrls(config))
    }

    private fun isRpc(config: SourceConfig): Boolean = httpUrl(config.url).encodedPath.trimEnd('/').endsWith("/api")

    private suspend fun rpc(config: SourceConfig, method: String): JsonElement {
        val request = buildJsonObject {
            put("jsonrpc", "2.0"); put("id", 1); put("method", method)
            put("params", buildJsonObject { put("mac", config.mac) })
        }
        val response = parseProviderJson(http.postJson(config.url, request.toString(), config.headers).text()) as? JsonObject
            ?: throw ProviderException("Invalid Stalker JSON-RPC response")
        if (response["error"] != null && response["error"] !is JsonNull) throw ProviderException("Stalker JSON-RPC request was rejected")
        val result = response["result"]?.takeUnless { it is JsonNull } ?: throw ProviderException("Stalker JSON-RPC returned no result")
        if (result is JsonPrimitive && result.content in setOf("false", "0", ""))
            throw ProviderException("Stalker JSON-RPC rejected the request")
        return result
    }

    private suspend fun loadRpc(config: SourceConfig): Catalog {
        rpc(config, "handshake")
        val result = rpc(config, "get_channels")
        val rows = when (result) {
            is JsonArray -> result
            is JsonObject -> listOf("data", "items", "channels").firstNotNullOfOrNull { result[it] as? JsonArray }
                ?: JsonArray(result.values.filterIsInstance<JsonObject>())
            else -> throw ProviderException("Invalid JSON-RPC channel catalogue")
        }
        val input = httpUrl(config.url)
        val parts = input.pathSegments.filter(String::isNotBlank).dropLast(1)
        val portal = input.newBuilder().encodedPath("/").query(null).fragment(null)
            .apply { parts.forEach { addPathSegment(it) } }.build()
        val entries = rows.objects().mapNotNull { channel ->
            val id = channel.string("id").ifBlank { channel.string("ch_id") }
            if (id.isBlank()) return@mapNotNull null
            val url = channel.string("url").takeIf(String::isNotBlank)?.let { resolveHttp(config.url, it) }
                ?: portal.newBuilder().addPathSegment("stream").addPathSegment("$id.m3u8").addQueryParameter("mac", config.mac).build().toString()
            MediaEntry(stableId(config.id, "stalker", id), config.id, channel.string("name").ifBlank { "Channel $id" }, url,
                group = rpcCategory(channel).ifBlank { "Other" },
                logo = resolveHttp(config.url, channel.string("logo").ifBlank { channel.string("icon") }.ifBlank { channel.string("tv_icon") }),
                epgId = channel.string("xmltv_id").ifBlank { id }, headers = config.headers, providerId = id,
                headerOrigins = headerOrigins(config.headers, config.url),
                nameMessage = if (channel.string("name").isBlank()) CoreMessage(CoreMessageKey.CHANNEL, listOf(id)) else null,
                groupMessage = if (rpcCategory(channel).isBlank()) CoreMessage(CoreMessageKey.OTHER_GROUP) else null)
        }
        return Catalog(entries, epgUrls(config))
    }

    private fun epgUrls(config: SourceConfig): List<String> = listOfNotNull(config.epgUrl.takeIf(String::isNotBlank)?.let { httpUrl(it).toString() })

    private fun rpcCategory(channel: JsonObject): String = sequenceOf("genre", "categories", "category")
        .mapNotNull { key -> when (val value = channel[key]) {
            is JsonPrimitive -> value.takeUnless { it is JsonNull }?.content
            is JsonArray -> (value.firstOrNull() as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
            else -> null
        } }.firstOrNull(String::isNotBlank).orEmpty()
}
