package com.audiobookshelf.app.server

import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.accounts.ShelfDriveConnectionDataCleaner
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.util.SafeJsonObject as JSObject
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.utils.MediaConstants
import io.paperdb.Paper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ApiHandlerMalformedResponseTest {
  private lateinit var server: MockWebServer
  private lateinit var handler: ApiHandler
  private lateinit var connectionConfig: ServerConnectionConfig
  private lateinit var tokenStorage: InMemoryRefreshTokenStorage

  @Before
  fun setUp() {
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
    val testClient = OkHttpClient.Builder()
      .sslSocketFactory(
        clientCertificates.sslSocketFactory(),
        clientCertificates.trustManager
      )
      .build()
    server = MockWebServer().apply {
      useHttps(serverCertificates.sslSocketFactory(), false)
      start()
    }
    Paper.init(RuntimeEnvironment.getApplication())
    Paper.book("device").destroy()
    Paper.book("localLibraryItems").destroy()
    val config = ServerConnectionConfig(
      id = "test",
      index = 0,
      name = "test",
      address = server.url("/").toString().trimEnd('/'),
      version = "2.26.0",
      userId = "user",
      username = "reviewer",
      token = "token",
      customHeaders = mapOf(
        "X-Proxy-Key" to "proxy-secret",
        "Authorization" to "malicious override"
      )
    )
    connectionConfig = config
    DeviceManager.deviceData = DeviceData(
      mutableListOf(config),
      config.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = config
    SecureStorage(RuntimeEnvironment.getApplication()).removeRefreshToken(config.id)
    tokenStorage = InMemoryRefreshTokenStorage()
    handler = ApiHandler(
      RuntimeEnvironment.getApplication(),
      tokenStorage,
      testClient,
      testClient
    )
  }

  @After
  fun tearDown() {
    handler.shutdown()
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = DeviceData(
      mutableListOf(),
      null,
      DeviceSettings.default(),
      null
    )
    Paper.book("device").destroy()
    Paper.book("localLibraryItems").destroy()
    server.shutdown()
  }

  @Test
  fun `permanent refresh rejection invalidates AAOS root and publishes sign in`() {
    val serviceController =
      Robolectric.buildService(PlayerNotificationService::class.java).create()
    val service = serviceController.get()
    // Release-source-set tests correctly remove cleartext profiles during
    // service startup. Restore this loopback fixture only after startup so the
    // ApiHandler can exercise the real 401/refresh-rejection path.
    connectionConfig.token = "token"
    DeviceManager.deviceData = DeviceData(
      mutableListOf(connectionConfig),
      connectionConfig.id,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = connectionConfig
    server.enqueue(MockResponse().setResponseCode(401))
    val latch = CountDownLatch(1)

    handler.getLibraries { latch.countDown() }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    shadowOf(android.os.Looper.getMainLooper()).idle()
    assertNull(DeviceManager.serverConnectionConfig)
    assertNull(DeviceManager.deviceData.lastServerConnectionConfigId)
    assertEquals("", connectionConfig.token)

    val state = service.mediaSession.controller.playbackState
    assertEquals(PlaybackStateCompat.STATE_ERROR, state.state)
    assertEquals(PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED, state.errorCode)
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

    serviceController.destroy()
  }

  @Test
  fun `late refresh cannot mutate a readded profile with the same deterministic id`() {
    assertTrue(tokenStorage.storeRefreshToken(connectionConfig.id, "refresh-before-removal"))
    val oldLease = DeviceManager.captureConnectionLease(connectionConfig)
      ?: throw AssertionError("No initial connection lease")
    val refreshStarted = CountDownLatch(1)
    val releaseRefresh = CountDownLatch(1)
    server.dispatcher = object : Dispatcher() {
      override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
        "/api/libraries?include=stats" -> MockResponse().setResponseCode(401)
        "/auth/refresh" -> {
          refreshStarted.countDown()
          if (!releaseRefresh.await(3, TimeUnit.SECONDS)) {
            MockResponse().setResponseCode(503)
          } else {
            MockResponse().setBody(
              """{"user":{"accessToken":"late-access","refreshToken":"late-refresh"}}"""
            )
          }
        }
        else -> MockResponse().setResponseCode(404)
      }
    }
    val callbackLatch = CountDownLatch(1)

    handler.getLibraries { callbackLatch.countDown() }

    assertTrue("refresh request did not start", refreshStarted.await(2, TimeUnit.SECONDS))

    assertTrue(
      ShelfDriveConnectionDataCleaner(RuntimeEnvironment.getApplication(), tokenStorage)
        .removeConnectionData(connectionConfig.id)
    )

    val replacement = config(connectionConfig.id, "new-access")
    synchronized(DeviceManager.connectionPersistenceMonitor) {
      synchronized(DeviceManager.connectionStateMonitor) {
        DeviceManager.advanceConnectionStateEpoch()
        DeviceManager.deviceData = DeviceData(
          mutableListOf(replacement),
          replacement.id,
          DeviceSettings.default(),
          null
        )
        assertTrue(DeviceManager.trySelectServerConnectionConfig(replacement))
      }
      assertTrue(tokenStorage.storeRefreshToken(replacement.id, "new-refresh"))
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    }
    releaseRefresh.countDown()

    assertTrue("callback timed out", callbackLatch.await(3, TimeUnit.SECONDS))
    assertEquals(2, server.requestCount)
    assertFalse(DeviceManager.isConnectionLeaseCurrent(oldLease))
    assertEquals(replacement, DeviceManager.serverConnectionConfig)
    assertEquals("new-access", replacement.token)
    assertEquals("new-refresh", tokenStorage.getRefreshToken(replacement.id))
  }

  @Test
  fun `wrong libraries field type completes with an empty list`() {
    server.enqueue(MockResponse().setBody("""{"libraries":"not-an-array"}"""))
    val latch = CountDownLatch(1)
    var result: List<*>? = null

    handler.getLibraries {
      result = it
      latch.countDown()
    }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    assertNotNull(result)
    assertTrue(result!!.isEmpty())
  }

  @Test
  fun `incomplete library item completes with null`() {
    server.enqueue(MockResponse().setBody("""{"id":"book-with-missing-fields"}"""))
    val latch = CountDownLatch(1)
    var completed = false
    var result: Any? = "not completed"

    handler.getLibraryItem("book") {
      result = it
      completed = true
      latch.countDown()
    }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    assertTrue(completed)
    assertNull(result)
  }

  @Test
  fun `search encodes path and query data without changing semantics`() {
    server.enqueue(MockResponse().setBody(
      """{"book":[],"podcast":[],"series":[],"authors":[]}"""
    ))
    val latch = CountDownLatch(1)

    handler.getSearchResults("library/one", "name & #1") { latch.countDown() }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    val request = server.takeRequest(1, TimeUnit.SECONDS)
      ?: throw AssertionError("No search request received")
    assertTrue(request.path!!.startsWith("/api/libraries/library%2Fone/search?"))
    assertTrue(request.path!!.contains("q=name%20%26%20%231"))
  }

  @Test
  fun `native requests apply proxy headers without allowing auth override`() {
    server.enqueue(MockResponse().setBody("""{"libraries":[]}"""))
    val latch = CountDownLatch(1)

    handler.getLibraries { latch.countDown() }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    val request = server.takeRequest(1, TimeUnit.SECONDS)
      ?: throw AssertionError("No libraries request received")
    assertTrue(request.getHeader("X-Proxy-Key") == "proxy-secret")
    assertTrue(request.getHeader("Authorization") == "Bearer token")
  }

  @Test
  fun `oversized declared response completes once with typed empty result`() {
    server.enqueue(
      MockResponse()
        .setBody("{}")
        .setHeader("Content-Length", ApiHandler.MAX_RESPONSE_BODY_BYTES + 1L)
    )
    val latch = CountDownLatch(1)
    val callbackCount = AtomicInteger(0)
    var result: List<*>? = null

    handler.getLibraries {
      result = it
      callbackCount.incrementAndGet()
      latch.countDown()
    }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    assertNotNull(result)
    assertTrue(result!!.isEmpty())
    assertEquals(1, callbackCount.get())
  }

  @Test
  fun `candidate auth helpers never mutate the active connection`() {
    val active = config("active", "active-token")
    val candidate = config("candidate", "candidate-token")
    val data = DeviceData(
      mutableListOf(active, candidate),
      active.id,
      DeviceSettings.default(),
      null
    )

    assertEquals(candidate, refreshConfigForRequest(candidate, active))
    updateAccessTokenForConnection(data, candidate, "new-candidate-token")

    assertEquals("active-token", active.token)
    assertEquals("new-candidate-token", candidate.token)
    assertEquals(
      "new-candidate-token",
      latestAccessTokenForConnection(data, candidate.id, candidate, active)
    )

    clearAccessTokenForConnection(data, candidate)
    assertEquals("active-token", active.token)
    assertEquals("", candidate.token)
    assertEquals(active.id, data.lastServerConnectionConfigId)
  }

  @Test
  fun `refresh gate coalesces per connection and drains callbacks once`() {
    val gate = PerConnectionRefreshGate<String, Any, String>()
    val firstOwner = Any()
    val secondOwner = Any()

    assertTrue(gate.join("first", firstOwner, "request-1"))
    assertFalse(gate.join("first", secondOwner, "request-2"))
    assertTrue(gate.join("second", secondOwner, "request-3"))
    assertEquals(listOf("request-1", "request-2"), gate.drain("first", firstOwner))
    assertTrue(gate.drain("first", firstOwner).isEmpty())
    assertEquals(listOf("request-3"), gate.drain("second", secondOwner))
    assertTrue(gate.join("first", firstOwner, "request-4"))
  }

  @Test
  fun `refresh gate teardown releases a dead leader and preserves live leaders`() {
    val gate = PerConnectionRefreshGate<String, Any, String>()
    val deadLeader = Any()
    val liveLeader = Any()
    val follower = Any()

    assertTrue(gate.join("dead-key", deadLeader, "dead-leader-request"))
    assertFalse(gate.join("dead-key", follower, "dead-follower-request"))
    assertTrue(gate.join("live-key", liveLeader, "live-leader-request"))
    assertFalse(gate.join("live-key", deadLeader, "dead-owned-follower"))

    assertEquals(
      listOf("dead-leader-request", "dead-follower-request", "dead-owned-follower"),
      gate.abortOwner(deadLeader)
    )
    assertTrue(gate.join("dead-key", liveLeader, "replacement-leader-request"))
    // The dead leader's late cancellation callback must not drain the new
    // entry created for the same key after teardown.
    assertTrue(gate.drain("dead-key", deadLeader).isEmpty())
    assertEquals(listOf("live-leader-request"), gate.drain("live-key", liveLeader))
    assertEquals(
      listOf("replacement-leader-request"),
      gate.drain("dead-key", liveLeader)
    )
  }

  @Test
  fun `bounded JSON stream rejects data beyond its hard limit`() {
    val output = BoundedByteArrayOutputStream(4)
    output.write(byteArrayOf(1, 2, 3, 4))

    try {
      output.write(5)
      throw AssertionError("oversized JSON should have been rejected")
    } catch (_: IllegalStateException) {
      // Expected: callers convert this into their typed failure callback.
    }
    assertEquals(4, output.size())
  }

  @Test
  fun `close playback reports server failure`() {
    server.enqueue(MockResponse().setResponseCode(500))
    val latch = CountDownLatch(1)
    var success = true

    handler.closePlaybackSession("session/with spaces", DeviceManager.serverConnectionConfig) {
      success = it
      latch.countDown()
    }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    assertFalse(success)
    val request = server.takeRequest(1, TimeUnit.SECONDS)
      ?: throw AssertionError("No close request received")
    assertTrue(request.path!!.contains("session%2Fwith%20spaces"))
  }

  @Test
  fun `update progress reports server failure and encodes item id`() {
    server.enqueue(MockResponse().setResponseCode(500))
    val latch = CountDownLatch(1)
    var success = true

    handler.updateMediaProgress("item/with spaces", null, JSObject()) {
      success = it
      latch.countDown()
    }

    assertTrue("callback timed out", latch.await(2, TimeUnit.SECONDS))
    assertFalse(success)
    val request = server.takeRequest(1, TimeUnit.SECONDS)
      ?: throw AssertionError("No progress request received")
    assertTrue(request.path!!.contains("item%2Fwith%20spaces"))
  }

  private fun config(id: String, token: String) = ServerConnectionConfig(
    id = id,
    index = 0,
    name = id,
    address = server.url("/").toString().trimEnd('/'),
    version = "2.26.0",
    userId = "user",
    username = "reviewer",
    token = token,
    customHeaders = null
  )

  private class InMemoryRefreshTokenStorage : RefreshTokenStorage {
    private val tokens = mutableMapOf<String, String>()

    @Synchronized
    override fun storeRefreshToken(serverConnectionId: String, refreshToken: String): Boolean {
      tokens[serverConnectionId] = refreshToken
      return true
    }

    @Synchronized
    override fun getRefreshToken(serverConnectionId: String): String? = tokens[serverConnectionId]

    @Synchronized
    override fun removeRefreshToken(serverConnectionId: String): Boolean {
      tokens.remove(serverConnectionId)
      return true
    }

    @Synchronized
    override fun hasRefreshToken(serverConnectionId: String): Boolean =
      tokens.containsKey(serverConnectionId)
  }
}
