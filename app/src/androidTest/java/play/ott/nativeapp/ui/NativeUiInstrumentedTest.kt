package play.ott.nativeapp.ui

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.core.SourceConfig
import play.ott.nativeapp.core.SourceKind

/** UI behavior is exercised without changing the installed application's real source vault. */
@RunWith(AndroidJUnit4::class)
class NativeUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val source = SourceConfig("ui-test", "Демоисточник", SourceKind.M3U, "https://example.com/list.m3u")
    private val entry = MediaEntry("ui-test:movie", source.id, "Тестовый ролик", "https://example.com/video.mp4", kind = MediaKind.MOVIE)

    @Test fun onboardingDemoSelectionPlaybackBackAndSourceEditor() {
        var snapshot by mutableStateOf(AppUiState())
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            OttNativeApp(
                state = snapshot,
                onAction = { action ->
                    actions += action
                    when (action) {
                        AppAction.AddDemo -> snapshot = snapshot.copy(sources = listOf(source), selectedSourceId = source.id, entries = listOf(entry))
                        is AppAction.Play -> snapshot = snapshot.copy(playingEntry = action.entry)
                        is AppAction.SaveSource -> snapshot = snapshot.copy(sources = snapshot.sources + action.source)
                        AppAction.StopPlayback -> snapshot = snapshot.copy(playingEntry = null)
                        else -> Unit
                    }
                },
                controller = null, onPictureInPicture = {}, onFullscreenChanged = {},
            )
        }
        compose.onNodeWithText("Попробовать демо").performScrollTo().performClick()
        // A movie-only source must select the film tab; an empty live section must not hide it.
        compose.onNodeWithTag("catalog-item-${entry.id}").assertIsDisplayed().performClick()
        compose.onNodeWithTag("player-container").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithTag("player-container").assertDoesNotExist()
        compose.onNodeWithTag("now-playing-bar").assertIsDisplayed()
        compose.onNodeWithText("Источники").performClick()
        compose.onNodeWithText("Добавить по ссылке").performScrollTo().performClick()
        compose.onNodeWithText("Название источника").performTextInput("Новая библиотека")
        compose.onNodeWithText("Адрес плейлиста").performTextInput("https://example.com/new.m3u")
        compose.onNodeWithText("Добавить", substring = false).performClick()
        compose.runOnIdle {
            assertTrue(actions.any { it == AppAction.AddDemo })
            assertEquals(entry, (actions.first { it is AppAction.Play } as AppAction.Play).entry)
            assertTrue(actions.any { it is AppAction.SaveSource && it.source.name == "Новая библиотека" && it.source.url == "https://example.com/new.m3u" })
        }
    }

    @Test fun televisionRailSupportsDirectionalFocusAndSelection() {
        val television = Configuration(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
        }
        compose.setContent {
            CompositionLocalProvider(LocalConfiguration provides television) {
                OttNativeApp(
                    state = AppUiState(sources = listOf(source), selectedSourceId = source.id, entries = listOf(entry)),
                    onAction = {}, controller = null, onPictureInPicture = {}, onFullscreenChanged = {},
                )
            }
        }
        compose.onNodeWithText("Эфир").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Эфир").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Фильмы").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.onNodeWithTag("catalog-item-${entry.id}").assertIsDisplayed()
    }
}
