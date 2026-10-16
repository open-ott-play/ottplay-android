package play.ott.nativeapp.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import play.ott.nativeapp.data.TrackPreference

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class TrackPreferencesTest {
    private fun audio(language: String, role: Int = 0) = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC).setLanguage(language).setRoleFlags(role).build()
    private fun text(language: String) = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).setLanguage(language).build()
    private fun tracks(vararg groups: TrackGroup) = Tracks(groups.map { group ->
        Tracks.Group(group, false, IntArray(group.length) { C.FORMAT_HANDLED }, BooleanArray(group.length))
    })

    @Test fun `saved language follows reordered tracks instead of saved index`() {
        val original = TrackGroup("old", audio("en"), audio("ru"))
        val selected = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(original, 1)).build()
        val choice = TrackPreferences.capture(selected, C.TRACK_TYPE_AUDIO)
        val replacement = TrackGroup("new", audio("ru"), audio("en"))
        val applied = TrackPreferences.apply(selected, tracks(replacement), listOf(choice, TrackPreference()))
        assertEquals("ru", choice.language)
        assertEquals(listOf(0), applied.overrides[replacement]?.trackIndices?.toList())
        assertFalse(applied.overrides.containsKey(original))
    }

    @Test fun `commentary role does not replace normal audio in same language`() {
        val group = TrackGroup("audio", audio("en", C.ROLE_FLAG_COMMENTARY), audio("en"))
        val applied = TrackPreferences.apply(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, tracks(group),
            listOf(TrackPreference("track", "en"), TrackPreference()))
        assertEquals(listOf(1), applied.overrides[group]?.trackIndices?.toList())
    }

    @Test fun `missing requested subtitles stay off then recover when their language returns`() {
        val choices = listOf(TrackPreference(), TrackPreference("track", "ru"))
        val english = TrackGroup("english", text("en"))
        val missing = TrackPreferences.apply(TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, tracks(english), choices)
        assertTrue(C.TRACK_TYPE_TEXT in missing.disabledTrackTypes)
        val russian = TrackGroup("russian", text("ru"))
        val recovered = TrackPreferences.apply(missing, tracks(russian), choices)
        assertFalse(C.TRACK_TYPE_TEXT in recovered.disabledTrackTypes)
        assertEquals(listOf(0), recovered.overrides[russian]?.trackIndices?.toList())
    }

    @Test fun `off persists and auto removes explicit overrides`() {
        val group = TrackGroup("text", text("en"))
        val parameters = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, 0)).build()
        val off = TrackPreferences.apply(parameters, tracks(group), listOf(TrackPreference(), TrackPreference("off")))
        assertTrue(C.TRACK_TYPE_TEXT in off.disabledTrackTypes)
        assertEquals(TrackPreference("off"), TrackPreferences.capture(off, C.TRACK_TYPE_TEXT))
        val auto = TrackPreferences.apply(off, tracks(group), listOf(TrackPreference(), TrackPreference()))
        assertFalse(C.TRACK_TYPE_TEXT in auto.disabledTrackTypes)
        assertTrue(auto.overrides.isEmpty())
    }
}
