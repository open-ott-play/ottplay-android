package play.ott.nativeapp.data

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.R
import play.ott.nativeapp.AppTransportPolicy
import play.ott.nativeapp.core.*
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ImportResult(val count: Int, val notes: List<String> = emptyList())

class NativeRepository(
    private val context: Context,
    private val transportPolicy: RemoteTransportPolicy = AppTransportPolicy.current,
) {
    private val vault = SourceVault(context)
    private val db = CatalogDatabase(context, vault)
    private val sourceMutex = Mutex()
    private val refreshMutex = Mutex()
    val preferences = PreferencesStore(context)
    val providers = ProviderRepository(transportPolicy = transportPolicy)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun sources(): List<SourceConfig> = withContext(Dispatchers.IO) { sourceMutex.withLock { vault.read() } }

    suspend fun save(source: SourceConfig) = withContext(Dispatchers.IO) {
        validateSource(source)
        refreshMutex.withLock { sourceMutex.withLock {
            val sources = vault.read()
            val previous = sources.firstOrNull { it.id == source.id }
            if (previous != null && previous != source) db.delete(source.id)
            vault.write(sources.filterNot { it.id == source.id } + source)
        }}
    }

    private fun validateSource(source: SourceConfig) {
        require(source.id.isNotBlank() && source.name.isNotBlank()) { "Укажите название источника" }
        require(source.id.length <= 160 && source.name.length <= 200) { "Слишком длинное название" }
        require(source.catchupDaysFallback.isFinite() && source.catchupDaysFallback in 0.0..3650.0) { "Invalid archive duration" }
        val scheme = source.url.toUri().scheme
        require(scheme in setOf("http", "https", "content") || source.url == DEMO_URL) { "Unsupported source address" }
        if (scheme == "http" || scheme == "https") transportPolicy.requireHttpUrl(source.url)
        if (scheme == "content" || source.url == DEMO_URL) require(source.kind == SourceKind.M3U) { "Invalid source type" }
        if (source.kind == SourceKind.XTREAM) require(source.username.isNotBlank() && source.password.isNotBlank()) { "Missing credentials" }
        if (source.kind == SourceKind.STALKER) require(Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(source.mac)) { "Invalid MAC address" }
        if (source.epgUrl.isNotBlank()) transportPolicy.requireHttpUrl(source.epgUrl)
        require(source.headers.size <= 64) { "Too many headers" }
        source.headers.forEach { (name, value) ->
            require(name.lowercase() !in setOf("host", "content-length", "connection", "transfer-encoding")) { "Unsupported header" }
            okhttp3.Headers.Builder().add(name, value)
        }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            sourceMutex.withLock { vault.write(vault.read().filterNot { it.id == id }) }
            db.delete(id)
        }
    }

    suspend fun cached(id: String): Catalog = withContext(Dispatchers.IO) { db.read(id) }

    suspend fun refresh(source: SourceConfig): Catalog = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val catalog = when {
                source.url == DEMO_URL -> Catalog(listOf(MediaEntry(
                    id = source.id + ":pattern", sourceId = source.id, name = "Тест изображения · 8 секунд",
                    url = "android.resource://" + context.packageName + "/" + R.raw.demo,
                    kind = MediaKind.MOVIE, group = "Локальный тест",
                    description = "Синтетический тестовый ролик. Работает без сети; звук — тишина."
                )))
                source.url.startsWith("content://") -> {
                    val text = context.contentResolver.openInputStream(source.url.toUri())?.use {
                        val bytes = readBounded(it, 32 * 1024 * 1024)
                        require(bytes.size <= 32 * 1024 * 1024) { "Плейлист слишком большой" }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error("Файл недоступен. Выберите его снова.")
                    M3uParser.parse(text, source)
                }
                else -> providers.load(source)
            }
            // Editing/removing a source while a refresh is in flight must not resurrect stale data.
            val current = sourceMutex.withLock { vault.read().firstOrNull { it.id == source.id } }
            if (current != source) error("Источник изменён. Повторите обновление.")
            db.replace(source.id, catalog)
            catalog
        }
    }

    suspend fun refreshEpg(source: SourceConfig, catalog: Catalog) = withContext(Dispatchers.IO) {
        val urls = (listOf(source.epgUrl) + catalog.epgUrls).filter { it.isNotBlank() }.distinct()
        if (urls.isEmpty()) return@withContext
        val programmes = urls.flatMap { providers.loadEpg(it, epgHeaders(source, it)) }
        refreshMutex.withLock {
            if (sources().firstOrNull { it.id == source.id } == source) db.replaceEpg(source.id, programmes)
        }
    }

    suspend fun programmes(entry: MediaEntry): List<Programme> = withContext(Dispatchers.IO) { db.programmes(entry) }

    suspend fun stream(source: SourceConfig, entry: MediaEntry): PlaybackStream =
        if (source.url == DEMO_URL) PlaybackStream(entry.url) else providers.resolve(source, entry)

    suspend fun exportSettings(): String = json.encodeToString(SettingsBackup(
        sources = sources(), preferences = preferences.data.first()
    ))

    suspend fun importSettings(text: String): ImportResult {
        require(text.length < 4 * 1024 * 1024) { "Файл настроек слишком большой" }
        val document = json.parseToJsonElement(text)
        val legacy = if (document is kotlinx.serialization.json.JsonObject && "sources" !in document) LegacySourceImporter.parse(text) else null
        val backup = if (legacy != null) SettingsBackup(sources = legacy.sources) else json.decodeFromString<SettingsBackup>(text)
        require(backup.version == 1) { "Неизвестная версия настроек" }
        require(backup.sources.size <= 100) { "Слишком много источников" }
        require(backup.sources.map { it.id }.distinct().size == backup.sources.size) { "Повторяются идентификаторы источников" }
        backup.sources.forEach(::validateSource)
        if (legacy == null) validateBackupPreferences(backup.preferences)
        // A persisted SAF permission belongs to one installation. Skip only these valid sources;
        // a mixed backup must still restore its network accounts and playback positions.
        val fileSources = backup.sources.filter { it.url.toUri().scheme == "content" }
        val importedSources = backup.sources - fileSources.toSet()
        val notes = legacy?.notes.orEmpty() + fileSources.map {
            "Локальный плейлист «${it.name}» нужно выбрать заново через системный диалог."
        }
        // Validate the complete import before writing any credentials.
        val sourceIds = withContext(Dispatchers.IO) { refreshMutex.withLock { sourceMutex.withLock {
            val merged = vault.read().associateBy { it.id }.toMutableMap()
            require((merged.keys + importedSources.map { it.id }).size <= 100) { "Слишком много источников" }
            importedSources.forEach { source ->
                if (merged[source.id] != source) db.delete(source.id)
                merged[source.id] = source
            }
            if (importedSources.isNotEmpty()) vault.write(merged.values.toList())
            merged.keys.toSet()
        }}}
        if (legacy == null) preferences.update { old -> mergeBackupPreferences(old, backup.preferences, sourceIds) }
        return ImportResult(importedSources.size, notes)
    }

    companion object {
        const val DEMO_URL = "demo://local"
        fun demoSource() = SourceConfig("local-demo", "Тест без интернета", SourceKind.M3U, DEMO_URL)
        fun fileSource(uri: String, name: String) = SourceConfig(UUID.randomUUID().toString(), name, SourceKind.M3U, uri)
    }
}


internal fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "File exceeds size limit" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

/** Advertised EPG URLs may belong to a different company; do not forward source credentials. */
internal fun epgHeaders(source: SourceConfig, epgUrl: String): Map<String, String> {
    val origin = source.url.toHttpUrlOrNull()
    val guide = epgUrl.toHttpUrlOrNull()
    val sameOrigin = origin != null && guide != null && origin.scheme == guide.scheme && origin.host == guide.host && origin.port == guide.port
    return if (sameOrigin) source.headers else source.headers.filterKeys {
        it.lowercase() in setOf("user-agent", "accept", "accept-language")
    }
}
