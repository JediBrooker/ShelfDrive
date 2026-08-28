package com.audiobookshelf.app.device

import android.content.Context
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.data.PlaybackSession
import com.audiobookshelf.app.data.ServerConnectionConfig
import io.paperdb.Paper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceManagerSecurityTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    Paper.init(context)
    Paper.book("device").destroy()
    Paper.book("playbackSession").destroy()
    DeviceManager.deviceData = emptyDeviceData()
    DeviceManager.serverConnectionConfig = null
  }

  @After
  fun tearDown() {
    DeviceManager.deviceData = emptyDeviceData()
    DeviceManager.serverConnectionConfig = null
    Paper.book("device").destroy()
    Paper.book("playbackSession").destroy()
  }

  @Test
  fun `server address requires a valid web origin without embedded credentials`() {
    assertTrue(DeviceManager.isServerAddressAllowed("https://example.test/audiobookshelf"))
    assertEquals(
      BuildConfig.DEBUG,
      DeviceManager.isServerAddressAllowed("http://example.test/audiobookshelf")
    )
    assertFalse(DeviceManager.isServerAddressAllowed("https://"))
    assertFalse(DeviceManager.isServerAddressAllowed("example.test"))
    assertFalse(DeviceManager.isServerAddressAllowed("file:///tmp/server"))
    assertFalse(DeviceManager.isServerAddressAllowed("https://user:secret@example.test"))
    assertFalse(DeviceManager.isServerAddressAllowed("https://example.test?token=secret"))
    assertFalse(DeviceManager.isServerAddressAllowed("https://example.test:invalid"))
    assertFalse(DeviceManager.isServerAddressAllowed("https://example.test/#fragment"))
  }

  @Test
  fun `rejecting malformed profile also removes its queued progress sessions`() {
    val rejected = config("rejected", "not-a-server")
    val retained = config("retained", "https://example.test")
    val queued = playbackSession("queued", rejected.id)
    val retainedSession = playbackSession("retained-session", retained.id)
    DeviceManager.deviceData = DeviceData(
      mutableListOf(rejected, retained),
      rejected.id,
      DeviceSettings.default(),
      queued
    )
    DeviceManager.serverConnectionConfig = rejected
    DeviceManager.dbManager.savePlaybackSession(queued)
    DeviceManager.dbManager.savePlaybackSession(retainedSession)

    assertEquals(listOf(rejected.id), DeviceManager.removeInsecureServerConnections())

    assertEquals(listOf(retained), DeviceManager.deviceData.serverConnectionConfigs)
    assertEquals(retained.id, DeviceManager.deviceData.lastServerConnectionConfigId)
    assertEquals(null, DeviceManager.deviceData.lastPlaybackSession)
    assertEquals(retained, DeviceManager.serverConnectionConfig)
    assertEquals(
      listOf(retainedSession.id),
      DeviceManager.dbManager.getPlaybackSessions().map { it.id }
    )
  }

  @Test
  fun `insecure profile removal is not published when durable commit fails`() {
    val rejected = config("rejected", "not-a-server")
    val queued = playbackSession("queued", rejected.id)
    val original = DeviceData(
      mutableListOf(rejected),
      rejected.id,
      DeviceSettings.default(),
      queued
    )
    DeviceManager.deviceData = original
    DeviceManager.serverConnectionConfig = rejected
    DeviceManager.dbManager.saveDeviceData(original)
    DeviceManager.dbManager.savePlaybackSession(queued)
    val epochBefore = DeviceManager.currentConnectionStateEpoch()

    val removed = DeviceManager.removeInsecureServerConnections {
      throw IllegalStateException("injected profile write failure")
    }

    assertTrue(removed.isEmpty())
    assertTrue(DeviceManager.deviceData === original)
    assertEquals(rejected, DeviceManager.serverConnectionConfig)
    assertEquals(epochBefore, DeviceManager.currentConnectionStateEpoch())
    assertEquals(listOf(rejected), DeviceManager.dbManager.getDeviceData().serverConnectionConfigs)
    assertEquals(listOf(queued.id), DeviceManager.dbManager.getPlaybackSessions().map { it.id })
  }

  @Test
  fun `queued playback sessions are isolated by account even when ids match`() {
    val accountA = playbackSession("shared-session", "account-a").apply {
      currentTime = 10.0
    }
    val accountB = playbackSession("shared-session", "account-b").apply {
      currentTime = 20.0
    }

    DeviceManager.dbManager.savePlaybackSession(accountA)
    DeviceManager.dbManager.savePlaybackSession(accountB)

    assertEquals(
      setOf("account-a" to 10.0, "account-b" to 20.0),
      DeviceManager.dbManager.getPlaybackSessions()
        .map { it.serverConnectionConfigId to it.currentTime }
        .toSet()
    )

    DeviceManager.dbManager.removePlaybackSession(accountB)

    assertEquals(
      listOf("account-a"),
      DeviceManager.dbManager.getPlaybackSessions().map { it.serverConnectionConfigId }
    )
  }

  @Test
  fun `stale owner rows cannot starve a bounded retry batch for saved profiles`() {
    repeat(75) { index ->
      DeviceManager.dbManager.savePlaybackSession(
        playbackSession("orphan-$index", "removed-account-$index")
      )
    }
    val eligible = playbackSession("eligible", "saved-account")
    DeviceManager.dbManager.savePlaybackSession(eligible)

    val retryBatch = DeviceManager.dbManager.getPlaybackSessionsForConnections(
      setOf("saved-account"),
      limit = 1
    )

    assertEquals(listOf("eligible"), retryBatch.map { it.id })
    assertEquals(listOf("saved-account"), retryBatch.map { it.serverConnectionConfigId })
  }

  @Test
  fun `legacy unscoped playback session migrates without a duplicate`() {
    val legacy = playbackSession("legacy-session", "account-a")
    Paper.book("playbackSession").write(legacy.id, legacy)

    assertEquals(listOf(legacy.id), DeviceManager.dbManager.getPlaybackSessions().map { it.id })
    assertFalse(Paper.book("playbackSession").allKeys.contains(legacy.id))
    assertEquals(1, Paper.book("playbackSession").allKeys.size)
  }

  @Test
  fun `old retry success cannot delete a newer checkpoint with the same identity`() {
    val original = playbackSession("retry-session", "account-a").apply {
      currentTime = 10.0
      updatedAt = 10L
    }
    DeviceManager.dbManager.savePlaybackSession(original)
    val sentSnapshot = DeviceManager.dbManager.getPlaybackSessions().single()
    val replacement = playbackSession("retry-session", "account-a").apply {
      currentTime = 25.0
      updatedAt = 25L
    }
    DeviceManager.dbManager.savePlaybackSession(replacement)

    assertFalse(DeviceManager.dbManager.removePlaybackSessionIfUnchanged(sentSnapshot))
    assertEquals(
      25.0,
      DeviceManager.dbManager.getPlaybackSessions().single().currentTime,
      0.0
    )

    val currentSnapshot = DeviceManager.dbManager.getPlaybackSessions().single()
    assertTrue(DeviceManager.dbManager.removePlaybackSessionIfUnchanged(currentSnapshot))
    assertTrue(DeviceManager.dbManager.getPlaybackSessions().isEmpty())
  }

  @Test
  fun `older async checkpoint cannot overwrite a newer committed session`() {
    val owner = config("account-a", "https://example.test")
    DeviceManager.deviceData = DeviceData(
      mutableListOf(owner),
      owner.id,
      DeviceSettings.default(),
      null
    )
    assertTrue(DeviceManager.trySelectServerConnectionConfig(owner))
    val lease = DeviceManager.captureConnectionLease(owner)
      ?: throw AssertionError("missing test lease")
    val older = playbackSession("older", owner.id).apply { connectionLease = lease }
    val newer = playbackSession("newer", owner.id).apply { connectionLease = lease }
    val olderRevision = DeviceManager.reservePlaybackCheckpointRevision()
    val newerRevision = DeviceManager.reservePlaybackCheckpointRevision()

    assertTrue(DeviceManager.setLastPlaybackSession(newer, lease, newerRevision))
    assertFalse(DeviceManager.setLastPlaybackSession(older, lease, olderRevision))
    assertEquals("newer", DeviceManager.deviceData.lastPlaybackSession?.id)
    assertEquals("newer", DeviceManager.dbManager.getDeviceData().lastPlaybackSession?.id)
  }

  @Test
  fun `connection profile accessors hold the state lock and return a structural snapshot`() {
    val first = config("first", "https://first.example.test")
    val second = config("second", "https://second.example.test")
    val guardedConfigs = LockAssertingMutableList(
      DeviceManager.connectionStateMonitor,
      mutableListOf(first, second)
    )
    DeviceManager.deviceData = DeviceData(
      guardedConfigs,
      second.id,
      DeviceSettings.default(),
      null
    )

    val snapshot = DeviceManager.snapshotServerConnectionConfigs()

    assertEquals(listOf(first, second), snapshot)
    assertEquals(first, DeviceManager.getServerConnectionConfig(first.id))
    assertEquals(second, DeviceManager.getLastServerConnectionConfig())

    synchronized(DeviceManager.connectionStateMonitor) {
      guardedConfigs.remove(first)
    }
    assertEquals(
      "the returned snapshot must not share the mutable list structure",
      listOf(first, second),
      snapshot
    )
    assertEquals(listOf(second), DeviceManager.snapshotServerConnectionConfigs())
  }

  private fun config(id: String, address: String) = ServerConnectionConfig(
    id = id,
    index = 0,
    name = id,
    address = address,
    version = "2.26.0",
    userId = "user",
    username = "username",
    token = "token",
    customHeaders = null
  )

  private fun playbackSession(id: String, configId: String) = PlaybackSession(
    id = id,
    userId = "user",
    libraryItemId = "item",
    episodeId = null,
    mediaType = "book",
    mediaMetadata = MediaTypeMetadata("Title", false),
    deviceInfo = DeviceInfo("device", "maker", "model", 35, "1"),
    chapters = emptyList(),
    displayTitle = "Title",
    displayAuthor = "Author",
    coverPath = null,
    duration = 60.0,
    playMethod = 0,
    startedAt = 1L,
    updatedAt = 1L,
    timeListening = 0L,
    audioTracks = mutableListOf(),
    currentTime = 0.0,
    libraryItem = null,
    localLibraryItem = null,
    localEpisodeId = null,
    serverConnectionConfigId = configId,
    serverAddress = "https://example.test",
    mediaPlayer = null
  )

  private fun emptyDeviceData() =
    DeviceData(mutableListOf(), null, DeviceSettings.default(), null)

  /** Fails immediately if production code traverses this list without the state lock. */
  private class LockAssertingMutableList<T>(
    private val monitor: Any,
    private val delegate: MutableList<T>
  ) : MutableList<T> by delegate {
    private fun assertStateLockHeld() {
      check(Thread.holdsLock(monitor)) {
        "serverConnectionConfigs was read without connectionStateMonitor"
      }
    }

    override val size: Int
      get() {
        assertStateLockHeld()
        return delegate.size
      }

    override fun get(index: Int): T {
      assertStateLockHeld()
      return delegate[index]
    }

    override fun iterator(): MutableIterator<T> {
      assertStateLockHeld()
      return delegate.iterator()
    }

    override fun listIterator(): MutableListIterator<T> {
      assertStateLockHeld()
      return delegate.listIterator()
    }

    override fun listIterator(index: Int): MutableListIterator<T> {
      assertStateLockHeld()
      return delegate.listIterator(index)
    }
  }
}
