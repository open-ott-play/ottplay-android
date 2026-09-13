package play.ott.nativeapp.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class LegacyImportResult(val sources: List<SourceConfig>, val notes: List<String>)

/**
 * Imports provider configuration from an explicitly selected legacy storage dump.
 * The ordinary FOSS settings-v1 export does not include provider credentials.
 * This reader never executes JavaScript, decodes arbitrary compressed payloads, or fetches URLs.
 */
object LegacySourceImporter {
    fun parse(text: String): LegacyImportResult {
        if (text.length > 2 * 1024 * 1024) throw ProviderException("Legacy settings file exceeds 2 MB")
        val root = asObject(parseProviderJson(text)) ?: throw ProviderException("Legacy settings must be a JSON object")
        val containers = mutableListOf(root)
        listOf("localStorage", "storage", "values").forEach { key -> root[key]?.let(::asObject)?.let(containers::add) }
        fun find(vararg names: String): JsonObject? = containers.firstNotNullOfOrNull { container ->
            names.firstNotNullOfOrNull { name -> container[name]?.let(::asObject) }
        }
        val notes = mutableListOf<String>()
        val sources = mutableListOf<SourceConfig>()
        val m3u = find("m3um3uArr", "m3uArr") ?: root.takeIf { it["M3Us"] is JsonArray }
        (m3u?.get("M3Us") as? JsonArray)?.objects()?.forEachIndexed { index, slot ->
            val url = slot.string("www").trim()
            if (url.isNotBlank()) {
                val parsed = url.toHttpUrlOrNull()
                if (parsed == null) notes += "Плейлист ${index + 1}: локальный файл или неподдерживаемый адрес. Импортируйте исходный M3U-файл отдельно."
                else sources += SourceConfig(stableId("legacy", "m3u", parsed.toString()),
                    slot.string("name").ifBlank { "Импортированный плейлист ${index + 1}" }, SourceKind.M3U, parsed.toString())
            }
            if ((slot.string("rechours").toDoubleOrNull() ?: 0.0) > 0)
                notes += "Плейлист ${index + 1}: часы архива из старых настроек не перенесены. Новое приложение читает глубину архива из catchup-тегов плейлиста."
            if (slot.string("medUrl").isNotBlank()) notes += "Плейлист ${index + 1}: отдельная медиатека старого формата не импортирована как M3U."
        }
        val xtream = find("xtreamxtream_data", "xtream_data")
        if (xtream != null) {
            val url = xtream.string("server").trim().toHttpUrlOrNull()
            val username = xtream.string("username")
            val password = xtream.string("password")
            if (url != null && username.isNotBlank() && password.isNotBlank()) {
                sources += SourceConfig(stableId("legacy", "xtream", url.toString(), username), "Импортированный Xtream", SourceKind.XTREAM,
                    url.toString(), username, password)
            } else notes += "В старых настройках Xtream отсутствуют логин или пароль либо указан некорректный адрес сервера."
        }
        val stalker = find("stalkerstalker_data", "stalker_data")
        if (stalker != null) {
            val portal = stalker.string("portal").trim().toHttpUrlOrNull()
            val mac = stalker.string("mac").trim()
            if (portal != null && mac.matches(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}"))) {
                // The FOSS provider is JSON-RPC, not the MAG load.php protocol. Preserve that choice.
                val url = if (portal.encodedPath.trimEnd('/').endsWith("/api")) portal.toString()
                    else portal.toString().trimEnd('/') + "/stalker_portal/api/"
                sources += SourceConfig(stableId("legacy", "stalker", portal.toString(), mac.lowercase()), "Импортированный Stalker",
                    SourceKind.STALKER, url, mac = mac)
            } else notes += "В старых настройках Stalker указан некорректный адрес портала или MAC-адрес."
        }
        if (sources.isEmpty()) notes += if (root["settings"] is JsonObject && root.string("version") == "1")
            "Резервная копия FOSS settings-v1 содержит параметры и старые ID каналов, но не адреса и учётные данные провайдеров. Добавьте источник или импортируйте исходный M3U-файл."
        else "В файле не найдены поддерживаемые настройки источников M3U, Xtream или Stalker."
        if (root["favoritesArray"] != null || root["parentalArray"] != null)
            notes += "Старые ID избранных и заблокированных каналов несовместимы с новым каталогом. Выберите эти каналы заново после импорта источников."
        return LegacyImportResult(sources.distinctBy { it.id }, notes.distinct())
    }

    private fun asObject(element: JsonElement): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString) runCatching { parseProviderJson(element.content) as? JsonObject }.getOrNull() else null
        else -> null
    }
}
