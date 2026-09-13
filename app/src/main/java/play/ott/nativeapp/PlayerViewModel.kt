package play.ott.nativeapp

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import play.ott.nativeapp.core.*
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.playback.PlaybackItems
import play.ott.nativeapp.ui.AppAction
import play.ott.nativeapp.ui.AppUiState

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as OttplayApplication).repository
    private val mutableState = MutableStateFlow(AppUiState(isBusy = true, loadingMessage = "Загрузка библиотеки"))
    val state: StateFlow<AppUiState> = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var playJob: Job? = null
    private var recoveryJob: Job? = null
    private var playGeneration = 0L
    private var resolvingSourceId: String? = null
    private var loadGeneration = 0L
    private var pending: PendingPlay? = null
    private data class PendingPlay(val entry: MediaEntry, val stream: PlaybackStream, val positionMs: Long)
    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            mutableState.update { it.copy(error = "Ошибка воспроизведения (" + error.errorCodeName + "). Попробуйте другой поток или повторите подключение.") }
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            controller?.let { restorePlaybackEntry(it, item) }
        }
    }
    init {
        viewModelScope.launch {
            repository.preferences.data.collect { preferences ->
                mutableState.update { it.copy(favoriteIds = preferences.favorites, backgroundPlayback = preferences.backgroundPlayback) }
            }
        }
        viewModelScope.launch {
            try {
                val sources = repository.sources()
                val selected = repository.preferences.data.first().selectedSourceId
                    ?.takeIf { id -> sources.any { it.id == id } } ?: sources.firstOrNull()?.id
                mutableState.update { it.copy(sources = sources, selectedSourceId = selected, isBusy = false) }
                if (selected != null) load(selected, refresh = false)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutableState.update { it.copy(isBusy = false, error = "Не удалось прочитать сохранённые источники. Данные не удалены.") }
            }
        }
    }

    fun attachController(value: MediaController) {
        controller?.removeListener(listener)
        controller = value
        value.addListener(listener)
        pending?.let {
            pending = null
            try { begin(it) } catch (error: Exception) { showError(error) }
        }
        restorePlaybackEntry(value, value.currentMediaItem)
    }

    fun detachController(value: MediaController?) {
        if (controller === value) { recoveryJob?.cancel(); value?.removeListener(listener); controller = null }
    }

    /** The player may belong to a different source than the catalogue currently being browsed. */
    private fun restorePlaybackEntry(value: MediaController, item: MediaItem?) {
        recoveryJob?.cancel()
        if (item == null) {
            mutableState.update { it.copy(playingEntry = null) }
            return
        }
        if (state.value.playingEntry?.id == item.mediaId) return
        val generation = playGeneration
        recoveryJob = viewModelScope.launch {
            try {
                val sources = repository.sources()
                val sourceIds = sources.mapTo(hashSetOf()) { it.id }
                var entry = state.value.entries.firstOrNull { it.id == item.mediaId && it.sourceId in sourceIds }
                if (entry == null) {
                    // Episodes and archive items are not in the top-level cached catalogue. Keep
                    // their descriptor in private request metadata, alongside the existing URL.
                    entry = item.requestMetadata.extras?.getString(EXTRA_ENTRY)?.let { encoded ->
                        runCatching { entryJson.decodeFromString<MediaEntry>(encoded) }.getOrNull()
                    }?.takeIf { it.id == item.mediaId && it.sourceId in sourceIds }
                }
                if (entry == null) {
                    for (source in sources) {
                        val cached = try { repository.cached(source.id).entries }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { continue } // An unrelated broken cache cannot hide an active player.
                        ensureActive()
                        entry = cached.firstOrNull { it.id == item.mediaId && it.sourceId == source.id }
                        if (entry != null) break
                    }
                }
                val recoveredSourceId = entry?.sourceId
                if (recoveredSourceId != null && repository.sources().none { it.id == recoveredSourceId }) entry = null
                ensureActive()
                if (controller !== value || value.currentMediaItem?.mediaId != item.mediaId || generation != playGeneration) return@launch
                mutableState.update { it.copy(playingEntry = entry) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (controller === value && value.currentMediaItem?.mediaId == item.mediaId) {
                    mutableState.update { it.copy(notice = "Не удалось восстановить описание текущего видео. Воспроизведение продолжается.") }
                }
            }
        }
    }

    fun onPlaybackSessionError() { mutableState.update { it.copy(error = "Не удалось переключить канал. Обновите источник и повторите попытку.") } }

    fun onControllerFailure() { mutableState.update { it.copy(isBusy = false, error = "Не удалось подключиться к службе воспроизведения") } }

    fun dispatch(action: AppAction) {
        when (action) {
            is AppAction.SelectSource -> load(action.sourceId, false)
            is AppAction.SaveSource -> operation {
                retirePendingSource(action.source.id)
                loadJob?.cancelAndJoin()
                repository.save(action.source)
                val sources = repository.sources()
                mutableState.update { it.copy(sources = sources) }
                load(action.source.id, true)
            }
            is AppAction.DeleteSource -> operation {
                retirePendingSource(action.sourceId)
                if (state.value.playingEntry?.sourceId == action.sourceId) stop()
                loadJob?.cancel()
                repository.remove(action.sourceId)
                val sources = repository.sources()
                val id = state.value.selectedSourceId?.takeUnless { it == action.sourceId } ?: sources.firstOrNull()?.id
                mutableState.update { it.copy(sources = sources, selectedSourceId = id, entries = emptyList(), isBusy = false) }
                if (id != null) load(id, false)
            }
            AppAction.Refresh -> state.value.selectedSourceId?.let { load(it, true) }
            is AppAction.ToggleFavorite -> operation {
                repository.preferences.update {
                    it.copy(favorites = if (action.entry.id in it.favorites) it.favorites - action.entry.id else it.favorites + action.entry.id)
                }
            }
            is AppAction.Play -> play(action.entry)
            is AppAction.OpenEpg -> {
                detailJob?.cancel()
                mutableState.update { it.copy(epgEntry = action.entry, programmes = emptyList()) }
                detailJob = operation {
                    val programmes = repository.programmes(action.entry)
                    mutableState.update { if (it.epgEntry?.id == action.entry.id) it.copy(programmes = programmes) else it }
                }
            }
            is AppAction.PlayProgramme -> {
                val stream = CatchupResolver.resolve(action.entry, action.programme)
                if (stream == null) mutableState.update { it.copy(error = "Архив для этой передачи недоступен у источника") }
                else play(action.entry.copy(id = action.entry.id + ":archive:" + action.programme.startMillis,
                    name = action.programme.title, kind = MediaKind.MOVIE), stream)
            }
            is AppAction.OpenSeries -> {
                detailJob?.cancel()
                mutableState.update { it.copy(seriesEntry = action.entry, episodes = emptyList()) }
                detailJob = operation {
                    val source = repository.sources().first { it.id == action.entry.sourceId }
                    val episodes = repository.providers.loadEpisodes(source, action.entry)
                    mutableState.update { if (it.seriesEntry?.id == action.entry.id) it.copy(episodes = episodes) else it }
                }
            }
            AppAction.CloseEpg -> { detailJob?.cancel(); mutableState.update { it.copy(epgEntry = null, programmes = emptyList()) } }
            AppAction.CloseSeries -> { detailJob?.cancel(); mutableState.update { it.copy(seriesEntry = null, episodes = emptyList()) } }
            AppAction.DismissError -> mutableState.update { it.copy(error = null) }
            AppAction.DismissNotice -> mutableState.update { it.copy(notice = null) }
            AppAction.StopPlayback -> stop()
            is AppAction.SetBackgroundPlayback -> operation { repository.preferences.update { it.copy(backgroundPlayback = action.enabled) } }
            AppAction.AddDemo -> operation {
                val source = NativeRepository.demoSource()
                repository.save(source)
                val sources = repository.sources()
                mutableState.update { it.copy(sources = sources) }
                load(source.id, true)
            }
            AppAction.ImportPlaylist, AppAction.ImportSettings, AppAction.ExportSettings -> Unit // System document launchers in Activity.
        }
    }

    private fun load(id: String, refresh: Boolean) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        detailJob?.cancel()
        mutableState.update { it.copy(selectedSourceId = id, entries = emptyList(), epgEntry = null,
            seriesEntry = null, isBusy = true, loadingMessage = "Загрузка каталога", error = null) }
        loadJob = viewModelScope.launch {
            try {
                repository.preferences.update { it.copy(selectedSourceId = id) }
                val source = repository.sources().first { it.id == id }
                val cached = try { repository.cached(id) } catch (e: Exception) {
                    if (e is CancellationException || !refresh) throw e
                    Catalog(emptyList())
                }
                if (state.value.selectedSourceId != id) return@launch
                mutableState.update { it.copy(entries = cached.entries, notice = cached.notes.takeIf { notes -> notes.isNotEmpty() }?.joinToString("\n") ?: it.notice) }
                val catalog = if (refresh || cached.entries.isEmpty()) repository.refresh(source) else cached
                ensureActive()
                if (state.value.selectedSourceId != id) return@launch
                mutableState.update { it.copy(entries = catalog.entries, isBusy = false, notice = catalog.notes.takeIf { notes -> notes.isNotEmpty() }?.joinToString("\n") ?: it.notice) }
                if (refresh || cached.entries.isEmpty()) {
                    try {
                        repository.refreshEpg(source, catalog)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { mutableState.update {
                        val epgNotice = "Каталог обновлён. EPG сейчас недоступна; сохранён предыдущий кэш."
                        it.copy(notice = listOfNotNull(it.notice, epgNotice).distinct().joinToString("\n"))
                    } }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
            finally { if (generation == loadGeneration && state.value.selectedSourceId == id) mutableState.update { it.copy(isBusy = false) } }
        }
    }

    private fun play(entry: MediaEntry, resolved: PlaybackStream? = null) {
        if (entry.playbackUnsupportedReason != null) {
            mutableState.update { it.copy(error = "Настройка DRM или формата этой записи не поддерживается. Текущее воспроизведение продолжается.") }
            return
        }
        if (entry.kind == MediaKind.SERIES) { dispatch(AppAction.OpenSeries(entry)); return }
        playJob?.cancel()
        pending = null
        resolvingSourceId = entry.sourceId
        val generation = ++playGeneration
        playJob = operation {
            val source = repository.sources().first { it.id == entry.sourceId }
            val stream = resolved ?: repository.stream(source, entry)
            val position = if (entry.kind == MediaKind.LIVE) 0L else repository.preferences.data.first().resumePositions[entry.id] ?: 0L
            ensureActive()
            if (generation != playGeneration) return@operation
            if (repository.sources().firstOrNull { it.id == source.id } != source) error("Source changed during playback request")
            val request = PendingPlay(entry, stream, position)
            if (controller == null) {
                pending = request
                mutableState.update { it.copy(playingEntry = entry, notice = "Подключение к службе воспроизведения") }
            } else begin(request)
        }
    }

    private fun begin(request: PendingPlay) {
        val player = controller ?: return
        resolvingSourceId = null
        val item = PlaybackItems.build(
            id = request.entry.id, title = request.entry.name, url = request.stream.url,
            headers = request.stream.headers, artwork = request.entry.logo.takeIf { it.isNotBlank() },
            isLive = request.entry.kind == MediaKind.LIVE, startPositionMs = request.positionMs,
            drm = request.entry.drm, mimeType = request.entry.mimeType ?: request.stream.mimeType,
            unsupportedReason = request.entry.playbackUnsupportedReason,
        ).let { built ->
            val extras = Bundle(built.requestMetadata.extras ?: Bundle()).apply {
                putString(EXTRA_ENTRY, entryJson.encodeToString(request.entry))
            }
            built.buildUpon()
                .setRequestMetadata(built.requestMetadata.buildUpon().setExtras(extras).build())
                .build()
        }
        PlaybackItems.requireSupported(getApplication(), item)
        player.setMediaItem(item, PlaybackItems.startPositionMs(item))
        player.prepare()
        player.play()
        mutableState.update { it.copy(playingEntry = request.entry, error = null, notice = null) }
    }

    private fun retirePendingSource(sourceId: String) {
        if (resolvingSourceId == sourceId || pending?.entry?.sourceId == sourceId) {
            playJob?.cancel(); ++playGeneration; pending = null; resolvingSourceId = null
        }
    }

    private fun stop() {
        playJob?.cancel(); ++playGeneration; pending = null; resolvingSourceId = null
        controller?.stop(); controller?.clearMediaItems()
        mutableState.update { it.copy(playingEntry = null) }
    }

    fun importPlaylist(uri: Uri, name: String) = operation {
        val source = NativeRepository.fileSource(uri.toString(), name)
        repository.save(source)
        val sources = repository.sources()
        mutableState.update { it.copy(sources = sources) }
        load(source.id, true)
    }

    fun importSettings(uri: Uri) = operation {
        val text = withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { play.ott.nativeapp.data.readBounded(it, 4 * 1024 * 1024).decodeToString() }
                ?: error("Файл недоступен")
        }
        // Import may replace accounts. A detached controller must not later start a URL resolved
        // with the previous credentials; currently playing media remains owned by the service.
        playJob?.cancelAndJoin()
        ++playGeneration
        pending = null
        resolvingSourceId = null
        loadJob?.cancelAndJoin()
        val result = repository.importSettings(text)
        val sources = repository.sources()
        mutableState.update { it.copy(sources = sources, notice = "Импортировано источников: " + result.count + if (result.notes.isEmpty()) "" else ". " + result.notes.joinToString(" ")) }
        val selected = repository.preferences.data.first().selectedSourceId
        (sources.firstOrNull { it.id == selected } ?: sources.firstOrNull())?.let { load(it.id, false) }
    }

    fun exportSettings(uri: Uri) = operation {
        val text = repository.exportSettings()
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: error("Не удалось записать файл")
        }
        mutableState.update { it.copy(notice = "Настройки экспортированы. Файл содержит адреса и учётные данные источников.") }
    }

    private fun operation(block: suspend CoroutineScope.() -> Unit): Job = viewModelScope.launch {
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { showError(e) }
    }

    private fun showError(error: Exception) {
        // Network/library exception messages may contain provider URLs and credentials.
        val message = when (error) {
            is java.net.UnknownHostException -> "Сервер не найден. Проверьте адрес и подключение."
            is java.net.SocketTimeoutException -> "Сервер не ответил вовремя. Попробуйте обновить источник."
            else -> "Не удалось выполнить операцию. Проверьте адрес, учётные данные и формат источника; сохранённые данные не удалены."
        }
        mutableState.update { it.copy(error = message, isBusy = false) }
    }

    private companion object {
        const val EXTRA_ENTRY = "play.ott.nativeapp.entry"
        val entryJson = Json { ignoreUnknownKeys = true }
    }
}
