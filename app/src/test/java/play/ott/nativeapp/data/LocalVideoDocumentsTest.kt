package play.ott.nativeapp.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import play.ott.nativeapp.core.*
import play.ott.nativeapp.playback.ItemMediaSourceFactory
import play.ott.nativeapp.playback.PlaybackItems
import play.ott.nativeapp.playback.LocalPlaybackQueue
import play.ott.nativeapp.playback.validateLocalPlayback
import play.ott.nativeapp.playback.PlaybackTestApplication
import java.io.File
import javax.crypto.KeyGenerator

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = PlaybackTestApplication::class)
class LocalVideoDocumentsTest {
    class Provider : ContentProvider() {
        var queries = 0
        var opens = 0
        lateinit var original: File
        override fun onCreate(): Boolean {
            original = File(requireNotNull(context).cacheDir, "original-video.mp4").apply { writeBytes("original-video-bytes".toByteArray()) }
            return true
        }
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            queries++
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
            return MatrixCursor(columns).apply {
                addRow(columns.map { when(it) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "root/one.mp4"
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> "one.mp4"
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> "video/mp4"
                    else -> null
                } })
            }
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            check(mode == "r"); opens++
            return ParcelFileDescriptor.open(original, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri) = "video/mp4"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("read only")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("read only")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("read only")
    }
    private val root = Uri.parse("content://local.test/tree/root")
    private val file = DocumentsContract.buildDocumentUriUsingTree(root, "root/one.mp4")
    private fun install() = Robolectric.buildContentProvider(Provider::class.java)
        .create(ProviderInfo().apply { authority = "local.test"; exported = true; grantUriPermissions = true }).get()

    @Test fun grantedOriginalIsSeekedDirectlyByTheActualMediaSourceDataFactory() = runBlocking {
        val provider = install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val source = SourceConfig("folder", "Folder", SourceKind.LOCAL, root.toString(), localFolder = true)
        val documents = AndroidLocalVideoDocuments(context)
        val owner = LocalVideoOwner()
        val entry = requireNotNull(documents.document(source, file.toString(), owner))
        assertTrue(documents.readable(source, entry, owner))
        assertEquals(1, provider.opens)
        val item = PlaybackItems.build("one", "One", file.toString(), mimeType = "video/mp4")
        val data = ItemMediaSourceFactory(context, OkHttpClient()).dataSourceFactoryFor(item).createDataSource()
        try {
            data.open(DataSpec.Builder().setUri(file).setPosition(9).setLength(5).build())
            val buffer = ByteArray(5)
            assertEquals(5, data.read(buffer, 0, buffer.size))
            assertEquals("video", buffer.toString(Charsets.UTF_8))
            assertEquals(file, data.uri)
        } finally { data.close() }
        assertEquals(2, provider.opens)
        assertEquals("original-video-bytes", provider.original.readText())
    }

    @Test fun revokedGrantAndForeignTreeAreRejectedBeforeOriginalIo() = runBlocking {
        val provider = install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        resolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val source = SourceConfig("folder", "Folder", SourceKind.LOCAL, root.toString(), localFolder = true)
        val documents = AndroidLocalVideoDocuments(context)
        assertNotNull(documents.document(source, file.toString(), LocalVideoOwner()))
        val reads = provider.queries
        resolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(runCatching { documents.document(source, file.toString(), LocalVideoOwner()) }.isFailure)
        resolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val foreign = DocumentsContract.buildDocumentUriUsingTree(Uri.parse("content://local.test/tree/other"), "other/one.mp4")
        assertTrue(runCatching { documents.document(source, foreign.toString(), LocalVideoOwner()) }.isFailure)
        assertEquals(reads, provider.queries)
        assertEquals(0, provider.opens)
    }

    @Test fun registeringGrantDoesNotScanAndDeletingOneBindingRetainsTheOther() = runBlocking {
        val provider = install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = NativeRepository(context)
        val vault: SourceVault = ReflectionHelpers.getField(repository, "vault")
        val db: CatalogDatabase = ReflectionHelpers.getField(repository, "db")
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        try {
            val folder = repository.addLocal(listOf(root), true)
            val selected = repository.addLocal(listOf(file), false)
            assertEquals(folder.id, repository.addLocal(listOf(root), true).id)
            assertNotEquals(folder.id, selected.id)
            assertEquals(0, provider.queries)
            assertEquals(0, provider.opens)
            assertEquals(2, vault.read().size)
            repository.remove(selected.id)
            assertEquals(listOf(folder.id), vault.read().map { it.id })
            val permissions = context.contentResolver.persistedUriPermissions.map { it.uri }.toSet()
            assertTrue(root in permissions)
            assertFalse(file in permissions)
        } finally { db.close() }
    }
    @Test fun realProviderDiscoveryOpensNoOriginalUntilAPlaybackCandidateIsChosen() = runBlocking {
        val provider = install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.contentResolver.takePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val source = SourceConfig("folder", "Folder", SourceKind.LOCAL, root.toString(), localFolder = true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = LocalVideoLibrary(scope, AndroidLocalVideoDocuments(context), Dispatchers.IO) { _, _, _, _ -> }
        try {
            val observed = library.scan(source)
            val found = withTimeout(5000) { observed.first { !it.scanning }.entries }
            assertEquals(1, found.size)
            assertTrue(provider.queries > 0)
            assertEquals(0, provider.opens)
            assertEquals(found.single(), firstPlayableLocalVideo(observed) { library.validate(source, it) })
            assertEquals(1, provider.opens)
        } finally { scope.cancel() }
    }

    @Test fun queuedMediaValidatesAtOpenAndRejectsRevokedOrDifferentDocuments() = runBlocking {
        val provider = install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = NativeRepository(context)
        val vault: SourceVault = ReflectionHelpers.getField(repository, "vault")
        val db: CatalogDatabase = ReflectionHelpers.getField(repository, "db")
        val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        ReflectionHelpers.setField(vault, "key\$delegate", lazy { key })
        try {
            val source = repository.addLocal(listOf(root), true)
            val entry = MediaEntry(localVideoId(source.id, file.toString()), source.id, "One", file.toString(), mimeType = "video/mp4")
            val built = PlaybackItems.build(entry.id, entry.name, entry.url, mimeType = entry.mimeType)
            val item = built.buildUpon().setRequestMetadata(built.requestMetadata.buildUpon().setExtras(
                Bundle(built.requestMetadata.extras ?: Bundle()).apply {
                    putString(LocalPlaybackQueue.EXTRA_SOURCE, source.id)
                    putString("play.ott.nativeapp.entry", Json.encodeToString(entry))
                }
            ).build()).build()
            val factory = ItemMediaSourceFactory(context, OkHttpClient(), localValidator = { validateLocalPlayback(repository, it) })
                .dataSourceFactoryFor(item)
            assertEquals(0, provider.opens)
            val data = factory.createDataSource()
            try {
                data.open(DataSpec.Builder().setUri(file).setPosition(9).setLength(5).build())
                val buffer = ByteArray(5)
                assertEquals(5, data.read(buffer, 0, 5))
                assertEquals("video", buffer.toString(Charsets.UTF_8))
            } finally { data.close() }
            assertEquals(2, provider.opens) // Selected-file validation, then the direct Media3 original.
            context.contentResolver.releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val revoked = factory.createDataSource()
            try { assertTrue(runCatching { revoked.open(DataSpec.Builder().setUri(file).build()) }.isFailure) }
            finally { revoked.close() }
            val different = factory.createDataSource()
            try { assertTrue(runCatching { different.open(DataSpec.Builder().setUri(root).build()) }.isFailure) }
            finally { different.close() }
            assertEquals(2, provider.opens)
        } finally { db.close() }
    }

    @Test @Config(sdk = [30]) fun fullGrantBudgetRejectsNewSelectionsWithoutEvictingExistingPermissions() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val old = (0 until 127).map { Uri.parse("content://grants.test/document/$it") }
        old.forEach { resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        // Write-only grants consume the same system budget as read grants.
        val writeOnly = Uri.parse("content://grants.test/document/write-only")
        resolver.takePersistableUriPermission(writeOnly, Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val before = resolver.persistedUriPermissions.map { it.uri }.toSet()
        val failure = runCatching { PersistedDocumentGrants.take(resolver, listOf(file), "capacity exceeded") }.exceptionOrNull()
        assertEquals("capacity exceeded", failure?.message)
        assertEquals(before, resolver.persistedUriPermissions.map { it.uri }.toSet())
        PersistedDocumentGrants.take(resolver, listOf(old.first(), old.first()), "capacity exceeded")
        assertEquals(before, resolver.persistedUriPermissions.map { it.uri }.toSet())
    }

    @Test @Config(sdk = [30]) fun grantPreflightCountsOnlyNewDistinctSelections() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val old = (0 until 127).map { Uri.parse("content://grants.test/document/$it") }
        old.forEach { resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        PersistedDocumentGrants.take(resolver, listOf(old.first(), file, file), "capacity exceeded")
        assertEquals(128, resolver.persistedUriPermissions.size)
        assertTrue(resolver.persistedUriPermissions.any { it.uri == file && it.isReadPermission })
        val next = Uri.parse("content://grants.test/document/overflow")
        assertTrue(runCatching { PersistedDocumentGrants.take(resolver, listOf(next), "capacity exceeded") }.isFailure)
        assertEquals(128, resolver.persistedUriPermissions.size)
    }

}
