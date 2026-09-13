package play.ott.nativeapp.data

/** Validate every preference before the importer writes either vault or catalogue. */
internal fun validateBackupPreferences(preferences: UserPreferences) {
    fun validId(id: String) = id.isNotBlank() && id.length <= 512 && id.none { it.code < 32 }
    require(preferences.selectedSourceId == null || validId(preferences.selectedSourceId)) { "Invalid selected source" }
    require(preferences.favorites.size <= 10_000 && preferences.favorites.all(::validId)) { "Invalid favorites" }
    require(preferences.resumePositions.size <= 500 && preferences.resumePositions.all { (id, position) ->
        validId(id) && position >= 0
    }) { "Invalid playback positions" }
}

internal fun mergeBackupPreferences(old: UserPreferences, imported: UserPreferences, sourceIds: Set<String>): UserPreferences {
    val positions = LinkedHashMap(old.resumePositions)
    imported.resumePositions.forEach { (id, position) -> positions.remove(id); positions[id] = position }
    while (positions.size > 500) positions.remove(positions.keys.first())
    return old.copy(
        selectedSourceId = imported.selectedSourceId?.takeIf { it in sourceIds } ?: old.selectedSourceId,
        favorites = (old.favorites + imported.favorites).toList().takeLast(10_000).toSet(),
        backgroundPlayback = imported.backgroundPlayback,
        resumePositions = positions,
    )
}
