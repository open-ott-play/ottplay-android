package play.ott.nativeapp.ui

import android.content.Context
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.View
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import play.ott.nativeapp.core.MediaEntry

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun NativePlayerPane(
    entry: MediaEntry,
    controller: MediaController?,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
    onPictureInPicture: () -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    inPictureInPicture: Boolean = false,
) {
    var resizeMode by rememberSaveable { mutableStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var buffering by remember(controller) { mutableStateOf(controller == null || controller.playbackState == Player.STATE_BUFFERING) }
    var playbackError by remember(controller, entry.id) { mutableStateOf(controller?.playerError?.errorCodeName) }
    var optionsOpen by remember { mutableStateOf(false) }
    var trackType by remember { mutableStateOf<Int?>(null) }
    var nativeView by remember { mutableStateOf<RemotePlayerView?>(null) }
    var controlsVisible by remember { mutableStateOf(false) }
    val isTv = LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val context = LocalContext.current
    BackHandler(enabled = isTv && controller != null && controlsVisible && !optionsOpen && trackType == null && !inPictureInPicture) {
        nativeView?.hideController()
    }
    if (optionsOpen && !inPictureInPicture) PlaybackOptionsDialog(
        controller, resizeMode,
        onResize = { resizeMode = it },
        onTracks = { optionsOpen = false; trackType = it },
        onPictureInPicture, onStop,
        onDismiss = { optionsOpen = false; if (isTv) nativeView?.focusPlaybackControl() },
    )
    DisposableEffect(trackType, controller, entry.id) {
        val type = trackType
        val dialog = if (type != null && controller != null && controller.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
            TrackSelectionDialogBuilder(context, if (type == C.TRACK_TYPE_AUDIO) "Аудиодорожка" else "Субтитры", controller, type)
                .setShowDisableOption(type == C.TRACK_TYPE_TEXT).build().apply {
                    setOnDismissListener { trackType = null; if (isTv) nativeView?.focusPlaybackControl() }
                    show()
                }
        } else null
        onDispose { dialog?.dismiss() }
    }
    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) playbackError = null
            }
            override fun onPlayerError(error: PlaybackException) {
                buffering = false
                playbackError = error.errorCodeName
            }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    Column(modifier.testTag("player-container").background(Color.Black)) {
        if (!inPictureInPicture) Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                entry.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
            )
            ActionButton(if (fullscreen) "Назад" else "На весь экран", onFullscreen, compact = true)
            ActionButton("Ещё", { optionsOpen = true }, Modifier.testTag("player-options"), compact = true)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context ->
                    RemotePlayerView(context).apply {
                        nativeView = this
                        openOptions = { optionsOpen = true }
                        tag = "ott-native-video"
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        player = controller
                        useController = true
                        controllerAutoShow = true
                        controllerShowTimeoutMs = 4000
                        if (isTv) setControllerAnimationEnabled(false)
                        setShowSubtitleButton(true)
                        setShowFastForwardButton(true)
                        setShowRewindButton(true)
                        setShowPreviousButton(true)
                        setShowNextButton(true)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        setKeepContentOnPlayerReset(true)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        contentDescription = "Видеоплеер. ОК — управление, меню — дорожки и параметры."
                        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { controlsVisible = it == View.VISIBLE })
                        if (isTv) post { focusPlaybackControl() }
                    }
                },
                update = { view ->
                    if (view.player !== controller) view.player = controller
                    view.resizeMode = resizeMode
                    view.useController = !inPictureInPicture
                },
                onRelease = { it.player = null; if (nativeView === it) nativeView = null },
                modifier = Modifier.fillMaxSize(),
            )
            if (!inPictureInPicture && (controller == null || buffering)) CircularProgressIndicator()
            playbackError?.takeUnless { inPictureInPicture }?.let {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .96f)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Не удалось воспроизвести", style = MaterialTheme.typography.titleMedium)
                    Text("Проверьте подключение и доступность канала. Код: $it", style = MaterialTheme.typography.bodySmall, maxLines = 3)
                    ActionButton("Повторить", onRetry, selected = true)
                }
            }
        }
    }
}

/** Channel keys remain in the native media-session path, including lazy provider resolution. */
@androidx.annotation.OptIn(UnstableApi::class)
private class RemotePlayerView(context: Context) : PlayerView(context) {
    var openOptions: () -> Unit = {}
    private var playbackFocusPending = false

    fun focusPlaybackControl() {
        playbackFocusPending = true
        restorePlaybackFocusWhenReady()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) restorePlaybackFocusWhenReady()
    }

    private fun restorePlaybackFocusWhenReady() {
        if (!playbackFocusPending || !hasWindowFocus()) return
        post {
            // Dismissing a dialog schedules removal of its separate window. Restore
            // focus only after that handoff, without stealing unrelated future focus.
            if (playbackFocusPending && isAttachedToWindow && hasWindowFocus()) {
                showController()
                if (findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).requestFocus()) {
                    playbackFocusPending = false
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openOptions()
            return true
        }
        val currentPlayer = player ?: return super.dispatchKeyEvent(event)
        val forward = event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP
        val backward = event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN
        if (!forward && !backward) return super.dispatchKeyEvent(event)
        val itemCommand = if (forward) Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM else Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        val generalCommand = if (forward) Player.COMMAND_SEEK_TO_NEXT else Player.COMMAND_SEEK_TO_PREVIOUS
        if (!currentPlayer.isCommandAvailable(itemCommand) && !currentPlayer.isCommandAvailable(generalCommand)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (currentPlayer.isCommandAvailable(itemCommand)) {
                if (forward) currentPlayer.seekToNextMediaItem() else currentPlayer.seekToPreviousMediaItem()
            } else {
                if (forward) currentPlayer.seekToNext() else currentPlayer.seekToPrevious()
            }
        }
        return true
    }
}
