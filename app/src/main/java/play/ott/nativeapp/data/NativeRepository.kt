package play.ott.nativeapp.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.R
import play.ott.nativeapp.i18n.AppLanguages
import play.ott.nativeapp.AppTransportPolicy
import play.ott.nativeapp.core.*
import play.ott.core.NativeGuideRefresh
import play.ott.core.NativeGuideRefreshAction
import play.ott.core.NativeGuideRefreshFormat
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ImportResult(val count: Int, val notes: List<String> = emptyList(), val messages: List<CoreMessage> = emptyList())

class NativeRepository(
    private val context: Context,
    private val transportPolicy: RemoteTransportPolicy = AppTransportPolicy.current,
) {
    private fun message(id: Int, vararg args: Any) = AppLanguages.localizedContext(context).getString(id, *args)

    fun displaySource(source: SourceConfig): SourceConfig =
        if (source.nameIsUserDefined) source
        else if (source.nameMessage != null) {
            val generated = requireNotNull(source.nameMessage)
            if (generated.isValidSourceName()) source.copy(name = generated.localized(context, source.name)) else source
        }
        else if (source.id == "local-demo" && source.url == DEMO_URL && source.name in setOf("Offline demo", "Тест без интернета"))
            source.copy(name = message(R.string.message_demo_source), nameMessage = CoreMessage(CoreMessageKey.DEMO_SOURCE)) else source

    fun displayEntry(entry: MediaEntry): MediaEntry =
        if (isBundledDemoEntry(entry, entry.sourceId) && entry.url == bundledDemoUrl)
            entry.copy(name = message(R.string.message_demo_name), group = message(R.string.message_demo_group),
                description = message(R.string.message_demo_description))
        else if (entry.nameMessage != null || entry.groupMessage != null) entry.copy(
            name = entry.nameMessage?.localized(context, entry.name) ?: entry.name,
            group = entry.groupMessage?.localized(context, entry.group) ?: entry.group)
        else entry

    fun catalogNotes(catalog: Catalog): List<String> =
        displayNotes(catalog.notes, catalog.messages)

    fun importNotes(result: ImportResult): List<String> =
        displayNotes(result.notes, result.messages)

    private fun displayNotes(notes: List<String>, messages: List<CoreMessage>): List<String> = when {
        messages.isEmpty() -> notes
        // Older or edited snapshots retain their original notes when metadata is unusable.
        messages.any { !it.hasValidArguments() } && notes.isNotEmpty() -> notes
        else -> messages.map { it.localized(context) }
    }

    private val vault = SourceVault(context)
    private val db = CatalogDatabase(context, vault)
    private val sourceMutex = Mutex()
    private val refreshMutex = Mutex()
    val preferences = PreferencesStore(context)
    val providers = ProviderRepository(transportPolicy = transportPolicy)
    private val localScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val localVideos = LocalVideoLibrary(context, localScope) { source, entries, reset, owner ->
        withContext(Dispatchers.IO) {
            sourceMutex.withLock {
                check(vault.read().firstOrNull { it.id == source.id } == source) { "Local source changed" }
                owner.persist { db.appendLocal(source.id, entries, reset) }
            }
        }
    }
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun sources(): List<SourceConfig> = withContext(Dispatchers.IO) { sourceMutex.withLock { vault.read() } }

    suspend fun save(source: SourceConfig) = withContext(Dispatchers.IO) {
        require(source.nameMessage?.isValidSourceName() != false) { "Invalid generated source name metadata" }
        // A user rename retires the generated label; their text must never be translated.
        val generatedName = source.nameMessage?.takeIf {
            !source.nameIsUserDefined && (source.name == it.english() || source.name == it.localized(context))
        }
        val saved = source.copy(name = generatedName?.english() ?: source.name, nameMessage = generatedName,
            nameIsUserDefined = source.nameIsUserDefined || (source.nameMessage != null && generatedName == null))
        validateSource(saved)
        refreshMutex.withLock { sourceMutex.withLock {
            val sources = vault.read()
            val previous = sources.firstOrNull { it.id == source.id }
            if (previous != null && previous != saved) {
                localVideos.forget(source.id)
                db.delete(source.id)
            }
            vault.write(sources.filterNot { it.id == source.id } + saved)
        }}
    }

    private fun validateSource(source: SourceConfig) {
        require(source.nameMessage?.isValidSourceName() != false) { "Invalid generated source name metadata" }
        require(source.id.isNotBlank() && source.name.isNotBlank()) { message(R.string.message_source_name_required) }
        require(source.id.length <= 160 && source.name.length <= 200) { message(R.string.message_source_name_too_long) }
        require(source.catchupDaysFallback.isFinite() && source.catchupDaysFallback in 0.0..3650.0) { "Invalid archive duration" }
        val scheme = source.url.toUri().scheme
        require(scheme in setOf("http", "https", "content") || source.url == DEMO_URL) { "Unsupported source address" }
        if (scheme == "http" || scheme == "https") transportPolicy.requireHttpUrl(source.url)
        if (source.kind == SourceKind.LOCAL) {
            val selected = if (source.localFolder) listOf(source.url) else source.localDocuments.ifEmpty { listOf(source.url) }
            require(scheme == "content" && selected.size in 1..128 && selected.distinct().size == selected.size)
            require(selected.all { it.length <= 8192 && it.toUri().scheme == "content" })
            require(!source.localFolder || android.provider.DocumentsContract.isTreeUri(source.url.toUri()))
            require(source.headers.isEmpty() && source.epgUrl.isBlank() && source.username.isBlank() && source.password.isBlank())
        } else {
            require(!source.localFolder && source.localDocuments.isEmpty())
            if (scheme == "content" || source.url == DEMO_URL) require(source.kind == SourceKind.M3U) { "Invalid source type" }
        }
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
        localVideos.forget(id)
        refreshMutex.withLock {
            sourceMutex.withLock {
                val old = vault.read()
                val removed = old.firstOrNull { it.id == id }
                val remaining = old.filterNot { it.id == id }
                vault.write(remaining)
                if (removed?.kind == SourceKind.LOCAL) {
                    val retained = remaining.flatMap { it.localDocuments + it.url }.toSet()
                    (removed.localDocuments + removed.url).distinct().filterNot { it in retained }.forEach {
                        try { context.contentResolver.releasePersistableUriPermission(it.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        catch (_: SecurityException) { }
                    }
                }
            }
            db.delete(id)
        }
    }

    suspend fun addLocal(uris: List<Uri>, folder: Boolean): SourceConfig = withContext(Dispatchers.IO) {
        require(uris.size in 1..128 && (!folder || uris.size == 1))
        val selected = uris.map { it.toString() }.distinct()
        require(selected.all { it.length <= 8192 && it.toUri().scheme == "content" })
        require(!folder || android.provider.DocumentsContract.isTreeUri(uris.first()))
        val before = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        try {
            selected.forEach { context.contentResolver.takePersistableUriPermission(it.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val key = (if (folder) "folder\u0000" else "files\u0000") + selected.sorted().joinToString("\u0000")
            val id = "local-" + UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8))
            val label = if (folder) android.provider.DocumentsContract.getTreeDocumentId(uris.first())
                .substringAfterLast('/').substringAfterLast(':').take(200).ifBlank { message(R.string.local_videos) }
                else message(R.string.local_videos)
            val source = SourceConfig(id, label, SourceKind.LOCAL, selected.first(), localFolder = folder,
                localDocuments = if (folder) emptyList() else selected)
            check(sources().let { values -> values.size < 100 || values.any { it.id == id } })
            save(source)
            source
        } catch (error: Exception) {
            // If stored ownership cannot be read, retaining a grant is safer
            // than withdrawing another source's access during error cleanup.
            val retained = runCatching { sources().flatMap { it.localDocuments + it.url }.toSet() }.getOrNull()
                ?: throw error
            selected.filter { it !in before && it !in retained }.forEach {
                try { context.contentResolver.releasePersistableUriPermission(it.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { }
            }
            throw error
        }
    }

    // Keep the R reference visible to release resource shrinking, while persisting its stable name.
    private val bundledDemoUrl: String
        get() = "android.resource://" + context.packageName + "/raw/" + context.resources.getResourceEntryName(R.raw.demo)

    private fun isBundledDemoEntry(entry: MediaEntry, sourceId: String): Boolean {
        if (entry.sourceId != sourceId || entry.id != "$sourceId:pattern" || entry.kind != MediaKind.MOVIE) return false
        val uri = entry.url.toUri()
        if (uri.scheme != "android.resource" || uri.authority != context.packageName || uri.query != null || uri.fragment != null) return false
        val path = uri.pathSegments
        // Older releases persisted numeric resource IDs, which change between APKs.
        return path == listOf("raw", "demo") || (path.size == 1 && path[0].toIntOrNull()?.ushr(24) == 0x7f)
    }

    private fun bundledDemoEntry(sourceId: String) = MediaEntry(
        id = "$sourceId:pattern", sourceId = sourceId, name = "Test pattern · 8 seconds",
        url = bundledDemoUrl, kind = MediaKind.MOVIE, group = "Local test",
        description = "A synthetic test video. Works offline; the audio track is silent."
    )

    suspend fun cached(id: String): Catalog = withContext(Dispatchers.IO) {
        val cached = db.read(id)
        if (cached.entries.none { isBundledDemoEntry(it, id) && it.url != bundledDemoUrl }) return@withContext cached
        refreshMutex.withLock {
            val source = sourceMutex.withLock { vault.read().firstOrNull { it.id == id } }
            // Re-read under the refresh lock so source edits or refreshes cannot be overwritten.
            val current = db.read(id)
            if (source?.kind != SourceKind.M3U || source.url != DEMO_URL) return@withLock current
            val restored = current.copy(entries = current.entries.map { entry ->
                if (isBundledDemoEntry(entry, id) && entry.url != bundledDemoUrl) bundledDemoEntry(id) else entry
            })
            if (restored != current) db.replace(id, restored)
            restored
        }
    }

    suspend fun refresh(source: SourceConfig): Catalog = withContext(Dispatchers.IO) {
        if (source.kind == SourceKind.LOCAL) {
            val result = localVideos.scan(source, true).first { !it.scanning }
            check(!result.failed && !result.cancelled) { "Local source unavailable" }
            return@withContext Catalog(result.entries)
        }
        refreshMutex.withLock {
            val catalog = when {
                source.url == DEMO_URL -> Catalog(listOf(bundledDemoEntry(source.id)))
                source.url.startsWith("content://") -> {
                    val text = context.contentResolver.openInputStream(source.url.toUri())?.use {
                        val bytes = readBounded(it, 32 * 1024 * 1024)
                        require(bytes.size <= 32 * 1024 * 1024) { message(R.string.message_playlist_too_large) }
                        bytes.toString(Charsets.UTF_8)
                    } ?: error(message(R.string.message_file_reselect))
                    M3uParser.parse(text, source)
                }
                else -> providers.load(source)
            }
            // Editing/removing a source while a refresh is in flight must not resurrect stale data.
            val current = sourceMutex.withLock { vault.read().firstOrNull { it.id == source.id } }
            if (current != source) error(message(R.string.message_source_changed))
            db.replace(source.id, catalog)
            catalog
        }
    }

    suspend fun refreshEpg(source: SourceConfig, catalog: Catalog) = withContext(Dispatchers.IO) {
        val urls = providers.epgSources(source, catalog)
        val refresh = NativeGuideRefresh(urls.size, NativeGuideRefreshFormat.ANDROID)
        val programmes = mutableListOf<Programme>()
        var failure: Throwable? = null
        while (true) when (refresh.action()) {
            NativeGuideRefreshAction.FETCH -> {
                val url = urls[refresh.index()]
                try {
                    programmes += providers.loadEpg(url, epgHeaders(source, url))
                } catch (error: Throwable) {
                    failure = error
                }
                refresh.advance(failure == null)
            }
            NativeGuideRefreshAction.VALIDATE_SOURCE -> refreshMutex.withLock {
                // Keep equality and the transaction under the same lock as source edits.
                refresh.advance(sources().firstOrNull { it.id == source.id } == source)
                if (refresh.action() == NativeGuideRefreshAction.WRITE_DATABASE) {
                    try {
                        db.replaceEpg(source.id, programmes)
                    } catch (error: Throwable) {
                        failure = error
                    }
                    refresh.advance(failure == null)
                }
            }
            NativeGuideRefreshAction.REPLACE, NativeGuideRefreshAction.SKIP -> return@withContext
            NativeGuideRefreshAction.FAIL -> throw checkNotNull(failure)
            else -> error("Unexpected Android guide refresh action: ${refresh.action()}")
        }
    }

    suspend fun programmes(entry: MediaEntry): List<Programme> = withContext(Dispatchers.IO) { db.programmes(entry) }

    suspend fun stream(source: SourceConfig, entry: MediaEntry): PlaybackStream =
        if (source.kind == SourceKind.LOCAL) {
            check(sources().firstOrNull { it.id == source.id } == source)
            check(localVideos.validate(source, entry)) { "Local video unavailable" }
            check(sources().firstOrNull { it.id == source.id } == source)
            PlaybackStream(entry.url, mimeType = entry.mimeType)
        } else if (source.url == DEMO_URL) PlaybackStream(
            if (source.kind == SourceKind.M3U && isBundledDemoEntry(entry, source.id)) bundledDemoUrl else entry.url
        ) else providers.resolve(source, entry)

    suspend fun exportSettings(): String = json.encodeToString(SettingsBackup(
        sources = sources(), preferences = preferences.data.first()
    ))

    suspend fun importSettings(text: String): ImportResult {
        require(text.length < 4 * 1024 * 1024) { message(R.string.message_settings_too_large) }
        val document = json.parseToJsonElement(text)
        val legacy = if (document is kotlinx.serialization.json.JsonObject && "sources" !in document) LegacySourceImporter.parse(text, CoreTextResolver { it.localized(context) }) else null
        val backup = if (legacy != null) SettingsBackup(sources = legacy.sources) else json.decodeFromString<SettingsBackup>(text)
        require(backup.version == 1) { message(R.string.message_settings_version_unknown) }
        require(backup.sources.size <= 100) { message(R.string.message_too_many_sources) }
        require(backup.sources.map { it.id }.distinct().size == backup.sources.size) { message(R.string.message_duplicate_source_ids) }
        backup.sources.forEach(::validateSource)
        if (legacy == null) validateBackupPreferences(backup.preferences)
        // A persisted SAF permission belongs to one installation. Skip only these valid sources;
        // a mixed backup must still restore its network accounts and playback positions.
        val fileSources = backup.sources.filter { it.url.toUri().scheme == "content" }
        val importedSources = backup.sources - fileSources.toSet()
        val messages = legacy?.messages.orEmpty() + fileSources.map {
            CoreMessage(CoreMessageKey.LOCAL_PLAYLIST_RESELECT, listOf(it.name))
        }
        // Validate the complete import before writing any credentials.
        val sourceIds = withContext(Dispatchers.IO) { refreshMutex.withLock { sourceMutex.withLock {
            val merged = vault.read().associateBy { it.id }.toMutableMap()
            require((merged.keys + importedSources.map { it.id }).size <= 100) { message(R.string.message_too_many_sources) }
            importedSources.forEach { source ->
                if (merged[source.id] != source) db.delete(source.id)
                merged[source.id] = source
            }
            if (importedSources.isNotEmpty()) vault.write(merged.values.toList())
            merged.keys.toSet()
        }}}
        if (legacy == null) preferences.update { old -> mergeBackupPreferences(old, backup.preferences, sourceIds) }
        return ImportResult(importedSources.size, messages.map { it.localized(context) }, messages)
    }

    companion object {
        const val DEMO_URL = "demo://local"
        fun demoSource() = SourceConfig("local-demo", "Offline demo", SourceKind.M3U, DEMO_URL, nameMessage = CoreMessage(CoreMessageKey.DEMO_SOURCE))
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
