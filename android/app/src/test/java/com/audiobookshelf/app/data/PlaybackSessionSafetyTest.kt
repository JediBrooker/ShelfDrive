package com.audiobookshelf.app.data

import android.content.Context
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.player.PLAYMETHOD_DIRECTPLAY
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
class PlaybackSessionSafetyTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Paper.init(context)
    DeviceManager.coverCache = null
    DeviceManager.serverConnectionConfig = null
  }

  @After
  fun tearDown() {
    DeviceManager.coverCache = null
    DeviceManager.serverConnectionConfig = null
  }

  @Test
  fun `empty or malformed durations produce finite clamped progress`() {
    val empty = session(duration = 0.0, currentTime = 20.0)
    assertEquals(0.0, empty.progress, 0.0)
    assertEquals(0L, empty.totalDurationMs)

    val fallbackDuration = session(duration = 100.0, currentTime = 150.0)
    assertEquals(1.0, fallbackDuration.progress, 0.0)
    assertEquals(100_000L, fallbackDuration.totalDurationMs)

    val nonFinite = session(duration = Double.NaN, currentTime = Double.POSITIVE_INFINITY)
    assertEquals(0.0, nonFinite.progress, 0.0)
    assertEquals(0L, nonFinite.currentTimeMs)
  }

  @Test
  fun `sync data cannot persist negative listening time or non-finite position`() {
    val playback = session(duration = 100.0, currentTime = 10.0).apply {
      timeListening = -1L
    }

    playback.syncData(MediaProgressSyncData(-50L, 100.0, Double.NaN))

    assertEquals(0L, playback.timeListening)
    assertEquals(0.0, playback.currentTime, 0.0)
    assertTrue(playback.progress.isFinite())
  }

  @Test
  fun `paper compare-and-delete token is excluded from server JSON`() {
    val playback = session(duration = 100.0, currentTime = 10.0).apply {
      persistenceToken = "paper-only-secret-token"
    }

    val json = jacksonObjectMapper().writeValueAsString(playback)

    assertFalse(json.contains("persistenceToken"))
    assertFalse(json.contains("paper-only-secret-token"))
  }

  @Test
  fun `old server cover metadata never exposes access token`() {
    val server = serverConfig(version = "2.16.0", token = "super-secret-token")
    DeviceManager.serverConnectionConfig = server
    val playback = session(duration = 100.0, currentTime = 10.0).apply {
      coverPath = "/cover"
      serverConnectionConfigId = server.id
      serverAddress = server.address
    }

    val cover = playback.getCoverUri(context).toString()

    assertEquals(
      "android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon",
      cover
    )
    assertFalse(cover.contains("super-secret-token"))
  }

  @Test
  fun `new server playback artwork also stays local until cached`() {
    val server = serverConfig(version = "2.22.0", token = "secret")
    DeviceManager.serverConnectionConfig = server
    val playback = session(duration = 100.0, currentTime = 10.0).apply {
      coverPath = "/cover"
      serverConnectionConfigId = server.id
      serverAddress = server.address
    }

    assertEquals(
      "android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon",
      playback.getCoverUri(context).toString()
    )
  }

  @Test
  fun `foreign or numeric resource cover falls back to owned named drawable`() {
    val localItem = LocalLibraryItem(
      id = "local",
      folderId = "folder",
      basePath = "",
      absolutePath = "",
      contentUrl = "",
      isInvalid = false,
      mediaType = "book",
      media = MediaType(MediaTypeMetadata("Title", false), null),
      localFiles = mutableListOf(),
      coverContentUrl = "android.resource://old.package/2131230890",
      coverAbsolutePath = null,
      isLocal = true,
      serverConnectionConfigId = null,
      serverAddress = null,
      serverUserId = null,
      libraryItemId = null
    )
    val playback = session(duration = 100.0, currentTime = 0.0).apply {
      localLibraryItem = localItem
    }

    assertEquals(
      "android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon",
      playback.getCoverUri(context).toString()
    )
  }

  @Test
  fun `malformed track timing cannot overflow media positions`() {
    val malformed = audioTrack(Double.NaN, Double.POSITIVE_INFINITY)
    assertEquals(0L, malformed.startOffsetMs)
    assertEquals(0L, malformed.durationMs)
    assertEquals(0L, malformed.endOffsetMs)

    val enormous = audioTrack(Double.MAX_VALUE, Double.MAX_VALUE)
    assertEquals(Long.MAX_VALUE, enormous.startOffsetMs)
    assertEquals(Long.MAX_VALUE, enormous.durationMs)
    assertEquals(Long.MAX_VALUE, enormous.endOffsetMs)
    val enormousChapter = enormous.getBookChapter()
    assertTrue(enormousChapter.end.isFinite())
    assertEquals(Long.MAX_VALUE, enormousChapter.endMs)
  }

  @Test
  fun `downloaded session persistence removes an expired server identity`() {
    val owner = serverConfig(version = "2.26.0", token = "token")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(owner),
      owner.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = owner
    val localItem = LocalLibraryItem(
      id = "local-book",
      folderId = "folder",
      basePath = "",
      absolutePath = "",
      contentUrl = "content://local/book",
      isInvalid = false,
      mediaType = "book",
      media = MediaType(MediaTypeMetadata("Title", false), null),
      localFiles = mutableListOf(),
      coverContentUrl = null,
      coverAbsolutePath = null,
      isLocal = true,
      serverConnectionConfigId = owner.id,
      serverAddress = owner.address,
      serverUserId = owner.userId,
      libraryItemId = "remote-book"
    )
    val playback = session(duration = 100.0, currentTime = 10.0).apply {
      playMethod = PLAYMETHOD_LOCAL
      localLibraryItem = localItem
      libraryItemId = "remote-book"
      episodeId = "remote-episode"
      serverConnectionConfigId = owner.id
      serverAddress = owner.address
      connectionLease = DeviceManager.captureConnectionLease(owner)
    }

    val whileConnected = playback.copySanitizedForPersistence()
    assertEquals(owner.id, whileConnected.serverConnectionConfigId)

    synchronized(DeviceManager.connectionStateMonitor) {
      DeviceManager.advanceConnectionStateEpoch()
      DeviceManager.deviceData = DeviceData(
        mutableListOf(),
        null,
        DeviceSettings.default(),
        null
      )
      DeviceManager.serverConnectionConfig = null
    }
    val afterRemoval = playback.copySanitizedForPersistence()

    assertTrue(afterRemoval.isLocal)
    assertEquals("local-book", afterRemoval.localLibraryItemId)
    assertEquals("local-book", afterRemoval.mediaItemId)
    assertNull(afterRemoval.serverConnectionConfigId)
    assertNull(afterRemoval.serverAddress)
    assertNull(afterRemoval.userId)
    assertNull(afterRemoval.libraryItemId)
    assertNull(afterRemoval.episodeId)
    assertNull(afterRemoval.connectionLease)
    assertNull(afterRemoval.libraryItem)
    assertNull(afterRemoval.localLibraryItem?.serverConnectionConfigId)
    assertNull(afterRemoval.localLibraryItem?.serverAddress)
    assertNull(afterRemoval.localLibraryItem?.serverUserId)
    assertNull(afterRemoval.localLibraryItem?.libraryItemId)
    assertEquals("content://local/book", afterRemoval.localLibraryItem?.contentUrl)
    // Sanitizing persistence must not interrupt the in-memory download.
    assertEquals(owner.id, playback.serverConnectionConfigId)
    assertEquals(owner.id, playback.localLibraryItem?.serverConnectionConfigId)
    assertEquals("remote-book", playback.localLibraryItem?.libraryItemId)
  }

  @Test
  fun `legacy local checkpoint with no top-level owner loses every nested server identity`() {
    val episode = PodcastEpisode(
      id = "local-episode",
      index = 1,
      episode = null,
      episodeType = null,
      title = "Episode",
      subtitle = null,
      description = null,
      pubDate = null,
      publishedAt = 1L,
      audioFile = null,
      audioTrack = null,
      chapters = emptyList(),
      duration = 60.0,
      size = 0L,
      serverEpisodeId = "remote-episode",
      localEpisodeId = "local-episode"
    )
    val podcast = Podcast(
      PodcastMetadata("Podcast", "Author", null, mutableListOf(), false),
      null,
      mutableListOf(),
      mutableListOf(episode),
      false,
      1
    )
    val localItem = LocalLibraryItem(
      id = "local-podcast",
      folderId = "folder",
      basePath = "/local",
      absolutePath = "/local/podcast",
      contentUrl = "content://local/podcast",
      isInvalid = false,
      mediaType = "podcast",
      media = podcast,
      localFiles = mutableListOf(),
      coverContentUrl = null,
      coverAbsolutePath = null,
      isLocal = true,
      serverConnectionConfigId = "legacy-owner",
      serverAddress = "https://old.example.test",
      serverUserId = "remote-user",
      libraryItemId = "remote-item"
    )
    val legacy = session(60.0, 10.0).apply {
      playMethod = PLAYMETHOD_LOCAL
      userId = "remote-user"
      libraryItemId = "remote-item"
      episodeId = "remote-episode"
      serverAddress = "https://old.example.test"
      serverConnectionConfigId = null
      localLibraryItem = localItem
      localEpisodeId = "local-episode"
    }

    val sanitized = legacy.copySanitizedForPersistence()

    assertNull(sanitized.userId)
    assertNull(sanitized.libraryItemId)
    assertNull(sanitized.episodeId)
    assertNull(sanitized.serverConnectionConfigId)
    assertNull(sanitized.serverAddress)
    assertNull(sanitized.localLibraryItem?.serverConnectionConfigId)
    assertNull(sanitized.localLibraryItem?.serverAddress)
    assertNull(sanitized.localLibraryItem?.serverUserId)
    assertNull(sanitized.localLibraryItem?.libraryItemId)
    val sanitizedPodcast = sanitized.localLibraryItem?.media as Podcast
    assertNull(sanitizedPodcast.episodes?.single()?.serverEpisodeId)
    assertEquals("content://local/podcast", sanitized.localLibraryItem?.contentUrl)
    assertEquals("remote-episode", episode.serverEpisodeId)
  }

  private fun session(duration: Double, currentTime: Double) = PlaybackSession(
    id = "session",
    userId = "user",
    libraryItemId = "item/id",
    episodeId = null,
    mediaType = "book",
    mediaMetadata = MediaTypeMetadata("Title", false),
    deviceInfo = DeviceInfo("device", "maker", "model", 35, "1"),
    chapters = emptyList(),
    displayTitle = "Title",
    displayAuthor = "Author",
    coverPath = null,
    duration = duration,
    playMethod = PLAYMETHOD_DIRECTPLAY,
    startedAt = 1L,
    updatedAt = 1L,
    timeListening = 0L,
    audioTracks = mutableListOf(),
    currentTime = currentTime,
    libraryItem = null,
    localLibraryItem = null,
    localEpisodeId = null,
    serverConnectionConfigId = null,
    serverAddress = null,
    mediaPlayer = null
  )

  private fun serverConfig(version: String, token: String) = ServerConnectionConfig(
    id = "server",
    index = 0,
    name = "server",
    address = "https://example.test/base",
    version = version,
    userId = "user",
    username = "username",
    token = token,
    customHeaders = null
  )

  private fun audioTrack(startOffset: Double, duration: Double) = AudioTrack(
    index = 0,
    startOffset = startOffset,
    duration = duration,
    title = "Track",
    contentUrl = "/track",
    mimeType = "audio/mpeg",
    metadata = null,
    isLocal = false,
    localFileId = null,
    serverIndex = null
  )
}
