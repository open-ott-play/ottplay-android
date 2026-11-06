package play.ott.nativeapp.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.core.MediaEntry
import play.ott.nativeapp.core.MediaKind
import play.ott.nativeapp.playback.PlaybackTestApplication
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class LocalCatalogPersistenceTest {
    @Test fun incrementalEncryptedPrefixSurvivesReopenWithoutRewritingEarlierBlocks() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vault = SourceVault(context)
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        fun entry(id: String, source: String = "local") = MediaEntry(id, source, id, "content://video/$id",
            MediaKind.MOVIE, headerOrigins = emptyMap())
        var db = CatalogDatabase(context, vault)
        try {
            db.appendLocal("local", listOf(entry("first")), true)
            db.appendLocal("other", listOf(entry("untouched", "other")), true)
            val firstCipher = db.readableDatabase.rawQuery("SELECT payload FROM entries WHERE source_id='local' AND ordinal=0", null)
                .use { it.moveToFirst(); it.getBlob(0) }
            db.appendLocal("local", listOf(entry("second")), false)
            val retainedCipher = db.readableDatabase.rawQuery("SELECT payload FROM entries WHERE source_id='local' AND ordinal=0", null)
                .use { it.moveToFirst(); it.getBlob(0) }
            assertArrayEquals(firstCipher, retainedCipher)
            assertFalse(firstCipher.toString(Charsets.UTF_8).contains("content://video/first"))
            db.close(); db = CatalogDatabase(context, vault)
            assertEquals(listOf("first", "second"), db.read("local").entries.map { it.id })
            assertEquals(listOf("untouched"), db.read("other").entries.map { it.id })
            db.appendLocal("local", emptyList(), true)
            assertTrue(db.read("local").entries.isEmpty())
            assertEquals(1, db.read("other").entries.size)
        } finally { db.close() }
    }
}
