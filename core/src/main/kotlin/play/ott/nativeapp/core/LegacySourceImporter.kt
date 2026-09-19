package play.ott.nativeapp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LegacyImportResult(val sources: List<SourceConfig>, val notes: List<String>, val messages: List<CoreMessage> = emptyList())

/**
 * Imports provider configuration from an explicitly selected legacy storage dump.
 * The ordinary FOSS settings-v1 export does not include provider credentials.
 * This reader never executes JavaScript, decodes arbitrary compressed payloads, or fetches URLs.
 */
object LegacySourceImporter {
    fun parse(text: String, textResolver: CoreTextResolver = CoreTextResolver.ENGLISH): LegacyImportResult {
        if (text.length > 2 * 1024 * 1024) throw ProviderException("Legacy settings file exceeds 2 MB")
        val root = asObject(parseProviderJson(text)) ?: throw ProviderException("Legacy settings must be a JSON object")
        val containers = mutableListOf(root)
        listOf("localStorage", "storage", "values").forEach { key -> root[key]?.let(::asObject)?.let(containers::add) }
        fun find(vararg names: String): JsonObject? = containers.firstNotNullOfOrNull { container ->
            names.firstNotNullOfOrNull { name -> container[name]?.let(::asObject) }
        }
        val messages = mutableListOf<CoreMessage>()
        val sources = mutableListOf<SourceConfig>()
        val m3u = find("m3um3uArr", "m3uArr") ?: root.takeIf { it["M3Us"] is JsonArray }
        (m3u?.get("M3Us") as? JsonArray)?.objects()?.forEachIndexed { index, slot ->
            val url = slot.string("www").trim()
            if (url.isNotBlank()) {
                val parsed = url.toHttpUrlOrNull()
                if (parsed == null) messages += CoreMessage(CoreMessageKey.LEGACY_PLAYLIST_UNSUPPORTED, listOf((index + 1).toString()))
                else sources += SourceConfig(stableId("legacy", "m3u", parsed.toString()),
                    slot.string("name").ifBlank { textResolver.resolve(CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf((index + 1).toString()))) }, SourceKind.M3U, parsed.toString(),
                    catchupDaysFallback = (slot.string("rechours").toDoubleOrNull() ?: 0.0)
                        .takeIf { it.isFinite() && it in 0.0..87_600.0 }?.div(24) ?: 0.0,
                    nameMessage = if (slot.string("name").isBlank()) CoreMessage(CoreMessageKey.IMPORTED_PLAYLIST, listOf((index + 1).toString())) else null)
            }
            if (slot.string("medUrl").isNotBlank()) messages += CoreMessage(CoreMessageKey.LEGACY_LIBRARY_SKIPPED, listOf((index + 1).toString()))
        }
        val xtream = find("xtreamxtream_data", "xtream_data")
        if (xtream != null) {
            val url = xtream.string("server").trim().toHttpUrlOrNull()
            val username = xtream.string("username")
            val password = xtream.string("password")
            if (url != null && username.isNotBlank() && password.isNotBlank()) {
                sources += SourceConfig(stableId("legacy", "xtream", url.toString(), username), textResolver.resolve(CoreMessage(CoreMessageKey.IMPORTED_XTREAM)), SourceKind.XTREAM,
                    url.toString(), username, password, nameMessage = CoreMessage(CoreMessageKey.IMPORTED_XTREAM))
            } else messages += CoreMessage(CoreMessageKey.LEGACY_XTREAM_INVALID)
        }
        val stalker = find("stalkerstalker_data", "stalker_data")
        if (stalker != null) {
            val portal = stalker.string("portal").trim().toHttpUrlOrNull()
            val mac = stalker.string("mac").trim()
            if (portal != null && mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) {
                // The FOSS provider is JSON-RPC, not the MAG load.php protocol. Preserve that choice.
                val url = if (portal.encodedPath.trimEnd('/').endsWith("/api")) portal.toString()
                    else portal.toString().trimEnd('/') + "/stalker_portal/api/"
                sources += SourceConfig(stableId("legacy", "stalker", portal.toString(), mac.lowercase()), textResolver.resolve(CoreMessage(CoreMessageKey.IMPORTED_STALKER)),
                    SourceKind.STALKER, url, mac = mac, nameMessage = CoreMessage(CoreMessageKey.IMPORTED_STALKER))
            } else messages += CoreMessage(CoreMessageKey.LEGACY_STALKER_INVALID)
        }
        if (sources.isEmpty()) messages += if (root["settings"] is JsonObject && root.string("version") == "1")
            CoreMessage(CoreMessageKey.LEGACY_BACKUP_WITHOUT_SOURCES)
        else CoreMessage(CoreMessageKey.LEGACY_NO_SOURCES)
        if (root["favoritesArray"] != null || root["parentalArray"] != null)
            messages += CoreMessage(CoreMessageKey.LEGACY_FAVORITES_SKIPPED)
        return LegacyImportResult(sources.distinctBy { it.id }, messages.distinct().map(textResolver::resolve), messages.distinct())
    }

    private fun asObject(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString) runCatching { parseProviderJson(element.content) as? JsonObject }.getOrNull() else null
        else -> null
    }
}
