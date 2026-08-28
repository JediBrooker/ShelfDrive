package com.audiobookshelf.app.managers

import android.content.Context
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.plugins.AbsLog
import io.paperdb.Paper
import java.io.File

internal const val MAX_MEDIA_ITEM_HISTORY_EVENTS = 256

class DbManager {
  val tag = "DbManager"
  private var cachedLogCount: Int? = null
  private var logWritesSincePrune = LOG_PRUNE_INTERVAL

  private inline fun <reified T> readSafely(bookName: String, key: String): T? {
    return try {
      val stored = Paper.book(bookName).read<Any>(key)
      if (stored == null || stored is T) {
        stored as T?
      } else {
        // Paper's generic read is erased; without an explicit runtime check,
        // the generated ClassCastException occurs at the caller outside this
        // helper's try/catch.
        Log.e(tag, "Ignoring entry with the wrong type in $bookName")
        null
      }
    } catch (error: RuntimeException) {
      // A single stale/corrupt Paper entry must not take down the AAOS media
      // service. Keep the entry for possible recovery and omit it this run.
      Log.e(tag, "Ignoring unreadable entry in $bookName (${error.javaClass.simpleName})")
      null
    }
  }

  private fun keysSafely(bookName: String): List<String> {
    return try {
      Paper.book(bookName).allKeys
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to enumerate $bookName (${error.javaClass.simpleName})")
      emptyList()
    }
  }

  companion object {
    private var isDbInitialized = false
    private const val LOG_RETENTION_MS = 48L * 60L * 60L * 1000L
    private const val MAX_LOG_ENTRIES = 1_000
    private const val LOG_PRUNE_INTERVAL = 50

    @Synchronized
    fun initialize(ctx: Context) {
      if (isDbInitialized) return
      Paper.init(ctx)
      isDbInitialized = true
      Log.i("DbManager", "Initialized Paper db")
    }
  }

  fun getDeviceData(): DeviceData {
    return readSafely<DeviceData>("device", "data")
            ?: DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
  }
  fun saveDeviceData(deviceData: DeviceData) {
    Paper.book("device").write("data", deviceData)
  }

  fun getLocalLibraryItems(mediaType: String? = null): MutableList<LocalLibraryItem> {
    val localLibraryItems: MutableList<LocalLibraryItem> = mutableListOf()
    keysSafely("localLibraryItems").forEach {
      val localLibraryItem = readSafely<LocalLibraryItem>("localLibraryItems", it)
      if (localLibraryItem != null &&
                      (mediaType.isNullOrEmpty() || mediaType == localLibraryItem.mediaType)
      ) {
        localLibraryItems.add(localLibraryItem)
      }
    }
    return localLibraryItems
  }

  fun getLocalLibraryItemsInFolder(folderId: String): List<LocalLibraryItem> {
    val localLibraryItems = getLocalLibraryItems()
    return localLibraryItems.filter { it.folderId == folderId }
  }

  fun getLocalLibraryItemByLId(
    libraryItemId: String,
    connectionId: String
  ): LocalLibraryItem? {
    if (connectionId.isBlank()) return null
    return getLocalLibraryItems().find {
      it.libraryItemId == libraryItemId &&
        it.serverConnectionConfigId == connectionId
    }
  }

  fun getLocalLibraryItem(localLibraryItemId: String): LocalLibraryItem? {
    return readSafely("localLibraryItems", localLibraryItemId)
  }

  fun getLocalLibraryItemWithEpisode(podcastEpisodeId: String): LibraryItemWithEpisode? {
    getLocalLibraryItems("podcast").forEach { localLibraryItem ->
      val podcast = localLibraryItem.media as? Podcast ?: return@forEach
      val podcastEpisode = podcast.episodes?.find { it.id == podcastEpisodeId }
      if (podcastEpisode != null) {
        return LibraryItemWithEpisode(localLibraryItem, podcastEpisode)
      }
    }
    return null
  }

  fun removeLocalLibraryItem(localLibraryItemId: String) {
    Paper.book("localLibraryItems").delete(localLibraryItemId)
  }

  fun saveLocalLibraryItems(localLibraryItems: List<LocalLibraryItem>) {
    localLibraryItems.map { Paper.book("localLibraryItems").write(it.id, it) }
  }

  fun saveLocalLibraryItem(localLibraryItem: LocalLibraryItem) {
    Paper.book("localLibraryItems").write(localLibraryItem.id, localLibraryItem)
  }

  fun saveLocalFolder(localFolder: LocalFolder) {
    Paper.book("localFolders").write(localFolder.id, localFolder)
  }

  fun getLocalFolder(folderId: String): LocalFolder? {
    return readSafely("localFolders", folderId)
  }

  fun getAllLocalFolders(): List<LocalFolder> {
    val localFolders: MutableList<LocalFolder> = mutableListOf()
    keysSafely("localFolders").forEach { localFolderId ->
      readSafely<LocalFolder>("localFolders", localFolderId)?.let { localFolders.add(it) }
    }
    return localFolders
  }

  fun removeLocalFolder(folderId: String) {
    val localLibraryItems = getLocalLibraryItemsInFolder(folderId)
    localLibraryItems.forEach { Paper.book("localLibraryItems").delete(it.id) }
    Paper.book("localFolders").delete(folderId)
  }

  fun saveDownloadItem(downloadItem: DownloadItem) {
    Paper.book("downloadItems").write(downloadItem.id, downloadItem)
  }

  fun removeDownloadItem(downloadItemId: String) {
    Paper.book("downloadItems").delete(downloadItemId)
  }

  fun getDownloadItems(): List<DownloadItem> {
    val downloadItems: MutableList<DownloadItem> = mutableListOf()
    keysSafely("downloadItems").forEach { downloadItemId ->
      readSafely<DownloadItem>("downloadItems", downloadItemId)?.let { downloadItems.add(it) }
    }
    return downloadItems
  }

  fun saveLocalMediaProgress(mediaProgress: LocalMediaProgress) {
    Paper.book("localMediaProgress").write(mediaProgress.id, mediaProgress)
  }
  // For books this will just be the localLibraryItemId for podcast episodes this will be
  // "{localLibraryItemId}-{episodeId}"
  fun getLocalMediaProgress(localMediaProgressId: String): LocalMediaProgress? {
    return readSafely("localMediaProgress", localMediaProgressId)
  }
  fun getAllLocalMediaProgress(): List<LocalMediaProgress> {
    val mediaProgress: MutableList<LocalMediaProgress> = mutableListOf()
    keysSafely("localMediaProgress").forEach { localMediaProgressId ->
      readSafely<LocalMediaProgress>("localMediaProgress", localMediaProgressId)?.let {
        mediaProgress.add(it)
      }
    }
    return mediaProgress
  }
  fun removeLocalMediaProgress(localMediaProgressId: String) {
    Paper.book("localMediaProgress").delete(localMediaProgressId)
  }

  fun removeAllLocalMediaProgress() {
    Paper.book("localMediaProgress").destroy()
  }

  // Make sure all local file ids still exist
  fun cleanLocalLibraryItems() {
    val localLibraryItems = getLocalLibraryItems()

    localLibraryItems.forEach { lli ->
      var hasUpdates = false

      // Check local files
      lli.localFiles =
              lli.localFiles.filter { localFile ->
                val file = File(localFile.absolutePath)
                if (!file.exists()) {
                  Log.d(tag, "Removed a missing file from a local library item")
                  hasUpdates = true
                }
                file.exists()
              }.toMutableList()

      // Check audio tracks and episodes
      if (lli.isPodcast) {
        val podcast = lli.media as? Podcast
        if (podcast == null) {
          Log.e(tag, "Ignoring local item with mismatched podcast media type")
          lli.isInvalid = true
          saveLocalLibraryItem(lli)
          return@forEach
        }
        podcast.episodes =
                podcast.episodes.orEmpty().filter { ep ->
                  if (lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } == null) {
                    Log.d(tag, "Removed a missing podcast episode from a local library item")
                    hasUpdates = true
                  }
                  ep.audioTrack != null &&
                          lli.localFiles.find { lf -> lf.id == ep.audioTrack?.localFileId } != null
                }.toMutableList()
      } else {
        val book = lli.media as? Book
        if (book == null) {
          Log.e(tag, "Ignoring local item with mismatched book media type")
          lli.isInvalid = true
          saveLocalLibraryItem(lli)
          return@forEach
        }
        book.tracks =
                book.tracks.orEmpty().filter { track ->
                  if (lli.localFiles.find { lf -> lf.id == track.localFileId } == null) {
                    Log.d(tag, "Removed a missing audio track from a local library item")
                    hasUpdates = true
                  }
                  lli.localFiles.find { lf -> lf.id == track.localFileId } != null
                }.toMutableList()
      }

      // Check cover still there
      lli.coverAbsolutePath?.let {
        val coverFile = File(it)

        if (!coverFile.exists()) {
          Log.d(tag, "Removed a missing cover from a local library item")
          lli.coverAbsolutePath = null
          lli.coverContentUrl = null
          hasUpdates = true
        }
      }

      if (hasUpdates) {
        Log.d(tag, "Saving a cleaned local library item")
        Paper.book("localLibraryItems").write(lli.id, lli)
      }
    }
  }

  // Remove any local media progress where the local media item is not found
  fun cleanLocalMediaProgress() {
    val localMediaProgress = getAllLocalMediaProgress()
    val localLibraryItems = getLocalLibraryItems()
    localMediaProgress.forEach {
      val matchingLLI = localLibraryItems.find { lli -> lli.id == it.localLibraryItemId }
      if (!it.id.startsWith("local")) {
        // A bug on the server when syncing local media progress was replacing the media progress id
        // causing duplicate progress. Remove them.
        Log.d(
                tag,
                "cleanLocalMediaProgress: Invalid local media progress does not start with 'local' (fixed on server 2.0.24)"
        )
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI == null) {
        Log.d(tag, "Removing orphaned local media progress")
        Paper.book("localMediaProgress").delete(it.id)
      } else if (matchingLLI.isPodcast) {
        if (it.localEpisodeId.isNullOrEmpty()) {
          Log.d(tag, "cleanLocalMediaProgress: Podcast media progress has no episode id - removing")
          Paper.book("localMediaProgress").delete(it.id)
        } else {
          val podcast = matchingLLI.media as? Podcast
          if (podcast == null) {
            Log.e(tag, "Removing progress for mismatched podcast media type")
            Paper.book("localMediaProgress").delete(it.id)
            return@forEach
          }
          val matchingLEp = podcast.episodes?.find { ep -> ep.id == it.localEpisodeId }
          if (matchingLEp == null) {
            Log.d(tag, "Removing local progress for a missing podcast episode")
            Paper.book("localMediaProgress").delete(it.id)
          }
        }
      }
    }
  }

  fun saveMediaItemHistory(mediaItemHistory: MediaItemHistory) {
    val overflow = mediaItemHistory.events.size - MAX_MEDIA_ITEM_HISTORY_EVENTS
    if (overflow > 0) {
      mediaItemHistory.events.subList(0, overflow).clear()
    }
    val storageKey = mediaItemHistoryStorageKey(
      mediaItemHistory.id,
      mediaItemHistory.serverConnectionConfigId,
      mediaItemHistory.isLocal
    )
    Paper.book("mediaItemHistory").write(storageKey, mediaItemHistory)
    // Migrate a matching v1 row that used the unscoped media ID as its key.
    readSafely<MediaItemHistory>("mediaItemHistory", mediaItemHistory.id)?.let { legacy ->
      if (legacy.serverConnectionConfigId == mediaItemHistory.serverConnectionConfigId &&
        legacy.isLocal == mediaItemHistory.isLocal
      ) {
        Paper.book("mediaItemHistory").delete(mediaItemHistory.id)
      }
    }
  }

  fun getMediaItemHistory(
    id: String,
    connectionId: String? = null,
    isLocal: Boolean = false
  ): MediaItemHistory? {
    if (connectionId != null || isLocal) {
      val scopedKey = mediaItemHistoryStorageKey(id, connectionId, isLocal)
      readSafely<MediaItemHistory>("mediaItemHistory", scopedKey)?.let { return it }
      val legacy = readSafely<MediaItemHistory>("mediaItemHistory", id)
      if (legacy != null && legacy.serverConnectionConfigId == connectionId &&
        legacy.isLocal == isLocal
      ) {
        saveMediaItemHistory(legacy)
        return legacy
      }
      return null
    }
    // Compatibility for diagnostic/UI callers that predate scoped history.
    readSafely<MediaItemHistory>("mediaItemHistory", id)?.let { return it }
    return getAllMediaItemHistory().firstOrNull { it.id == id }
  }
  fun getAllMediaItemHistory(): List<MediaItemHistory> {
    return keysSafely("mediaItemHistory").mapNotNull { id ->
      readSafely<MediaItemHistory>("mediaItemHistory", id)
    }
  }
  fun removeMediaItemHistoryForConnection(connectionId: String) {
    keysSafely("mediaItemHistory").forEach { storageKey ->
      val history = readSafely<MediaItemHistory>("mediaItemHistory", storageKey)
      if (history?.serverConnectionConfigId == connectionId) {
        Paper.book("mediaItemHistory").delete(storageKey)
      }
    }
  }

  private fun mediaItemHistoryStorageKey(
    mediaItemId: String,
    connectionId: String?,
    isLocal: Boolean
  ): String {
    val scope = when {
      connectionId != null -> "server:$connectionId"
      isLocal -> "local"
      else -> "unowned"
    }
    return "v2:${scope.length}:$scope:$mediaItemId"
  }

  fun savePlaybackSession(playbackSession: PlaybackSession) {
    val priorToken = playbackSession.persistenceToken
    playbackSession.persistenceToken = java.util.UUID.randomUUID().toString()
    val storageKey = playbackSessionStorageKey(
      playbackSession.id,
      playbackSession.serverConnectionConfigId,
      playbackSession.isLocal
    )
    try {
      Paper.book("playbackSession").write(storageKey, playbackSession)
    } catch (error: RuntimeException) {
      playbackSession.persistenceToken = priorToken
      throw error
    }
    // Version 1 used only the server-provided session ID. Remove that row only
    // when it belongs to this exact owner; another account with the same ID is
    // independent data.
    readSafely<PlaybackSession>("playbackSession", playbackSession.id)?.let { legacy ->
      if (playbackSessionIdentity(legacy) == playbackSessionIdentity(playbackSession)) {
        try {
          Paper.book("playbackSession").delete(playbackSession.id)
        } catch (error: RuntimeException) {
          Log.e(tag, "Unable to remove migrated legacy checkpoint (${error.javaClass.simpleName})")
        }
      }
    }
  }

  fun removePlaybackSession(
    playbackSessionId: String,
    connectionId: String?,
    isLocal: Boolean = false
  ) {
    Paper.book("playbackSession").delete(
      playbackSessionStorageKey(playbackSessionId, connectionId, isLocal)
    )
    readSafely<PlaybackSession>("playbackSession", playbackSessionId)?.let { legacy ->
      if (playbackSessionIdentity(legacy) ==
        playbackSessionIdentity(playbackSessionId, connectionId, isLocal)
      ) {
        Paper.book("playbackSession").delete(playbackSessionId)
      }
    }
  }

  fun removePlaybackSession(playbackSession: PlaybackSession) {
    removePlaybackSession(
      playbackSession.id,
      playbackSession.serverConnectionConfigId,
      playbackSession.isLocal
    )
  }

  fun getPlaybackSession(
    playbackSessionId: String,
    connectionId: String?,
    isLocal: Boolean = false
  ): PlaybackSession? {
    readSafely<PlaybackSession>(
      "playbackSession",
      playbackSessionStorageKey(playbackSessionId, connectionId, isLocal)
    )?.let { return it }
    return readSafely<PlaybackSession>("playbackSession", playbackSessionId)
      ?.takeIf {
        playbackSessionIdentity(it) ==
          playbackSessionIdentity(playbackSessionId, connectionId, isLocal)
      }
  }

  /** Delete a retry snapshot only if no newer checkpoint replaced it in flight. */
  fun removePlaybackSessionIfUnchanged(expected: PlaybackSession): Boolean {
    return try {
      val current = getPlaybackSession(
        expected.id,
        expected.serverConnectionConfigId,
        expected.isLocal
      ) ?: return false
      val tokenMatches = !expected.persistenceToken.isNullOrBlank() &&
        expected.persistenceToken == current.persistenceToken
      val legacySnapshotMatches = expected.persistenceToken.isNullOrBlank() &&
        current.persistenceToken.isNullOrBlank() &&
        expected.updatedAt == current.updatedAt &&
        expected.timeListening == current.timeListening &&
        expected.currentTime.toBits() == current.currentTime.toBits()
      if (!tokenMatches && !legacySnapshotMatches) return false
      removePlaybackSession(expected)
      true
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to compare-and-delete playback checkpoint (${error.javaClass.simpleName})")
      false
    }
  }

  fun getPlaybackSessions(limit: Int = Int.MAX_VALUE): List<PlaybackSession> {
    if (limit <= 0) return emptyList()
    val bookName = "playbackSession"
    val keys = keysSafely(bookName)
    val sessionsByIdentity = linkedMapOf<String, PlaybackSession>()
    // Read scoped rows first so a crash between the v2 write and legacy delete
    // cannot surface the same checkpoint twice.
    for (storageKey in keys.filter { it.startsWith("v2:") }) {
      if (sessionsByIdentity.size >= limit) break
      readSafely<PlaybackSession>(bookName, storageKey)?.let { session ->
        sessionsByIdentity[playbackSessionIdentity(session)] = session
      }
    }
    for (legacyKey in keys.filterNot { it.startsWith("v2:") }) {
      if (sessionsByIdentity.size >= limit) break
      readSafely<PlaybackSession>(bookName, legacyKey)?.let { session ->
        val identity = playbackSessionIdentity(session)
        if (!sessionsByIdentity.containsKey(identity)) {
          try {
            savePlaybackSession(session)
          } catch (error: RuntimeException) {
            // Return the readable legacy row for best-effort retry even when
            // migration cannot be committed on a low-storage/corrupt device.
            Log.e(tag, "Unable to migrate legacy playback checkpoint (${error.javaClass.simpleName})")
          }
          sessionsByIdentity[identity] = session
        } else {
          try {
            Paper.book(bookName).delete(legacyKey)
          } catch (error: RuntimeException) {
            Log.e(tag, "Unable to remove duplicate legacy checkpoint (${error.javaClass.simpleName})")
          }
        }
      }
    }
    return sessionsByIdentity.values.toList()
  }

  /**
   * Loads a bounded retry batch for currently saved profiles without letting
   * stale owner rows consume that bound. Scoped v2 keys carry their owner in
   * the key, so they can be filtered before Paper deserializes any value. A
   * legacy row is read only for migration and is accepted after validating its
   * embedded owner.
   */
  fun getPlaybackSessionsForConnections(
    connectionIds: Set<String>,
    limit: Int
  ): List<PlaybackSession> {
    if (limit <= 0) return emptyList()
    val allowedIds = connectionIds.filterTo(linkedSetOf()) { it.isNotBlank() }
    if (allowedIds.isEmpty()) return emptyList()

    val bookName = "playbackSession"
    val keys = keysSafely(bookName)
    val allowedPrefixes = allowedIds.map { connectionId ->
      val scope = "server:$connectionId"
      "v2:${scope.length}:$scope:"
    }
    val sessionsByIdentity = linkedMapOf<String, PlaybackSession>()

    for (storageKey in keys.asSequence()
      .filter { key -> key.startsWith("v2:") && allowedPrefixes.any(key::startsWith) }
    ) {
      if (sessionsByIdentity.size >= limit) break
      readSafely<PlaybackSession>(bookName, storageKey)
        ?.takeIf { it.serverConnectionConfigId in allowedIds }
        ?.let { sessionsByIdentity[playbackSessionIdentity(it)] = it }
    }

    if (sessionsByIdentity.size < limit) {
      for (legacyKey in keys.asSequence().filterNot { it.startsWith("v2:") }) {
        if (sessionsByIdentity.size >= limit) break
        val session = readSafely<PlaybackSession>(bookName, legacyKey) ?: continue
        if (session.serverConnectionConfigId !in allowedIds) continue
        val identity = playbackSessionIdentity(session)
        if (!sessionsByIdentity.containsKey(identity)) {
          try {
            savePlaybackSession(session)
          } catch (error: RuntimeException) {
            Log.e(tag, "Unable to migrate legacy playback checkpoint (${error.javaClass.simpleName})")
          }
          sessionsByIdentity[identity] = session
        } else {
          try {
            Paper.book(bookName).delete(legacyKey)
          } catch (error: RuntimeException) {
            Log.e(tag, "Unable to remove duplicate legacy checkpoint (${error.javaClass.simpleName})")
          }
        }
      }
    }
    return sessionsByIdentity.values.toList()
  }

  private fun playbackSessionStorageKey(
    sessionId: String,
    connectionId: String?,
    isLocal: Boolean
  ): String = "v2:${playbackSessionIdentity(sessionId, connectionId, isLocal)}"

  private fun playbackSessionIdentity(session: PlaybackSession): String =
    playbackSessionIdentity(
      session.id,
      session.serverConnectionConfigId,
      session.isLocal
    )

  private fun playbackSessionIdentity(
    sessionId: String,
    connectionId: String?,
    isLocal: Boolean
  ): String {
    val scope = when {
      !connectionId.isNullOrBlank() -> "server:$connectionId"
      isLocal -> "local"
      else -> "unowned"
    }
    return "${scope.length}:$scope:$sessionId"
  }

  @Synchronized
  fun saveLog(log:AbsLog) {
    try {
      val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
      if (log.timestamp < cutoff) return

      if (cachedLogCount == null ||
        logWritesSincePrune >= LOG_PRUNE_INTERVAL ||
        (cachedLogCount ?: 0) >= MAX_LOG_ENTRIES
      ) {
        // Leave one slot for the incoming entry so the on-disk hard cap is
        // never exceeded, even though routine age pruning is periodic.
        cachedLogCount = pruneLogs(MAX_LOG_ENTRIES - 1)
        logWritesSincePrune = 0
      }

      if ((cachedLogCount ?: 0) >= MAX_LOG_ENTRIES) {
        Log.w(tag, "Dropping diagnostic log because the bounded store cannot be pruned")
        return
      }

      Paper.book("log").write(log.id, log)
      cachedLogCount = (cachedLogCount ?: 0) + 1
      logWritesSincePrune++
    } catch (error: RuntimeException) {
      // Logging is diagnostic. A full/corrupt store must never crash media.
      Log.e(tag, "Unable to persist diagnostic log (${error.javaClass.simpleName})")
    }
  }
  fun getAllLogs() : List<AbsLog> {
    val logs:MutableList<AbsLog> = mutableListOf()
    keysSafely("log").forEach { logId ->
      readSafely<AbsLog>("log", logId)?.let {
        logs.add(it)
      }
    }
    return logs.sortedBy { it.timestamp }
  }
  @Synchronized
  fun removeAllLogs() {
    try {
      Paper.book("log").destroy()
      cachedLogCount = 0
      logWritesSincePrune = 0
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to clear diagnostic logs (${error.javaClass.simpleName})")
    }
  }

  @Synchronized
  fun cleanLogs() {
    cachedLogCount = pruneLogs(MAX_LOG_ENTRIES)
    logWritesSincePrune = 0
  }

  /** Returns the number of valid entries retained. Never logs through AbsLogger. */
  private fun pruneLogs(maxEntries: Int): Int {
    val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
    val keys = keysSafely("log")
    val validLogs = mutableListOf<AbsLog>()
    val idsToDelete = mutableSetOf<String>()

    keys.forEach { logId ->
      val log = readSafely<AbsLog>("log", logId)
      if (log == null || log.timestamp < cutoff) {
        idsToDelete += logId
      } else {
        validLogs += log
      }
    }

    val retained = validLogs
      .sortedWith(compareByDescending<AbsLog> { it.timestamp }.thenByDescending { it.id })
      .take(maxEntries.coerceAtLeast(0))
    val retainedIds = retained.mapTo(mutableSetOf()) { it.id }
    validLogs.forEach { if (it.id !in retainedIds) idsToDelete += it.id }

    var deletionFailures = 0
    idsToDelete.forEach { logId ->
      try {
        Paper.book("log").delete(logId)
      } catch (error: RuntimeException) {
        deletionFailures++
        Log.e(tag, "Unable to prune a diagnostic log (${error.javaClass.simpleName})")
      }
    }
    if (idsToDelete.isNotEmpty()) {
      Log.i(tag, "Pruned ${idsToDelete.size} expired, malformed, or excess diagnostic logs")
    }
    return retained.size + deletionFailures
  }
}
