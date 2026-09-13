package play.ott.nativeapp.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import coil.compose.AsyncImage
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import kotlinx.coroutines.launch

private enum class LibraryTab(val title: String, val heading: String) {
    LIVE("Эфир", "Телеканалы"), MOVIES("Фильмы", "Кино на ваш вечер"),
    SERIES("Сериалы", "Следующая история"), FAVORITES("Избранное", "Всегда под рукой"),
}

@Composable
fun OttNativeApp(
    state: AppUiState,
    onAction: (AppAction) -> Unit,
    controller: MediaController?,
    onPictureInPicture: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    inPictureInPicture: Boolean = false,
) {
    val configuration = LocalConfiguration.current
    val isTv = configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    var tab by rememberSaveable { mutableStateOf(LibraryTab.LIVE) }
    var search by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable(state.selectedSourceId, tab) { mutableStateOf("") }
    var showSources by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var sourceEditorOpen by rememberSaveable { mutableStateOf(false) }
    var sourceEditorId by rememberSaveable { mutableStateOf<String?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var autoOpenedEntry by rememberSaveable { mutableStateOf<String?>(null) }
    var initializedSource by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val selectedSource = state.sources.firstOrNull { it.id == state.selectedSourceId }
    val tabEntries = remember(state.entries, state.favoriteIds, tab) {
        state.entries.filter { entry ->
            when (tab) {
                LibraryTab.LIVE -> entry.kind == MediaKind.LIVE
                LibraryTab.MOVIES -> entry.kind == MediaKind.MOVIE
                LibraryTab.SERIES -> entry.kind == MediaKind.SERIES
                LibraryTab.FAVORITES -> entry.id in state.favoriteIds
            }
        }
    }
    val groups = remember(tabEntries) { tabEntries.map { it.group }.filter { it.isNotBlank() }.distinct().sorted() }
    val visibleEntries = remember(tabEntries, search, group) {
        tabEntries.filter { (group.isBlank() || it.group == group) && (search.isBlank() || it.name.contains(search, ignoreCase = true) || it.group.contains(search, ignoreCase = true)) }
    }
    val railFocus = remember { FocusRequester() }
    val welcomeFocus = remember { FocusRequester() }
    val catalogFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val focusScope = rememberCoroutineScope()
    var lastFocusedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val focusEntryId = visibleEntries.firstOrNull { it.id == lastFocusedEntryId }?.id ?: visibleEntries.firstOrNull()?.id
    var libraryFocusSet by remember(isTv, fullscreen, state.sources.isEmpty()) { mutableStateOf(false) }
    LaunchedEffect(isTv, fullscreen, state.isBusy, state.sources.isEmpty()) {
        if (isTv && !fullscreen && !state.isBusy && !libraryFocusSet) {
            // Request only on entering the library, after layout and automatic tab selection.
            // Later state updates must not pull focus out of search or a dialog.
            withFrameNanos { }
            if (state.sources.isEmpty()) welcomeFocus.requestFocus()
            else if (lastFocusedEntryId != null && focusEntryId != null) {
                gridState.scrollToItem(visibleEntries.indexOfFirst { it.id == focusEntryId })
                withFrameNanos { }
                catalogFocus.requestFocus()
            } else railFocus.requestFocus()
            libraryFocusSet = true
        }
    }
    LaunchedEffect(state.selectedSourceId, state.entries) {
        if (initializedSource != state.selectedSourceId && state.entries.isNotEmpty()) {
            initializedSource = state.selectedSourceId
            tab = when {
                state.entries.any { it.kind == MediaKind.LIVE } -> LibraryTab.LIVE
                state.entries.any { it.kind == MediaKind.MOVIE } -> LibraryTab.MOVIES
                state.entries.any { it.kind == MediaKind.SERIES } -> LibraryTab.SERIES
                else -> LibraryTab.FAVORITES
            }
        }
    }
    LaunchedEffect(state.playingEntry?.id) {
        val id = state.playingEntry?.id
        if (id == null) { fullscreen = false; autoOpenedEntry = null }
        else if (id != autoOpenedEntry) { fullscreen = true; autoOpenedEntry = id }
    }
    LaunchedEffect(fullscreen) { onFullscreenChanged(fullscreen) }
    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it); onAction(AppAction.DismissNotice) }
    }
    BackHandler(enabled = state.playingEntry != null && state.epgEntry == null && state.seriesEntry == null && !showSources && !showSettings && !sourceEditorOpen) {
        if (fullscreen) fullscreen = false else onAction(AppAction.StopPlayback)
    }
    OttTheme {
        Surface(modifier = Modifier.fillMaxSize().then(if (inPictureInPicture) Modifier else Modifier.safeDrawingPadding()), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if ((fullscreen || inPictureInPicture) && state.playingEntry != null) {
                    NativePlayerPane(state.playingEntry, controller, true, { fullscreen = false }, onPictureInPicture,
                        { onAction(AppAction.StopPlayback) }, { onAction(AppAction.Play(state.playingEntry)) }, Modifier.fillMaxSize(), inPictureInPicture)
                } else {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val wide = isTv || maxWidth >= 840.dp
                        Row(Modifier.fillMaxSize().padding(if (isTv) 28.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (wide) {
                                Column(Modifier.width(188.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Brand()
                                    Spacer(Modifier.height(18.dp))
                                    LibraryTab.entries.forEach { option ->
                                        ActionButton(option.title, { tab = option }, Modifier.fillMaxWidth()
                                            .testTag("library-tab-${option.name}")
                                            .then(if (tab == option) Modifier.focusRequester(railFocus) else Modifier)
                                            .onPreviewKeyEvent { event ->
                                                if (isTv && event.type == KeyEventType.KeyDown && event.key == Key.DirectionRight &&
                                                    (state.sources.isEmpty() || focusEntryId != null)) {
                                                    if (event.nativeKeyEvent.repeatCount == 0) {
                                                        if (state.sources.isEmpty()) welcomeFocus.requestFocus()
                                                        else focusScope.launch {
                                                            // A remembered grid position can put a new tab's target
                                                            // outside the lazy viewport. Compose it before focusing.
                                                            gridState.scrollToItem(visibleEntries.indexOfFirst { it.id == focusEntryId })
                                                            withFrameNanos { }
                                                            catalogFocus.requestFocus()
                                                        }
                                                    }
                                                    true
                                                } else false
                                            }, selected = tab == option)
                                    }
                                    Spacer(Modifier.weight(1f))
                                    Text("БИБЛИОТЕКА", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    ActionButton("Источники", { showSources = true }, Modifier.fillMaxWidth())
                                    ActionButton("Настройки", { showSettings = true }, Modifier.fillMaxWidth())
                                }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!wide) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(Modifier.weight(1f)) { Brand() }
                                        ActionButton("Источники", { showSources = true }, compact = true)
                                        ActionButton("Ещё", { showSettings = true }, compact = true)
                                    }
                                }
                                if (state.sources.isEmpty()) {
                                    Welcome(
                                        modifier = Modifier.weight(1f),
                                        onAdd = { sourceEditorId = null; sourceEditorOpen = true },
                                        onImport = { onAction(AppAction.ImportPlaylist) },
                                        onDemo = { onAction(AppAction.AddDemo) },
                                        initialFocus = welcomeFocus,
                                    )
                                } else {
                                    LibraryHeader(tab.heading, selectedSource, state.sources, onAction, state.isBusy)
                                    state.playingEntry?.let { entry ->
                                        Card(onClick = { fullscreen = true }, modifier = Modifier.testTag("now-playing-bar"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("СЕЙЧАС ИГРАЕТ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                                }
                                                ActionButton("Открыть", { fullscreen = true }, compact = true)
                                            }
                                        }
                                    }
                                    if (!wide) {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(LibraryTab.entries) { option ->
                                                ActionButton(option.title, { tab = option }, selected = tab == option, compact = true)
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = search, onValueChange = { search = it }, singleLine = true,
                                        placeholder = { Text("Поиск по названию или группе") },
                                        label = { Text("Поиск") },
                                        trailingIcon = { if (search.isNotEmpty()) TextButton(onClick = { search = "" }) { Text("Сброс") } },
                                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                                    )
                                    if (groups.isNotEmpty()) {
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            item { FilterChip(selected = group.isBlank(), onClick = { group = "" }, label = { Text("Все группы") }) }
                                            items(groups) { name -> FilterChip(selected = group == name, onClick = { group = name }, label = { Text(name) }) }
                                        }
                                    }
                                    if (state.isBusy) {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                        if (state.loadingMessage.isNotBlank()) Text(state.loadingMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("${visibleEntries.size} ${entryCountWord(visibleEntries.size)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (isTv) Text("Пульт: стрелки · ОК · Назад", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (visibleEntries.isEmpty()) {
                                        EmptyLibrary(tab, search.isNotEmpty() || group.isNotEmpty(), state.isBusy,
                                            { search = ""; group = "" }, { onAction(AppAction.Refresh) }, Modifier.weight(1f))
                                    } else {
                                        LazyVerticalGrid(
                                            state = gridState,
                                            columns = GridCells.Adaptive(if (wide) 218.dp else 152.dp),
                                            modifier = Modifier.weight(1f).testTag("library-grid"),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                            contentPadding = PaddingValues(bottom = 24.dp),
                                        ) {
                                            items(visibleEntries, key = { it.id }) { entry ->
                                                MediaCard(entry, entry.id in state.favoriteIds, state.playingEntry?.id == entry.id, onAction,
                                                    Modifier.then(if (entry.id == focusEntryId) Modifier.focusRequester(catalogFocus) else Modifier)
                                                        .onFocusChanged { if (it.hasFocus) lastFocusedEntryId = entry.id })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
        if (showSources) {
            SourcesDialog(state, onAction, onDismiss = { showSources = false }, onEdit = { source ->
                sourceEditorId = source?.id; sourceEditorOpen = true
            })
        }
        if (showSettings) SettingsDialog(state, onAction) { showSettings = false }
        if (sourceEditorOpen) {
            SourceEditor(state.sources.firstOrNull { it.id == sourceEditorId }, { sourceEditorOpen = false }) {
                onAction(AppAction.SaveSource(it)); sourceEditorOpen = false; showSources = false
            }
        }
        state.epgEntry?.let { GuideDialog(it, state.programmes, state.isBusy, onAction) }
        state.seriesEntry?.let { SeriesDialog(it, state.episodes, state.isBusy, onAction) }
        state.error?.let { message ->
            AlertDialog(
                onDismissRequest = { onAction(AppAction.DismissError) },
                title = { Text("Не удалось завершить действие") },
                text = { Text(message) },
                confirmButton = { ActionButton("Понятно", { onAction(AppAction.DismissError) }, selected = true) },
            )
        }
    }
}

@Composable
private fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Text("▶", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Column {
            Text("OTT PLAY", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text("Ваш эфир. Ваш выбор.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LibraryHeader(title: String, source: SourceConfig?, sources: List<SourceConfig>, onAction: (AppAction) -> Unit, loading: Boolean) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ActionButton(source?.name ?: "Выбрать источник", { menuOpen = true }, Modifier.fillMaxWidth(), compact = true)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    sources.forEach { candidate ->
                        DropdownMenuItem(text = { Text(candidate.name) }, onClick = { onAction(AppAction.SelectSource(candidate.id)); menuOpen = false })
                    }
                }
            }
            ActionButton("Обновить", { onAction(AppAction.Refresh) }, enabled = !loading, compact = true)
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onAdd: () -> Unit, onImport: () -> Unit, onDemo: () -> Unit, initialFocus: FocusRequester) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.Start) {
        Text("ДОБРО ПОЖАЛОВАТЬ", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Весь ваш эфир.\nНа любом экране.", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Добавьте плейлист M3U или подключите аккаунт Xtream / Stalker. Каналы, фильмы, сериалы и телепрограмма появятся здесь.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        ActionButton("Добавить источник", onAdd, Modifier.focusRequester(initialFocus).testTag("welcome-add-source"), selected = true)
        Spacer(Modifier.height(8.dp))
        ActionButton("Открыть файл M3U", onImport)
        Spacer(Modifier.height(8.dp))
        ActionButton("Попробовать демо", onDemo)
        Spacer(Modifier.height(20.dp))
        Text("Для демо не нужен аккаунт. Вы сможете удалить источник в любой момент.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyLibrary(tab: LibraryTab, filtered: Boolean, loading: Boolean, onClear: () -> Unit, onRefresh: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(when { loading -> "Загружаем библиотеку…"; filtered -> "Ничего не найдено"; tab == LibraryTab.FAVORITES -> "Ваше избранное появится здесь"; else -> "В этом разделе пока пусто" }, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(when { filtered -> "Попробуйте другое название или группу."; tab == LibraryTab.FAVORITES -> "Добавляйте каналы и фильмы кнопкой «В избранное»."; else -> "Проверьте источник или обновите каталог." }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (!loading && filtered) ActionButton("Сбросить фильтры", onClear, Modifier.padding(top = 16.dp))
        if (!loading && !filtered && tab != LibraryTab.FAVORITES) ActionButton("Обновить", onRefresh, Modifier.padding(top = 16.dp))
    }
}

@Composable
internal fun MediaCard(entry: MediaEntry, favorite: Boolean, playing: Boolean, onAction: (AppAction) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Card(
        onClick = { onAction(if (entry.kind == MediaKind.SERIES) AppAction.OpenSeries(entry) else AppAction.Play(entry)) },
        modifier = modifier.fillMaxWidth().testTag("catalog-item-${entry.id}").onFocusChanged { focused = it.isFocused }
            .border(BorderStroke(if (focused || playing) 2.dp else 0.dp, if (focused || playing) MaterialTheme.colorScheme.primary else Color.Transparent), shape),
        colors = CardDefaults.cardColors(containerColor = if (focused) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
        shape = shape,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (entry.kind == MediaKind.LIVE) 2.15f else 1.7f)
                .background(Brush.linearGradient(listOf(Color(0xFF263B3D), Color(0xFF19282F)))),
            contentAlignment = Alignment.Center,
        ) {
            Text(entry.name.take(2).uppercase(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
            if (entry.logo.isNotBlank()) AsyncImage(entry.logo, contentDescription = null, modifier = Modifier.fillMaxSize().padding(12.dp), contentScale = ContentScale.Fit)
            if (playing) Text("СЕЙЧАС ИГРАЕТ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.background.copy(alpha = .85f)).padding(6.dp))
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
            Text(entry.group.ifBlank { if (entry.kind == MediaKind.LIVE) "Прямой эфир" else "Видео" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ActionButton(if (favorite) "В избранном ✓" else "В избранное", { onAction(AppAction.ToggleFavorite(entry)) }, Modifier.fillMaxWidth().semantics { contentDescription = if (favorite) "Удалить ${entry.name} из избранного" else "Добавить ${entry.name} в избранное" }, compact = true, selected = favorite)
            if (entry.kind == MediaKind.LIVE) ActionButton("Телепрограмма", { onAction(AppAction.OpenEpg(entry)) }, Modifier.fillMaxWidth(), compact = true)
        }
    }
}

@Composable
private fun SourcesDialog(state: AppUiState, onAction: (AppAction) -> Unit, onDismiss: () -> Unit, onEdit: (SourceConfig?) -> Unit) {
    var deleting by remember { mutableStateOf<SourceConfig?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Источники") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.sources.isEmpty()) item { Text("Добавьте плейлист или аккаунт провайдера.") }
                items(state.sources, key = { it.id }) { source ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(source.name, style = MaterialTheme.typography.titleMedium)
                        Text(source.kind.label() + if (source.id == state.selectedSourceId) " · выбран" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ActionButton("Выбрать", { onAction(AppAction.SelectSource(source.id)); onDismiss() }, Modifier.weight(1f), compact = true)
                            ActionButton("Изменить", { onEdit(source) }, Modifier.weight(1f), compact = true)
                        }
                        TextButton(onClick = { deleting = source }) { Text("Удалить источник", color = MaterialTheme.colorScheme.error) }
                        HorizontalDivider()
                    }
                }
                item { ActionButton("Добавить по ссылке", { onEdit(null) }, Modifier.fillMaxWidth(), selected = true) }
                item { ActionButton("Открыть файл M3U", { onAction(AppAction.ImportPlaylist); onDismiss() }, Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
    deleting?.let { source ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Удалить ${source.name}?") },
            text = { Text("Источник и его настройки будут удалены из приложения.") },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Отмена") } },
            confirmButton = { ActionButton("Удалить", { onAction(AppAction.DeleteSource(source.id)); deleting = null }) },
        )
    }
}

@Composable
private fun SettingsDialog(state: AppUiState, onAction: (AppAction) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Настройки") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Фоновое воспроизведение", style = MaterialTheme.typography.titleSmall)
                        Text("Продолжать звук при сворачивании приложения", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = state.backgroundPlayback, onCheckedChange = { onAction(AppAction.SetBackgroundPlayback(it)) }, modifier = Modifier.semantics { contentDescription = "Фоновое воспроизведение" })
                }
                HorizontalDivider()
                Text("Резервная копия", style = MaterialTheme.typography.titleSmall)
                Text("Настройки источников могут содержать пароли. Сохраните файл в надёжном месте.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ActionButton("Экспортировать настройки", { onAction(AppAction.ExportSettings); onDismiss() }, Modifier.fillMaxWidth())
                ActionButton("Импортировать настройки", { onAction(AppAction.ImportSettings); onDismiss() }, Modifier.fillMaxWidth())
                ActionButton("Добавить демоисточник", { onAction(AppAction.AddDemo); onDismiss() }, Modifier.fillMaxWidth())
            }
        },
        confirmButton = { ActionButton("Готово", onDismiss, selected = true) },
    )
}

private fun entryCountWord(count: Int): String = when {
    count % 100 in 11..14 -> "записей"
    count % 10 == 1 -> "запись"
    count % 10 in 2..4 -> "записи"
    else -> "записей"
}
