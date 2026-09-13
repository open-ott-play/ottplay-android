package play.ott.nativeapp.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsBackupPolicyTest {
    @Test fun `restore merges favorites and resume offsets while honoring imported source and background choice`() {
        val old = UserPreferences("old", setOf("favorite-old"), true, mapOf("old-film" to 15_000, "film" to 1_000))
        val backup = UserPreferences("new", setOf("favorite-new"), false, mapOf("film" to 42_000, "episode" to 12_000))
        validateBackupPreferences(backup)
        val merged = mergeBackupPreferences(old, backup, setOf("old", "new"))
        assertEquals("new", merged.selectedSourceId)
        assertFalse(merged.backgroundPlayback)
        assertEquals(setOf("favorite-old", "favorite-new"), merged.favorites)
        assertEquals(mapOf("old-film" to 15_000L, "film" to 42_000L, "episode" to 12_000L), merged.resumePositions)
    }

    @Test fun `missing file source cannot replace selected source and imported positions take precedence within capacity`() {
        val old = UserPreferences(selectedSourceId = "network", resumePositions = (0 until 500).associate { "old-$it" to 10L })
        val backup = UserPreferences(selectedSourceId = "file", resumePositions = mapOf("old-0" to 20, "new" to 30))
        val merged = mergeBackupPreferences(old, backup, setOf("network"))
        assertEquals("network", merged.selectedSourceId)
        assertEquals(500, merged.resumePositions.size)
        assertEquals(20, merged.resumePositions["old-0"])
        assertEquals(30, merged.resumePositions["new"])
        assertFalse("old-1" in merged.resumePositions)
    }

    @Test fun `malformed preferences fail before storage mutation`() {
        listOf(
            UserPreferences(resumePositions = mapOf("movie" to -1)),
            UserPreferences(resumePositions = (0..500).associate { "movie-$it" to 0L }),
            UserPreferences(favorites = setOf("")),
            UserPreferences(selectedSourceId = "bad\nsource"),
            UserPreferences(favorites = setOf("x".repeat(513))),
        ).forEach { assertFailsWith<IllegalArgumentException> { validateBackupPreferences(it) } }
        assertTrue(runCatching { validateBackupPreferences(UserPreferences()) }.isSuccess)
    }
}
