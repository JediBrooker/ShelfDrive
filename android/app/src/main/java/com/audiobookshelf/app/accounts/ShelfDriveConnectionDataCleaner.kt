package com.audiobookshelf.app.accounts

import android.content.Context
import android.app.DownloadManager
import android.util.Log
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.downloads.OfflineDownloadPlanner
import com.audiobookshelf.app.downloads.OfflineDownloadCoordinator
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.managers.InternalDownloadManager
import com.audiobookshelf.app.player.PlayerNotificationService

/** Durable operations are injectable so commit failures can be verified atomically. */
internal interface ConnectionDataPersistence {
  fun initialize(context: Context)
  fun commitDeviceData(deviceData: DeviceData)
  fun removeAncillaryConnectionData(connectionId: String)
}

private object DeviceManagerConnectionDataPersistence : ConnectionDataPersistence {
  private lateinit var appContext: Context

  override fun initialize(context: Context) {
    appContext = context.applicationContext
    DbManager.initialize(appContext)
  }

  override fun commitDeviceData(deviceData: DeviceData) {
    DeviceManager.dbManager.saveDeviceData(deviceData)
  }

  override fun removeAncillaryConnectionData(connectionId: String) {
    DeviceManager.dbManager.getPlaybackSessions()
      .filter { it.serverConnectionConfigId == connectionId }
      .forEach(DeviceManager.dbManager::removePlaybackSession)
    DeviceManager.dbManager.removeMediaItemHistoryForConnection(connectionId)

    // Finished downloads remain playable, but Disconnect severs every durable
    // server/user/library linkage so they become device-only media.
    DeviceManager.dbManager.getLocalLibraryItems()
      .filter { it.serverConnectionConfigId == connectionId }
      .map { it.copyWithoutServerIdentity() }
      .forEach(DeviceManager.dbManager::saveLocalLibraryItem)
    DeviceManager.dbManager.getAllLocalMediaProgress()
      .filter { it.serverConnectionConfigId == connectionId }
      .forEach { progress ->
        progress.serverConnectionConfigId = null
        progress.serverAddress = null
        progress.serverUserId = null
        progress.libraryItemId = null
        progress.episodeId = null
        DeviceManager.dbManager.saveLocalMediaProgress(progress)
      }

    // Pending rows can contain bearer-authenticated platform requests or
    // legacy token-bearing URLs. Retain the purge tombstone and ownership row
    // until cancellation is confirmed; an orphan must never outlive Logout.
    val internalCanceled = InternalDownloadManager.cancelForConnection(connectionId)
    val managedCanceled = OfflineDownloadCoordinator(appContext)
      .removeManagedJobsForConnection(connectionId)
    val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    var legacyCanceled = true
    DeviceManager.dbManager.getDownloadItems()
      .filter { it.serverConnectionConfigId == connectionId }
      .filterNot(OfflineDownloadPlanner::isManagedJob)
      .forEach { item ->
        var itemCanceled = true
        item.downloadItemParts.forEach { part ->
          if (!part.isInternalStorage) {
            part.downloadId?.takeIf { it > 0L }?.let { id ->
              if (downloadManager == null || !removePlatformRow(downloadManager, id)) {
                itemCanceled = false
              }
            }
          }
        }
        if (itemCanceled) {
          DeviceManager.dbManager.removeDownloadItem(item.id)
        } else {
          legacyCanceled = false
        }
      }
    if (!internalCanceled || !managedCanceled || !legacyCanceled) {
      throw IllegalStateException("Pending download cancellation will be retried")
    }
  }

  private fun removePlatformRow(downloadManager: DownloadManager, id: Long): Boolean {
    val removed = runCatching { downloadManager.remove(id) }.getOrNull()
    if (removed != null && removed > 0) return true
    val cursor = runCatching {
      downloadManager.query(DownloadManager.Query().setFilterById(id))
    }.getOrNull() ?: return false
    return cursor.use { !it.moveToFirst() }
  }
}

/** Removes app-private authentication state when Android removes an account. */
internal class ShelfDriveConnectionDataCleaner(
  context: Context,
  private val secureStorage: RefreshTokenStorage = SecureStorage(context.applicationContext),
  private val persistence: ConnectionDataPersistence = DeviceManagerConnectionDataPersistence
) : ConnectionDataCleaner {
  private val appContext = context.applicationContext

  @Synchronized
  override fun removeConnectionData(connectionId: String): Boolean {
    if (connectionId.isBlank()) return false
    return try {
      persistence.initialize(appContext)
      val cleaned = synchronized(DeviceManager.connectionPersistenceMonitor) {
        if (!markPendingPurge(appContext, connectionId)) return@synchronized false
        val priorRefreshToken = secureStorage.getRefreshToken(connectionId)
        if (!secureStorage.removeRefreshToken(connectionId)) {
          clearPendingPurge(appContext, connectionId)
          return@synchronized false
        }

        // Build a detached candidate without mutating the process-visible
        // DeviceData object. If the durable write fails, playback and browse
        // callers continue to see exactly the previously committed profile.
        val candidate = synchronized(DeviceManager.connectionStateMonitor) {
          buildCleanupCandidate(
            current = DeviceManager.deviceData,
            active = DeviceManager.serverConnectionConfig,
            connectionId = connectionId
          )
        }

        try {
          persistence.commitDeviceData(candidate.deviceData)
        } catch (error: RuntimeException) {
          if (priorRefreshToken != null &&
            !secureStorage.storeRefreshToken(connectionId, priorRefreshToken)
          ) {
            Log.e(TAG, "Unable to restore credential after profile commit failure")
          }
          clearPendingPurge(appContext, connectionId)
          Log.e(TAG, "Unable to commit ShelfDrive profile removal", error)
          return@synchronized false
        }

        synchronized(DeviceManager.connectionStateMonitor) {
          DeviceManager.advanceConnectionStateEpoch()
          DeviceManager.deviceData = candidate.deviceData

          // The active selection can legitimately change while the durable
          // write is in progress. Preserve a still-saved live selection; only
          // replace it when it points at the profile being removed.
          val activeNow = DeviceManager.serverConnectionConfig
          val nextActive = when {
            activeNow?.id == connectionId -> candidate.fallbackActive
            candidate.deviceData.serverConnectionConfigs.any { it === activeNow } -> activeNow
            else -> candidate.fallbackActive
          }
          DeviceManager.serverConnectionConfig = nextActive
        }

        // Ancillary checkpoints and history are not the source of account
        // truth. Purge them only after DeviceData is durable, so an exception
        // can never delete rows while leaving a profile that reappears after
        // process restart.
        try {
          persistence.removeAncillaryConnectionData(connectionId)
          clearPendingPurge(appContext, connectionId)
        } catch (error: RuntimeException) {
          // Keep the durable tombstone. Service startup retries this purge even
          // though the profile itself is already gone and no longer appears in
          // Settings.
          Log.e(TAG, "Deferring removed profile's ancillary purge", error)
        }
        true
      }
      if (cleaned) PlayerNotificationService.requestBrowseRefresh("account removed")
      cleaned
    } catch (error: RuntimeException) {
      Log.e(TAG, "Unable to remove ShelfDrive connection data", error)
      false
    }
  }

  private fun buildCleanupCandidate(
    current: DeviceData,
    active: ServerConnectionConfig?,
    connectionId: String
  ): CleanupCandidate {
    val retainedConfigs = current.serverConnectionConfigs
      .filterNot { it.id == connectionId }
      .toMutableList()
    val fallback = retainedConfigs.firstOrNull {
      it.token.isNotBlank() && DeviceManager.isServerAddressAllowed(it.address)
    }
    val nextLastConnectionId = if (current.lastServerConnectionConfigId == connectionId) {
      fallback?.id
    } else {
      current.lastServerConnectionConfigId
    }
    val nextPlaybackSession = current.lastPlaybackSession
      ?.takeUnless { it.serverConnectionConfigId == connectionId }
    val nextActive = active
      ?.takeUnless { it.id == connectionId }
      ?.takeIf { selected -> retainedConfigs.any { it === selected } }
      ?: fallback

    return CleanupCandidate(
      deviceData = DeviceData(
        serverConnectionConfigs = retainedConfigs,
        lastServerConnectionConfigId = nextLastConnectionId,
        deviceSettings = current.deviceSettings,
        lastPlaybackSession = nextPlaybackSession
      ),
      fallbackActive = nextActive
    )
  }

  private data class CleanupCandidate(
    val deviceData: DeviceData,
    val fallbackActive: ServerConnectionConfig?
  )

  companion object {
    private const val TAG = "ShelfDriveAccounts"
    private const val PURGE_PREFS = "ShelfDrivePendingConnectionPurges"
    private const val PURGE_IDS = "connection_ids"

    private fun markPendingPurge(context: Context, connectionId: String): Boolean {
      val prefs = context.getSharedPreferences(PURGE_PREFS, Context.MODE_PRIVATE)
      val ids = prefs.getStringSet(PURGE_IDS, emptySet()).orEmpty().toMutableSet()
      ids += connectionId
      return prefs.edit().putStringSet(PURGE_IDS, ids).commit()
    }

    private fun clearPendingPurge(context: Context, connectionId: String): Boolean {
      val prefs = context.getSharedPreferences(PURGE_PREFS, Context.MODE_PRIVATE)
      val ids = prefs.getStringSet(PURGE_IDS, emptySet()).orEmpty().toMutableSet()
      ids -= connectionId
      return prefs.edit().putStringSet(PURGE_IDS, ids).commit()
    }
    /** Completes a user-authorized disconnect interrupted after its durable commit. */
    fun retryPendingPurges(context: Context) {
      val appContext = context.applicationContext
      val prefs = appContext.getSharedPreferences(PURGE_PREFS, Context.MODE_PRIVATE)
      val pending = prefs.getStringSet(PURGE_IDS, emptySet()).orEmpty().toList()
      pending.forEach { connectionId ->
        synchronized(DeviceManager.connectionPersistenceMonitor) {
          if (DeviceManager.getServerConnectionConfig(connectionId) != null) {
            ShelfDriveConnectionDataCleaner(appContext).removeConnectionData(connectionId)
            return@synchronized
          }
          try {
            DeviceManagerConnectionDataPersistence.initialize(appContext)
            DeviceManagerConnectionDataPersistence.removeAncillaryConnectionData(connectionId)
            clearPendingPurge(appContext, connectionId)
          } catch (error: RuntimeException) {
            Log.e(TAG, "Pending connection purge remains deferred", error)
          }
        }
      }
    }
  }
}
