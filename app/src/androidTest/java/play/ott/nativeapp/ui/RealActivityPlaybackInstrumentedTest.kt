package play.ott.nativeapp.ui

import android.content.Intent
import android.content.res.Configuration
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.MainActivity
import play.ott.nativeapp.OttplayApplication
import play.ott.nativeapp.data.NativeRepository
import play.ott.nativeapp.data.UserPreferences
import play.ott.nativeapp.playback.PlaybackTestLifecycle

/** Real Activity, persisted catalogue, ViewModel, service and decoder; no replacement UI/controller. */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class RealActivityPlaybackInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository get() = (context.applicationContext as OttplayApplication).repository
    private val source = NativeRepository.demoSource().copy(id = "activity-test-${UUID.randomUUID()}", name = "Activity test")
    private val entryId get() = "${source.id}:pattern"
    private val isTv get() = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    private var activity: MainActivity? = null
    private var savedPreferences: UserPreferences? = null

    @Before fun seedOnlyOurSyntheticSource(): Unit = runBlocking {
        PlaybackTestLifecycle.finishPreviousPlayback()
        savedPreferences = repository.preferences.data.first()
        repository.save(source)
        repository.refresh(source)
        repository.preferences.update { it.copy(selectedSourceId = source.id, backgroundPlayback = true) }
    }

    @After fun restoreUserPreferencesAndRemoveOnlyOurSource(): Unit = runBlocking {
        onMain { activity?.finish() }
        PlaybackTestLifecycle.finishPreviousPlayback()
        repository.remove(source.id)
        savedPreferences?.let { previous -> repository.preferences.update { previous } }
    }

    @Test fun coldLaunchSelectsWithRemoteAndNativeControlsChangeActualPlayback() {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity = instrumentation.startActivitySync(launch) as MainActivity
        awaitNode("catalog-item-$entryId")
        if (isTv) {
            // Crucially no RequestFocus or touch injection: launch must create usable remote focus.
            awaitFocused("library-tab-MOVIES")
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("catalog-item-$entryId").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        } else compose.onNodeWithTag("catalog-item-$entryId").performClick()
        awaitNode("player-container")
        val playerView = awaitPlayerView()
        val actualPlayer = requireNotNull(playerView.player)
        // Keep the eight-second deterministic fixture available while interacting with menus.
        onMain { requireNotNull(playerView.player).repeatMode = Player.REPEAT_MODE_ONE }
        awaitPlayback("Video must actually decode") { it.isPlaying && it.videoSize.width > 0 && it.currentPosition > 100 }
        if (isTv) {
            awaitViewFocus(androidx.media3.ui.R.id.exo_play_pause)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
        } else onMain { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick() }
        awaitPlayback("Native pause must preserve the selected item") { !it.playWhenReady && it.currentMediaItem?.mediaId == entryId }
        if (isTv) key(KeyEvent.KEYCODE_DPAD_CENTER)
        else onMain { playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick() }
        awaitPlayback("Native play must resume") { it.isPlaying }

        openOptions()
        compose.onNodeWithTag("player-subtitle-options").assertIsNotEnabled() // Fixture has no subtitle stream.
        activateMenuItem("player-speed-options")
        activateMenuItem("player-speed-1.25")
        awaitPlayback("Speed selection must reach the service") { it.playbackParameters.speed == 1.25f }
        openOptions()
        activateMenuItem("player-scale-${AspectRatioFrameLayout.RESIZE_MODE_ZOOM}")
        onMain { assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, playerView.resizeMode) }

        if (isTv) {
            // The first Back dismisses controls; the second returns to the catalogue and restores focus.
            key(KeyEvent.KEYCODE_BACK)
            onMain { assertTrue("Back must hide native controls", !playerView.isControllerFullyVisible) }
            compose.onNodeWithTag("player-container").assertIsDisplayed()
        }
        key(KeyEvent.KEYCODE_BACK)
        awaitNode("now-playing-bar")
        if (isTv) awaitFocused("catalog-item-$entryId")
        awaitPlaybackWithoutSurface("Leaving fullscreen must keep the service playing", actualPlayer) { it.isPlaying }
    }

    private fun openOptions() {
        if (isTv) key(KeyEvent.KEYCODE_MENU) else compose.onNodeWithTag("player-options").performClick()
        awaitNode("player-speed-options")
    }

    private fun activateMenuItem(tag: String) {
        if (isTv) {
            // Follow actual D-pad navigation, without assigning focus to the desired target.
            repeat(10) {
                if (isFocused(tag)) { key(KeyEvent.KEYCODE_DPAD_CENTER); return }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
            }
            throw AssertionError("Remote could not reach $tag")
        } else {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            // The menu action updates Compose state; wait for AndroidView.update to apply it.
            compose.waitForIdle()
        }
    }

    private fun isFocused(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes()
        .any { it.config.getOrElse(SemanticsProperties.Focused) { false } }

    private fun awaitFocused(tag: String) {
        compose.waitUntil(10_000) { isFocused(tag) }
        compose.onNodeWithTag(tag).assertIsFocused()
    }

    private fun awaitNode(tag: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun awaitPlayerView(): PlayerView {
        var view: PlayerView? = null
        awaitState("Activity did not attach its native PlayerView") {
            onMain { view = activity?.window?.decorView?.findViewWithTag("ott-native-video") }
            view?.player != null
        }
        return requireNotNull(view)
    }

    private fun awaitViewFocus(id: Int) = awaitState("Native playback control did not receive TV focus") {
        var focused = false
        onMain { focused = activity?.currentFocus?.id == id }
        focused
    }

    private fun awaitPlayback(message: String, condition: (Player) -> Boolean) {
        val player = awaitPlayerView().player
        awaitPlaybackWithoutSurface(message, player, condition)
    }

    private fun awaitPlaybackWithoutSurface(message: String, player: Player?, condition: (Player) -> Boolean) {
        awaitState(message) {
            var ready = false
            onMain {
                val actual = requireNotNull(player)
                assertEquals("Unexpected decoder error", null, actual.playerError?.errorCodeName)
                ready = condition(actual)
            }
            ready
        }
    }

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        throw AssertionError(message)
    }

    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); compose.waitForIdle() }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
}
