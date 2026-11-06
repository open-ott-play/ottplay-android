package play.ott.nativeapp.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal class DocumentGrantLimitException(message: String) : IllegalArgumentException(message)

/** Android 8–11 can silently evict the oldest permission beyond 128 persisted grants. */
internal object PersistedDocumentGrants {
    private const val LIMIT = 128

    @Synchronized
    fun take(resolver: ContentResolver, selected: List<Uri>, capacityMessage: String): Set<String> {
        val before = resolver.persistedUriPermissions
        val all = before.map { it.uri.toString() }.toSet()
        val readable = before.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
        val requested = selected.distinct()
        val added = requested.count { it.toString() !in all }
        if (added != 0 && before.size + added > LIMIT) throw DocumentGrantLimitException(capacityMessage)
        try {
            requested.filter { it.toString() !in readable }.forEach {
                resolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (failure: Exception) {
            requested.filter { it.toString() !in readable }.forEach {
                try { resolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                catch (_: SecurityException) { }
            }
            throw failure
        }
        return readable
    }
}
