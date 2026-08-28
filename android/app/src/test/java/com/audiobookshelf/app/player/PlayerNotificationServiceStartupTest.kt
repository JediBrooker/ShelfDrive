package com.audiobookshelf.app.player

import android.content.Intent
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.view.KeyEvent
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.BookMetadata
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.Podcast
import com.audiobookshelf.app.data.PodcastMetadata
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.accounts.ShelfDriveConnectionDataCleaner
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.server.ApiHandler
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.utils.MediaConstants
import io.paperdb.Paper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlayerNotificationServiceStartupTest {
  @Before
  fun setUp() {
    Paper.init(RuntimeEnvironment.getApplication())
    Paper.book("device").destroy()
    Paper.book("localLibraryItems").destroy()
    Paper.book("playbackSession").destroy()
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = emptyDeviceData()
  }

  @After
  fun tearDown() {
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = emptyDeviceData()
    Paper.book("device").destroy()
    Paper.book("localLibraryItems").destroy()
    Paper.book("playbackSession").destroy()
  }

  @Test
  fun serviceConstructsAndRejectsArbitraryExportedBind() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    assertNotNull(service.mediaSession)
    assertNull(service.onBind(Intent("com.example.untrusted.BIND")))

    controller.destroy()
  }

  @Test
  fun playbackNotificationChannelUsesShelfDriveBranding() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val notificationManager = service.getSystemService(NotificationManager::class.java)
    val channel = notificationManager.getNotificationChannel("audiobookshelf_channel")

    assertEquals("ShelfDrive playback", channel.name.toString())
    assertEquals("Playback controls and current media", channel.description)

    controller.destroy()
  }

  @Test
  fun mediaSessionAdvertisesBrowseSelectionAndVoicePlaybackActions() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val actions = service.mediaSession.controller.playbackState.actions

    assertTrue(actions and PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID != 0L)
    assertTrue(actions and PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID != 0L)
    assertTrue(actions and PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH != 0L)
    assertTrue(actions and PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH != 0L)

    controller.destroy()
  }

  @Test
  fun customOfflineBrowseActionsPublishAllDefinitionsForAnyPositivePerItemLimit() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    fun rootWithLimit(limit: Int) = service.onGetRoot(
      service.packageName,
      Process.myUid(),
      Bundle().apply {
        putInt(MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT, limit)
      }
    )

    val unsupported = rootWithLimit(0)
    val oneAction = rootWithLimit(1)
    val allActions = rootWithLimit(3)

    assertNull(
      unsupported?.extras?.getParcelableArrayList<Bundle>(
        MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST
      )
    )
    assertEquals(
      3,
      oneAction?.extras?.getParcelableArrayList<Bundle>(
        MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST
      )?.size
    )
    assertEquals(
      3,
      allActions?.extras?.getParcelableArrayList<Bundle>(
        MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST
      )?.size
    )

    controller.destroy()
  }

  @Test
  fun signedOutPreparePlayAndSearchKeepTheActionableAuthenticationError() {
    DeviceManager.deviceData = DeviceData(
      serverConnectionConfigs = mutableListOf(),
      lastServerConnectionConfigId = null,
      deviceSettings = DeviceSettings.default(),
      lastPlaybackSession = null
    )
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    val callback = MediaSessionCallback(service)

    callback.onPrepare()
    assertActionableSignInError(service)

    callback.onPlay()
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertActionableSignInError(service)

    callback.onPrepareFromSearch("private title", null)
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertActionableSignInError(service)

    callback.onPlayFromSearch("private title", null)
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertActionableSignInError(service)

    callback.onPrepareFromMediaId("missing-item", null)
    assertActionableSignInError(service)

    callback.onPlayFromMediaId("missing-item", null)
    assertActionableSignInError(service)

    callback.onSkipToPrevious()
    callback.onSkipToNext()
    callback.onFastForward()
    callback.onRewind()
    callback.onSeekTo(1_000L)
    assertActionableSignInError(service)

    callback.onPause()
    callback.onStop()
    assertActionableSignInError(service)

    assertTrue(
      callback.onMediaButtonEvent(
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(
          Intent.EXTRA_KEY_EVENT,
          KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
        )
      )
    )
    assertActionableSignInError(service)

    callback.release()
    controller.destroy()
  }

  @Test
  fun signedOutLocalSessionRemainsPlayableWithoutAConfiguredServer() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val localSession = playbackSession("local", "download", 60.0).apply {
      playMethod = PLAYMETHOD_LOCAL
      serverConnectionConfigId = "removed-account"
      serverAddress = "https://private.example.test"
      userId = "removed-user"
    }

    service.preparePlayer(localSession, false, 1f)

    assertEquals(1, service.currentPlayer.mediaItemCount)
    assertEquals(localSession, service.currentPlaybackSession)
    assertFalse(
      service.mediaSession.controller.playbackState.errorCode ==
        PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED
    )
    awaitCondition("local resume checkpoint") {
      DeviceManager.dbManager.getDeviceData().lastPlaybackSession != null
    }
    val persistedSession = DeviceManager.dbManager.getDeviceData().lastPlaybackSession
    assertNotNull(persistedSession)
    assertEquals(PLAYMETHOD_LOCAL, persistedSession?.playMethod)
    assertEquals(1, persistedSession?.audioTracks?.size)
    assertNull(persistedSession?.serverConnectionConfigId)
    assertNull(persistedSession?.serverAddress)
    assertNull(persistedSession?.userId)
    assertNull(persistedSession?.libraryItemId)
    assertNull(persistedSession?.episodeId)

    val callback = MediaSessionCallback(service)
    callback.onPrepare()
    assertEquals(1, service.currentPlayer.mediaItemCount)
    assertFalse(
      service.mediaSession.controller.playbackState.errorCode ==
        PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED
    )

    callback.release()
    controller.destroy()
  }

  @Test
  fun androidAutoNaturalBookEndFinishesOnceClearsTimelineAndRestartsFromBeginning() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val audioFile = writeSilentWav(
      File(service.cacheDir, "natural-end-restart.wav"),
      durationSeconds = 120
    )
    val localItemId = "natural-end-download"
    val session = playbackSession("natural-end-session", localItemId, 120.0).apply {
      playMethod = PLAYMETHOD_LOCAL
      userId = null
      libraryItemId = null
      serverConnectionConfigId = null
      serverAddress = null
      audioTracks = mutableListOf(
        audioTracks.single().copy(
          contentUrl = Uri.fromFile(audioFile).toString(),
          mimeType = "audio/wav",
          isLocal = true,
          localFileId = "natural-end-track"
        )
      )
      localLibraryItem = localLibraryItem(localItemId, audioTracks)
    }
    var callback: MediaSessionCallback? = null

    try {
      assertNotNull(service.onGetRoot(service.packageName, Process.myUid(), null))
      DeviceManager.dbManager.saveLocalLibraryItem(checkNotNull(session.localLibraryItem))
      service.preparePlayer(session, false, 1f)
      awaitCondition("initial local playback checkpoint") {
        DeviceManager.deviceData.lastPlaybackSession?.id == session.id
      }
      assertTrue(service.handlesEndedPlaybackAsCompletion())
      assertEquals(1, service.currentPlayer.mediaItemCount)

      service.mediaProgressSyncer.start(session)
      service.handlePlaybackEnded()
      service.handlePlaybackEnded()

      // The final checkpoint continues in the background, but an ended Exo
      // item must never remain available for a no-op Player.play() call.
      assertEquals(0, service.currentPlayer.mediaItemCount)
      awaitCondition("completed playback terminal state") {
        service.currentPlaybackSession == null && service.currentPlayer.mediaItemCount == 0
      }

      val localProgress = DeviceManager.dbManager.getLocalMediaProgress(localItemId)
      assertNotNull(localProgress)
      assertTrue(localProgress?.isFinished == true)
      assertEquals(120.0, localProgress?.currentTime ?: -1.0, 0.0)
      val terminalEvents = DeviceManager.dbManager
        .getMediaItemHistory(localItemId, null, true)
        ?.events
        .orEmpty()
        .filter { it.name == "Finished" || it.name == "Pause" }
      assertEquals(listOf("Finished"), terminalEvents.map { it.name })

      callback = MediaSessionCallback(service)
      callback.onPlay()
      awaitCondition("completed local book restart") {
        service.currentPlaybackSession?.id == session.id &&
          service.currentPlayer.mediaItemCount == 1
      }
      assertEquals(0.0, service.currentPlaybackSession?.currentTime ?: -1.0, 0.0)
      assertTrue(service.currentPlayer.playWhenReady)
    } finally {
      callback?.release()
      controller.destroy()
      audioFile.delete()
    }
  }

  @Test
  fun offlineRemoteBookEndPersistsFinishedCheckpointBeforeClosing() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    assertTrue(DeviceManager.trySelectServerConnectionConfig(config))
    shadowOf(service.getSystemService(ConnectivityManager::class.java))
      .setActiveNetworkInfo(null)
    assertFalse(DeviceManager.checkConnectivity(service))
    val session = playbackSession(
      "offline-natural-end",
      "offline-natural-item",
      120.0
    ).apply {
      serverConnectionConfigId = config.id
      connectionLease = DeviceManager.captureConnectionLease(config)
    }

    assertNotNull(service.onGetRoot(service.packageName, Process.myUid(), null))
    service.currentPlayer.setMediaItems(session.getMediaItems(service, config))
    service.currentPlaybackSession = session
    service.mediaProgressSyncer.start(session)

    service.handlePlaybackEnded()

    assertEquals(0, service.currentPlayer.mediaItemCount)
    awaitCondition("offline remote completion") {
      service.currentPlaybackSession == null
    }
    val queuedCheckpoint = DeviceManager.dbManager.getPlaybackSessions()
      .singleOrNull { it.id == session.id }
    assertNotNull(queuedCheckpoint)
    assertEquals(120.0, queuedCheckpoint?.currentTime ?: -1.0, 0.0)
    val finishedEvents = DeviceManager.dbManager
      .getMediaItemHistory("offline-natural-item", config.id, false)
      ?.events
      .orEmpty()
      .filter { it.name == "Finished" }
    assertEquals(1, finishedEvents.size)
    assertFalse(finishedEvents.single().serverSyncAttempted == true)

    controller.destroy()
  }

  @Test
  fun validRemotePodcastCompletionRetainsAutoNextStrategy() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val podcastSession = playbackSession("podcast-session", "podcast-item", 60.0).apply {
      mediaType = "podcast"
      episodeId = "finished-episode"
      libraryItem = podcastLibraryItem("podcast-item")
    }

    assertTrue(service.usesPodcastAutoNextForEndedPlayback(podcastSession))
    assertFalse(
      service.usesPodcastAutoNextForEndedPlayback(
        podcastSession.clone().apply { playMethod = PLAYMETHOD_LOCAL }
      )
    )
    assertFalse(
      service.usesPodcastAutoNextForEndedPlayback(
        playbackSession("book-session", "book-item", 60.0)
      )
    )

    controller.destroy()
  }

  @Test
  fun malformedPartialTrackListIsRejectedBeforeSessionCommit() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val malformed = playbackSession("malformed-local", "download", 180.0).apply {
      playMethod = PLAYMETHOD_LOCAL
      audioTracks = mutableListOf(
        audioTracks.single().copy(
          index = 0,
          contentUrl = "content://com.example.downloads/track-0",
          isLocal = true
        ),
        audioTracks.single().copy(
          index = 1,
          startOffset = 60.0,
          contentUrl = "",
          isLocal = true
        ),
        audioTracks.single().copy(
          index = 2,
          startOffset = 120.0,
          contentUrl = "content://com.example.downloads/track-2",
          isLocal = true
        )
      )
      currentTime = 125.0
    }

    service.preparePlayer(malformed, true, 1f)

    assertNull(service.currentPlaybackSession)
    assertEquals(0, service.currentPlayer.mediaItemCount)
    assertEquals(
      PlaybackStateCompat.ERROR_CODE_APP_ERROR,
      service.mediaSession.controller.playbackState.errorCode
    )
    assertNull(DeviceManager.dbManager.getDeviceData().lastPlaybackSession)

    controller.destroy()
  }

  @Test
  fun hlsWithoutCredentialIsRejectedBeforeSessionCommit() {
    val config = serverConnectionConfig().apply { token = "" }
    DeviceManager.deviceData = DeviceData(
      serverConnectionConfigs = mutableListOf(config),
      lastServerConnectionConfigId = config.id,
      deviceSettings = DeviceSettings.default(),
      lastPlaybackSession = null
    )
    DeviceManager.serverConnectionConfig = config
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val hlsSession = playbackSession("hls", "private", 60.0).apply {
      playMethod = PLAYMETHOD_TRANSCODE
      serverConnectionConfigId = config.id
      connectionLease = DeviceManager.captureConnectionLease(config)
    }

    service.preparePlayer(hlsSession, true, 1f)

    assertActionableSignInError(service)
    assertNull(service.currentPlaybackSession)
    assertEquals(0, service.currentPlayer.mediaItemCount)
    assertNull(DeviceManager.dbManager.getDeviceData().lastPlaybackSession)

    controller.destroy()
  }

  @Test
  fun lateRemotePrepareAfterAccountRemovalCannotReplaceAuthenticationError() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val callback = MediaSessionCallback(service)
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      serverConnectionConfigs = mutableListOf(config),
      lastServerConnectionConfigId = config.id,
      deviceSettings = DeviceSettings.default(),
      lastPlaybackSession = null
    )
    DeviceManager.serverConnectionConfig = config

    val stateField = MediaSessionCallback::class.java
      .getDeclaredField("playbackPreparationState")
      .apply { isAccessible = true }
    val preparationState = stateField.get(callback) as PlaybackPreparationState
    val prepareGeneration = preparationState.begin(playWhenReady = true)

    // Simulate account removal after the server request started, then an AAOS
    // prepare command followed by the already-queued successful callback.
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = emptyDeviceData()
    callback.onPrepare()
    assertActionableSignInError(service)

    // Even if a profile is added again before the old callback arrives, the
    // generation invalidated by the auth transition must stay stale.
    DeviceManager.deviceData = DeviceData(
      serverConnectionConfigs = mutableListOf(config),
      lastServerConnectionConfigId = config.id,
      deviceSettings = DeviceSettings.default(),
      lastPlaybackSession = null
    )
    DeviceManager.serverConnectionConfig = config
    val lateSession = playbackSession("late", "private", 60.0).apply {
      serverConnectionConfigId = config.id
    }

    MediaSessionCallback::class.java
      .getDeclaredMethod(
        "finishPrepare",
        java.lang.Long.TYPE,
        PlaybackSession::class.java
      )
      .apply { isAccessible = true }
      .invoke(callback, prepareGeneration, lateSession)
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertActionableSignInError(service)
    assertEquals(0, service.currentPlayer.mediaItemCount)
    assertNull(service.currentPlaybackSession)

    // Every non-callback producer is protected at the service boundary too.
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = emptyDeviceData()
    service.preparePlayer(
      playbackSession("direct-late", "private", 60.0).apply {
        serverConnectionConfigId = config.id
      },
      true,
      1f
    )
    assertActionableSignInError(service)
    assertEquals(0, service.currentPlayer.mediaItemCount)
    assertNull(service.currentPlaybackSession)

    callback.release()
    controller.destroy()
  }

  @Test
  fun genericBrowseFailureAfterAccountRemovalKeepsAuthenticationResolution() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      serverConnectionConfigs = mutableListOf(config),
      lastServerConnectionConfigId = config.id,
      deviceSettings = DeviceSettings.default(),
      lastPlaybackSession = null
    )
    DeviceManager.serverConnectionConfig = config

    service.setMediaUnavailablePlaybackState()
    assertEquals(
      PlaybackStateCompat.ERROR_CODE_APP_ERROR,
      service.mediaSession.controller.playbackState.errorCode
    )

    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = emptyDeviceData()
    service.setMediaUnavailablePlaybackState()

    assertActionableSignInError(service)

    service.handlePlayerPlaybackError("generic failure")
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertActionableSignInError(service)
    controller.destroy()
  }

  private fun assertActionableSignInError(service: PlayerNotificationService) {
    val state = service.mediaSession.controller.playbackState
    assertEquals(PlaybackStateCompat.STATE_ERROR, state.state)
    assertEquals(PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED, state.errorCode)
    assertEquals(service.getString(com.audiobookshelf.app.R.string.car_sign_in_required),
      state.errorMessage?.toString())
    assertEquals(
      service.getString(com.audiobookshelf.app.R.string.car_sign_in_action),
      state.extras?.getString(
        MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL
      )
    )
    assertTrue(
      state.extras?.containsKey(
        MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT
      ) == true
    )
  }

  @Test
  fun stopPublishesStoppedStateInsteadOfOnlyPausing() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      serverConnectionConfigs = mutableListOf(config),
      lastServerConnectionConfigId = config.id,
      deviceSettings = DeviceSettings.default(),
      lastPlaybackSession = null
    )
    DeviceManager.serverConnectionConfig = config
    val callback = MediaSessionCallback(service)

    callback.onStop()

    assertEquals(
      PlaybackStateCompat.STATE_STOPPED,
      service.mediaSession.controller.playbackState.state
    )
    assertTrue(
      service.mediaSession.controller.playbackState.actions and
        PlaybackStateCompat.ACTION_PLAY != 0L
    )

    callback.release()
    controller.destroy()
  }

  @Test
  fun coldLocalResumeUsesTheNewestPersistedListeningPosition() {
    val saved = playbackSession("saved-local", "item", trackDuration = 60.0).apply {
      playMethod = PLAYMETHOD_LOCAL
      currentTime = 4.0
      updatedAt = 100L
    }
    val progress = LocalMediaProgress(
      id = "local-item",
      localLibraryItemId = "local-item",
      localEpisodeId = null,
      duration = 60.0,
      progress = 0.5,
      currentTime = 30.0,
      isFinished = false,
      ebookLocation = null,
      ebookProgress = null,
      lastUpdate = 200L,
      startedAt = 1L,
      finishedAt = null,
      serverConnectionConfigId = null,
      serverAddress = null,
      serverUserId = null,
      libraryItemId = "item",
      episodeId = null
    )

    val restored = hydrateLocalResumeSession(saved, progress)

    assertEquals(30.0, restored.currentTime, 0.0)
    assertEquals(200L, restored.updatedAt)
    assertEquals(4.0, saved.currentTime, 0.0)
  }

  @Test
  fun destructionInvalidatesCallbacksAndARecreatedServiceStartsOpen() {
    val firstController = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val firstService = firstController.get()

    assertTrue(firstService.isServiceAlive())
    firstController.destroy()
    assertFalse(firstService.isServiceAlive())
    assertTrue(PlayerNotificationService.isClosed)
    assertTrue(firstService.sleepTimerManager.isTerminated)
    assertTrue(firstService.mediaProgressSyncer.isTerminated)

    val secondController = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val secondService = secondController.get()
    assertTrue(secondService.isServiceAlive())
    assertFalse(PlayerNotificationService.isClosed)

    secondController.destroy()
  }

  @Test
  fun browseLoadDeadlinePrecedesDetachedResultDeadline() {
    assertTrue(
      PlayerNotificationService.BROWSE_LOAD_TIMEOUT_MS <
        PlayerNotificationService.BROWSE_RESULT_TIMEOUT_MS
    )
  }

  @Test
  fun everyPlaybackPreparationDeadlineMeetsCarContentLoadBudget() {
    assertTrue(MediaSessionCallback.PREPARATION_TIMEOUT_MS < 10_000L)
  }

  @Test
  fun endToEndPreparationDeadlineExpiresOnlyTheCurrentGeneration() {
    val expirations = mutableListOf<Pair<Long, String>>()
    val deadline = PlaybackPreparationDeadline(
      android.os.Handler(android.os.Looper.getMainLooper()),
      100L
    ) { generation, message -> expirations += generation to message }

    deadline.schedule(1L, "stale")
    deadline.schedule(2L, "current")
    shadowOf(android.os.Looper.getMainLooper()).idleFor(99L, TimeUnit.MILLISECONDS)
    assertTrue(expirations.isEmpty())

    shadowOf(android.os.Looper.getMainLooper()).idleFor(1L, TimeUnit.MILLISECONDS)
    assertEquals(listOf(2L to "current"), expirations)

    deadline.schedule(3L, "completed")
    deadline.clear(3L)
    shadowOf(android.os.Looper.getMainLooper()).idleFor(100L, TimeUnit.MILLISECONDS)
    assertEquals(listOf(2L to "current"), expirations)
  }

  @Test
  fun invalidChapterTimerDoesNotEnterRunningState() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    assertFalse(
      service.sleepTimerManager.setManualSleepTimer(
        playbackSessionId = "missing-session",
        time = 0L,
        isChapterTime = true
      )
    )
    assertFalse(service.sleepTimerManager.isTimerRunning)

    controller.destroy()
  }

  @Test
  fun activeSleepTimerIsCancelledByServiceDestruction() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    assertTrue(
      service.sleepTimerManager.setManualSleepTimer(
        playbackSessionId = "test-session",
        time = 60_000L,
        isChapterTime = false
      )
    )
    assertTrue(service.sleepTimerManager.isTimerRunning)

    controller.destroy()

    assertFalse(service.sleepTimerManager.isTimerRunning)
    assertTrue(service.sleepTimerManager.isTerminated)
  }

  @Test
  fun fixedSleepTimerRejectsNonPositiveDurations() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    assertFalse(service.sleepTimerManager.setManualSleepTimer("session", 0L, false))
    assertFalse(service.sleepTimerManager.setManualSleepTimer("session", -1L, false))
    assertFalse(service.sleepTimerManager.isTimerRunning)

    controller.destroy()
  }

  @Test
  fun progressSyncCallbacksCompleteWhenInactiveOrAlreadyShutdown() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    var pauseCompleted = false
    var finishedCompleted = false
    var inactiveSyncCompleted = false

    service.mediaProgressSyncer.pause { pauseCompleted = true }
    service.mediaProgressSyncer.finished { finishedCompleted = true }
    service.mediaProgressSyncer.seek()
    service.mediaProgressSyncer.sync(false, 1.0) { result ->
      assertNull(result)
      inactiveSyncCompleted = true
    }

    assertTrue(pauseCompleted)
    assertTrue(finishedCompleted)
    assertTrue(inactiveSyncCompleted)

    controller.destroy()

    var shutdownSyncCompleted = false
    service.mediaProgressSyncer.sync(false, 1.0) { result ->
      assertNull(result)
      shutdownSyncCompleted = true
    }
    assertTrue(shutdownSyncCompleted)
  }

  @Test
  fun pausedElapsedTimeIsNeverCountedAsListeningAfterResume() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    val source = playbackSession("pause-ledger", "item", 7_200.0).apply {
      serverConnectionConfigId = config.id
      connectionLease = DeviceManager.captureConnectionLease(config)
    }
    var elapsedMs = 1_000L
    service.mediaProgressSyncer.shutdown()
    service.mediaProgressSyncer = MediaProgressSyncer(service, service.apiHandler) { elapsedMs }

    service.mediaProgressSyncer.start(source)
    elapsedMs = 11_500L
    val firstSync = CountDownLatch(1)
    service.mediaProgressSyncer.sync(false, 10.0) { firstSync.countDown() }
    assertTrue(firstSync.await(2, TimeUnit.SECONDS))
    assertEquals(
      10L,
      service.mediaProgressSyncer.currentPlaybackSession?.timeListening
    )

    val paused = CountDownLatch(1)
    service.mediaProgressSyncer.pause { paused.countDown() }
    assertTrue(paused.await(2, TimeUnit.SECONDS))

    // A long parked interval must not become listening debt. The 500 ms
    // remainder from the first interval plus 5.5 active seconds rounds to six.
    elapsedMs = 3_600_000L
    service.mediaProgressSyncer.start(source)
    elapsedMs += 5_500L
    val resumedSync = CountDownLatch(1)
    service.mediaProgressSyncer.sync(false, 15.0) { resumedSync.countDown() }
    assertTrue(resumedSync.await(2, TimeUnit.SECONDS))
    assertEquals(
      16L,
      service.mediaProgressSyncer.currentPlaybackSession?.timeListening
    )

    controller.destroy()
  }

  @Test
  fun bufferingElapsedTimeIsNeverCountedAsListeningAfterResume() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    val source = playbackSession("buffer-ledger", "item", 7_200.0).apply {
      serverConnectionConfigId = config.id
      connectionLease = DeviceManager.captureConnectionLease(config)
    }
    var elapsedMs = 1_000L
    service.mediaProgressSyncer.shutdown()
    service.mediaProgressSyncer = MediaProgressSyncer(service, service.apiHandler) { elapsedMs }

    service.mediaProgressSyncer.start(source)
    elapsedMs = 6_500L
    assertTrue(service.mediaProgressSyncer.suspendForBuffering(currentTime = 5.5))
    assertTrue(service.mediaProgressSyncer.isSuspendedForBuffering)
    assertFalse(service.mediaProgressSyncer.listeningTimerRunning)

    // The hour-long network stall is not active listening. The 500 ms
    // remainder on either side of the stall combines into one whole second.
    elapsedMs = 3_600_000L
    assertTrue(service.mediaProgressSyncer.resumeAfterBuffering(source))
    elapsedMs += 4_500L
    val resumedSync = CountDownLatch(1)
    service.mediaProgressSyncer.sync(false, 10.0) { resumedSync.countDown() }
    assertTrue(resumedSync.await(2, TimeUnit.SECONDS))
    assertEquals(
      10L,
      service.mediaProgressSyncer.currentPlaybackSession?.timeListening
    )

    controller.destroy()
  }

  @Test
  fun progressSyncSerializesBlockedRequestsAndShutdownCompletesEachCallbackOnce() {
    val certificate = HeldCertificate.Builder()
      .commonName("localhost")
      .addSubjectAlternativeName("localhost")
      .addSubjectAlternativeName("127.0.0.1")
      .build()
    val serverCertificates = HandshakeCertificates.Builder()
      .heldCertificate(certificate)
      .build()
    val clientCertificates = HandshakeCertificates.Builder()
      .addTrustedCertificate(certificate.certificate)
      .build()
    val client = OkHttpClient.Builder()
      .sslSocketFactory(
        clientCertificates.sslSocketFactory(),
        clientCertificates.trustManager
      )
      .build()
    val firstStarted = CountDownLatch(1)
    val releaseFirst = CountDownLatch(1)
    val thirdStarted = CountDownLatch(1)
    val releaseThird = CountDownLatch(1)
    val requestOrdinal = AtomicInteger()
    val server = MockWebServer().apply {
      useHttps(serverCertificates.sslSocketFactory(), false)
      dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
          if (!request.path.orEmpty().endsWith("/sync")) {
            return MockResponse().setResponseCode(404)
          }
          when (requestOrdinal.incrementAndGet()) {
            1 -> {
              firstStarted.countDown()
              releaseFirst.await(3, TimeUnit.SECONDS)
            }
            3 -> {
              thirdStarted.countDown()
              releaseThird.await(3, TimeUnit.SECONDS)
            }
          }
          return MockResponse().setBody("{}")
        }
      }
      start()
    }
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()

    try {
      val connectivity = service.getSystemService(ConnectivityManager::class.java)
      val connectivityShadow = shadowOf(connectivity)
      connectivityShadow.setActiveNetworkInfo(
        ShadowNetworkInfo.newInstance(
          NetworkInfo.DetailedState.CONNECTED,
          ConnectivityManager.TYPE_WIFI,
          0,
          true,
          true
        )
      )
      val activeNetwork = connectivity.activeNetwork
        ?: throw AssertionError("Robolectric did not create an active test network")
      val capabilities = ShadowNetworkCapabilities.newInstance()
      shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      connectivityShadow.setNetworkCapabilities(activeNetwork, capabilities)
      assertTrue(DeviceManager.checkConnectivity(service))

      val testHandler = ApiHandler(
        RuntimeEnvironment.getApplication(),
        SecureStorage(RuntimeEnvironment.getApplication()),
        client,
        client
      )
      service.mediaProgressSyncer.shutdown()
      service.apiHandler.shutdown()
      service.apiHandler = testHandler
      service.mediaProgressSyncer = MediaProgressSyncer(service, testHandler)

      val config = serverConnectionConfig().apply {
        address = server.url("/").toString().trimEnd('/')
      }
      DeviceManager.deviceData = DeviceData(
        mutableListOf(config),
        config.id,
        DeviceSettings.default(),
        null
      )
      assertTrue(DeviceManager.trySelectServerConnectionConfig(config))
      val session = playbackSession("serial-session", "serial-item", 120.0).apply {
        serverConnectionConfigId = config.id
        serverAddress = config.address
        connectionLease = DeviceManager.captureConnectionLease(config)
      }
      service.mediaProgressSyncer.start(session)

      val callbacks = CopyOnWriteArrayList<Int>()
      val shutdownResults = CopyOnWriteArrayList<Boolean>()
      val firstPhase = CountDownLatch(2)
      service.mediaProgressSyncer.sync(true, 10.0) {
        callbacks += 1
        firstPhase.countDown()
        throw IllegalStateException("test callback failure")
      }
      assertTrue("first request did not start", firstStarted.await(2, TimeUnit.SECONDS))
      assertEquals(
        "/api/session/serial-session/sync",
        server.takeRequest(1, TimeUnit.SECONDS)?.path
      )
      service.mediaProgressSyncer.sync(true, 20.0) {
        callbacks += 2
        firstPhase.countDown()
      }
      assertNull("second sync overlapped the first", server.takeRequest(250, TimeUnit.MILLISECONDS))

      releaseFirst.countDown()
      assertTrue("serialized callbacks timed out", firstPhase.await(3, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2), callbacks.toList())
      assertEquals(
        "/api/session/serial-session/sync",
        server.takeRequest(1, TimeUnit.SECONDS)?.path
      )
      assertEquals(20.0, service.mediaProgressSyncer.currentPlaybackSession?.currentTime ?: -1.0, 0.0)

      val shutdownPhase = CountDownLatch(2)
      service.mediaProgressSyncer.sync(true, 30.0) { result ->
        callbacks += 3
        shutdownResults += result == null
        shutdownPhase.countDown()
      }
      assertTrue("third request did not start", thirdStarted.await(2, TimeUnit.SECONDS))
      assertEquals(
        "/api/session/serial-session/sync",
        server.takeRequest(1, TimeUnit.SECONDS)?.path
      )
      service.mediaProgressSyncer.sync(true, 40.0) { result ->
        callbacks += 4
        shutdownResults += result == null
        shutdownPhase.countDown()
      }
      assertNull("queued shutdown sync was dispatched", server.takeRequest(250, TimeUnit.MILLISECONDS))

      service.mediaProgressSyncer.shutdown()
      assertTrue("shutdown did not drain callbacks", shutdownPhase.await(1, TimeUnit.SECONDS))
      assertEquals(listOf(1, 2, 3, 4), callbacks.toList())
      assertEquals(listOf(true, true), shutdownResults.toList())

      releaseThird.countDown()
      val networkDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
      while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < networkDeadline) {
        Thread.sleep(10)
      }
      assertEquals("late network completion invoked a callback twice", 4, callbacks.size)
      assertEquals("shutdown dispatched queued work", 3, server.requestCount)
    } finally {
      releaseFirst.countDown()
      releaseThird.countDown()
      controller.destroy()
      server.shutdown()
    }
  }

  @Test
  fun finishedProgressEventUsesCapturedSessionBeforeReset() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    val session = playbackSession("finished-session", "finished-item", trackDuration = 60.0)
      .apply {
        serverConnectionConfigId = config.id
        connectionLease = DeviceManager.captureConnectionLease(config)
      }
    val completed = CountDownLatch(1)

    service.mediaProgressSyncer.start(session)
    service.mediaProgressSyncer.finished { completed.countDown() }

    assertTrue(completed.await(2, TimeUnit.SECONDS))
    assertNull(service.mediaProgressSyncer.currentPlaybackSession)
    assertTrue(
      DeviceManager.dbManager
        .getMediaItemHistory("finished-item")
        ?.events
        ?.any { it.name == "Finished" } == true
    )

    controller.destroy()
  }

  @Test
  fun nonFiniteProgressIsRejectedWithoutMutatingSession() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    val session = playbackSession("finite-session", "finite-item", trackDuration = 60.0)
      .apply {
        serverConnectionConfigId = config.id
        connectionLease = DeviceManager.captureConnectionLease(config)
      }
    val callbackInvoked = CountDownLatch(1)

    service.mediaProgressSyncer.start(session)
    service.mediaProgressSyncer.sync(false, Double.NaN) { result ->
      assertNull(result)
      callbackInvoked.countDown()
    }

    assertTrue(callbackInvoked.await(2, TimeUnit.SECONDS))
    assertEquals(0.0, service.mediaProgressSyncer.currentPlaybackSession?.currentTime ?: -1.0, 0.0)

    controller.destroy()
  }

  @Test
  fun accountRemovalCannotRecreateQueuedRemotePlaybackSession() {
    val controller = Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = controller.get()
    val config = serverConnectionConfig()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    val session = playbackSession("removed-session", "private-item", 60.0).apply {
      serverConnectionConfigId = config.id
      connectionLease = DeviceManager.captureConnectionLease(config)
    }
    DeviceManager.dbManager.savePlaybackSession(session)
    service.mediaProgressSyncer.start(session)
    assertNotNull(service.mediaProgressSyncer.currentPlaybackSession)

    assertTrue(
      ShelfDriveConnectionDataCleaner(RuntimeEnvironment.getApplication())
        .removeConnectionData(config.id)
    )
    shadowOf(android.os.Looper.getMainLooper()).idle()

    assertNull(service.mediaProgressSyncer.currentPlaybackSession)
    assertTrue(
      DeviceManager.dbManager.getPlaybackSessions().none {
        it.serverConnectionConfigId == config.id
      }
    )

    // A stale player callback cannot restart persistence for the removed owner.
    service.mediaProgressSyncer.start(session)
    assertNull(service.mediaProgressSyncer.currentPlaybackSession)
    assertTrue(DeviceManager.dbManager.getPlaybackSessions().isEmpty())

    controller.destroy()
  }

  private fun playbackSession(
    sessionId: String,
    libraryItemId: String,
    trackDuration: Double
  ) = PlaybackSession(
    id = sessionId,
    userId = "user",
    libraryItemId = libraryItemId,
    episodeId = null,
    mediaType = "book",
    mediaMetadata = MediaTypeMetadata("Title", false),
    deviceInfo = DeviceInfo("device", "maker", "model", 28, "1"),
    chapters = emptyList(),
    displayTitle = "Title",
    displayAuthor = "Author",
    coverPath = null,
    duration = trackDuration,
    playMethod = PLAYMETHOD_DIRECTPLAY,
    startedAt = 1L,
    updatedAt = 1L,
    timeListening = 0L,
    audioTracks = mutableListOf(
      AudioTrack(
        index = 0,
        startOffset = 0.0,
        duration = trackDuration,
        title = "Track",
        contentUrl = "https://example.test/audio",
        mimeType = "audio/mpeg",
        metadata = null,
        isLocal = false,
        localFileId = null,
        serverIndex = 0
      )
    ),
    currentTime = 0.0,
    libraryItem = null,
    localLibraryItem = null,
    localEpisodeId = null,
    serverConnectionConfigId = null,
    serverAddress = "https://example.test",
    mediaPlayer = null
  )

  private fun serverConnectionConfig() = ServerConnectionConfig(
    id = "server",
    index = 0,
    name = "Server",
    address = "https://example.test",
    version = "2.0.0",
    userId = "user",
    username = "reviewer",
    token = "token",
    customHeaders = null
  )

  private fun localLibraryItem(id: String, tracks: MutableList<AudioTrack>) = LocalLibraryItem(
    id = id,
    folderId = "downloads",
    basePath = "",
    absolutePath = "",
    contentUrl = "",
    isInvalid = false,
    mediaType = "book",
    media = Book(
      metadata = BookMetadata(
        "Title",
        null,
        null,
        null,
        mutableListOf(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        null,
        null,
        null,
        null,
        null
      ),
      coverPath = null,
      tags = emptyList(),
      audioFiles = null,
      chapters = null,
      tracks = tracks.toMutableList(),
      ebookFile = null,
      size = null,
      duration = tracks.sumOf { it.duration },
      numTracks = tracks.size
    ),
    localFiles = mutableListOf(),
    coverContentUrl = null,
    coverAbsolutePath = null,
    isLocal = true,
    serverConnectionConfigId = null,
    serverAddress = null,
    serverUserId = null,
    libraryItemId = null
  )

  private fun podcastLibraryItem(id: String) = LibraryItem(
    id = id,
    ino = "",
    libraryId = "library",
    folderId = "folder",
    path = "",
    relPath = "",
    mtimeMs = 0L,
    ctimeMs = 0L,
    birthtimeMs = 0L,
    addedAt = 0L,
    updatedAt = 0L,
    lastScan = null,
    scanVersion = null,
    isMissing = false,
    isInvalid = false,
    mediaType = "podcast",
    media = Podcast(
      PodcastMetadata("Podcast", "Author", null, mutableListOf(), false),
      null,
      mutableListOf(),
      mutableListOf(),
      false,
      0
    ),
    libraryFiles = null,
    userMediaProgress = null,
    collapsedSeries = null,
    localLibraryItemId = null,
    recentEpisode = null
  )

  private fun writeSilentWav(file: File, durationSeconds: Int): File {
    val sampleRate = 8_000
    val dataSize = sampleRate * durationSeconds
    val bytes = ByteArray(44 + dataSize)
    "RIFF".toByteArray().copyInto(bytes, 0)
    "WAVE".toByteArray().copyInto(bytes, 8)
    "fmt ".toByteArray().copyInto(bytes, 12)
    "data".toByteArray().copyInto(bytes, 36)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply {
      putInt(4, 36 + dataSize)
      putInt(16, 16)
      putShort(20, 1.toShort())
      putShort(22, 1.toShort())
      putInt(24, sampleRate)
      putInt(28, sampleRate)
      putShort(32, 1.toShort())
      putShort(34, 8.toShort())
      putInt(40, dataSize)
    }
    bytes.fill(128.toByte(), 44)
    file.writeBytes(bytes)
    return file
  }

  private fun awaitCondition(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
    while (System.nanoTime() < deadline) {
      shadowOf(android.os.Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(10)
    }
    throw AssertionError("Timed out waiting for $description")
  }

  private fun emptyDeviceData() = DeviceData(
    serverConnectionConfigs = mutableListOf(),
    lastServerConnectionConfigId = null,
    deviceSettings = DeviceSettings.default(),
    lastPlaybackSession = null
  )
}
