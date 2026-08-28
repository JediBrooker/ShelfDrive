package com.audiobookshelf.app.player

import android.content.Intent
import android.os.*
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import com.audiobookshelf.app.R
import com.audiobookshelf.app.data.LibraryItemWrapper
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PodcastEpisode
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.VoiceSearchRequest
import com.audiobookshelf.app.media.VoiceSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MediaSessionCallback(private val playerNotificationService:PlayerNotificationService) : MediaSessionCompat.Callback() {
  var tag = "MediaSessionCallback"

  private val mainHandler = Handler(Looper.getMainLooper())
  private val playbackPreparationState = PlaybackPreparationState()
  private val voiceSearchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var voiceSearchJob: Job? = null
  private val preparationDeadline = PlaybackPreparationDeadline(
    mainHandler,
    PREPARATION_TIMEOUT_MS
  ) { prepareGeneration, message ->
    voiceSearchJob?.cancel()
    voiceSearchJob = null
    failPreparation(prepareGeneration, message)
  }
  private val mediaButtonLock = Any()
  @Volatile private var released = false
  private var mediaButtonClickCount: Int = 0
  private val mediaButtonClickTimeout: Long = 1000  //ms
  private val mediaButtonTimeoutRunnable = Runnable {
    val clickCount = synchronized(mediaButtonLock) {
      val count = mediaButtonClickCount
      mediaButtonClickCount = 0
      count
    }
    if (released || !playerNotificationService.isServiceAlive()) return@Runnable
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) {
      return@Runnable
    }
    when {
      clickCount == 2 -> {
        playerNotificationService.jumpBackward()
        requestPlay()
      }
      clickCount >= 3 -> {
        playerNotificationService.jumpForward()
        requestPlay()
      }
    }
  }

  /** Cancel delayed commands and invalidate in-flight prepare completions. */
  fun release() {
    released = true
    playbackPreparationState.cancel()
    cancelPreparationWork()
    voiceSearchScope.cancel()
    synchronized(mediaButtonLock) {
      mediaButtonClickCount = 0
    }
    mainHandler.removeCallbacksAndMessages(null)
  }

  /** Advance the generation from any thread before a posted account refresh. */
  internal fun invalidatePendingPreparation() {
    playbackPreparationState.cancel()
  }

  override fun onPrepare() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) {
      return
    }
    cancelPreparationWork()
    Log.d(tag, "ON PREPARE MEDIA SESSION COMPAT")
    if (playerNotificationService.currentPlayer.mediaItemCount > 0) {
      playbackPreparationState.cancel()
      playerNotificationService.prepareCurrentPlayer()
      return
    }
    prepareResumeOrFallback(false)
  }

  override fun onPlay() {
    Log.d(tag, "ON PLAY MEDIA SESSION COMPAT")
    requestPlay()
  }

  override fun onPrepareFromSearch(query: String?, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM SEARCH $query")
    prepareFromSearch(query, extras, false)
  }

  override fun onPlayFromSearch(query: String?, extras: Bundle?) {
    Log.d(tag, "ON PLAY FROM SEARCH $query")
    prepareFromSearch(query, extras, true)
  }

  private fun prepareFromSearch(query: String?, extras: Bundle?, playWhenReady: Boolean) {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn()) return
    cancelPreparationWork()
    val prepareGeneration = playbackPreparationState.begin(playWhenReady)
    val request = VoiceSearchRequest.from(query, extras)
    if (!publishPreparationState(PlaybackStateCompat.STATE_CONNECTING)) return
    schedulePreparationTimeout(
      prepareGeneration,
      playerNotificationService.getString(R.string.voice_search_unavailable)
    )

    voiceSearchJob = voiceSearchScope.launch {
      val result = try {
        playerNotificationService.mediaManager.resolveVoiceSearch(request)
      } catch (cancelled: CancellationException) {
        return@launch
      } catch (error: Exception) {
        Log.e(tag, "Voice search resolution failed (${error.javaClass.simpleName})")
        VoiceSearchResult.Unavailable
      }

      mainHandler.post {
        if (released || !playerNotificationService.isServiceAlive() ||
          !playbackPreparationState.isCurrent(prepareGeneration)
        ) {
          return@post
        }
        voiceSearchJob = null

        when (result) {
          is VoiceSearchResult.Found -> prepareItem(
            result.resolution.item,
            result.resolution.episode,
            prepareGeneration
          )
          VoiceSearchResult.NoMatch -> {
            val message = if (request.isGeneralRequest) {
              playerNotificationService.getString(R.string.voice_search_no_playable_media)
            } else {
              playerNotificationService.getString(
                R.string.voice_search_no_match,
                request.displayTerm
              )
            }
            failPreparation(prepareGeneration, message)
          }
          VoiceSearchResult.Unavailable -> failPreparation(
            prepareGeneration,
            playerNotificationService.getString(R.string.voice_search_unavailable)
          )
        }
      }
    }
  }

  override fun onPause() {
    Log.d(tag, "ON PAUSE MEDIA SESSION COMPAT")
    requestPause()
  }

  override fun onStop() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    cancelPreparationWork()
    playbackPreparationState.cancel()
    playerNotificationService.stopPlaybackForResume()
  }

  override fun onSkipToPrevious() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    playerNotificationService.jumpBackward()
  }

  override fun onSkipToNext() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    playerNotificationService.jumpForward()
  }

  override fun onFastForward() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    playerNotificationService.jumpForward()
  }

  override fun onRewind() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    playerNotificationService.seekPlayer(playerNotificationService.getCurrentTrackStartOffsetMs())
  }

  override fun onSeekTo(pos: Long) {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    val currentTrackStartOffset = playerNotificationService.getCurrentTrackStartOffsetMs()
    playerNotificationService.seekPlayer(currentTrackStartOffset + pos)
  }

  private fun onChangeSpeed() {
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    // cycle to next speed, only contains preset android app options, as each increment needs it's own icon
    // Rounding values in the event a non preset value (.5, 1, 1.2, 1.5, 2, 3) is selected in the phone app
    val mediaManager = playerNotificationService.mediaManager
    val newSpeed = when (mediaManager.getSavedPlaybackRate()) {
      in 0.5f..0.7f -> 1.0f
      in 0.8f..1.0f -> 1.2f
      in 1.1f..1.2f -> 1.5f
      in 1.3f..1.5f -> 2.0f
      in 1.6f..2.0f -> 3.0f
      in 2.1f..3.0f -> 0.5f
      // anything set above 3 (can happen in the android app) will be reset to 1
      else -> 1.0f
    }
    mediaManager.setSavedPlaybackRate(newSpeed)
    playerNotificationService.setPlaybackSpeed(newSpeed)
    playerNotificationService.clientEventEmitter?.onPlaybackSpeedChanged(newSpeed)
  }

  override fun onPrepareFromMediaId(mediaId: String?, extras: Bundle?) {
    Log.d(tag, "ON PREPARE FROM MEDIA ID $mediaId")
    prepareFromMediaId(mediaId, false)
  }

  override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
    Log.d(tag, "ON PLAY FROM MEDIA ID $mediaId")
    prepareFromMediaId(mediaId, true)
  }

  private fun prepareFromMediaId(mediaId: String?, playWhenReady: Boolean) {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn()) return
    cancelPreparationWork()
    val prepareGeneration = playbackPreparationState.begin(playWhenReady)
    if (!publishPreparationState(PlaybackStateCompat.STATE_CONNECTING)) return
    schedulePreparationTimeout(
      prepareGeneration,
      playerNotificationService.getString(R.string.voice_search_playback_failed)
    )
    val libraryItemWrapper: LibraryItemWrapper?
    var podcastEpisode: PodcastEpisode? = null

    if (mediaId.isNullOrEmpty()) {
      libraryItemWrapper = playerNotificationService.mediaManager.getFirstItem()
    } else {
      val libraryItemWithEpisode = playerNotificationService.mediaManager.getPodcastWithEpisodeByEpisodeId(mediaId)
      if (libraryItemWithEpisode != null) {
        libraryItemWrapper = libraryItemWithEpisode.libraryItemWrapper
        podcastEpisode = libraryItemWithEpisode.episode
      } else {
        libraryItemWrapper = playerNotificationService.mediaManager.getById(mediaId)
        if (libraryItemWrapper == null) {
          Log.e(tag, "onPlayFromMediaId: Media item not found")
        }
      }
    }

    if (libraryItemWrapper == null) {
      failPreparation(
        prepareGeneration,
        playerNotificationService.getString(R.string.voice_search_no_playable_media)
      )
    } else {
      prepareItem(libraryItemWrapper, podcastEpisode, prepareGeneration)
    }
  }

  /**
   * Restores the user's last session after AAOS recreates the service. Remote
   * sessions are renewed through the server instead of reusing an expired
   * stream URL; downloaded sessions can be prepared entirely offline.
   */
  private fun prepareResumeOrFallback(playWhenReady: Boolean) {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn()) return
    cancelPreparationWork()
    val prepareGeneration = playbackPreparationState.begin(playWhenReady)
    if (!publishPreparationState(PlaybackStateCompat.STATE_CONNECTING)) return
    schedulePreparationTimeout(
      prepareGeneration,
      playerNotificationService.getString(R.string.voice_search_playback_failed)
    )
    val lastSession = DeviceManager.deviceData.lastPlaybackSession

    // A remote resume URL must not outlive the account/profile that authorized
    // it. Local downloaded sessions remain available while signed out.
    if (lastSession != null && abortPendingPreparationForRequiredSignIn(lastSession)) return

    if (lastSession == null) {
      val firstItem = playerNotificationService.mediaManager.getFirstItem()
      if (firstItem == null) {
        failPreparation(
          prepareGeneration,
          playerNotificationService.getString(R.string.voice_search_no_playable_media)
        )
      } else {
        prepareItem(firstItem, null, prepareGeneration)
      }
      return
    }

    if (lastSession.isLocal) {
      val storedProgress = runCatching {
        DeviceManager.dbManager.getLocalMediaProgress(lastSession.localMediaProgressId)
      }.getOrNull()
      finishPrepare(
        prepareGeneration,
        hydrateLocalResumeSession(lastSession, storedProgress)
      )
      return
    }

    val existingLease = lastSession.connectionLease
    val connectionConfig = if (existingLease == null) {
      // Transient leases are intentionally absent after a process restart. A
      // persisted session may therefore bind to the currently saved owner once.
      DeviceManager.getServerConnectionConfig(lastSession.serverConnectionConfigId)
    } else {
      // In-process work must retain the exact account lifetime. Never let an
      // old callback bind to a remove/re-add profile with the same stable ID.
      DeviceManager.getServerConnectionConfig(existingLease)
    }
    val connectionLease = existingLease
      ?: DeviceManager.captureConnectionLease(connectionConfig)
    val libraryItemId = lastSession.libraryItemId
    if (connectionConfig == null || connectionLease == null ||
      connectionConfig.id != lastSession.serverConnectionConfigId ||
      libraryItemId.isNullOrBlank()
    ) {
      failPreparation(
        prepareGeneration,
        playerNotificationService.getString(R.string.car_sign_in_required)
      )
      return
    }

    if (!DeviceManager.trySelectServerConnectionConfig(connectionConfig, connectionLease)) {
      failPreparation(
        prepareGeneration,
        playerNotificationService.getString(R.string.car_sign_in_required)
      )
      return
    }
    try {
      playerNotificationService.apiHandler.playLibraryItem(
        libraryItemId,
        lastSession.episodeId,
        playerNotificationService.getPlayItemRequestPayload(lastSession.isHLS),
        connectionConfig
      ) { renewedSession ->
        if (released || !playerNotificationService.isServiceAlive()) return@playLibraryItem
        if (renewedSession == null) {
          mainHandler.post {
            failPreparation(
              prepareGeneration,
              playerNotificationService.getString(R.string.voice_search_playback_failed)
            )
          }
        } else {
          finishPrepare(prepareGeneration, renewedSession)
        }
      }
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to renew the saved playback session (${error.javaClass.simpleName})")
      failPreparation(
        prepareGeneration,
        playerNotificationService.getString(R.string.voice_search_playback_failed)
      )
    }
  }

  private fun prepareItem(
    libraryItemWrapper: LibraryItemWrapper,
    podcastEpisode: PodcastEpisode?,
    prepareGeneration: Long
  ) {
    if (released || !playerNotificationService.isServiceAlive()) return
    try {
      playerNotificationService.mediaManager.play(
        libraryItemWrapper,
        podcastEpisode,
        playerNotificationService.getPlayItemRequestPayload(false)
      ) { playbackSession ->
        if (released || !playerNotificationService.isServiceAlive()) return@play
        if (playbackSession == null) {
          Log.e(tag, "Failed to play library item")
          mainHandler.post {
            failPreparation(
              prepareGeneration,
              playerNotificationService.getString(R.string.voice_search_playback_failed)
            )
          }
        } else {
          finishPrepare(prepareGeneration, playbackSession)
        }
      }
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to prepare media (${error.javaClass.simpleName})")
      failPreparation(
        prepareGeneration,
        playerNotificationService.getString(R.string.voice_search_playback_failed)
      )
    }
  }

  private fun failPreparation(prepareGeneration: Long, message: String) {
    if (released || !playerNotificationService.isServiceAlive() ||
      !playbackPreparationState.cancel(prepareGeneration)
    ) {
      return
    }
    clearPreparationTimeout(prepareGeneration)
    Log.w(tag, "Prepare request failed")
    publishPreparationState(PlaybackStateCompat.STATE_ERROR, message)
  }

  private fun publishPreparationState(state: Int, errorMessage: String? = null): Boolean {
    if (released || !playerNotificationService.isServiceAlive()) return false
    // Account removal can race an asynchronous prepare completion. Re-check
    // here as a final precedence guard before replacing MediaSession state.
    if (abortPendingPreparationForRequiredSignIn()) return false

    val current = playerNotificationService.mediaSession.controller.playbackState
    val builder = PlaybackStateCompat.Builder()
      .setState(state, current?.position ?: 0L, 0f)
    current?.let { playbackState ->
      builder.setActions(playbackState.actions)
      builder.setBufferedPosition(playbackState.bufferedPosition)
      builder.setActiveQueueItemId(playbackState.activeQueueItemId)
      playbackState.customActions.forEach(builder::addCustomAction)
    }
    if (errorMessage != null) {
      builder.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, errorMessage)
    }
    playerNotificationService.mediaSession.setPlaybackState(builder.build())
    return true
  }

  private fun finishPrepare(prepareGeneration: Long, playbackSession: PlaybackSession) {
    mainHandler.post {
      if (released || !playerNotificationService.isServiceAlive()) return@post
      // Invalidate the generation before publishing the auth error so this
      // completion (and every duplicate/late completion) becomes a no-op.
      if (abortPendingPreparationForRequiredSignIn(playbackSession)) return@post
      val playWhenReady = playbackPreparationState.resolve(prepareGeneration)
      if (playWhenReady == null) {
        Log.d(tag, "Ignoring stale prepare completion $prepareGeneration")
        return@post
      }
      clearPreparationTimeout(prepareGeneration)

      val playbackRate = playerNotificationService.mediaManager.getSavedPlaybackRate()
      try {
        // Account removal can occur while reading player preferences. Keep the
        // auth state authoritative at the final mutation boundary as well.
        if (abortPendingPreparationForRequiredSignIn(playbackSession)) return@post
        playerNotificationService.preparePlayer(playbackSession, playWhenReady, playbackRate)
      } catch (error: RuntimeException) {
        Log.e(tag, "Player preparation failed (${error.javaClass.simpleName})")
        publishPreparationState(
          PlaybackStateCompat.STATE_ERROR,
          playerNotificationService.getString(R.string.voice_search_playback_failed)
        )
      }
    }
  }

  private fun requestPlay() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) {
      return
    }
    playbackPreparationState.updateDesiredPlayWhenReady(true)
    if (playerNotificationService.currentPlayer.mediaItemCount > 0) {
      playerNotificationService.play()
    } else if (!playbackPreparationState.hasPendingPreparation()) {
      prepareResumeOrFallback(true)
    }
  }

  private fun requestPause() {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    playbackPreparationState.updateDesiredPlayWhenReady(false)
    playerNotificationService.pause()
  }

  override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
    if (released || !playerNotificationService.isServiceAlive()) return false
    return handleCallMediaButton(mediaButtonEvent)
  }

  private fun handleCallMediaButton(intent: Intent): Boolean {
    Log.w(tag, "Ignoring unsupported media-button request")

    // Hardware keys are user operations too. Keep every play/pause/seek/skip
    // key a no-op while AAOS is presenting the terminal sign-in resolution.
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) {
      return true
    }

    if(Intent.ACTION_MEDIA_BUTTON == intent.action) {
      val keyEvent = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
      }

      Log.d(tag, "handleCallMediaButton keyEvent = $keyEvent | action ${keyEvent?.action}")

      // Widget button intent is only sending the action down event
      if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
        Log.d(tag, "handleCallMediaButton: key action_down for ${keyEvent.keyCode}")
        when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            Log.d(tag, "handleCallMediaButton: Media Play/Pause")

            // TODO: Play/pause event sent from widget when app is closed. Currently the service gets destroyed before anything can happen
//            if (playerNotificationService.currentPlaybackSession == null && DeviceManager.deviceData.lastPlaybackSession != null) {
//              Log.i(tag, "No playback session but had one in the db")
//
//              val connectionConfig = DeviceManager.deviceData.serverConnectionConfigs.find { it.id == DeviceManager.deviceData.lastPlaybackSession?.serverConnectionConfigId }
//              connectionConfig?.let {
//                Log.i(tag, "Setting playback session from db $it")
//                DeviceManager.serverConnectionConfig = it
//
//                playerNotificationService.currentPlaybackSession = DeviceManager.deviceData.lastPlaybackSession
//                playerNotificationService.startNewPlaybackSession()
//                return true
//              }
//            }

            if (playerNotificationService.mPlayer.isPlaying) {
              if (0 == mediaButtonClickCount) requestPause()
              handleMediaButtonClickCount()
            } else {
              if (0 == mediaButtonClickCount) {
                requestPlay()
              }
              handleMediaButtonClickCount()
            }
          }
          KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
            Log.d(tag, "handleCallMediaButton: Media Fast Forward")
            playerNotificationService.jumpForward()
          }
          KeyEvent.KEYCODE_MEDIA_REWIND -> {
            Log.d(tag, "handleCallMediaButton: Media Rewind")
            playerNotificationService.jumpBackward()
          }
        }
      }

      if (keyEvent?.action == KeyEvent.ACTION_UP) {
        Log.d(tag, "handleCallMediaButton: key action_up for ${keyEvent.keyCode}")
        when (keyEvent.keyCode) {
          KeyEvent.KEYCODE_HEADSETHOOK -> {
            Log.d(tag, "handleCallMediaButton: Headset Hook")
            if (0 == mediaButtonClickCount) {
              if (playerNotificationService.mPlayer.isPlaying)
                requestPause()
              else
                requestPlay()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PLAY -> {
            Log.d(tag, "handleCallMediaButton: Media Play")
            if (0 == mediaButtonClickCount) {
              requestPlay()
            }
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_PAUSE -> {
            Log.d(tag, "handleCallMediaButton: Media Pause")
            if (0 == mediaButtonClickCount) requestPause()
            handleMediaButtonClickCount()
          }
          KeyEvent.KEYCODE_MEDIA_NEXT -> {
            playerNotificationService.jumpForward()
          }
          KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            playerNotificationService.jumpBackward()
          }
          KeyEvent.KEYCODE_MEDIA_STOP -> {
            onStop()
          }
          else -> {
            Log.d(tag, "KeyCode:${keyEvent.keyCode}")
            return false
          }
        }
      }
    }
    return true
  }

  private fun handleMediaButtonClickCount() {
    val shouldSchedule = synchronized(mediaButtonLock) {
      mediaButtonClickCount++
      mediaButtonClickCount == 1
    }
    if (shouldSchedule) {
      mainHandler.postDelayed(mediaButtonTimeoutRunnable, mediaButtonClickTimeout)
    }
  }

  private fun cancelPreparationWork() {
    preparationDeadline.cancel()
    voiceSearchJob?.cancel()
    voiceSearchJob = null
  }

  /**
   * Authentication expiry is a terminal prepare state on AAOS. Cancel work
   * and advance the generation before publishing STATE_ERROR so no already-
   * queued server callback can replace the actionable sign-in resolution.
   */
  private fun abortPendingPreparationForRequiredSignIn(
    playbackSession: PlaybackSession? = null
  ): Boolean {
    if (!playerNotificationService.isSignInRequiredForPlayback(playbackSession)) return false
    cancelPreparationWork()
    playbackPreparationState.cancel()
    return playerNotificationService.publishSignInRequiredForPlaybackIfNeeded(playbackSession)
  }

  private fun schedulePreparationTimeout(prepareGeneration: Long, message: String) {
    preparationDeadline.schedule(prepareGeneration, message)
  }

  private fun clearPreparationTimeout(prepareGeneration: Long) {
    preparationDeadline.clear(prepareGeneration)
  }

  override fun onCustomAction(action: String?, extras: Bundle?) {
    if (released || !playerNotificationService.isServiceAlive()) return
    if (abortPendingPreparationForRequiredSignIn(playerNotificationService.currentPlaybackSession)) return
    super.onCustomAction(action, extras)

    when (action) {
      CUSTOM_ACTION_JUMP_FORWARD -> playerNotificationService.jumpForward()
      CUSTOM_ACTION_JUMP_BACKWARD -> playerNotificationService.jumpBackward()
      CUSTOM_ACTION_SKIP_FORWARD -> playerNotificationService.skipToNext()
      CUSTOM_ACTION_SKIP_BACKWARD -> playerNotificationService.skipToPrevious()
      CUSTOM_ACTION_CHANGE_SPEED -> onChangeSpeed()
    }
  }

  companion object {
    // Car quality DR-3 requires content within ten seconds. Leave headroom for
    // the host to render the resulting state after our callback completes.
    internal const val PREPARATION_TIMEOUT_MS = 8_000L
  }
}

/** One deadline for the complete prepare pipeline, not just catalog lookup. */
internal class PlaybackPreparationDeadline(
  private val handler: Handler,
  private val timeoutMs: Long,
  private val onExpired: (Long, String) -> Unit
) {
  private var scheduledGeneration: Long? = null
  private var scheduledTimeout: Runnable? = null

  fun schedule(prepareGeneration: Long, message: String) {
    cancel()
    val timeout = Runnable {
      if (scheduledGeneration != prepareGeneration) return@Runnable
      scheduledGeneration = null
      scheduledTimeout = null
      onExpired(prepareGeneration, message)
    }
    scheduledGeneration = prepareGeneration
    scheduledTimeout = timeout
    handler.postDelayed(timeout, timeoutMs)
  }

  fun clear(prepareGeneration: Long) {
    if (scheduledGeneration != prepareGeneration) return
    cancel()
  }

  fun cancel() {
    scheduledTimeout?.let(handler::removeCallbacks)
    scheduledTimeout = null
    scheduledGeneration = null
  }
}

internal fun hydrateLocalResumeSession(
  savedSession: PlaybackSession,
  storedProgress: LocalMediaProgress?
): PlaybackSession {
  val restored = savedSession.clone()
  if (storedProgress == null || storedProgress.lastUpdate < restored.updatedAt) return restored

  val maxTime = restored.getTotalDuration().takeIf { it.isFinite() && it > 0.0 }
    ?: Double.MAX_VALUE
  restored.currentTime = storedProgress.currentTime
    .takeIf { it.isFinite() }
    ?.coerceIn(0.0, maxTime)
    ?: restored.currentTime
  restored.updatedAt = maxOf(restored.updatedAt, storedProgress.lastUpdate)
  return restored
}

internal class PlaybackPreparationState {
  private var latestPrepareGeneration = 0L
  private var pendingPrepareGeneration: Long? = null

  var desiredPlayWhenReady = false
    private set

  @Synchronized
  fun begin(playWhenReady: Boolean): Long {
    desiredPlayWhenReady = playWhenReady
    latestPrepareGeneration++
    pendingPrepareGeneration = latestPrepareGeneration
    return latestPrepareGeneration
  }

  @Synchronized
  fun updateDesiredPlayWhenReady(playWhenReady: Boolean) {
    desiredPlayWhenReady = playWhenReady
  }

  @Synchronized
  fun resolve(prepareGeneration: Long): Boolean? {
    return if (prepareGeneration == latestPrepareGeneration &&
      pendingPrepareGeneration == prepareGeneration
    ) {
      pendingPrepareGeneration = null
      desiredPlayWhenReady
    } else null
  }

  @Synchronized
  fun hasPendingPreparation(): Boolean =
    pendingPrepareGeneration == latestPrepareGeneration

  @Synchronized
  fun isCurrent(prepareGeneration: Long): Boolean =
    prepareGeneration == latestPrepareGeneration

  @Synchronized
  fun cancel(prepareGeneration: Long): Boolean {
    if (prepareGeneration != latestPrepareGeneration) return false
    desiredPlayWhenReady = false
    pendingPrepareGeneration = null
    latestPrepareGeneration++
    return true
  }

  @Synchronized
  fun cancel() {
    desiredPlayWhenReady = false
    pendingPrepareGeneration = null
    latestPrepareGeneration++
  }
}
