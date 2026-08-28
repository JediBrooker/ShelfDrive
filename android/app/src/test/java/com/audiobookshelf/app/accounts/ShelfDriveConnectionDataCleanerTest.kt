package com.audiobookshelf.app.accounts

import android.content.Context
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.LocalMediaProgress
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.player.PLAYMETHOD_DIRECTPLAY
import io.paperdb.Paper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfDriveConnectionDataCleanerTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Paper.init(context)
    TEST_BOOKS.forEach { Paper.book(it).destroy() }
    context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences(PURGE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    DeviceManager.deviceData = emptyDeviceData()
    DeviceManager.serverConnectionConfig = null
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData = emptyDeviceData()
    DeviceManager.serverConnectionConfig = null
    TEST_BOOKS.forEach { Paper.book(it).destroy() }
    context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE).edit().clear().commit()
    context.getSharedPreferences(PURGE_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
  }

  @Test
  fun removalDeletesTargetAndSelectsAnAuthenticatedRetainedProfile() {
    val removed = config("connection-1")
    val retained = config("connection-2")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(removed, retained),
      removed.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = removed
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE)
      .edit()
      .putString("refresh_token_${removed.id}", "encrypted-placeholder")
      .commit()
    assertTrue(SecureStorage(context).hasRefreshToken(removed.id))

    assertTrue(ShelfDriveConnectionDataCleaner(context).removeConnectionData(removed.id))

    assertEquals(listOf(retained), DeviceManager.deviceData.serverConnectionConfigs)
    assertEquals(retained.id, DeviceManager.deviceData.lastServerConnectionConfigId)
    assertEquals(retained, DeviceManager.serverConnectionConfig)
    assertFalse(SecureStorage(context).hasRefreshToken(removed.id))
    assertEquals(
      listOf(retained),
      DeviceManager.dbManager.getDeviceData().serverConnectionConfigs
    )
  }

  @Test
  fun capturedConfigCannotBeSelectedAfterAccountRemoval() {
    val removed = config("connection-race")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(removed),
      removed.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = removed
    val epochBeforeRemoval = DeviceManager.currentConnectionStateEpoch()
    val oldLease = DeviceManager.captureConnectionLease(removed)
      ?: throw AssertionError("No initial connection lease")

    assertTrue(ShelfDriveConnectionDataCleaner(context).removeConnectionData(removed.id))

    assertTrue(DeviceManager.currentConnectionStateEpoch() > epochBeforeRemoval)
    assertFalse(DeviceManager.isConnectionStateCurrent(epochBeforeRemoval, removed.id))
    assertFalse(DeviceManager.trySelectServerConnectionConfig(removed))
    assertNull(DeviceManager.serverConnectionConfig)
    assertFalse(DeviceManager.isServerConnectionConfigSaved(removed.id))

    val replacement = config(removed.id).apply { token = "replacement-access-token" }
    synchronized(DeviceManager.connectionPersistenceMonitor) {
      synchronized(DeviceManager.connectionStateMonitor) {
        DeviceManager.advanceConnectionStateEpoch()
        DeviceManager.deviceData.serverConnectionConfigs.add(replacement)
        DeviceManager.deviceData.lastServerConnectionConfigId = replacement.id
        assertTrue(DeviceManager.trySelectServerConnectionConfig(replacement))
      }
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    }

    assertFalse(DeviceManager.isConnectionLeaseCurrent(oldLease))
    assertNull(DeviceManager.getServerConnectionConfig(oldLease))
    assertFalse(DeviceManager.trySelectServerConnectionConfig(removed, oldLease))
    assertEquals(replacement, DeviceManager.serverConnectionConfig)
  }

  @Test
  fun removingOneProfileDoesNotInvalidateAnotherProfilesLease() {
    val removed = config("connection-a")
    val retained = config("connection-b")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(removed, retained),
      retained.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = retained
    val retainedLease = DeviceManager.captureConnectionLease(retained)
      ?: throw AssertionError("No retained connection lease")

    assertTrue(ShelfDriveConnectionDataCleaner(context).removeConnectionData(removed.id))

    assertTrue(DeviceManager.isConnectionLeaseCurrent(retainedLease))
    assertEquals(retained, DeviceManager.getServerConnectionConfig(retainedLease))
    assertEquals(retained, DeviceManager.serverConnectionConfig)
    assertTrue(DeviceManager.trySelectServerConnectionConfig(retained, retainedLease))
  }

  @Test
  fun credentialRemovalFailureLeavesProfileSelectionAndPersistenceUntouched() {
    val config = config("connection-storage-failure")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    val storage = FailingRemovalStorage(config.id, "refresh-token")

    assertFalse(ShelfDriveConnectionDataCleaner(context, storage).removeConnectionData(config.id))

    assertEquals(listOf(config), DeviceManager.deviceData.serverConnectionConfigs)
    assertEquals(config.id, DeviceManager.deviceData.lastServerConnectionConfigId)
    assertEquals(config, DeviceManager.serverConnectionConfig)
    assertEquals(
      listOf(config),
      DeviceManager.dbManager.getDeviceData().serverConnectionConfigs
    )
    assertEquals("refresh-token", storage.getRefreshToken(config.id))
  }

  @Test
  fun durableCommitFailureRestoresCredentialWithoutPublishingOrPurgingCandidate() {
    val removed = config("connection-commit-failure")
    val retained = config("connection-retained")
    val original = DeviceData(
      mutableListOf(removed, retained),
      removed.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.deviceData = original
    DeviceManager.serverConnectionConfig = removed
    DeviceManager.dbManager.saveDeviceData(original)
    val storage = MutableRefreshTokenStorage(removed.id, "refresh-token")
    val persistence = FailingCommitPersistence()
    val epochBeforeRemoval = DeviceManager.currentConnectionStateEpoch()

    assertFalse(
      ShelfDriveConnectionDataCleaner(context, storage, persistence)
        .removeConnectionData(removed.id)
    )

    assertTrue(persistence.initialized)
    assertEquals(listOf(retained.id), persistence.candidateConnectionIds)
    assertFalse(persistence.ancillaryRemovalCalled)
    assertTrue(DeviceManager.deviceData === original)
    assertEquals(listOf(removed, retained), DeviceManager.deviceData.serverConnectionConfigs)
    assertEquals(removed.id, DeviceManager.deviceData.lastServerConnectionConfigId)
    assertEquals(removed, DeviceManager.serverConnectionConfig)
    assertEquals(epochBeforeRemoval, DeviceManager.currentConnectionStateEpoch())
    assertEquals(
      listOf(removed, retained),
      DeviceManager.dbManager.getDeviceData().serverConnectionConfigs
    )
    assertEquals("refresh-token", storage.getRefreshToken(removed.id))
    assertEquals(1, storage.removalCount)
    assertEquals(1, storage.restorationCount)
  }

  @Test
  fun ancillaryFailureLeavesDurableTombstoneAndStartupRetryCompletesPurge() {
    val removed = config("connection-retry")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(removed),
      removed.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = removed
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    DeviceManager.dbManager.savePlaybackSession(playbackSession(removed.id))
    val storage = MutableRefreshTokenStorage(removed.id, "refresh-token")
    val persistence = AncillaryFailureAfterCommitPersistence()

    assertTrue(
      ShelfDriveConnectionDataCleaner(context, storage, persistence)
        .removeConnectionData(removed.id)
    )

    assertTrue(persistence.commitCalled)
    assertTrue(DeviceManager.dbManager.getPlaybackSessions().any {
      it.serverConnectionConfigId == removed.id
    })
    assertTrue(pendingPurgeIds().contains(removed.id))
    assertTrue(DeviceManager.deviceData.serverConnectionConfigs.isEmpty())

    // Simulate the next service start. The durable profile is already gone,
    // so retry only needs to finish owner-scoped ancillary cleanup.
    ShelfDriveConnectionDataCleaner.retryPendingPurges(context)

    assertTrue(DeviceManager.dbManager.getPlaybackSessions().none {
      it.serverConnectionConfigId == removed.id
    })
    assertFalse(pendingPurgeIds().contains(removed.id))
  }

  @Test
  fun removalKeepsDownloadsPlayableButStripsServerIdentityAndPendingTransfers() {
    val removed = config("connection-private-data")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(removed),
      removed.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = removed
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)

    val localMedia = MediaType(MediaTypeMetadata("Downloaded title", false), null)
    val localItem = LocalLibraryItem(
      id = "local-private-book",
      folderId = "internal-book",
      basePath = "/local",
      absolutePath = "/local/private-book",
      contentUrl = "content://shelfdrive/local-private-book",
      isInvalid = false,
      mediaType = "book",
      media = localMedia,
      localFiles = mutableListOf(),
      coverContentUrl = null,
      coverAbsolutePath = null,
      isLocal = true,
      serverConnectionConfigId = removed.id,
      serverAddress = removed.address,
      serverUserId = removed.userId,
      libraryItemId = "remote-private-book"
    )
    DeviceManager.dbManager.saveLocalLibraryItem(localItem)
    DeviceManager.dbManager.saveLocalMediaProgress(
      LocalMediaProgress(
        id = localItem.id,
        localLibraryItemId = localItem.id,
        localEpisodeId = null,
        duration = 100.0,
        progress = 0.42,
        currentTime = 42.0,
        isFinished = false,
        ebookLocation = null,
        ebookProgress = null,
        lastUpdate = 10L,
        startedAt = 1L,
        finishedAt = null,
        serverConnectionConfigId = removed.id,
        serverAddress = removed.address,
        serverUserId = removed.userId,
        libraryItemId = "remote-private-book",
        episodeId = null
      )
    )
    val localFolder = LocalFolder(
      "internal-book",
      "Internal storage",
      "",
      "",
      "",
      "",
      "internal",
      "book"
    )
    DeviceManager.dbManager.saveDownloadItem(
      DownloadItem(
        id = "pending-private-book",
        libraryItemId = "remote-private-book",
        episodeId = null,
        userMediaProgress = null,
        serverConnectionConfigId = removed.id,
        serverAddress = removed.address,
        serverUserId = removed.userId,
        mediaType = "book",
        itemFolderPath = "/local/private-book",
        localFolder = localFolder,
        itemTitle = "Downloaded title",
        itemSubfolder = "Downloaded title",
        media = localMedia,
        downloadItemParts = mutableListOf()
      )
    )

    assertTrue(ShelfDriveConnectionDataCleaner(context).removeConnectionData(removed.id))

    val retainedItem = DeviceManager.dbManager.getLocalLibraryItem(localItem.id)
      ?: throw AssertionError("Downloaded item was deleted")
    assertEquals("content://shelfdrive/local-private-book", retainedItem.contentUrl)
    assertNull(retainedItem.serverConnectionConfigId)
    assertNull(retainedItem.serverAddress)
    assertNull(retainedItem.serverUserId)
    assertNull(retainedItem.libraryItemId)

    val retainedProgress = DeviceManager.dbManager.getLocalMediaProgress(localItem.id)
      ?: throw AssertionError("Downloaded progress was deleted")
    assertEquals(42.0, retainedProgress.currentTime, 0.0)
    assertNull(retainedProgress.serverConnectionConfigId)
    assertNull(retainedProgress.serverAddress)
    assertNull(retainedProgress.serverUserId)
    assertNull(retainedProgress.libraryItemId)
    assertNull(retainedProgress.episodeId)
    assertTrue(DeviceManager.dbManager.getDownloadItems().isEmpty())
  }

  private fun config(id: String) = ServerConnectionConfig(
    id = id,
    index = 0,
    name = "example.test (reviewer)",
    address = "https://example.test",
    version = "2.26.0",
    userId = "user-1",
    username = "reviewer",
    token = "access-token",
    customHeaders = null
  )

  private fun emptyDeviceData() =
    DeviceData(mutableListOf(), null, DeviceSettings.default(), null)

  private fun playbackSession(connectionId: String) = PlaybackSession(
    id = "queued-$connectionId",
    userId = "user-1",
    libraryItemId = "private-item",
    episodeId = null,
    mediaType = "book",
    mediaMetadata = MediaTypeMetadata("Title", false),
    deviceInfo = DeviceInfo("device", "maker", "model", 35, "1"),
    chapters = emptyList(),
    displayTitle = "Title",
    displayAuthor = "Author",
    coverPath = null,
    duration = 60.0,
    playMethod = PLAYMETHOD_DIRECTPLAY,
    startedAt = 1L,
    updatedAt = 1L,
    timeListening = 0L,
    audioTracks = mutableListOf(
      AudioTrack(0, 0.0, 60.0, "Track", "https://example.test/audio", "audio/mpeg", null, false, null, 0)
    ),
    currentTime = 5.0,
    libraryItem = null,
    localLibraryItem = null,
    localEpisodeId = null,
    serverConnectionConfigId = connectionId,
    serverAddress = "https://example.test",
    mediaPlayer = null
  )

  private fun pendingPurgeIds(): Set<String> =
    context.getSharedPreferences(PURGE_PREFS, Context.MODE_PRIVATE)
      .getStringSet(PURGE_IDS, emptySet())
      .orEmpty()

  private companion object {
    const val PURGE_PREFS = "ShelfDrivePendingConnectionPurges"
    const val PURGE_IDS = "connection_ids"
    val TEST_BOOKS = listOf(
      "device",
      "playbackSession",
      "localLibraryItems",
      "localMediaProgress",
      "downloadItems",
      "mediaItemHistory"
    )
  }

  private class FailingRemovalStorage(
    private val connectionId: String,
    private val token: String
  ) : RefreshTokenStorage {
    override fun storeRefreshToken(serverConnectionId: String, refreshToken: String): Boolean =
      false

    override fun getRefreshToken(serverConnectionId: String): String? =
      token.takeIf { serverConnectionId == connectionId }

    override fun removeRefreshToken(serverConnectionId: String): Boolean = false

    override fun hasRefreshToken(serverConnectionId: String): Boolean =
      serverConnectionId == connectionId
  }

  private class MutableRefreshTokenStorage(
    private val connectionId: String,
    initialToken: String
  ) : RefreshTokenStorage {
    private var token: String? = initialToken
    var removalCount = 0
      private set
    var restorationCount = 0
      private set

    override fun storeRefreshToken(serverConnectionId: String, refreshToken: String): Boolean {
      if (serverConnectionId != connectionId) return false
      token = refreshToken
      restorationCount++
      return true
    }

    override fun getRefreshToken(serverConnectionId: String): String? =
      token.takeIf { serverConnectionId == connectionId }

    override fun removeRefreshToken(serverConnectionId: String): Boolean {
      if (serverConnectionId != connectionId) return false
      token = null
      removalCount++
      return true
    }

    override fun hasRefreshToken(serverConnectionId: String): Boolean =
      serverConnectionId == connectionId && token != null
  }

  private class FailingCommitPersistence : ConnectionDataPersistence {
    var initialized = false
      private set
    var candidateConnectionIds: List<String> = emptyList()
      private set
    var ancillaryRemovalCalled = false
      private set

    override fun initialize(context: Context) {
      initialized = true
    }

    override fun commitDeviceData(deviceData: DeviceData) {
      candidateConnectionIds = deviceData.serverConnectionConfigs.map { it.id }
      throw IllegalStateException("injected durable commit failure")
    }

    override fun removeAncillaryConnectionData(connectionId: String) {
      ancillaryRemovalCalled = true
    }
  }

  private class AncillaryFailureAfterCommitPersistence : ConnectionDataPersistence {
    var commitCalled = false
      private set

    override fun initialize(context: Context) = Unit

    override fun commitDeviceData(deviceData: DeviceData) {
      DeviceManager.dbManager.saveDeviceData(deviceData)
      commitCalled = true
    }

    override fun removeAncillaryConnectionData(connectionId: String) {
      throw IllegalStateException("injected ancillary cleanup interruption")
    }
  }
}
