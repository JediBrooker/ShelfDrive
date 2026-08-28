package com.audiobookshelf.app.accounts

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
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
import com.audiobookshelf.app.downloads.OfflineDownloadPlanner
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfDriveConnectionDataCleanerTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Paper.init(context)
    TEST_BOOKS.forEach { Paper.book(it).destroy() }
    ShadowDownloadManager.reset()
    OfflineDownloadPlanner.managedRoot(context)?.deleteRecursively()
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
    OfflineDownloadPlanner.managedRoot(context)?.deleteRecursively()
    ShadowDownloadManager.reset()
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

  @Test
  fun disconnectCancelsManagedPlatformTransferAndDeletesOnlyItsPrivateJobDirectory() {
    val removed = config("connection-managed-download")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(removed),
      removed.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = removed
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)

    val root = checkNotNull(OfflineDownloadPlanner.managedRoot(context))
    val jobId = OfflineDownloadPlanner.jobId(removed.id, "remote-managed-book")
    val jobFolder = File(root, jobId)
    val finalFile = File(jobFolder, "track-0001.mp3")
    val partialFile = File(jobFolder, "track-0001.mp3.part")
    assertTrue(jobFolder.mkdirs())
    partialFile.writeBytes("partial-audio".toByteArray())
    val source = Uri.parse(
      "https://example.test/api/items/remote-managed-book/file/audio-ino/download"
    )
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    // Robolectric starts synthetic IDs at zero, while Android's real IDs are
    // positive. Queue the unrelated row first so the managed row exercises
    // the production `id > 0` guard with a realistic ID.
    val unrelatedId = downloadManager.enqueue(
      DownloadManager.Request(Uri.parse("https://example.test/unrelated"))
        .setDestinationUri(Uri.fromFile(File(root.parentFile, "unrelated.part")))
    )
    val platformId = downloadManager.enqueue(
      DownloadManager.Request(source).setDestinationUri(Uri.fromFile(partialFile))
    )
    val managedFolder = LocalFolder(
      id = OfflineDownloadPlanner.MANAGED_FOLDER_ID,
      name = "ShelfDrive offline storage",
      contentUrl = "",
      basePath = root.absolutePath,
      absolutePath = root.absolutePath,
      simplePath = "offline",
      storageType = "internal",
      mediaType = "book"
    )
    DeviceManager.dbManager.saveDownloadItem(
      DownloadItem(
        id = jobId,
        libraryItemId = "remote-managed-book",
        episodeId = null,
        userMediaProgress = null,
        serverConnectionConfigId = removed.id,
        serverAddress = removed.address,
        serverUserId = removed.userId,
        mediaType = "book",
        itemFolderPath = jobFolder.absolutePath,
        localFolder = managedFolder,
        itemTitle = "Managed book",
        itemSubfolder = jobId,
        media = MediaType(MediaTypeMetadata("Managed book", false), null),
        downloadItemParts = mutableListOf(
          DownloadItemPart(
            id = "managed-part",
            downloadItemId = jobId,
            filename = finalFile.name,
            fileSize = partialFile.length(),
            finalDestinationPath = finalFile.absolutePath,
            serverPath = source.encodedPath.orEmpty(),
            localFolderName = managedFolder.name,
            localFolderUrl = "",
            localFolderId = managedFolder.id,
            ebookFile = null,
            audioTrack = null,
            episode = null,
            completed = false,
            moved = false,
            isMoving = false,
            failed = false,
            uri = source,
            destinationUri = Uri.fromFile(partialFile),
            finalDestinationUri = Uri.fromFile(finalFile),
            finalDestinationSubfolder = jobId,
            downloadId = platformId,
            progress = 0L,
            bytesDownloaded = 0L
          )
        )
      )
    )
    val shadowDownloadManager = shadowOf(downloadManager)
    assertEquals(2, shadowDownloadManager.requestCount)

    assertTrue(ShelfDriveConnectionDataCleaner(context).removeConnectionData(removed.id))

    assertNull(shadowDownloadManager.getRequest(platformId))
    assertEquals(1, shadowDownloadManager.requestCount)
    assertTrue(shadowDownloadManager.getRequest(unrelatedId) != null)
    assertTrue(DeviceManager.dbManager.getDownloadItems().isEmpty())
    assertFalse(jobFolder.exists())
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
