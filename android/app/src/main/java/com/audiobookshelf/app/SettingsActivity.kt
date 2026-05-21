package com.audiobookshelf.app

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.android.material.materialswitch.MaterialSwitch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Native AAOS-style Settings activity.
 *
 * Reachable via ACTION_APPLICATION_PREFERENCES (Car Media's settings cog).
 *
 * Sections mirror the Vue settings page so users get a consistent experience
 * across phone and car:
 *   - Server: URL / Username / Password + Sign In / Disconnect
 *   - User Interface: bookshelf view, lock orientation, haptic feedback
 *   - Playback: jump increments, auto-rewind, mp3 index seeking, seek-on-notif
 *   - Sleep Timer: shake-to-reset, shake sensitivity, audio fade out, vibrate
 *
 * Settings persist into the same Paper-backed DeviceData the Vue UI reads, so
 * changes are reflected in both surfaces.
 */
class SettingsActivity : AppCompatActivity() {
  private val tag = "SettingsActivity"

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()
  private val mapper = ObjectMapper()

  private lateinit var serverUrlInput: EditText
  private lateinit var usernameInput: EditText
  private lateinit var passwordInput: EditText
  private lateinit var serverStatus: TextView
  private lateinit var signInButton: TextView
  private lateinit var disconnectButton: TextView

  // Playback
  private lateinit var jumpForwardSpinner: Spinner
  private lateinit var jumpBackwardSpinner: Spinner
  private lateinit var disableAutoRewindSwitch: MaterialSwitch

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.SettingsTheme)
    super.onCreate(savedInstanceState)
    DbManager.initialize(applicationContext)
    setContentView(R.layout.activity_settings)

    findViewById<ImageView>(R.id.backButton).setOnClickListener { finish() }

    bindViews()
    populateFromCurrentState()
    wireListeners()
  }

  private fun bindViews() {
    serverUrlInput = findViewById(R.id.serverUrl)
    usernameInput = findViewById(R.id.username)
    passwordInput = findViewById(R.id.password)
    serverStatus = findViewById(R.id.serverStatus)
    signInButton = findViewById(R.id.signInButton)
    disconnectButton = findViewById(R.id.disconnectButton)

    jumpForwardSpinner = findViewById(R.id.jumpForwardSpinner)
    jumpBackwardSpinner = findViewById(R.id.jumpBackwardSpinner)
    disableAutoRewindSwitch = findViewById(R.id.disableAutoRewindSwitch)
  }

  private fun populateFromCurrentState() {
    val deviceData = DeviceManager.deviceData
    val activeConfig = DeviceManager.serverConnectionConfig
      ?: deviceData.serverConnectionConfigs.firstOrNull()
    val settings = deviceData.deviceSettings ?: DeviceSettings.default()

    serverUrlInput.setText(activeConfig?.address ?: "")
    usernameInput.setText(activeConfig?.username ?: "")
    passwordInput.setText("")  // never pre-fill passwords

    serverStatus.text = if (activeConfig != null) {
      "Connected to ${activeConfig.address} as ${activeConfig.username}"
    } else {
      "Not connected"
    }
    disconnectButton.isEnabled = activeConfig != null

    // Playback
    setupJumpSpinner(jumpForwardSpinner, settings.jumpForwardTime) { v -> mutateAndSave { it.jumpForwardTime = v } }
    setupJumpSpinner(jumpBackwardSpinner, settings.jumpBackwardsTime) { v -> mutateAndSave { it.jumpBackwardsTime = v } }
    disableAutoRewindSwitch.isChecked = settings.disableAutoRewind
  }

  private fun wireListeners() {
    signInButton.setOnClickListener { signIn() }
    disconnectButton.setOnClickListener { disconnect() }

    disableAutoRewindSwitch.setOnCheckedChangeListener { _, c -> mutateAndSave { it.disableAutoRewind = c } }
  }

  private fun setupJumpSpinner(spinner: Spinner, current: Int, onChange: (Int) -> Unit) {
    val options = listOf(5, 10, 15, 20, 30, 60, 90)
    val labels = options.map { "${it}s" }
    val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    spinner.adapter = adapter
    val initialIndex = options.indexOf(current).takeIf { it >= 0 } ?: 1
    spinner.setSelection(initialIndex)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        onChange(options[position])
      }
      override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
  }

  /** Atomically mutates DeviceSettings and persists via DbManager. */
  private fun mutateAndSave(mutate: (DeviceSettings) -> Unit) {
    val deviceData = DeviceManager.deviceData
    val settings = deviceData.deviceSettings ?: DeviceSettings.default()
    mutate(settings)
    deviceData.deviceSettings = settings
    DeviceManager.dbManager.saveDeviceData(deviceData)
  }

  private fun signIn() {
    val address = serverUrlInput.text?.toString()?.trim()?.trimEnd('/').orEmpty()
    val user = usernameInput.text?.toString()?.trim().orEmpty()
    val pass = passwordInput.text?.toString().orEmpty()
    if (address.isEmpty() || user.isEmpty() || pass.isEmpty()) {
      serverStatus.text = "Server URL, username, and password are all required"
      return
    }

    signInButton.isEnabled = false
    serverStatus.text = "Signing in..."

    Thread {
      val (config, errorMessage) = attemptLogin(address, user, pass)
      runOnUiThread {
        signInButton.isEnabled = true
        if (config != null) {
          persistServerConfig(config)
          serverStatus.text = "Connected to ${config.address} as ${config.username}"
          passwordInput.setText("")
          disconnectButton.isEnabled = true
          Toast.makeText(this, "Signed in to ${config.address}", Toast.LENGTH_SHORT).show()
        } else {
          serverStatus.text = errorMessage ?: "Sign in failed"
        }
      }
    }.start()
  }

  /**
   * POSTs username/password to <address>/login. Returns a partial
   * ServerConnectionConfig on success, or (null, message) on failure.
   */
  private fun attemptLogin(address: String, user: String, pass: String): Pair<ServerConnectionConfig?, String?> {
    return try {
      val body = mapper.createObjectNode().apply {
        put("username", user)
        put("password", pass)
      }.toString().toRequestBody("application/json".toMediaType())
      val request = Request.Builder()
        .url("$address/login")
        .post(body)
        .build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return@use null to "Sign in failed: HTTP ${response.code}"
        }
        val json: JsonNode = mapper.readTree(response.body?.byteStream())
        val userNode = json.path("user")
        val token = userNode.path("token").asText("")
        val userId = userNode.path("id").asText("")
        val username = userNode.path("username").asText(user)
        val serverVersion = json.path("serverSettings").path("version").asText(null)
        if (token.isEmpty()) {
          return@use null to "Sign in failed: server returned no token"
        }
        val config = ServerConnectionConfig(
          id = UUID.randomUUID().toString(),
          index = DeviceManager.deviceData.serverConnectionConfigs.size,
          name = "${address.removePrefix("http://").removePrefix("https://")} ($username)",
          address = address,
          version = serverVersion,
          userId = userId,
          username = username,
          token = token,
          customHeaders = null
        )
        config to null
      }
    } catch (e: Exception) {
      Log.w(tag, "Login error: ${e.message}", e)
      null to "Network error: ${e.javaClass.simpleName}"
    }
  }

  private fun persistServerConfig(config: ServerConnectionConfig) {
    val deviceData = DeviceManager.deviceData
    deviceData.serverConnectionConfigs.removeAll { it.address == config.address && it.username == config.username }
    deviceData.serverConnectionConfigs.add(config)
    deviceData.lastServerConnectionConfigId = config.id
    DeviceManager.serverConnectionConfig = config
    DeviceManager.dbManager.saveDeviceData(deviceData)
  }

  private fun disconnect() {
    val deviceData = DeviceManager.deviceData
    val current = DeviceManager.serverConnectionConfig
    if (current != null) {
      deviceData.serverConnectionConfigs.removeAll { it.id == current.id }
      DeviceManager.serverConnectionConfig = null
      deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(deviceData)
    }
    serverStatus.text = "Not connected"
    disconnectButton.isEnabled = false
    serverUrlInput.setText("")
    usernameInput.setText("")
    passwordInput.setText("")
    Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show()
  }
}
