package play.ott.nativeapp.data

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.IOException
import java.util.Locale
import play.ott.nativeapp.core.SourceConfig

/** Only selected SAF grants reach ContentResolver; no raw path or media copy. */
internal class AndroidLocalVideoDocuments(context: Context) : LocalVideoDocuments {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)

    private fun grant(source: SourceConfig, uri: Uri) {
        require(uri.scheme == "content") { "A document URI is required" }
        val grants = if (source.localFolder) listOf(source.url) else source.localDocuments.ifEmpty { listOf(source.url) }
        val required = if (source.localFolder) source.url else uri.toString()
        require(resolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == required }) {
            "Select the local source again to grant read access"
        }
        if (source.localFolder) {
            val root = Uri.parse(source.url)
            require(root.authority == uri.authority && DocumentsContract.getTreeDocumentId(root) == DocumentsContract.getTreeDocumentId(uri)) {
                "Document is outside the selected folder"
            }
        } else require(uri.toString() in grants) { "Document was not selected" }
    }

    private suspend fun <T> operation(owner: LocalVideoOwner, block: suspend (CancellationSignal) -> T): T {
        owner.check()
        val signal = CancellationSignal()
        val remove = owner.onCancel(signal::cancel)
        try { return block(signal).also { owner.check() } }
        finally { remove() }
    }

    private fun row(uri: Uri, name: String, declaredMime: String, group: String): LocalDocument? {
        val directory = declaredMime == DocumentsContract.Document.MIME_TYPE_DIR
        val type = declaredMime.takeUnless { it.isBlank() || it == "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)).orEmpty()
        if (!directory && !type.startsWith("video/") && !type.startsWith("audio/")) return null
        require(uri.toString().length <= 8192) { "Document address is too long" }
        return LocalDocument(uri.toString(), name.take(256).ifBlank { "Local video" }, type, directory, group.take(512))
    }

    override suspend fun folder(source: SourceConfig, uri: String, group: String, owner: LocalVideoOwner, emit: suspend (LocalDocument) -> Unit) {
        val tree = Uri.parse(source.url)
        val folder = Uri.parse(uri)
        grant(source, folder)
        val id = if (uri == source.url) DocumentsContract.getTreeDocumentId(tree) else DocumentsContract.getDocumentId(folder)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        operation(owner) { signal ->
            resolver.query(children, columns, null, null, null, signal)?.use { cursor ->
                while (cursor.moveToNext()) {
                    owner.visit()
                    val document = cursor.getString(0) ?: continue
                    val name = cursor.getString(1).orEmpty()
                    val mime = cursor.getString(2).orEmpty()
                    val child = DocumentsContract.buildDocumentUriUsingTree(tree, document)
                    row(child, name, mime, group)?.let { value ->
                        emit(if (value.directory) value.copy(group = listOf(group, name.take(256)).filter { it.isNotBlank() }.joinToString(" / ")) else value)
                    }
                }
            } ?: throw IOException("Local folder unavailable")
        }
    }

    override suspend fun document(source: SourceConfig, uri: String, owner: LocalVideoOwner): LocalDocument? {
        val selected = Uri.parse(uri)
        grant(source, selected)
        return operation(owner) { signal ->
            resolver.query(selected, columns, null, null, null, signal)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    owner.visit()
                    row(selected, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), "")
                }
            }
        }
    }

    override suspend fun readable(source: SourceConfig, document: LocalDocument, owner: LocalVideoOwner): Boolean {
        val uri = Uri.parse(document.uri)
        grant(source, uri)
        return operation(owner) { signal ->
            resolver.openAssetFileDescriptor(uri, "r", signal)?.use { descriptor ->
                descriptor.createInputStream().use { input ->
                    // Byte-range playback requires a seekable original. Never stage
                    // a pipe/cloud stream into a second full-file copy.
                    input.channel.position(descriptor.startOffset)
                    input.channel.size() > descriptor.startOffset
                }
            } ?: false
        }
    }
}
