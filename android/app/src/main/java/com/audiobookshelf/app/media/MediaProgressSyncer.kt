package com.audiobookshelf.app.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler

data class MediaProgressSyncData(
        var timeListened: Long, // seconds
        var duration: Double, // seconds
        var currentTime: Double // seconds
)

data class SyncResult(
        var serverSyncAttempted: Boolean,
        var serverSyncSuccess: Boolean?,
        var serverSyncMessage: String?
)

class MediaProgressSyncer(
        val playerNotificationService: PlayerNotificationService,
        private val apiHandler: ApiHandler,
        private val monotonicNowMs: () -> Long = SystemClock::elapsedRealtime
) {
  private val tag = "MediaProgressSync"
  private val METERED_CONNECTION_SYNC_INTERVAL = 60000

  private val mainHandler = Handler(Looper.getMainLooper())
  private val stateLock = Any()
  private val syncQueueLock = Any()
  private val syncExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-progress-sync")
  }
  private data class PendingSyncRequest(
    val snapshot: SessionSnapshot,
    val shouldSyncServer: Boolean,
    val currentTime: Double,
    val cutoffElapsedMs: Long,
    val allowDetachedCompletion: Boolean,
    val callback: (SyncResult?) -> Unit
  )
  private val pendingSyncRequests = java.util.ArrayDeque<PendingSyncRequest>()
  private var activeSyncRequest: PendingSyncRequest? = null
  private var syncRequestInFlight = false
  @Volatile private var shutdown = false
  @Volatile var listeningTimerRunning: Boolean = false
    private set

  private var sessionGeneration = 0L
  private var currentSourcePlaybackSession: PlaybackSession? = null
  private data class ListeningLedger(
    val baseTimeListening: Long,
    var cumulativeLocalSeconds: Long = 0L,
    var localRemainderMs: Long = 0L,
    var pendingServerMs: Long = 0L
  )
  private data class GenerationAccounting(
    var accountedThroughElapsedMs: Long,
    val ledger: ListeningLedger
  )
  private data class AccountingAmounts(
    val localSeconds: Long,
    val serverSeconds: Long,
    val preparedServerMs: Long
  )
  /**
   * Each generation owns an active-time clock, while pause/resume generations
   * for the exact same session share their unacknowledged server debt. This
   * excludes paused wall time without dropping a failed terminal sync.
   */
  private val generationAccounting = mutableMapOf<Long, GenerationAccounting>()
  private var currentListeningLedger: ListeningLedger? = null
  private var bufferingAccountingSuspended = false
  private var bufferingSuspendedAtElapsedMs = 0L

  private data class SessionSnapshot(
          val generation: Long,
          val playbackSession: PlaybackSession,
          val lastSyncTime: Long
  ) {
    val connectionLease: ConnectionLease?
      get() = playbackSession.connectionLease
    val displayTitle: String
      get() = playbackSession.displayTitle ?: "Unset"
    val sessionId: String
      get() = playbackSession.id
  }

  private val listeningTick = object : Runnable {
    override fun run() {
      if (shutdown || !listeningTimerRunning || !playerNotificationService.isServiceAlive()) {
        return
      }
      try {
        if (playerNotificationService.currentPlayer.isPlaying) {
          // Set auto sleep timer if enabled and within start/end time
          playerNotificationService.sleepTimerManager.checkAutoSleepTimer()

          // Only sync with server on unmetered connection every 15s OR sync with server if
          // last sync time is >= 60s
          val shouldSyncServer =
                  PlayerNotificationService.isUnmeteredNetwork ||
                          monotonicNowMs() - lastSyncTime >=
                                  METERED_CONNECTION_SYNC_INTERVAL

          val currentTime = playerNotificationService.getCurrentTimeSeconds()
          if (currentTime > 0) {
            val snapshot = currentSessionSnapshot() ?: return
            syncSnapshot(snapshot, shouldSyncServer, currentTime) { syncResult ->
              if (shutdown ||
                              !playerNotificationService.isServiceAlive() ||
                              !isCurrent(snapshot)
              ) return@syncSnapshot
              Log.d(tag, "Sync complete")
              MediaEventManager.saveEvent(
                snapshot.playbackSession,
                syncResult,
                snapshot.connectionLease
              )
            }
          }
        }
      } catch (error: Exception) {
        Log.e(tag, "Listening tick failed", error)
      } finally {
        scheduleNextListeningTickIfActive()
      }
    }
  }

  @Volatile private var lastSyncTime: Long = 0
  @Volatile private var failedSyncs: Int = 0

  @Volatile var currentPlaybackSession: PlaybackSession? = null // copy of pb session currently syncing
  @Volatile var currentLocalMediaProgress: LocalMediaProgress? = null

  private val currentDisplayTitle
    get() = currentPlaybackSession?.displayTitle ?: "Unset"
  val currentIsLocal
    get() = currentPlaybackSession?.isLocal == true
  val currentSessionId
    get() = currentPlaybackSession?.id ?: ""

  fun start(playbackSession: PlaybackSession) {
    if (shutdown || !canUseSession(playbackSession)) return
    var priorGenerationToDiscard: Long? = null
    synchronized(stateLock) {
      if (shutdown) return
      val priorGeneration = sessionGeneration
      val sameSourceSession = playbackSession === currentSourcePlaybackSession
      val priorSyncSession = currentPlaybackSession
      if (listeningTimerRunning) {
        Log.d(tag, "start: Timer already running for $currentDisplayTitle")
        if (playbackSession !== currentSourcePlaybackSession) {
          Log.d(tag, "Playback session changed, reset timer")
          currentLocalMediaProgress = null
          mainHandler.removeCallbacks(listeningTick)
          listeningTimerRunning = false
          lastSyncTime = 0L
          Log.d(tag, "start: Set last sync time 0 $lastSyncTime")
          failedSyncs = 0
        } else {
          return
        }
      } else if (playbackSession !== currentSourcePlaybackSession) {
        currentLocalMediaProgress = null
      }

      // A fresh generation prevents an older pause/stop response from mutating this session.
      sessionGeneration++
      listeningTimerRunning = true
      lastSyncTime = monotonicNowMs().coerceAtLeast(1L)
      bufferingAccountingSuspended = false
      bufferingSuspendedAtElapsedMs = 0L
      val listeningLedger = if (sameSourceSession) {
        currentListeningLedger ?: ListeningLedger(
          playbackSession.timeListening.coerceAtLeast(0L)
        )
      } else {
        ListeningLedger(playbackSession.timeListening.coerceAtLeast(0L))
      }
      currentListeningLedger = listeningLedger
      generationAccounting[sessionGeneration] = GenerationAccounting(
        accountedThroughElapsedMs = lastSyncTime,
        ledger = listeningLedger
      )
      currentSourcePlaybackSession = playbackSession
      currentPlaybackSession = if (sameSourceSession && priorSyncSession != null) {
        priorSyncSession.clone()
      } else {
        playbackSession.clone()
      }
      Log.d(
              tag,
              "start: init last sync time $lastSyncTime with playback session id=${currentPlaybackSession?.id}"
      )

      mainHandler.removeCallbacks(listeningTick)
      mainHandler.postDelayed(listeningTick, LISTENING_TICK_MS)
      priorGenerationToDiscard = priorGeneration
    }
    priorGenerationToDiscard?.let(::discardDetachedGenerationIfDrained)
  }

  fun play(playbackSession: PlaybackSession) {
    if (!canUseSession(playbackSession)) return
    Log.d(tag, "play ${playbackSession.displayTitle}")
    start(playbackSession)
    recordEventAsync {
      MediaEventManager.playEvent(playbackSession, playbackSession.connectionLease)
    }
  }

  /**
   * Stops the active-time clock while ExoPlayer is stalled with playWhenReady.
   * Buffering is not a user pause, so it neither emits a Pause event nor forces
   * a server request. The same-source ledger is shared across generations, so
   * an immediate resume cannot lose the queued pre-buffer listening interval.
   */
  fun suspendForBuffering(currentTime: Double = playerNotificationService.getCurrentTimeSeconds()): Boolean {
    val suspension = synchronized(stateLock) {
      val snapshot = currentPlaybackSession?.let {
        SessionSnapshot(sessionGeneration, it, lastSyncTime)
      }
      if (shutdown || !listeningTimerRunning || snapshot == null ||
        !currentTime.isFinite() || currentTime < 0.0
      ) {
        null
      } else {
        val cutoffElapsedMs = monotonicNowMs().coerceAtLeast(1L)
        mainHandler.removeCallbacks(listeningTick)
        listeningTimerRunning = false
        bufferingAccountingSuspended = true
        bufferingSuspendedAtElapsedMs = cutoffElapsedMs
        snapshot to cutoffElapsedMs
      }
    } ?: return false

    syncSnapshot(
      suspension.first,
      shouldSyncServer = false,
      currentTime = currentTime,
      allowDetachedCompletion = true,
      cutoffElapsedMs = suspension.second
    ) {
      Log.d(tag, "Buffering checkpoint persisted")
    }
    return true
  }

  /** Restarts only the active-time clock after a transient buffering stall. */
  fun resumeAfterBuffering(playbackSession: PlaybackSession): Boolean {
    val shouldResume = synchronized(stateLock) {
      if (shutdown || !bufferingAccountingSuspended ||
        playbackSession !== currentSourcePlaybackSession
      ) {
        false
      } else {
        bufferingAccountingSuspended = false
        bufferingSuspendedAtElapsedMs = 0L
        true
      }
    }
    if (!shouldResume) return false
    start(playbackSession)
    return listeningTimerRunning
  }

  val isSuspendedForBuffering: Boolean
    get() = synchronized(stateLock) { bufferingAccountingSuspended }

  fun stop(shouldSync: Boolean? = true, cb: () -> Unit) {
    val snapshot = currentSessionSnapshot()
    if ((!listeningTimerRunning && !isSuspendedForBuffering) || snapshot == null) {
      reset()
      return cb()
    }

    cancelListeningTimer()
    Log.d(tag, "stop: Stopping listening for ${snapshot.displayTitle}")

    val currentTime =
            if (shouldSync == true) playerNotificationService.getCurrentTimeSeconds() else 0.0
    if (currentTime > 0) { // Current time should always be > 0 on stop
      syncSnapshot(snapshot, true, currentTime, allowDetachedCompletion = true) { syncResult ->
        completePlaybackAction(
                event = {
                  MediaEventManager.stopEvent(
                    snapshot.playbackSession,
                    syncResult,
                    snapshot.connectionLease
                  )
                },
                cleanup = { resetIfCurrent(snapshot) },
                cb = cb
        )
      }
    } else {
      completePlaybackAction(
              event = {
                MediaEventManager.stopEvent(
                  snapshot.playbackSession,
                  null,
                  snapshot.connectionLease
                )
              },
              cleanup = { resetIfCurrent(snapshot) },
              cb = cb
      )
    }
  }

  fun pause(cb: () -> Unit) {
    val snapshot = currentSessionSnapshot()
    if ((!listeningTimerRunning && !isSuspendedForBuffering) || snapshot == null) return cb()

    cancelListeningTimer()
    Log.d(tag, "pause: Pausing progress syncer for ${snapshot.displayTitle}")
    Log.d(tag, "pause: Last sync time ${snapshot.lastSyncTime}")

    val currentTime = playerNotificationService.getCurrentTimeSeconds()
    if (currentTime > 0) { // Current time should always be > 0 on pause
      syncSnapshot(snapshot, true, currentTime, allowDetachedCompletion = true) { syncResult ->
        clearSyncCountersIfCurrent(snapshot)
        completePlaybackAction(
                event = {
                  MediaEventManager.pauseEvent(
                    snapshot.playbackSession,
                    syncResult,
                    snapshot.connectionLease
                  )
                },
                cb = cb
        )
      }
    } else {
      clearSyncCountersIfCurrent(snapshot)
      completePlaybackAction(
              event = {
                MediaEventManager.pauseEvent(
                  snapshot.playbackSession,
                  null,
                  snapshot.connectionLease
                )
              },
              cb = cb
      )
    }
  }

  fun finished(cb: (SyncResult?) -> Unit) {
    val snapshot = currentSessionSnapshot()
    if ((!listeningTimerRunning && !isSuspendedForBuffering) || snapshot == null) return cb(null)

    cancelListeningTimer()
    Log.d(tag, "finished: Stopping listening for ${snapshot.displayTitle}")

    syncSnapshot(
      snapshot,
      true,
      snapshot.playbackSession.duration,
      allowDetachedCompletion = true
    ) { syncResult ->
      completePlaybackAction(
              event = {
                MediaEventManager.finishedEvent(
                  snapshot.playbackSession,
                  syncResult,
                  snapshot.connectionLease
                )
              },
              cleanup = { resetIfCurrent(snapshot) },
              cb = { cb(syncResult) }
      )
    }
  }

  fun seek() {
    val playbackSession = currentSessionSnapshot()?.playbackSession
    if (playbackSession == null) {
      Log.e(tag, "seek: Playback session not set")
      return
    }

    playbackSession.currentTime = playerNotificationService.getCurrentTimeSeconds()
    Log.d(tag, "seek: ${playbackSession.displayTitle}, currentTime=${playbackSession.currentTime}")
    recordEventAsync {
      MediaEventManager.seekEvent(playbackSession, null, playbackSession.connectionLease)
    }
  }

  private fun recordEventAsync(event: () -> Unit) {
    if (shutdown) return
    try {
      syncExecutor.execute {
        if (shutdown) return@execute
        try {
          event()
        } catch (error: Exception) {
          Log.e(tag, "Playback event failed (${error.javaClass.simpleName})")
        }
      }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      Log.i(tag, "Playback event ignored after progress shutdown")
    }
  }

  // Currently unused
  fun syncFromServerProgress(mediaProgress: MediaProgress) {
    if (shutdown || !mediaProgress.currentTime.isFinite() || !mediaProgress.progress.isFinite()) {
      Log.e(tag, "Ignoring invalid or late server progress")
      return
    }

    val snapshot = currentSessionSnapshot() ?: return
    synchronized(stateLock) {
      if (!isCurrentLocked(snapshot)) return
      snapshot.playbackSession.updatedAt = mediaProgress.lastUpdate
      snapshot.playbackSession.currentTime = mediaProgress.currentTime
    }

    if (!playerNotificationService.isServiceAlive()) return
    MediaEventManager.syncEvent(
            mediaProgress,
            "Received from server get media progress request while playback session open",
            snapshot.connectionLease
    )
    saveLocalProgress(snapshot)
  }

  fun sync(shouldSyncServer: Boolean, currentTime: Double, cb: (SyncResult?) -> Unit) {
    if (shutdown) return cb(null)
    val snapshot = currentSessionSnapshot()
    if (snapshot == null) {
      Log.e(tag, "Playback session is not set")
      return cb(null)
    }
    syncSnapshot(snapshot, shouldSyncServer, currentTime, cb = cb)
  }

  private fun syncSnapshot(
          snapshot: SessionSnapshot,
          shouldSyncServer: Boolean,
          currentTime: Double,
          allowDetachedCompletion: Boolean = false,
          cutoffElapsedMs: Long? = null,
          cb: (SyncResult?) -> Unit
  ) {
    if (shutdown) return cb(null)
    val capturedCutoffElapsedMs = cutoffElapsedMs ?: synchronized(stateLock) {
      if (bufferingAccountingSuspended && bufferingSuspendedAtElapsedMs > 0L) {
        bufferingSuspendedAtElapsedMs
      } else {
        monotonicNowMs().coerceAtLeast(1L)
      }
    }
    val shouldStart = synchronized(syncQueueLock) {
      pendingSyncRequests.addLast(
        PendingSyncRequest(
          snapshot,
          shouldSyncServer,
          currentTime,
          capturedCutoffElapsedMs,
          allowDetachedCompletion,
          cb
        )
      )
      if (syncRequestInFlight) {
        false
      } else {
        syncRequestInFlight = true
        true
      }
    }
    if (shouldStart) runNextSyncRequest()
  }

  /**
   * Serialize progress transports. Periodic, Pause, Stop, and Finished can be
   * requested together; allowing overlap double-counts the same listening
   * interval and lets an older success delete a newer failed checkpoint.
   */
  private fun runNextSyncRequest() {
    val request = synchronized(syncQueueLock) {
      pendingSyncRequests.pollFirst().also { next ->
        if (next == null) {
          syncRequestInFlight = false
        } else {
          activeSyncRequest = next
        }
      }
    } ?: return

    val executionSnapshot = synchronized(stateLock) {
      if (isCurrentLocked(request.snapshot)) {
        SessionSnapshot(
          request.snapshot.generation,
          request.snapshot.playbackSession,
          lastSyncTime
        )
      } else {
        request.snapshot
      }
    }
    try {
      syncExecutor.execute {
        try {
          performSyncSnapshot(
            executionSnapshot,
            request.shouldSyncServer,
            request.currentTime,
            request.cutoffElapsedMs,
            request.allowDetachedCompletion
          ) { result ->
            completeSyncRequest(request, result)
          }
        } catch (error: Exception) {
          // Persistence and connectivity providers are outside our control.
          // Never let one failure terminate an Exo/main callback or wedge the
          // serialized queue before its completion is invoked.
          Log.e(tag, "Progress sync failed (${error.javaClass.simpleName})")
          completeSyncRequest(request, null)
        }
      }
    } catch (error: java.util.concurrent.RejectedExecutionException) {
      Log.i(tag, "Progress sync executor is already stopped")
      completeSyncRequest(request, null)
    }
  }

  /** Completes an active request once, then advances the serialized queue. */
  private fun completeSyncRequest(request: PendingSyncRequest, result: SyncResult?) {
    val accepted = synchronized(syncQueueLock) {
      if (activeSyncRequest !== request) {
        false
      } else {
        activeSyncRequest = null
        if (pendingSyncRequests.isEmpty()) syncRequestInFlight = false
        true
      }
    }
    if (!accepted) return

    try {
      request.callback(result)
    } catch (error: Exception) {
      // Network completions run on an OkHttp dispatcher. A consumer callback
      // must not terminate the AAOS process or strand later queued syncs.
      Log.e(tag, "Progress callback failed (${error.javaClass.simpleName})")
    } finally {
      discardDetachedGenerationIfDrained(request.snapshot.generation)
      runNextSyncRequest()
    }
  }

  private fun performSyncSnapshot(
          snapshot: SessionSnapshot,
          shouldSyncServer: Boolean,
          currentTime: Double,
          syncCutoffElapsedMs: Long,
          allowDetachedCompletion: Boolean,
          cb: (SyncResult?) -> Unit
  ) {
    if (shutdown) return cb(null)
    if (!currentTime.isFinite()) {
      Log.e(tag, "Current playback time is not finite: $currentTime")
      return cb(null)
    }
    if (!allowDetachedCompletion && !isCurrent(snapshot)) {
      Log.d(tag, "Ignoring sync for stale playback session ${snapshot.sessionId}")
      return cb(null)
    }
    if (snapshot.lastSyncTime <= 0) {
      Log.e(tag, "Last sync time is not set ${snapshot.lastSyncTime}")
      return cb(null)
    }

    val playbackSession = snapshot.playbackSession
    val duration = playbackSession.duration
    val totalDuration = playbackSession.getTotalDuration()
    if (!duration.isFinite() || duration <= 0.0 ||
                    !totalDuration.isFinite() || totalDuration <= 0.0
    ) {
      Log.e(tag, "Playback duration is not finite: duration=$duration total=$totalDuration")
      return cb(null)
    }

    val accounting = synchronized(stateLock) {
      accountListeningThroughLocked(
        snapshot,
        duration,
        currentTime,
        syncCutoffElapsedMs,
        allowDetachedCompletion
      )
    }
    if (accounting == null) return cb(null)
    val syncData = MediaProgressSyncData(accounting.serverSeconds, duration, currentTime)

    if (!playbackSession.currentTime.isFinite() || !playbackSession.progress.isFinite()) {
      Log.e(
              tag,
              "Current Playback Session invalid progress ${playbackSession.progress} | Current Time: ${playbackSession.currentTime} | Duration: ${playbackSession.getTotalDuration()}"
      )
      return cb(null)
    }

    val hasNetworkConnection = DeviceManager.checkConnectivity(playerNotificationService)

    // Serialize queued-session persistence with account removal. Local media
    // keeps its device-only progress after disconnect, but neither local nor
    // remote playback may recreate a server-linked sync row for a removed
    // profile.
    val ownerConnectionIsSaved = synchronized(DeviceManager.connectionPersistenceMonitor) {
      val lease = snapshot.connectionLease
      val saved = lease != null &&
        playbackSession.serverConnectionConfigId == lease.connectionId &&
        DeviceManager.isConnectionLeaseCurrent(lease)
      if (saved) DeviceManager.dbManager.savePlaybackSession(playbackSession)
      saved
    }
    if (!playbackSession.isLocal && !ownerConnectionIsSaved) {
      resetIfCurrent(snapshot)
      cb(null)
      return
    }

    if (playbackSession.isLocal) {
      // Save local progress sync
      saveLocalProgress(snapshot, allowDetachedCompletion)

      Log.d(
              tag,
              "Sync local device current serverConnectionConfigId=${DeviceManager.serverConnectionConfig?.id}"
      )
      AbsLogger.info("MediaProgressSyncer", "sync: Saved local progress")

      // Local library item is linked to a server library item. Only send when connected to the
      // same server that owns this local item.
      val isConnectedToSameServer =
              playbackSession.serverConnectionConfigId != null &&
                      DeviceManager.serverConnectionConfig?.id ==
                              playbackSession.serverConnectionConfigId
      if (hasNetworkConnection &&
                      shouldSyncServer &&
                      !playbackSession.libraryItemId.isNullOrEmpty() &&
                      isConnectedToSameServer
      ) {
        val ownerConfig = snapshot.connectionLease?.let(DeviceManager::getServerConnectionConfig)
        if (ownerConfig == null) {
          cb(SyncResult(false, null, null))
          return
        }
        apiHandler.sendLocalProgressSync(playbackSession, ownerConfig) { syncSuccess, errorMsg ->
          try {
            if (shutdown || !playerNotificationService.isServiceAlive()) {
              cb(null)
              return@sendLocalProgressSync
            }
            if (!DeviceManager.isConnectionLeaseCurrent(snapshot.connectionLease!!)) {
              cb(SyncResult(true, false, "Account changed during progress sync"))
              return@sendLocalProgressSync
            }
            if (syncSuccess) {
              val wasCurrent = markSyncSuccessIfCurrent(
                snapshot,
                updateLastSyncTime = true,
                syncCutoffElapsedMs = syncCutoffElapsedMs,
                acknowledgedServerMs = accounting.preparedServerMs
              )
              if (wasCurrent) {
                playerNotificationService.alertSyncSuccess()
              }
              // Transport requests are serialized, so no newer checkpoint for
              // this exact owner/session can be written before this completion.
              DeviceManager.dbManager.removePlaybackSessionIfUnchanged(playbackSession)
              AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced local progress")
            } else {
              val failureCount = markSyncFailureIfCurrent(snapshot)
              if (failureCount == FAILED_SYNC_ALERT_THRESHOLD) {
                playerNotificationService.alertSyncFailing()
              }
              AbsLogger.error("MediaProgressSyncer", "sync: Local progress sync failed (count: ${failureCount ?: 0})")
            }

            cb(SyncResult(true, syncSuccess, errorMsg))
          } catch (error: Exception) {
            Log.e(tag, "Local progress completion failed (${error.javaClass.simpleName})")
            cb(null)
          }
        }
      } else {
        AbsLogger.info("MediaProgressSyncer", "sync: Local progress deferred (network: $hasNetworkConnection, matching server: $isConnectedToSameServer)")
        cb(SyncResult(false, null, null))
      }
    } else if (hasNetworkConnection && shouldSyncServer && ownerConnectionIsSaved) {
      AbsLogger.info("MediaProgressSyncer", "sync: Sending progress to server")

      val ownerConfig = snapshot.connectionLease?.let(DeviceManager::getServerConnectionConfig)
      if (ownerConfig == null) {
        resetIfCurrent(snapshot)
        cb(null)
        return
      }
      apiHandler.sendProgressSync(snapshot.sessionId, syncData, ownerConfig) { syncSuccess, errorMsg ->
        try {
          if (shutdown || !playerNotificationService.isServiceAlive()) {
            cb(null)
            return@sendProgressSync
          }
          if (!DeviceManager.isConnectionLeaseCurrent(snapshot.connectionLease!!)) {
            resetIfCurrent(snapshot)
            cb(null)
            return@sendProgressSync
          }
          if (syncSuccess) {
            AbsLogger.info("MediaProgressSyncer", "sync: Successfully synced progress")

            val wasCurrent = markSyncSuccessIfCurrent(
              snapshot,
              updateLastSyncTime = true,
              syncCutoffElapsedMs = syncCutoffElapsedMs,
              acknowledgedServerMs = accounting.preparedServerMs
            )
            if (wasCurrent) {
              playerNotificationService.alertSyncSuccess()
            }
            DeviceManager.dbManager.removePlaybackSessionIfUnchanged(playbackSession)
          } else {
            val failureCount = markSyncFailureIfCurrent(snapshot)
            if (failureCount == FAILED_SYNC_ALERT_THRESHOLD) {
              playerNotificationService.alertSyncFailing()
            }
            AbsLogger.error("MediaProgressSyncer", "sync: Progress sync failed (count: ${failureCount ?: 0})")
          }
          cb(SyncResult(true, syncSuccess, errorMsg))
        } catch (error: Exception) {
          Log.e(tag, "Progress completion failed (${error.javaClass.simpleName})")
          cb(null)
        }
      }
    } else {
      AbsLogger.info("MediaProgressSyncer", "sync: Progress deferred (network: $hasNetworkConnection)")
      cb(SyncResult(false, null, null))
    }
  }

  private fun saveLocalProgress(
    snapshot: SessionSnapshot,
    allowDetachedCompletion: Boolean = false
  ) {
    val localProgress = synchronized(DeviceManager.connectionPersistenceMonitor) persistence@{
      // Account cleanup owns this same lane, so sanitization and the Paper
      // write are one ordered operation rather than a check-then-write race.
      val playbackSession = snapshot.playbackSession.copySanitizedForPersistence()
      val progress = synchronized(stateLock) {
        if (shutdown || (!allowDetachedCompletion && !isCurrentLocked(snapshot))) {
          return@persistence null
        }

        val isCurrent = isCurrentLocked(snapshot)
        val candidate = if (isCurrent) {
          currentLocalMediaProgress
            ?: DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
            ?: playbackSession.getNewLocalMediaProgress()
        } else {
          DeviceManager.dbManager.getLocalMediaProgress(playbackSession.localMediaProgressId)
            ?: playbackSession.getNewLocalMediaProgress()
        }
        candidate.updateFromPlaybackSession(playbackSession)

        if (playbackSession.serverConnectionConfigId == null) {
          candidate.serverConnectionConfigId = null
          candidate.serverAddress = null
          candidate.serverUserId = null
          candidate.libraryItemId = null
          candidate.episodeId = null
        }

        if (isCurrent) currentLocalMediaProgress = candidate
        candidate
      }

      if (!progress.progress.isFinite() ||
                      !progress.currentTime.isFinite() ||
                      !progress.duration.isFinite()
      ) {
        Log.e(tag, "Invalid progress on local media progress")
        return@persistence null
      }

      DeviceManager.dbManager.saveLocalMediaProgress(progress)
      progress
    }

    if (localProgress == null) return
    if (isCurrent(snapshot) && playerNotificationService.isServiceAlive()) {
      playerNotificationService.clientEventEmitter?.onLocalMediaProgressUpdate(localProgress)
    }
    Log.d(
            tag,
            "Saved Local Progress Current Time: ID ${localProgress.id} | ${localProgress.currentTime} | Duration ${localProgress.duration} | Progress ${localProgress.progressPercent}%"
    )
  }

  fun reset() {
    val generationToDiscard: Long
    synchronized(stateLock) {
      generationToDiscard = sessionGeneration
      sessionGeneration++
      mainHandler.removeCallbacks(listeningTick)
      listeningTimerRunning = false
      currentPlaybackSession = null
      currentSourcePlaybackSession = null
      currentListeningLedger = null
      bufferingAccountingSuspended = false
      bufferingSuspendedAtElapsedMs = 0L
      currentLocalMediaProgress = null
      lastSyncTime = 0L
      failedSyncs = 0
    }
    discardDetachedGenerationIfDrained(generationToDiscard)
    Log.d(tag, "reset: Set last sync time 0 $lastSyncTime")
  }

  /** Drop a remote sync generation as soon as its owning account disappears. */
  fun invalidateRemovedConnection() {
    val snapshot = currentSessionSnapshot() ?: return
    val playbackSession = snapshot.playbackSession
    if (playbackSession.isLocal) return
    val lease = snapshot.connectionLease ?: return resetIfCurrent(snapshot)
    if (playbackSession.serverConnectionConfigId != lease.connectionId ||
      !DeviceManager.isConnectionLeaseCurrent(lease)
    ) {
      resetIfCurrent(snapshot)
    }
  }

  /** Stops timers without sending a final progress request during service teardown. */
  fun shutdown() {
    shutdown = true
    reset()
    val abandonedRequests = synchronized(syncQueueLock) {
      buildList {
        activeSyncRequest?.let(::add)
        addAll(pendingSyncRequests)
      }.also {
        activeSyncRequest = null
        pendingSyncRequests.clear()
        syncRequestInFlight = false
      }
    }
    // Never invoke client callbacks while holding the queue lock. A callback
    // may synchronously request another sync, which now completes as shutdown.
    abandonedRequests.forEach { request ->
      try {
        request.callback(null)
      } catch (error: Exception) {
        Log.e(tag, "Progress callback failed during shutdown", error)
      }
    }
    syncExecutor.shutdownNow()
    synchronized(stateLock) {
      generationAccounting.clear()
      currentListeningLedger = null
      bufferingAccountingSuspended = false
      bufferingSuspendedAtElapsedMs = 0L
    }
  }

  internal val isTerminated: Boolean
    get() = shutdown

  private fun cancelListeningTimer() {
    synchronized(stateLock) {
      mainHandler.removeCallbacks(listeningTick)
      listeningTimerRunning = false
    }
  }

  private fun scheduleNextListeningTickIfActive() {
    synchronized(stateLock) {
      if (!shutdown && listeningTimerRunning && playerNotificationService.isServiceAlive()) {
        mainHandler.postDelayed(listeningTick, LISTENING_TICK_MS)
      }
    }
  }

  private fun currentSessionSnapshot(): SessionSnapshot? = synchronized(stateLock) {
    currentPlaybackSession?.let { SessionSnapshot(sessionGeneration, it, lastSyncTime) }
  }

  /** True when persistence/timers still belong to another exact session object. */
  fun isTrackingDifferentSession(playbackSession: PlaybackSession): Boolean =
    synchronized(stateLock) {
      currentPlaybackSession != null && currentSourcePlaybackSession !== playbackSession
    }

  private fun isCurrent(snapshot: SessionSnapshot): Boolean = synchronized(stateLock) {
    isCurrentLocked(snapshot)
  }

  private fun canUseSession(playbackSession: PlaybackSession): Boolean {
    if (playbackSession.isLocal) return true
    val lease = playbackSession.connectionLease ?: return false
    return playbackSession.serverConnectionConfigId == lease.connectionId &&
      DeviceManager.isConnectionLeaseCurrent(lease)
  }

  private fun isCurrentLocked(snapshot: SessionSnapshot): Boolean {
    return !shutdown &&
            snapshot.generation == sessionGeneration &&
            currentPlaybackSession === snapshot.playbackSession
  }

  private fun resetIfCurrent(snapshot: SessionSnapshot) {
    val didReset = synchronized(stateLock) {
      if (!isCurrentLocked(snapshot)) {
        false
      } else {
        sessionGeneration++
        mainHandler.removeCallbacks(listeningTick)
        listeningTimerRunning = false
        currentPlaybackSession = null
        currentLocalMediaProgress = null
        bufferingAccountingSuspended = false
        bufferingSuspendedAtElapsedMs = 0L
        lastSyncTime = 0L
        failedSyncs = 0
        true
      }
    }
    if (didReset) discardDetachedGenerationIfDrained(snapshot.generation)
  }

  private fun clearSyncCountersIfCurrent(snapshot: SessionSnapshot) {
    synchronized(stateLock) {
      if (!isCurrentLocked(snapshot)) return
      lastSyncTime = 0L
      failedSyncs = 0
      bufferingAccountingSuspended = false
      bufferingSuspendedAtElapsedMs = 0L
      Log.d(tag, "pause: Set last sync time 0 $lastSyncTime")
    }
  }

  private fun markSyncSuccessIfCurrent(
          snapshot: SessionSnapshot,
          updateLastSyncTime: Boolean,
          syncCutoffElapsedMs: Long,
          acknowledgedServerMs: Long
  ): Boolean = synchronized(stateLock) {
    generationAccounting[snapshot.generation]?.ledger?.let { ledger ->
      ledger.pendingServerMs = (ledger.pendingServerMs - acknowledgedServerMs)
        .coerceAtLeast(0L)
    }
    if (!isCurrentLocked(snapshot)) return@synchronized false
    failedSyncs = 0
    if (updateLastSyncTime) lastSyncTime = syncCutoffElapsedMs
    true
  }

  private fun discardDetachedGenerationIfDrained(generation: Long) {
    val stillQueued = synchronized(syncQueueLock) {
      activeSyncRequest?.snapshot?.generation == generation ||
        pendingSyncRequests.any { it.snapshot.generation == generation }
    }
    if (stillQueued) return
    synchronized(stateLock) {
      if (generation == sessionGeneration) return
      generationAccounting.remove(generation)
    }
  }

  private fun saturatingAdd(first: Long, second: Long): Long {
    val safeFirst = first.coerceAtLeast(0L)
    val safeSecond = second.coerceAtLeast(0L)
    return if (Long.MAX_VALUE - safeFirst < safeSecond) {
      Long.MAX_VALUE
    } else {
      safeFirst + safeSecond
    }
  }

  /** Caller must hold [stateLock]. */
  private fun accountListeningThroughLocked(
    snapshot: SessionSnapshot,
    duration: Double,
    currentTime: Double,
    syncCutoffElapsedMs: Long,
    allowDetachedCompletion: Boolean
  ): AccountingAmounts? {
    if (shutdown || (!allowDetachedCompletion && !isCurrentLocked(snapshot))) return null
    val generationClock = generationAccounting.getOrPut(snapshot.generation) {
      GenerationAccounting(
        snapshot.lastSyncTime.coerceAtLeast(1L),
        ListeningLedger(snapshot.playbackSession.timeListening.coerceAtLeast(0L))
      )
    }
    val activeDeltaMs = (
      syncCutoffElapsedMs - generationClock.accountedThroughElapsedMs
      ).coerceAtLeast(0L)
    val localWithRemainder = saturatingAdd(
      generationClock.ledger.localRemainderMs,
      activeDeltaMs
    )
    val localSeconds = localWithRemainder / 1000L
    generationClock.ledger.localRemainderMs = localWithRemainder % 1000L
    generationClock.ledger.pendingServerMs = saturatingAdd(
      generationClock.ledger.pendingServerMs,
      activeDeltaMs
    )
    generationClock.accountedThroughElapsedMs = maxOf(
      generationClock.accountedThroughElapsedMs,
      syncCutoffElapsedMs
    )
    val serverSeconds = generationClock.ledger.pendingServerMs / 1000L
    val preparedServerMs = generationClock.ledger.pendingServerMs -
      (generationClock.ledger.pendingServerMs % 1000L)
    generationClock.ledger.cumulativeLocalSeconds = saturatingAdd(
      generationClock.ledger.cumulativeLocalSeconds,
      localSeconds
    )
    snapshot.playbackSession.timeListening = saturatingAdd(
      generationClock.ledger.baseTimeListening,
      generationClock.ledger.cumulativeLocalSeconds
    )
    snapshot.playbackSession.syncData(MediaProgressSyncData(0L, duration, currentTime))
    return AccountingAmounts(localSeconds, serverSeconds, preparedServerMs)
  }

  private fun markSyncFailureIfCurrent(snapshot: SessionSnapshot): Int? =
          synchronized(stateLock) {
            if (!isCurrentLocked(snapshot)) return@synchronized null
            failedSyncs++
            val failureCount = failedSyncs
            if (failureCount >= FAILED_SYNC_ALERT_THRESHOLD) failedSyncs = 0
            failureCount
          }

  private fun completePlaybackAction(
          event: () -> Unit,
          cleanup: () -> Unit = {},
          cb: () -> Unit
  ) {
    try {
      if (!shutdown && playerNotificationService.isServiceAlive()) event()
    } catch (error: Exception) {
      Log.e(tag, "Unable to record playback event", error)
    } finally {
      try {
        cleanup()
      } catch (error: Exception) {
        Log.e(tag, "Unable to clean up playback progress state", error)
      } finally {
        cb()
      }
    }
  }

  companion object {
    private const val LISTENING_TICK_MS = 15_000L
    private const val FAILED_SYNC_ALERT_THRESHOLD = 2
  }
}
