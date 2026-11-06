package play.ott.nativeapp.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalSourceSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test fun `network source preserves its historical wire fields`() {
        val source = SourceConfig("network", "Playlist", SourceKind.M3U, "https://example.test/list.m3u")
        val encoded = json.encodeToString(source)
        val fields = json.parseToJsonElement(encoded).jsonObject
        assertFalse("localFolder" in fields)
        assertFalse("localDocuments" in fields)
        assertTrue("nameIsUserDefined" in fields)
        assertEquals(source, json.decodeFromString<SourceConfig>(encoded))
    }

    @Test fun `folder source retains its persisted tree grant`() {
        val source = SourceConfig("folder", "Videos", SourceKind.LOCAL, "content://videos/tree/root", localFolder = true)
        val encoded = json.encodeToString(source)
        assertTrue("localFolder" in json.parseToJsonElement(encoded).jsonObject)
        assertEquals(source, json.decodeFromString<SourceConfig>(encoded))
    }

    @Test fun `selected file source retains every persisted document grant`() {
        val selected = listOf("content://videos/document/first", "content://videos/document/second")
        val source = SourceConfig("files", "Videos", SourceKind.LOCAL, selected.first(), localDocuments = selected)
        val encoded = json.encodeToString(source)
        assertTrue("localDocuments" in json.parseToJsonElement(encoded).jsonObject)
        assertEquals(source, json.decodeFromString<SourceConfig>(encoded))
    }
}
