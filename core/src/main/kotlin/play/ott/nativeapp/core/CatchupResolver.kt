package play.ott.nativeapp.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

object CatchupResolver {
    /** Null means no supported archive or a programme outside the advertised retention window. */
    fun resolve(entry: MediaEntry, programme: Programme, nowMillis: Long = System.currentTimeMillis()): PlaybackStream? {
        val catchup = entry.catchup ?: return null
        if (entry.kind != MediaKind.LIVE || programme.startMillis >= nowMillis || programme.endMillis <= programme.startMillis) return null
        // Match the catalogue's case-insensitive XMLTV lookup and its display-name fallback.
        if (entry.epgId.isNotBlank() && !programme.channelId.equals(entry.epgId, ignoreCase = true) &&
            !programme.channelId.equals(entry.name, ignoreCase = true)) return null
        if (catchup.days > 0 && nowMillis - programme.startMillis > catchup.days * 86_400_000.0) return null
        val start = programme.startMillis / 1000
        val end = minOf(programme.endMillis, nowMillis) / 1000
        val now = nowMillis / 1000
        if (end <= start) return null
        val mode = catchup.mode.lowercase(Locale.ROOT)
        val raw = when {
            mode == "none" -> return null
            catchup.source.isNotBlank() -> if (mode == "append") entry.url + catchup.source else catchup.source
            mode.startsWith("flussonic") -> flussonic(entry.url, start, end, now) ?: return null
            mode in setOf("default", "shift", "append") -> entry.url + (if ('?' in entry.url) "&" else "?") + "utc=$start&lutc=$now"
            else -> return null
        }
        val zone = try { ZoneId.of(catchup.timeZone) } catch (_: Exception) { ZoneId.of("UTC") }
        val replacements = mapOf(
            "start" to start.toString(), "utc" to start.toString(), "end" to end.toString(), "utcend" to end.toString(),
            "timestamp" to now.toString(), "lutc" to now.toString(), "offset" to (now - start).toString(),
            "duration" to (end - start).toString(), "durationMinutes" to ceil((end - start) / 60.0).toLong().toString(),
            "startDate" to DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT).withZone(zone).format(Instant.ofEpochSecond(start)),
        )
        var substituted = raw
        replacements.forEach { (key, value) ->
            substituted = substituted.replace("\${$key}", value).replace("{$key}", value)
        }
        // Unknown templates must never fall back to live playback while the UI says "archive".
        if (Regex("\\$?\\{[^}]+}").containsMatchIn(substituted)) return null
        val url = resolveHttp(entry.url, substituted).takeIf(String::isNotBlank) ?: return null
        return PlaybackStream(url, entry.headers, inferMimeType(url))
    }

    private fun flussonic(url: String, start: Long, end: Long, now: Long): String? {
        val variants = linkedMapOf(
            "mpegts" to Pair("archive-$start-${end - start}.ts", "timeshift_abs-$start.ts"),
            "video.m3u8" to Pair("video-$start-${end - start}.m3u8", "video-timeshift_abs-$start.m3u8"),
            "mono.m3u8" to Pair("mono-$start-${end - start}.m3u8", "mono-timeshift_abs-$start.m3u8"),
            "index.m3u8" to Pair("archive-$start-${end - start}.m3u8", "timeshift_abs-$start.m3u8"),
            "index.mpd" to Pair("archive-$start-${end - start}.mpd", "timeshift_abs-$start.mpd"),
        )
        val parsed = httpUrl(url)
        val last = parsed.pathSegments.lastOrNull() ?: return null
        val replacement = variants[last] ?: return null
        return parsed.newBuilder().setPathSegment(parsed.pathSegments.lastIndex, if (start > now - 600) replacement.second else replacement.first).build().toString()
    }
}

internal fun inferMimeType(url: String): String? = when (httpUrl(url).encodedPath.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "m3u8" -> "application/x-mpegURL"
    "mpd" -> "application/dash+xml"
    "mp4", "m4v" -> "video/mp4"
    "ts" -> "video/mp2t"
    else -> null
}
