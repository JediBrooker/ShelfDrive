package com.audiobookshelf.app.downloads

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import com.audiobookshelf.app.data.AudioFile
import com.audiobookshelf.app.data.AudioTrack
import com.audiobookshelf.app.data.Book
import com.audiobookshelf.app.data.BookMetadata
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.DownloadUsingCellularSetting
import com.audiobookshelf.app.data.FileMetadata
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.models.DownloadItemPart
import io.paperdb.Paper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OfflineDownloadPlannerTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Paper.init(context)
    TEST_BOOKS.forEach { Paper.book(it).destroy() }
    ShadowDownloadManager.reset()
    OfflineDownloadPlanner.managedRoot(context)?.deleteRecursively()
  }

  @After
  fun tearDown() {
    OfflineDownloadPlanner.managedRoot(context)?.deleteRecursively()
    ShadowDownloadManager.reset()
    TEST_BOOKS.forEach { Paper.book(it).destroy() }
  }

  @Test
  fun `plain HTTP server is rejected before a download plan is created`() {
    val result = OfflineDownloadPlanner.plan(
      context,
      audiobook(),
      owner(address = "http://library.example.test")
    )

    assertEquals(
      OfflinePlanResult.Rejected(OfflinePlanFailure.INVALID_SERVER),
      result
    )
  }

  @Test
  fun `non audiobook and audiobook without tracks are rejected explicitly`() {
    val nonAudiobook = audiobook().apply { mediaType = "podcast" }
    val noAudio = audiobook(tracks = mutableListOf(), audioFiles = emptyList())

    assertEquals(
      OfflinePlanResult.Rejected(OfflinePlanFailure.NOT_AUDIOBOOK),
      OfflineDownloadPlanner.plan(context, nonAudiobook, owner())
    )
    assertEquals(
      OfflinePlanResult.Rejected(OfflinePlanFailure.NO_AUDIO),
      OfflineDownloadPlanner.plan(context, noAudio, owner())
    )
  }

  @Test
  fun `oversized track lists are rejected before platform rows are planned`() {
    val tracks = (0..OfflineDownloadPlanner.MAX_TRACKS_PER_BOOK).map { index ->
      track(path = "/server/book/$index.mp3").copy(index = index, serverIndex = index)
    }.toMutableList()
    val files = tracks.map { track ->
      audioFile(
        ino = "ino-${track.index}",
        path = checkNotNull(track.metadata).path
      ).copy(index = track.index)
    }

    assertEquals(
      OfflinePlanResult.Rejected(OfflinePlanFailure.CAPACITY_EXCEEDED),
      OfflineDownloadPlanner.plan(context, audiobook(tracks = tracks, audioFiles = files), owner())
    )
  }

  @Test
  fun `planned size falls back to matched audio file metadata`() {
    val item = audiobook()
    val book = item.media as Book
    checkNotNull(book.audioFiles).single().metadata.size = 4_096L

    val part = ready(OfflineDownloadPlanner.plan(context, item, owner()))
      .item.downloadItemParts.single()

    assertEquals(4_096L, part.fileSize)
  }

  @Test
  fun `bearer token is never placed in a planned download URL`() {
    val secret = "never-put-this-token-in-a-uri"
    val ready = ready(
      OfflineDownloadPlanner.plan(
        context,
        audiobook(),
        owner(token = secret)
      )
    )

    ready.item.downloadItemParts.forEach { part ->
      assertFalse(part.uri.toString().contains(secret))
      assertFalse(part.serverPath.contains(secret))
      assertEquals(null, part.uri.query)
      assertEquals(null, part.uri.fragment)
      assertEquals("https", part.uri.scheme)
      assertEquals(part.uri.encodedPath, part.serverPath)
    }
  }

  @Test
  fun `same server item is isolated by account in jobs paths and local ids`() {
    val libraryItem = audiobook(id = "shared-server-item")
    val accountA = ready(
      OfflineDownloadPlanner.plan(context, libraryItem, owner(id = "account-a"))
    ).item
    val accountB = ready(
      OfflineDownloadPlanner.plan(context, libraryItem, owner(id = "account-b"))
    ).item

    assertNotEquals(accountA.id, accountB.id)
    assertNotEquals(accountA.itemFolderPath, accountB.itemFolderPath)
    assertNotEquals(
      OfflineDownloadPlanner.localLibraryItemId(accountA),
      OfflineDownloadPlanner.localLibraryItemId(accountB)
    )
    assertFalse(accountA.id.contains("account-a"))
    assertFalse(accountB.id.contains("account-b"))
    assertFalse(accountA.id.contains(libraryItem.id))
    assertFalse(accountB.id.contains(libraryItem.id))

    val root = checkNotNull(OfflineDownloadPlanner.managedRoot(context)).canonicalFile
    listOf(accountA, accountB).forEach { item ->
      val folder = File(item.itemFolderPath).canonicalFile
      assertEquals(root, folder.parentFile)
      assertTrue(folder.toPath().startsWith(root.toPath()))
    }
  }

  @Test
  fun `separate attempts for the same account and book never share a job directory`() {
    val item = audiobook(id = "same-item")
    val first = ready(
      OfflineDownloadPlanner.plan(context, item, owner(), jobInstanceToken = "attempt-one")
    ).item
    val second = ready(
      OfflineDownloadPlanner.plan(context, item, owner(), jobInstanceToken = "attempt-two")
    ).item

    assertNotEquals(first.id, second.id)
    assertNotEquals(first.itemFolderPath, second.itemFolderPath)
    assertEquals(
      OfflineDownloadPlanner.localLibraryItemId(first),
      OfflineDownloadPlanner.localLibraryItemId(second)
    )
  }

  @Test
  fun `server supplied traversal strings cannot control local paths or filenames`() {
    val maliciousItemId = "../../outside?token=server-secret#fragment"
    val maliciousIno = "../audio/../../private?token=server-secret"
    val maliciousExtension = "../../MP3?token=server-secret"
    val ready = ready(
      OfflineDownloadPlanner.plan(
        context,
        audiobook(
          id = maliciousItemId,
          tracks = mutableListOf(
            track(path = "../../server/path/audio", extension = maliciousExtension)
          ),
          audioFiles = listOf(
            audioFile(
              ino = maliciousIno,
              path = "../../server/path/audio",
              extension = maliciousExtension
            )
          )
        ),
        owner()
      )
    )

    val itemFolder = File(ready.item.itemFolderPath).canonicalFile
    val root = checkNotNull(OfflineDownloadPlanner.managedRoot(context)).canonicalFile
    assertEquals(root, itemFolder.parentFile)
    assertTrue(itemFolder.name.matches(Regex("aaos-download-[a-f0-9]{32}")))

    ready.item.downloadItemParts.forEach { part ->
      val finalFile = File(part.finalDestinationPath).canonicalFile
      val partialFile = File(checkNotNull(part.destinationUri.path)).canonicalFile

      assertEquals(itemFolder, finalFile.parentFile)
      assertEquals(itemFolder, partialFile.parentFile)
      assertTrue(finalFile.name.matches(Regex("track-[0-9]{4}\\.[a-z0-9]{1,10}")))
      assertEquals("${finalFile.name}.part", partialFile.name)
      assertFalse(finalFile.path.contains("server-secret"))
      assertFalse(finalFile.path.contains(".."))
      assertFalse(part.uri.encodedPath.orEmpty().contains("/../"))
      assertEquals(null, part.uri.query)
      assertEquals(null, part.uri.fragment)
    }
  }

  @Test
  fun `managed path check rejects the root traversal and lookalike siblings`() {
    val root = checkNotNull(OfflineDownloadPlanner.managedRoot(context)).canonicalFile
    val containedBook = File(root, "aaos-download-contained")
    val traversedOutside = File(root, "../outside-book")
    val lookalikeSibling = File(root.parentFile, "${root.name}-lookalike/book")

    assertTrue(
      OfflineDownloadPlanner.isManagedItemPath(context, containedBook.absolutePath)
    )
    assertFalse(OfflineDownloadPlanner.isManagedItemPath(context, root.absolutePath))
    assertFalse(
      OfflineDownloadPlanner.isManagedItemPath(context, traversedOutside.absolutePath)
    )
    assertFalse(
      OfflineDownloadPlanner.isManagedItemPath(context, lookalikeSibling.absolutePath)
    )
  }

  @Test
  fun `managed downloads use scoped local ids while legacy downloads retain old ids`() {
    val managed = ready(
      OfflineDownloadPlanner.plan(
        context,
        audiobook(id = "library-item"),
        owner(id = "account-id")
      )
    ).item
    val legacy = managed.copy(
      id = "legacy-download-library-item",
      localFolder = managed.localFolder.copy(id = "internal-downloads")
    )

    assertTrue(OfflineDownloadPlanner.isManagedJob(managed))
    assertFalse(OfflineDownloadPlanner.isManagedJob(legacy))
    assertNotEquals("local_library-item", OfflineDownloadPlanner.localLibraryItemId(managed))
    assertEquals("local_library-item", OfflineDownloadPlanner.localLibraryItemId(legacy))
    assertTrue(
      OfflineDownloadPlanner.localLibraryItemId(managed)
        .matches(Regex("local_[a-f0-9]{32}"))
    )
  }

  @Test
  fun `coordinator state is account scoped and ignores legacy jobs`() {
    val libraryItem = audiobook(id = "shared-server-item")
    val managedA = ready(
      OfflineDownloadPlanner.plan(context, libraryItem, owner(id = "account-a"))
    ).item.copy(downloadItemParts = mutableListOf())
    val managedB = ready(
      OfflineDownloadPlanner.plan(context, libraryItem, owner(id = "account-b"))
    ).item.copy(downloadItemParts = mutableListOf())
    val legacyB = managedB.copy(
      id = "legacy-shared-server-item",
      localFolder = managedB.localFolder.copy(id = "internal-downloads")
    )
    DeviceManager.dbManager.saveDownloadItem(managedA)
    DeviceManager.dbManager.saveDownloadItem(legacyB)
    val coordinator = OfflineDownloadCoordinator(context)

    assertEquals(
      OfflineDownloadState.ACTIVE,
      coordinator.state("account-a", libraryItem.id)
    )
    assertEquals(
      OfflineDownloadState.NONE,
      coordinator.state("account-b", libraryItem.id)
    )
    assertEquals(
      OfflineDownloadState.NONE,
      coordinator.state("account-a", "different-item")
    )

    DeviceManager.dbManager.saveDownloadItem(managedB)
    assertEquals(
      OfflineDownloadState.ACTIVE,
      coordinator.state("account-b", libraryItem.id)
    )
  }

  @Test
  fun `enqueue uses a lease owned platform request without putting token in URI`() {
    val priorData = DeviceManager.deviceData
    val priorActive = DeviceManager.serverConnectionConfig
    val config = owner(token = "header-only-secret")
    val settings = DeviceSettings.default().apply {
      downloadUsingCellular = DownloadUsingCellularSetting.NEVER
    }
    try {
      DeviceManager.deviceData = DeviceData(
        mutableListOf(config), config.id, settings, null
      )
      DeviceManager.serverConnectionConfig = config
      val lease = checkNotNull(DeviceManager.captureConnectionLease(config))
      val callback = CountDownLatch(1)
      val outcome = AtomicReference<OfflineDownloadResult>()

      OfflineDownloadCoordinator(context) { _, _ -> true }
        .enqueue(audiobook(), config, lease) { result, _ ->
        outcome.set(result)
        callback.countDown()
      }

      assertTrue(awaitCallback(callback))
      assertEquals(OfflineDownloadResult.STARTED, outcome.get())
      val job = DeviceManager.dbManager.getDownloadItems()
        .single(OfflineDownloadPlanner::isManagedJob)
      val platformId = checkNotNull(job.downloadItemParts.single().downloadId)
      val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      val shadowRequest = shadowOf(shadowOf(downloadManager).getRequest(platformId))
      assertFalse(shadowRequest.uri.toString().contains(config.token))
      assertEquals(null, shadowRequest.uri.query)
      assertFalse(shadowRequest.allowedOverMetered)
      assertFalse(shadowRequest.allowedOverRoaming)
      assertEquals(
        listOf(
          "Accept-Encoding" to "identity",
          "Authorization" to "Bearer ${config.token}"
        ),
        shadowRequest.requestHeaders.map { it.first to it.second }
      )
    } finally {
      DeviceManager.dbManager.getDownloadItems()
        .filter(OfflineDownloadPlanner::isManagedJob)
        .forEach { DeviceManager.dbManager.removeDownloadItem(it.id) }
      DeviceManager.deviceData = priorData
      DeviceManager.serverConnectionConfig = priorActive
    }
  }

  @Test
  fun `removed account lease cannot enqueue a late authenticated download`() {
    val priorData = DeviceManager.deviceData
    val priorActive = DeviceManager.serverConnectionConfig
    val config = owner()
    try {
      DeviceManager.deviceData = DeviceData(
        mutableListOf(config), config.id, DeviceSettings.default(), null
      )
      DeviceManager.serverConnectionConfig = config
      val staleLease = checkNotNull(DeviceManager.captureConnectionLease(config))
      DeviceManager.deviceData = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)
      DeviceManager.serverConnectionConfig = null
      val callback = CountDownLatch(1)
      val outcome = AtomicReference<Pair<OfflineDownloadResult, OfflinePlanFailure?>>()

      OfflineDownloadCoordinator(context).enqueue(audiobook(), config, staleLease) { result, reason ->
        outcome.set(result to reason)
        callback.countDown()
      }

      assertTrue(awaitCallback(callback))
      assertEquals(
        OfflineDownloadResult.FAILED to OfflinePlanFailure.INVALID_SERVER,
        outcome.get()
      )
      assertTrue(
        DeviceManager.dbManager.getDownloadItems().none(OfflineDownloadPlanner::isManagedJob)
      )
      val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      assertEquals(0, shadowOf(downloadManager).requestCount)
    } finally {
      DeviceManager.deviceData = priorData
      DeviceManager.serverConnectionConfig = priorActive
    }
  }

  @Test
  fun `cancel invalidates an authenticated preflight before any platform row can start`() {
    val priorData = DeviceManager.deviceData
    val priorActive = DeviceManager.serverConnectionConfig
    val config = owner()
    val probeEntered = CountDownLatch(1)
    val releaseProbe = CountDownLatch(1)
    try {
      DeviceManager.deviceData = DeviceData(
        mutableListOf(config), config.id, DeviceSettings.default(), null
      )
      DeviceManager.serverConnectionConfig = config
      val lease = checkNotNull(DeviceManager.captureConnectionLease(config))
      val enqueueDone = CountDownLatch(1)
      val cancelDone = CountDownLatch(1)
      val enqueueResult = AtomicReference<OfflineDownloadResult>()
      val cancelResult = AtomicReference<OfflineDownloadResult>()
      val coordinator = OfflineDownloadCoordinator(context) { _, _ ->
        probeEntered.countDown()
        releaseProbe.await(5, TimeUnit.SECONDS)
        true
      }

      coordinator.enqueue(audiobook(), config, lease) { result, _ ->
        enqueueResult.set(result)
        enqueueDone.countDown()
      }
      assertTrue(probeEntered.await(5, TimeUnit.SECONDS))

      coordinator.cancel(config.id, "book-id") { result, _ ->
        cancelResult.set(result)
        cancelDone.countDown()
      }
      assertTrue(awaitCallback(cancelDone))
      assertEquals(OfflineDownloadResult.CANCELED, cancelResult.get())

      releaseProbe.countDown()
      assertTrue(awaitCallback(enqueueDone))
      assertEquals(OfflineDownloadResult.FAILED, enqueueResult.get())
      assertTrue(DeviceManager.dbManager.getDownloadItems().none(OfflineDownloadPlanner::isManagedJob))
      val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
      assertEquals(0, shadowOf(downloadManager).requestCount)
    } finally {
      releaseProbe.countDown()
      DeviceManager.dbManager.getDownloadItems()
        .filter(OfflineDownloadPlanner::isManagedJob)
        .forEach { DeviceManager.dbManager.removeDownloadItem(it.id) }
      DeviceManager.deviceData = priorData
      DeviceManager.serverConnectionConfig = priorActive
    }
  }

  @Test
  fun `publication is idempotent when process dies after final rename`() {
    val part = ready(
      OfflineDownloadPlanner.plan(context, audiobook(), owner())
    ).item.downloadItemParts.single()
    val partial = File(checkNotNull(part.destinationUri.path))
    val final = File(part.finalDestinationPath)
    val payload = "ID3complete-audio-payload".toByteArray()
    assertTrue(checkNotNull(partial.parentFile).mkdirs())
    partial.writeBytes(payload)
    val coordinator = OfflineDownloadCoordinator(context)

    assertTrue(publishPart(coordinator, part, payload.size.toLong()))
    assertFalse(partial.exists())
    assertTrue(final.isFile)
    assertTrue(payload.contentEquals(final.readBytes()))

    // Simulate a restart before Paper recorded completed=true. The final file
    // is authoritative and must be accepted without being moved or replaced.
    assertTrue(publishPart(coordinator, part, payload.size.toLong()))
    assertFalse(partial.exists())
    assertTrue(payload.contentEquals(final.readBytes()))

    // A stale/mismatched platform length must still fail closed.
    assertFalse(publishPart(coordinator, part, payload.size.toLong() + 1L))
    assertTrue(payload.contentEquals(final.readBytes()))
  }

  @Test
  fun `publication rejects non audio error bodies and declared size mismatches`() {
    val original = ready(
      OfflineDownloadPlanner.plan(context, audiobook(), owner())
    ).item.downloadItemParts.single()
    val coordinator = OfflineDownloadCoordinator(context)
    val partial = File(checkNotNull(original.destinationUri.path))
    assertTrue(checkNotNull(partial.parentFile).mkdirs())
    partial.writeText("<html>sign in required</html>")

    assertFalse(publishPart(coordinator, original, partial.length()))
    assertTrue(partial.isFile)

    partial.writeBytes("ID3valid-audio".toByteArray())
    val wrongSize = original.copy(fileSize = partial.length() + 1L)
    assertFalse(publishPart(coordinator, wrongSize, partial.length()))
    assertTrue(partial.isFile)
  }

  @Test
  @Config(sdk = [35], shadows = [RecoveryDownloadManagerShadow::class])
  fun `process death recovery requires exact source and destination and chooses newest id`() {
    val part = ready(
      OfflineDownloadPlanner.plan(context, audiobook(), owner())
    ).item.downloadItemParts.single()
    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val otherSource = "https://library.example.test/base/api/items/other"
    val otherDestination = Uri.fromFile(
      File(checkNotNull(part.destinationUri.path) + ".other")
    ).toString()
    RecoveryDownloadManagerShadow.rows = listOf(
      RecoveryDownloadRow(41L, part.uri.toString(), part.destinationUri.toString()),
      RecoveryDownloadRow(99L, otherSource, part.destinationUri.toString()),
      RecoveryDownloadRow(100L, part.uri.toString(), otherDestination),
      RecoveryDownloadRow(84L, part.uri.toString(), part.destinationUri.toString())
    )

    assertEquals(
      84L,
      recoverPlatformId(OfflineDownloadCoordinator(context), downloadManager, part)
    )
  }

  private fun ready(result: OfflinePlanResult): OfflinePlanResult.Ready {
    assertTrue("Expected a ready plan but was $result", result is OfflinePlanResult.Ready)
    return result as OfflinePlanResult.Ready
  }

  private fun awaitCallback(latch: CountDownLatch): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (latch.count > 0L && System.nanoTime() < deadline) {
      shadowOf(Looper.getMainLooper()).idle()
      Thread.sleep(10)
    }
    shadowOf(Looper.getMainLooper()).idle()
    return latch.count == 0L
  }

  private fun publishPart(
    coordinator: OfflineDownloadCoordinator,
    part: DownloadItemPart,
    platformBytes: Long
  ): Boolean {
    val method = OfflineDownloadCoordinator::class.java.getDeclaredMethod(
      "publishPart",
      DownloadItemPart::class.java,
      Long::class.javaPrimitiveType
    )
    method.isAccessible = true
    return method.invoke(coordinator, part, platformBytes) as Boolean
  }

  private fun recoverPlatformId(
    coordinator: OfflineDownloadCoordinator,
    downloadManager: DownloadManager,
    part: DownloadItemPart
  ): Long? {
    val method = OfflineDownloadCoordinator::class.java.getDeclaredMethod(
      "recoverPlatformId",
      DownloadManager::class.java,
      DownloadItemPart::class.java
    )
    method.isAccessible = true
    return method.invoke(coordinator, downloadManager, part) as Long?
  }

  private fun owner(
    id: String = "account-id",
    address: String = "https://library.example.test/base",
    token: String = "access-token"
  ) = ServerConnectionConfig(
    id = id,
    index = 0,
    name = "Library",
    address = address,
    version = "2.17.0",
    userId = "user-id",
    username = "reviewer",
    token = token,
    customHeaders = null
  )

  private fun audiobook(
    id: String = "book-id",
    tracks: MutableList<AudioTrack> = mutableListOf(track()),
    audioFiles: List<AudioFile> = listOf(audioFile())
  ) = LibraryItem(
    id = id,
    ino = "",
    libraryId = "library-id",
    folderId = "folder-id",
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
    mediaType = "book",
    media = Book(
      metadata = BookMetadata(
        title = "Test audiobook",
        subtitle = null,
        authors = null,
        narrators = null,
        genres = mutableListOf(),
        publishedYear = null,
        publishedDate = null,
        publisher = null,
        description = null,
        isbn = null,
        asin = null,
        language = null,
        explicit = false,
        authorName = "Author",
        authorNameLF = null,
        narratorName = null,
        seriesName = null,
        series = null
      ),
      coverPath = null,
      tags = emptyList(),
      audioFiles = audioFiles,
      chapters = null,
      tracks = tracks,
      ebookFile = null,
      size = null,
      duration = tracks.sumOf(AudioTrack::duration),
      numTracks = tracks.size
    ),
    libraryFiles = null,
    userMediaProgress = null,
    collapsedSeries = null,
    localLibraryItemId = null,
    recentEpisode = null
  )

  private fun track(
    path: String = "/server/book/track.mp3",
    extension: String = "mp3"
  ) = AudioTrack(
    index = 1,
    startOffset = 0.0,
    duration = 60.0,
    title = "Track",
    contentUrl = "",
    mimeType = "audio/mpeg",
    metadata = metadata(path, extension),
    isLocal = false,
    localFileId = null,
    serverIndex = 1
  )

  private fun audioFile(
    ino: String = "audio-file-ino",
    path: String = "/server/book/track.mp3",
    extension: String = "mp3"
  ) = AudioFile(
    index = 1,
    ino = ino,
    metadata = metadata(path, extension)
  )

  private fun metadata(path: String, extension: String) = FileMetadata(
    filename = "server-controlled-filename.$extension",
    ext = extension,
    path = path,
    relPath = path,
    size = 0L
  )

  private companion object {
    val TEST_BOOKS = listOf("downloadItems", "localLibraryItems", "localFolders")
  }
}

data class RecoveryDownloadRow(
  val id: Long,
  val source: String,
  val destination: String
)

/** MatrixCursor avoids Robolectric's default DownloadManager cursor lacking isNull(). */
@Implements(DownloadManager::class)
class RecoveryDownloadManagerShadow {
  @Implementation
  fun query(@Suppress("UNUSED_PARAMETER") query: DownloadManager.Query): Cursor {
    val cursor = MatrixCursor(
      arrayOf(
        DownloadManager.COLUMN_ID,
        DownloadManager.COLUMN_URI,
        DownloadManager.COLUMN_LOCAL_URI
      )
    )
    rows.forEach { row ->
      cursor.addRow(arrayOf<Any>(row.id, row.source, row.destination))
    }
    return cursor
  }

  companion object {
    var rows: List<RecoveryDownloadRow> = emptyList()
  }
}
