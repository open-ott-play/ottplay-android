package play.ott.nativeapp.core

import java.nio.file.Files
import java.nio.file.Path
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Host-only targets: no ProviderHttp instance, sockets, Android or media player. */
object PlaylistFuzz {
    private val source = SourceConfig("fuzz", "Fuzz", SourceKind.M3U, "https://example.invalid/list.m3u")

    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        if (data.size > 16384) return
        val text = data.toString(Charsets.UTF_8)
        val catalog = try { M3uParser.parse(text, source) }
            catch (_: ProviderException) { return }
            catch (error: IllegalArgumentException) {
                // Only the documented header validation failures are expected.
                if (error.message !in setOf("Invalid HTTP header name", "Invalid HTTP header value", "Unsupported HTTP header")) throw error
                return
            }
        check(catalog == M3uParser.parse(text, source)) { "Parsing must be deterministic" }
        check(catalog.entries.map { it.id }.distinct().size == catalog.entries.size)
        catalog.entries.forEach { entry ->
            check(entry.id.isNotBlank() && entry.sourceId == source.id)
            check(entry.url.toHttpUrlOrNull() != null) { "Only HTTP(S) entries are playable" }
            check(mergedHeaders(entry.headers) == entry.headers)
            entry.drm?.let { check(DrmPolicy.validated(it) == it) }
            entry.headerOrigins.orEmpty().values.forEach { value ->
                val origin = checkNotNull(value.toHttpUrlOrNull())
                check(origin.username.isEmpty() && origin.password.isEmpty())
                check(origin.encodedPath == "/" && origin.query == null && origin.fragment == null)
            }
        }
    }
}

object XmltvFuzz {
    @JvmStatic
    fun fuzzerTestOneInput(data: ByteArray) {
        if (data.size > 16384) return
        val rows = try { XmltvParser.parse(data) } catch (_: ProviderException) { return }
        check(rows == XmltvParser.parse(data)) { "Parsing must be deterministic" }
        rows.forEach {
            check(it.channelId.isNotBlank())
            check(it.endMillis > it.startMillis)
        }
    }
}

/** Fixed corpus replay for normal CI. It never starts a mutation engine. */
object ReplaySeeds {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.single())
        for ((directory, target) in listOf(
            "playlist" to PlaylistFuzz::fuzzerTestOneInput,
            "xmltv" to XmltvFuzz::fuzzerTestOneInput,
        )) {
            Files.list(root.resolve(directory)).use { paths ->
                val seeds = paths.sorted().toList()
                check(seeds.size >= 4)
                seeds.forEach { path ->
                    check(Files.isRegularFile(path) && !Files.isSymbolicLink(path))
                    check(Files.size(path) <= 16384)
                    target(Files.readAllBytes(path))
                }
            }
        }
        // Positive controls prevent an accidentally disabled parser from passing every seed.
        val source = SourceConfig("fuzz", "Fuzz", SourceKind.M3U, "https://example.invalid/list.m3u")
        check(M3uParser.parse("#EXTM3U\n#EXTINF:-1,Sample\nhttps://example.invalid/live\n", source).entries.size == 1)
        val xml = "<tv><programme channel=\"one\" start=\"20261021120000 +0000\" stop=\"20261021130000 +0000\"><title>Sample</title></programme></tv>"
        check(XmltvParser.parse(xml.toByteArray()).size == 1)
        val forbidden = "<!DOCTYPE tv [<!ENTITY x SYSTEM 'https://example.invalid/entity'>]><tv>&x;</tv>"
        try { XmltvParser.parse(forbidden.toByteArray()); error("DOCTYPE must be rejected") }
        catch (_: ProviderException) { /* Expected closed boundary. */ }
        println("Fixed parser seeds and positive/DOCTYPE controls passed")
    }
}
