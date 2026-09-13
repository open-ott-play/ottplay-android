package play.ott.nativeapp.core

import java.net.URLDecoder
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Extended M3U parser. Directives between EXTINF and its URI belong to that entry. */
object M3uParser {
    private val attribute = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s]+))")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, config: SourceConfig, baseUrl: String = config.url): Catalog {
        if (text.length > MAX_CATALOG_BYTES) throw ProviderException("Playlist exceeds the supported size limit")
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim).toList()
        val notes = if (lines.any { line ->
            line.startsWith("#KODIPROP:", true) && line.substringBefore('=').let {
                it.contains(".license_", true) || it.endsWith(".drm", true) || it.contains(".drm_", true)
            }
        }) listOf("Плейлист содержит настройки DRM для Kodi. Их лицензии и ключи не импортируются; защищённые записи могут не воспроизводиться.")
        else emptyList()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") || it.startsWith("#EXT-X-TARGETDURATION:") }) {
            val url = httpUrl(baseUrl).toString()
            return Catalog(listOf(MediaEntry(stableId(config.id, url), config.id, config.name.ifBlank { "HLS stream" }, url,
                headers = mergedHeaders(config.headers))), listOfNotNull(config.epgUrl.takeIf(String::isNotBlank)), notes)
        }
        val entries = linkedMapOf<String, MediaEntry>()
        val epgUrls = linkedSetOf<String>()
        if (config.epgUrl.isNotBlank()) resolveHttp(baseUrl, config.epgUrl).takeIf(String::isNotBlank)?.let(epgUrls::add)
        var defaults = emptyMap<String, String>()
        var attrs = emptyMap<String, String>()
        var name = ""
        var duration = -1.0
        var group = ""
        var entryHeaders = emptyMap<String, String>()
        var hasMetadata = false
        var invalidMetadata = false
        var index = 0
        fun reset() { attrs = emptyMap(); name = ""; duration = -1.0; entryHeaders = emptyMap(); hasMetadata = false; invalidMetadata = false }
        while (index < lines.size) {
            val line = lines[index++]
            if (line.isBlank()) continue
            if (line.length > 1_048_576) throw ProviderException("Playlist contains an oversized line")
            when {
                line.startsWith("#EXTM3U", true) -> {
                    defaults = attributes(line)
                    listOf("url-tvg", "x-tvg-url").forEach { key ->
                        defaults[key]?.split(',')?.map(String::trim)?.forEach { candidate ->
                            resolveHttp(baseUrl, candidate).takeIf(String::isNotBlank)?.let(epgUrls::add)
                        }
                    }
                }
                line.startsWith("#EXTINF:", true) -> {
                    reset()
                    var info = line.substringAfter(':')
                    // Accept folded metadata without accidentally treating a following URI as a title.
                    while (commaOutsideQuotes(info) < 0 && index < lines.size &&
                        lines[index].matches(Regex("(?:tvg-|group-title|catchup)[^\\r\\n]*", RegexOption.IGNORE_CASE))) {
                        info += " " + lines[index++]
                    }
                    val comma = commaOutsideQuotes(info)
                    if (comma >= 0) {
                        attrs = attributes(info.substring(0, comma))
                        name = info.substring(comma + 1).trim()
                        duration = info.substringBefore(' ').substringBefore(',').toDoubleOrNull() ?: -1.0
                        hasMetadata = true
                    } else invalidMetadata = true
                }
                line.startsWith("#EXTGRP:", true) -> group = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", true) -> {
                    val option = line.substringAfter(':')
                    val key = when (option.substringBefore('=').lowercase()) {
                        "http-user-agent" -> "User-Agent"
                        "http-referrer", "http-referer" -> "Referer"
                        "http-origin" -> "Origin"
                        else -> null
                    }
                    if (key != null) entryHeaders = mergedHeaders(entryHeaders, mapOf(key to option.substringAfter('=', "")))
                }
                line.startsWith("#KODIPROP:", true) && line.substringBefore('=').endsWith("stream_headers", true) ->
                    entryHeaders = mergedHeaders(entryHeaders, queryHeaders(line.substringAfter('=')))
                line.startsWith("#EXTHTTP:", true) -> {
                    try {
                        val data = json.parseToJsonElement(line.substringAfter(':')) as? JsonObject
                        val headers = data?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.content?.let { key to it } }?.toMap().orEmpty()
                        entryHeaders = mergedHeaders(entryHeaders, headers)
                    } catch (_: Exception) { throw ProviderException("Playlist contains invalid HTTP headers") }
                }
                line.startsWith('#') -> Unit
                else -> {
                    val rawUrl = line.substringBefore('|').trim()
                    if (invalidMetadata || (!hasMetadata && !rawUrl.startsWith("http://", true) && !rawUrl.startsWith("https://", true))) {
                        reset(); continue
                    }
                    val url = resolveHttp(baseUrl, rawUrl)
                    if (url.isBlank() || rawUrl.contains('<') || rawUrl.contains('>')) { reset(); continue }
                    val allAttrs = defaults + attrs
                    val title = name.ifBlank { attrs["tvg-name"].orEmpty() }.ifBlank { attrs["tvg-id"].orEmpty() }
                        .ifBlank { httpUrl(url).pathSegments.lastOrNull().orEmpty().ifBlank { "Stream ${entries.size + 1}" } }
                    val entryGroup = attrs["group-title"].orEmpty().ifBlank { group }
                    if (entryGroup.isNotBlank()) group = entryGroup
                    val days = sequenceOf("catchup-days", "timeshift", "tvg-rec")
                        .mapNotNull { allAttrs[it]?.toDoubleOrNull() }.firstOrNull()?.coerceAtLeast(0.0) ?: 0.0
                    val catchupMode = allAttrs["catchup"] ?: allAttrs["catchup-type"].orEmpty()
                    val catchupSource = allAttrs["catchup-source"].orEmpty()
                    val catchup = if (catchupMode.equals("none", true)) null else if (days > 0 || catchupSource.isNotEmpty() || catchupMode.isNotEmpty())
                        Catchup(catchupMode.ifBlank { "default" }, catchupSource, days) else null
                    val headers = mergedHeaders(config.headers, entryHeaders, if ('|' in line) queryHeaders(line.substringAfter('|')) else emptyMap())
                    val id = stableId(config.id, attrs["tvg-id"].orEmpty().ifBlank { url }, title)
                    entries.putIfAbsent(id, MediaEntry(
                        id, config.id, title, url,
                        kind = if ((hasMetadata && duration > 0) || attrs["type"].equals("movie", true)) MediaKind.MOVIE else MediaKind.LIVE,
                        group = entryGroup, logo = resolveHttp(baseUrl, attrs["tvg-logo"].orEmpty()),
                        epgId = attrs["tvg-id"].orEmpty(), headers = headers, catchup = catchup,
                    ))
                    if (entries.size > 100_000) throw ProviderException("Playlist contains more than 100,000 entries")
                    reset()
                }
            }
        }
        if (entries.isEmpty()) throw ProviderException("Playlist contains no playable HTTP or HTTPS entries")
        return Catalog(entries.values.toList(), epgUrls.toList(), notes)
    }

    private fun attributes(text: String): Map<String, String> = attribute.findAll(text).associate {
        it.groupValues[1].lowercase() to it.groups.drop(2).firstNotNullOf { group -> group?.value }
    }

    private fun commaOutsideQuotes(text: String): Int {
        var quote: Char? = null
        text.forEachIndexed { index, char ->
            if (quote == char) quote = null else if (quote == null && char in "\"'") quote = char
            else if (quote == null && char == ',') return index
        }
        return -1
    }

    internal fun queryHeaders(text: String): Map<String, String> = text.split('&').filter { '=' in it }.associate {
        try { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        catch (_: IllegalArgumentException) { throw ProviderException("Invalid encoded playlist header") }
    }.let { mergedHeaders(it) }
}

internal fun stableId(vararg parts: String): String = MessageDigest.getInstance("SHA-256")
    .digest(parts.joinToString("\u0000").toByteArray()).take(16).joinToString("") { "%02x".format(it) }
