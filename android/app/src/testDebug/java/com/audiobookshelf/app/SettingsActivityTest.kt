package com.audiobookshelf.app

import android.app.Activity
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import com.audiobookshelf.app.data.DeviceData
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.RefreshTokenStorage
import io.paperdb.Paper
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1000dp-h600dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsActivityTest {
  private lateinit var server: MockWebServer
  private lateinit var controller: ActivityController<SettingsActivity>
  private lateinit var activity: SettingsActivity
  private lateinit var refreshTokenStorage: InMemoryRefreshTokenStorage

  private val serverUrl get() = activity.findViewById<EditText>(R.id.serverUrl)
  private val username get() = activity.findViewById<EditText>(R.id.username)
  private val password get() = activity.findViewById<EditText>(R.id.password)
  private val signIn get() = activity.findViewById<TextView>(R.id.signInButton)
  private val disconnect get() = activity.findViewById<TextView>(R.id.disconnectButton)
  private val status get() = activity.findViewById<TextView>(R.id.serverStatus)
  private val address get() = server.url("/").toString().trimEnd('/')

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
    // Initialize Paper before the DeviceManager singleton reads its saved state.
    Paper.init(RuntimeEnvironment.getApplication())
    Paper.book("device").destroy()
    DeviceManager.deviceData = emptyDeviceData()
    DeviceManager.serverConnectionConfig = null
    refreshTokenStorage = InMemoryRefreshTokenStorage()
    controller = Robolectric.buildActivity(SettingsActivity::class.java)
    activity = controller.get().apply {
      refreshTokenStorageFactory = { refreshTokenStorage }
    }
    controller.setup()
    shadowOf(Looper.getMainLooper()).idle()
  }

  @After
  fun tearDown() {
    if (::controller.isInitialized) controller.pause().stop().destroy()
    if (::server.isInitialized) server.shutdown()
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData = emptyDeviceData()
    Paper.book("device").destroy()
  }

  @Test
  fun successfulSignInSavesConnectionRefreshesMediaOnceAndFinishes() {
    server.enqueue(successResponse())
    fillCredentials("correct-password")

    signIn.performClick()
    confirmDataDisclosure()

    // The paused main looper keeps the response callback queued until await().
    assertFalse(signIn.isEnabled)
    assertFalse(disconnect.isEnabled)
    assertFalse(serverUrl.isEnabled)
    assertFalse(username.isEnabled)
    assertFalse(password.isEnabled)
    assertEquals("Signing in…", signIn.text.toString())
    assertSuccessfulSignIn(expectedRefreshToken = "reviewer-refresh-token")

    val request = server.takeRequest(1, TimeUnit.SECONDS)
      ?: throw AssertionError("No login request received")
    assertEquals("POST", request.method)
    assertEquals("/login", request.path)
    assertEquals("true", request.getHeader("x-return-tokens"))
  }

  @Test
  fun legacyTokenResponseStillSignsIn() {
    server.enqueue(MockResponse().setResponseCode(200).setBody(
      """{"user":{"id":"reviewer-id","username":"reviewer","token":"reviewer-token"},"serverSettings":{"version":"2.25.0"}}"""
    ))
    fillCredentials("correct-password")

    signIn.performClick()
    confirmDataDisclosure()

    assertSuccessfulSignIn()
  }

  @Test
  fun legacyTokenResponseRemovesRefreshCredentialFromAnEarlierLogin() {
    val configId = DeviceManager.getBase64Id("$address@reviewer")
    assertTrue(refreshTokenStorage.storeRefreshToken(configId, "stale-refresh-token"))
    server.enqueue(MockResponse().setResponseCode(200).setBody(
      """{"user":{"id":"reviewer-id","username":"reviewer","token":"reviewer-token"},"serverSettings":{"version":"2.25.0"}}"""
    ))
    fillCredentials("correct-password")

    signIn.performClick()
    confirmDataDisclosure()

    assertSuccessfulSignIn(expectedRefreshToken = null)
  }

  @Test
  fun loginRedirectIsRejectedWithoutReplayingCredentials() {
    val redirectTarget = MockWebServer().apply { start() }
    try {
      server.enqueue(
        MockResponse()
          .setResponseCode(307)
          .addHeader("Location", redirectTarget.url("/credential-target"))
      )
      fillCredentials("correct-password")

      signIn.performClick()
      confirmDataDisclosure()

      assertSignInFailed("HTTP 307")
      assertNull(redirectTarget.takeRequest(250, TimeUnit.MILLISECONDS))
      assertNoSavedConnectionOrRefresh()
    } finally {
      redirectTarget.shutdown()
    }
  }

  @Test
  fun rejectedCredentialsRemainEditableAndCanBeRetriedSuccessfully() {
    server.enqueue(MockResponse().setResponseCode(401))
    fillCredentials("wrong-password")

    signIn.performClick()
    confirmDataDisclosure()

    assertSignInFailed("password")
    assertEquals(address, serverUrl.text.toString())
    assertEquals("reviewer", username.text.toString())
    assertEquals("wrong-password", password.text.toString())
    assertTrue(serverUrl.isEnabled)
    assertTrue(username.isEnabled)
    assertTrue(password.isEnabled)
    assertNoSavedConnectionOrRefresh()

    password.setText("correct-password")
    server.enqueue(successResponse())
    signIn.performClick()
    confirmDataDisclosure()

    assertSuccessfulSignIn(expectedRefreshToken = "reviewer-refresh-token")
    assertEquals(2, server.requestCount)
  }

  @Test
  fun forbiddenRequestShowsHttpStatusWithoutBlamingPassword() {
    server.enqueue(MockResponse().setResponseCode(403))
    fillCredentials("correct-password")

    signIn.performClick()
    confirmDataDisclosure()

    val message = assertSignInFailed("HTTP 403")
    assertFalse(message.contains("password", ignoreCase = true))
    assertEquals("correct-password", password.text.toString())
    assertNoSavedConnectionOrRefresh()
  }

  @Test
  fun unreachableServerShowsConnectionFailureAndKeepsCredentials() {
    fillCredentials("correct-password")
    val unreachableAddress = serverUrl.text.toString()
    server.shutdown()

    signIn.performClick()
    confirmDataDisclosure()

    assertSignInFailed("Could not reach the server")
    assertEquals(unreachableAddress, serverUrl.text.toString())
    assertEquals("correct-password", password.text.toString())
    assertNoSavedConnectionOrRefresh()
  }

  @Test
  fun missingFieldsShowFailureWithoutSendingLoginRequest() {
    serverUrl.setText(address)
    username.setText("reviewer")

    signIn.performClick()

    assertSignInFailed("password")
    assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
    assertNoSavedConnectionOrRefresh()
  }

  @Test
  fun `parked settings exposes compliant offline fallback and readable privacy text`() {
    val downloadCurrent = activity.findViewById<TextView>(R.id.downloadCurrentBookButton)
    val offlineHelp = activity.findViewById<TextView>(R.id.offlineCurrentBookSummary)
    val privacy = activity.findViewById<TextView>(R.id.privacyPolicySummary)
    val minimumTarget = (76 * activity.resources.displayMetrics.density).toInt()

    assertFalse(downloadCurrent.isEnabled)
    assertTrue(downloadCurrent.layoutParams.height >= minimumTarget)
    assertTrue(offlineHelp.text.toString().contains("Start an audiobook"))
    assertTrue(privacy.text.toString().contains("JediBkApps"))
    assertTrue(privacy.text.toString().contains("christianbrooker@gmail.com"))
  }

  @Test
  fun reviewingDataDisclosureDoesNotContactServerOrSaveAccount() {
    fillCredentials("correct-password")

    signIn.performClick()
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(signIn.isEnabled)
    assertTrue(serverUrl.isEnabled)
    assertTrue(username.isEnabled)
    assertTrue(password.isEnabled)
    assertNull(server.takeRequest(250, TimeUnit.MILLISECONDS))
    assertNoSavedConnectionOrRefresh()
  }

  @Test
  fun successfulHttpResponseWithoutTokenDoesNotSignIn() {
    server.enqueue(MockResponse().setResponseCode(200).setBody(
      """{"user":{"id":"reviewer-id","username":"reviewer"}}"""
    ))
    fillCredentials("correct-password")

    signIn.performClick()
    confirmDataDisclosure()

    assertSignInFailed("valid sign-in response")
    assertEquals("correct-password", password.text.toString())
    assertNoSavedConnectionOrRefresh()
  }

  @Test
  fun oversizedChunkedLoginResponseIsRejectedWithoutSavingCredentials() {
    server.enqueue(
      MockResponse()
        .setResponseCode(200)
        .setChunkedBody("x".repeat(1024 * 1024 + 1), 8192)
    )
    fillCredentials("correct-password")

    signIn.performClick()
    confirmDataDisclosure()

    assertSignInFailed("valid sign-in response")
    assertEquals("correct-password", password.text.toString())
    assertNoSavedConnectionOrRefresh()
  }

  @Test
  fun savedProfileWithoutActiveSelectionCanStillBeDisconnected() {
    val saved = ServerConnectionConfig(
      id = "saved-profile",
      index = 0,
      name = "example.test (reviewer)",
      address = "https://example.test",
      version = "2.26.0",
      userId = "reviewer-id",
      username = "reviewer",
      token = "expired-token",
      customHeaders = null
    )
    controller.pause().stop().destroy()
    DeviceManager.deviceData = DeviceData(
      mutableListOf(saved),
      null,
      DeviceSettings.default(),
      null
    )
    DeviceManager.serverConnectionConfig = null
    DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
    controller = Robolectric.buildActivity(SettingsActivity::class.java)
    activity = controller.get().apply {
      refreshTokenStorageFactory = { refreshTokenStorage }
    }
    controller.setup()
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(disconnect.isEnabled)
    assertEquals("https://example.test", serverUrl.text.toString())
    disconnect.performClick()
    await("connection cleanup") {
      DeviceManager.deviceData.serverConnectionConfigs.isEmpty() && !disconnect.isEnabled
    }

    assertTrue(DeviceManager.deviceData.serverConnectionConfigs.isEmpty())
    assertTrue(DeviceManager.dbManager.getDeviceData().serverConnectionConfigs.isEmpty())
    assertFalse(disconnect.isEnabled)
  }

  private fun fillCredentials(passwordValue: String) {
    serverUrl.setText(address)
    username.setText("reviewer")
    password.setText(passwordValue)
  }

  private fun confirmDataDisclosure() {
    shadowOf(Looper.getMainLooper()).idle()
    val message = status.text.toString()
    assertTrue(message.startsWith("Data sent to your server"))
    assertTrue(message.contains(address))
    assertTrue(message.contains("username and password"))
    assertTrue(message.contains("random install-scoped app-instance identifier"))
    assertTrue(message.contains("background progress sync"))
    assertTrue(message.contains("server you chose"))
    assertEquals("Continue and sign in", signIn.text.toString())
    signIn.performClick()
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun assertSuccessfulSignIn(expectedRefreshToken: String? = null) {
    await("successful sign-in") { activity.isFinishing }

    val saved = DeviceManager.dbManager.getDeviceData()
    assertEquals(1, saved.serverConnectionConfigs.size)
    val config = saved.serverConnectionConfigs.single()
    assertEquals(address, config.address)
    assertEquals("reviewer", config.username)
    assertEquals("reviewer-token", config.token)
    assertEquals(config.id, saved.lastServerConnectionConfigId)
    assertEquals(config, DeviceManager.serverConnectionConfig)
    assertEquals(expectedRefreshToken, refreshTokenStorage.getRefreshToken(config.id))
    assertEquals("", password.text.toString())
    assertEquals(Activity.RESULT_OK, shadowOf(activity).resultCode)

    assertNull(
      "Settings must not start a background media service",
      shadowOf(activity).nextStartedService
    )
  }

  private fun assertSignInFailed(reason: String): String {
    await("sign-in failure") {
      status.text.toString().startsWith("Sign-in failed") && signIn.isEnabled
    }
    assertFalse(activity.isFinishing)
    assertTrue(signIn.isEnabled)
    val message = status.text.toString()
    assertTrue("Expected a readable failure reason, got: $message",
      message.contains(reason, ignoreCase = true))
    return message
  }

  private fun assertNoSavedConnectionOrRefresh() {
    assertNull(DeviceManager.serverConnectionConfig)
    assertTrue(DeviceManager.deviceData.serverConnectionConfigs.isEmpty())
    assertTrue(DeviceManager.dbManager.getDeviceData().serverConnectionConfigs.isEmpty())
    assertNull(shadowOf(activity).nextStartedService)
  }

  private fun await(description: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    do {
      shadowOf(Looper.getMainLooper()).idle()
      if (condition()) return
      Thread.sleep(10)
    } while (System.nanoTime() < deadline)
    throw AssertionError("Timed out waiting for $description; status: ${status.text}")
  }

  private fun emptyDeviceData() = DeviceData(mutableListOf(), null, DeviceSettings.default(), null)

  private fun successResponse() = MockResponse().setResponseCode(200).setBody(
    """{"user":{"id":"reviewer-id","username":"reviewer","accessToken":"reviewer-token","refreshToken":"reviewer-refresh-token"},"serverSettings":{"version":"2.26.0"}}"""
  )

  private class InMemoryRefreshTokenStorage : RefreshTokenStorage {
    private val values = ConcurrentHashMap<String, String>()

    override fun storeRefreshToken(serverConnectionId: String, refreshToken: String): Boolean {
      values[serverConnectionId] = refreshToken
      return true
    }

    override fun getRefreshToken(serverConnectionId: String): String? = values[serverConnectionId]

    override fun removeRefreshToken(serverConnectionId: String): Boolean {
      values.remove(serverConnectionId)
      return true
    }

    override fun hasRefreshToken(serverConnectionId: String): Boolean =
      values.containsKey(serverConnectionId)
  }
}
