package com.audiobookshelf.app.media

import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.player.PlayerNotificationService

object MediaEventManager {
  const val tag = "MediaEventManager"

  var clientEventEmitter: PlayerNotificationService.ClientEventEmitter? = null

  fun playEvent(playbackSession: PlaybackSession, lease: ConnectionLease? = null) {
    Log.i(tag, "Play event")
    addPlaybackEvent("Play", playbackSession, null, lease)
  }

  fun pauseEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    lease: ConnectionLease? = null
  ) {
    Log.i(tag, "Pause event")
    addPlaybackEvent("Pause", playbackSession, syncResult, lease)
  }

  fun stopEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    lease: ConnectionLease? = null
  ) {
    Log.i(tag, "Stop event")
    addPlaybackEvent("Stop", playbackSession, syncResult, lease)
  }

  fun saveEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    lease: ConnectionLease? = null
  ) {
    Log.i(tag, "Save event")
    addPlaybackEvent("Save", playbackSession, syncResult, lease)
  }

  fun finishedEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    lease: ConnectionLease? = null
  ) {
    Log.i(tag, "Finished event")
    addPlaybackEvent("Finished", playbackSession, syncResult, lease)
  }

  fun seekEvent(
    playbackSession: PlaybackSession,
    syncResult: SyncResult?,
    lease: ConnectionLease? = null
  ) {
    Log.i(tag, "Seek event")
    addPlaybackEvent("Seek", playbackSession, syncResult, lease)
  }

  fun syncEvent(
    mediaProgress: MediaProgressWrapper,
    description: String,
    lease: ConnectionLease? = null
  ) {
    Log.i(tag, "Sync event")
    addSyncEvent("Sync", mediaProgress, description, lease)
  }

  private fun addSyncEvent(
          eventName: String,
          mediaProgress: MediaProgressWrapper,
          description: String,
          lease: ConnectionLease?
  ) {
    try {
      val mediaItemHistory = synchronized(DeviceManager.connectionPersistenceMonitor) {
        if (lease == null || !DeviceManager.isConnectionLeaseCurrent(lease)) {
          Log.i(tag, "Ignoring sync history from an expired account lifecycle")
          return
        }
        val history = DeviceManager.dbManager.getMediaItemHistory(
          mediaProgress.mediaItemId,
          lease.connectionId,
          false
        )
        if (history == null || history.serverConnectionConfigId != lease.connectionId) {
          Log.w(tag, "Sync event history was unavailable for the current account")
          return
        }

        val mediaItemEvent = MediaItemEvent(
          name = eventName,
          type = "Sync",
          description = description,
          currentTime = mediaProgress.currentTime,
          serverSyncAttempted = false,
          serverSyncSuccess = null,
          serverSyncMessage = null,
          timestamp = System.currentTimeMillis()
        )
        history.events.add(mediaItemEvent)
        DeviceManager.dbManager.saveMediaItemHistory(history)
        history
      }

      clientEventEmitter?.onMediaItemHistoryUpdated(mediaItemHistory)
    } catch (error: Exception) {
      // Play/seek events originate in Exo callbacks. Storage corruption or a
      // consumer failure must never escape Player.Listener and crash AAOS.
      Log.e(tag, "Unable to record sync history (${error.javaClass.simpleName})")
    }
  }

  private fun addPlaybackEvent(
          eventName: String,
          playbackSession: PlaybackSession,
          syncResult: SyncResult?,
          lease: ConnectionLease?
  ) {
    try {
      val mediaItemHistory = synchronized(DeviceManager.connectionPersistenceMonitor) {
        val durableSession = playbackSession.copySanitizedForPersistence()
        if (!durableSession.isLocal && (
            lease == null ||
              durableSession.serverConnectionConfigId != lease.connectionId ||
              !DeviceManager.isConnectionLeaseCurrent(lease)
          )) {
          Log.i(tag, "Ignoring playback history from an expired account lifecycle")
          return
        }
        val history = getMediaItemHistoryForSession(durableSession)
          ?: createMediaItemHistoryForSession(durableSession)
        if (durableSession.isLocal && durableSession.serverConnectionConfigId == null) {
          history.serverConnectionConfigId = null
          history.serverAddress = null
          history.serverUserId = null
        }

        val mediaItemEvent = MediaItemEvent(
          name = eventName,
          type = "Playback",
          description = "",
          currentTime = durableSession.currentTime,
          serverSyncAttempted = syncResult?.serverSyncAttempted ?: false,
          serverSyncSuccess = syncResult?.serverSyncSuccess,
          serverSyncMessage = syncResult?.serverSyncMessage,
          timestamp = System.currentTimeMillis()
        )
        history.events.add(mediaItemEvent)
        DeviceManager.dbManager.saveMediaItemHistory(history)
        history
      }

      clientEventEmitter?.onMediaItemHistoryUpdated(mediaItemHistory)
    } catch (error: Exception) {
      Log.e(tag, "Unable to record playback history (${error.javaClass.simpleName})")
    }
  }

  private fun getMediaItemHistoryForSession(session: PlaybackSession): MediaItemHistory? =
    DeviceManager.dbManager.getMediaItemHistory(
      session.mediaItemId,
      session.serverConnectionConfigId,
      session.isLocal && session.serverConnectionConfigId == null
    )

  private fun createMediaItemHistoryForSession(playbackSession: PlaybackSession): MediaItemHistory {
    Log.i(tag, "Creating media item history")
    val libraryItemId = playbackSession.libraryItemId ?: ""
    val episodeId: String? = playbackSession.episodeId
    return MediaItemHistory(
            id = playbackSession.mediaItemId,
            mediaDisplayTitle = playbackSession.displayTitle ?: "Unset",
            libraryItemId,
            episodeId,
            playbackSession.isLocal,
            playbackSession.serverConnectionConfigId,
            playbackSession.serverAddress,
            playbackSession.userId,
            createdAt = System.currentTimeMillis(),
            events = mutableListOf()
    )
  }
}
