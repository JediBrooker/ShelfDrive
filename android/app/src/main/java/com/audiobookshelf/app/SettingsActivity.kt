package com.audiobookshelf.app

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.audiobookshelf.app.accounts.AccountRegistrationResult
import com.audiobookshelf.app.accounts.ServerConnectionAccountRegistry
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.player.PlayerNotificationService
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import java.util.concurrent.Executors
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

  private companion object {
    const val MAX_LOGIN_RESPONSE_BYTES = 1024L * 1024L
  }
  private val accountRegistry by lazy {
    ServerConnectionAccountRegistry(applicationContext)
  }
  private val accountIoExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-account-io")
  }
  /** Instance-scoped test seam; production always keeps the secure default. */
  internal var refreshTokenStorageFactory: (android.content.Context) -> RefreshTokenStorage =
    { context -> SecureStorage(context) }
  private var accountAuthenticatorResponse: AccountAuthenticatorResponse? = null

  // Deliberately not a data class: generated data-class toString() would put
  // the access/refresh credentials into crash inspection and debug strings.
  private class LoginAttempt(
    val config: ServerConnectionConfig? = null,
    val refreshToken: String? = null,
    val errorMessage: String? = null
  )

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS)
    // Login bodies contain the user's password. Never replay them to a
    // server-selected redirect target, even when the target is HTTPS.
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
  private val mapper = ObjectMapper()

  private lateinit var serverUrlInput: EditText
  private lateinit var usernameInput: EditText
  private lateinit var passwordInput: EditText
  private lateinit var serverStatus: TextView
  private lateinit var signInButton: TextView
  private lateinit var disconnectButton: TextView
  private var displayedConnectionId: String? = null

  // Playback
  private lateinit var jumpForwardSpinner: Spinner
  private lateinit var jumpBackwardSpinner: Spinner
  private lateinit var disableAutoRewindSwitch: MaterialSwitch

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.SettingsTheme)
    super.onCreate(savedInstanceState)
    @Suppress("DEPRECATION")
    accountAuthenticatorResponse =
      intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
    accountAuthenticatorResponse?.onRequestContinued()
    DbManager.initialize(applicationContext)
    setContentView(R.layout.activity_settings)

    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        finishSettings()
      }
    })

    findViewById<ImageView>(R.id.backButton).setOnClickListener { finishSettings() }
    findViewById<TextView>(R.id.privacyPolicyButton).setOnClickListener { showPrivacyPolicy() }

    bindViews()
    populateFromCurrentState()
    wireListeners()
    accountIoExecutor.execute {
      removeLegacyCleartextConnections()
      reconcileAndroidAccounts()
      runOnUiThread {
        if (!isFinishing && !isDestroyed) populateFromCurrentState()
      }
    }
  }

  override fun onDestroy() {
    // A parked sign-in can still be waiting on network I/O when AAOS closes
    // the activity. Cancel it so callbacks cannot retain or update a dead UI.
    runCatching { httpClient.dispatcher.cancelAll() }
    runCatching { httpClient.connectionPool.evictAll() }
    runCatching { httpClient.dispatcher.executorService.shutdownNow() }
    accountIoExecutor.shutdownNow()
    if (isFinishing && !isChangingConfigurations) {
      cancelAuthenticatorResponse()
    }
    super.onDestroy()
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
      ?: DeviceManager.getLastServerConnectionConfig()
    val displayConfig = activeConfig
      ?: DeviceManager.snapshotServerConnectionConfigs().firstOrNull()
    val settings = deviceData.deviceSettings ?: DeviceSettings.default()

    // A saved profile is useful for pre-filling the form, but it is not an
    // authenticated session after logout or a failed token refresh.
    DeviceManager.serverConnectionConfig = activeConfig
    displayedConnectionId = displayConfig?.id
    serverUrlInput.setText(displayConfig?.address ?: "")
    usernameInput.setText(displayConfig?.username ?: "")
    passwordInput.setText("")  // never pre-fill passwords

    serverStatus.text = if (activeConfig != null) {
      getString(R.string.settings_connected, activeConfig.address, activeConfig.username)
    } else {
      getString(R.string.settings_not_connected)
    }
    // A refresh failure can deliberately clear the active selection while the
    // local profile remains. Keep that displayed profile removable so the
    // privacy-policy Disconnect path never becomes unreachable.
    disconnectButton.isEnabled = displayConfig != null

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
    synchronized(DeviceManager.connectionPersistenceMonitor) {
      val deviceData = synchronized(DeviceManager.connectionStateMonitor) {
        val current = DeviceManager.deviceData
        val settings = current.deviceSettings ?: DeviceSettings.default()
        mutate(settings)
        current.deviceSettings = settings
        current
      }
      DeviceManager.dbManager.saveDeviceData(deviceData)
    }
  }

  private fun signIn() {
    val enteredAddress = serverUrlInput.text?.toString()?.trim()?.trimEnd('/').orEmpty()
    val user = usernameInput.text?.toString()?.trim().orEmpty()
    val pass = passwordInput.text?.toString().orEmpty()
    if (enteredAddress.isEmpty() || user.isEmpty() || pass.isEmpty()) {
      showSignInFailure(getString(R.string.settings_sign_in_required_fields))
      return
    }
    val parsedAddress = enteredAddress.toHttpUrlOrNull()
    if (parsedAddress == null) {
      showSignInFailure(getString(R.string.settings_sign_in_invalid_url))
      return
    }
    if (!DeviceManager.isServerAddressAllowed(parsedAddress.toString())) {
      showSignInFailure(getString(R.string.settings_sign_in_https_required))
      return
    }
    if (!accountRegistry.canModifyAccounts()) {
      showSignInFailure(getString(R.string.shelfdrive_account_changes_restricted))
      return
    }
    val address = parsedAddress.toString().trimEnd('/')

    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.data_disclosure_title)
      .setMessage(getString(R.string.data_disclosure_message, address))
      .setNegativeButton(R.string.data_disclosure_cancel, null)
      .setPositiveButton(R.string.data_disclosure_continue) { _, _ ->
        beginSignIn(address, user, pass)
      }
      .show()
  }

  private fun beginSignIn(address: String, user: String, pass: String) {
    setSigningIn(true)
    serverStatus.setTextColor(getColor(R.color.settings_status_info))
    serverStatus.setText(R.string.settings_signing_in)

    accountIoExecutor.execute {
      val loginAttempt = attemptLogin(address, user, pass)
      val registration = loginAttempt.config?.let(accountRegistry::registerOrUpdate)
      val persisted = if (loginAttempt.config != null && registration?.succeeded == true) {
        persistServerConfig(loginAttempt.config, loginAttempt.refreshToken)
      } else {
        false
      }
      if (!persisted && loginAttempt.config != null &&
        registration?.status == AccountRegistrationResult.Status.CREATED
      ) {
        accountRegistry.remove(loginAttempt.config.id, cleanConnectionData = false)
      }
      runOnUiThread {
        if (isFinishing || isDestroyed) return@runOnUiThread

        setSigningIn(false)
        if (loginAttempt.config != null) {
          if (registration?.succeeded != true) {
            val message = if (
              registration?.status == AccountRegistrationResult.Status.RESTRICTED
            ) {
              R.string.shelfdrive_account_changes_restricted
            } else {
              R.string.shelfdrive_account_registration_failed
            }
            showSignInFailure(getString(message))
            return@runOnUiThread
          }
          if (!persisted) {
            showSignInFailure(getString(R.string.settings_sign_in_storage_error))
            return@runOnUiThread
          }
          completeAuthenticatorResponse(registration.account)
          passwordInput.setText("")
          Toast.makeText(this, R.string.settings_sign_in_success, Toast.LENGTH_SHORT).show()
          setResult(RESULT_OK)
          // Persistence already refreshes Car Media. Close Settings so the host
          // shows the media screen without requiring another tap on Back.
          finish()
        } else {
          showSignInFailure(
            loginAttempt.errorMessage ?: getString(R.string.settings_sign_in_invalid_response)
          )
        }
      }
    }
  }

  private fun setSigningIn(signingIn: Boolean) {
    signInButton.isEnabled = !signingIn
    signInButton.setText(if (signingIn) R.string.settings_signing_in else R.string.car_sign_in_action)
    serverUrlInput.isEnabled = !signingIn
    usernameInput.isEnabled = !signingIn
    passwordInput.isEnabled = !signingIn
    disconnectButton.isEnabled = !signingIn && displayedConnectionId != null
  }

  private fun showSignInFailure(reason: String) {
    serverStatus.text = getString(R.string.settings_sign_in_failed_reason, reason)
    serverStatus.setTextColor(getColor(R.color.settings_status_error))
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_sign_in_failed)
      .setMessage(reason)
      .setPositiveButton(android.R.string.ok, null)
      .show()
  }

  /**
   * POSTs username/password to <address>/login. Modern Audiobookshelf servers
   * return accessToken/refreshToken; older servers returned token. Accept both
   * response formats so the native AAOS sign-in screen works across versions.
   */
  private fun attemptLogin(address: String, user: String, pass: String): LoginAttempt {
    return try {
      val body = mapper.createObjectNode().apply {
        put("username", user)
        put("password", pass)
      }.toString().toRequestBody("application/json".toMediaType())
      val request = Request.Builder()
        .url("$address/login")
        .addHeader("x-return-tokens", "true")
        .post(body)
        .build()
      httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          val reason = if (response.code == 401) {
            getString(R.string.settings_sign_in_invalid_credentials)
          } else {
            getString(R.string.settings_sign_in_http_error, response.code)
          }
          return@use LoginAttempt(errorMessage = reason)
        }
        val responseJson = readBoundedLoginResponse(response.body)
          ?: return@use LoginAttempt(
            errorMessage = getString(R.string.settings_sign_in_invalid_response)
          )
        val json: JsonNode = mapper.readTree(responseJson)
        val userNode = json.path("user")
        val token = userNode.path("accessToken").asText("")
          .ifEmpty { userNode.path("token").asText("") }
        val refreshToken = userNode.path("refreshToken").asText("").ifBlank { null }
        val userId = userNode.path("id").asText("")
        val username = userNode.path("username").asText(user)
        val serverVersion = json.path("serverSettings").path("version").asText(null)
        if (token.isEmpty()) {
          return@use LoginAttempt(
            errorMessage = getString(R.string.settings_sign_in_invalid_response)
          )
        }
        val config = ServerConnectionConfig(
          id = DeviceManager.getBase64Id("$address@$username"),
          index = DeviceManager.snapshotServerConnectionConfigs().size,
          name = "${address.removePrefix("http://").removePrefix("https://")} ($username)",
          address = address,
          version = serverVersion,
          userId = userId,
          username = username,
          token = token,
          customHeaders = null
        )
        LoginAttempt(config = config, refreshToken = refreshToken)
      }
    } catch (e: Exception) {
      Log.w(tag, "Login failed (${e.javaClass.simpleName})")
      val message = when (e) {
        is IllegalArgumentException -> R.string.settings_sign_in_invalid_url
        is JsonProcessingException -> R.string.settings_sign_in_invalid_response
        is IOException -> R.string.settings_sign_in_network_error
        else -> R.string.settings_sign_in_invalid_response
      }
      LoginAttempt(errorMessage = getString(message))
    }
  }

  /**
   * Reads at most one MiB even when a server omits Content-Length or uses a
   * compressed/chunked response. Reading only Okio's bounded buffer avoids an
   * untrusted sign-in endpoint exhausting the AAOS process heap.
   */
  private fun readBoundedLoginResponse(body: ResponseBody?): String? {
    if (body == null) return null
    val declaredLength = body.contentLength()
    if (declaredLength > MAX_LOGIN_RESPONSE_BYTES) return null

    val source = body.source()
    val reachedLimitPlusOne = source.request(MAX_LOGIN_RESPONSE_BYTES + 1L)
    if (reachedLimitPlusOne && source.buffer.size > MAX_LOGIN_RESPONSE_BYTES) {
      return null
    }
    return source.buffer.readUtf8()
  }

  private fun persistServerConfig(config: ServerConnectionConfig, refreshToken: String?): Boolean {
    val persisted = synchronized(DeviceManager.connectionPersistenceMonitor) {
      val storage = refreshTokenStorageFactory(applicationContext)
      val priorRefreshToken = storage.getRefreshToken(config.id)
      val credentialUpdated = if (refreshToken.isNullOrEmpty()) {
        // A successful login to an older server must retire a refresh token
        // left by an earlier modern login for this deterministic profile ID.
        storage.removeRefreshToken(config.id)
      } else {
        storage.storeRefreshToken(config.id, refreshToken)
      }
      if (!credentialUpdated) {
        Log.e(tag, "Could not persist the encrypted refresh credential")
        return@synchronized false
      }

      val (priorConfigs, priorLast, priorActive) =
        synchronized(DeviceManager.connectionStateMonitor) {
          Triple(
            DeviceManager.deviceData.serverConnectionConfigs.toList(),
            DeviceManager.deviceData.lastServerConnectionConfigId,
            DeviceManager.serverConnectionConfig
          )
        }
      try {
        val deviceData = synchronized(DeviceManager.connectionStateMonitor) {
          DeviceManager.advanceConnectionStateEpoch()
          val current = DeviceManager.deviceData
          current.serverConnectionConfigs.removeAll {
            it.address == config.address && it.username == config.username
          }
          current.serverConnectionConfigs.add(config)
          current.lastServerConnectionConfigId = config.id
          check(DeviceManager.trySelectServerConnectionConfig(config))
          current
        }
        DeviceManager.dbManager.saveDeviceData(deviceData)
        displayedConnectionId = config.id
        true
      } catch (error: RuntimeException) {
        Log.e(tag, "Could not persist the server profile (${error.javaClass.simpleName})")
        synchronized(DeviceManager.connectionStateMonitor) {
          DeviceManager.advanceConnectionStateEpoch()
          DeviceManager.deviceData.serverConnectionConfigs.clear()
          DeviceManager.deviceData.serverConnectionConfigs.addAll(priorConfigs)
          DeviceManager.deviceData.lastServerConnectionConfigId = priorLast
          DeviceManager.serverConnectionConfig = priorActive
        }
        if (priorRefreshToken == null) storage.removeRefreshToken(config.id)
        else storage.storeRefreshToken(config.id, priorRefreshToken)
        false
      }
    }
    if (persisted) notifyMediaBrowserServerConfigChanged("signed in")
    return persisted
  }

  private fun notifyMediaBrowserServerConfigChanged(reason: String) {
    // Car Media already binds the browser service. Refresh it in-process when
    // present; a future bind reads the persisted state if it is not running.
    PlayerNotificationService.requestBrowseRefresh(reason)
  }

  private fun removeLegacyCleartextConnections() {
    val removedIds = synchronized(DeviceManager.connectionPersistenceMonitor) {
      val ids = DeviceManager.removeInsecureServerConnections()
      if (ids.isNotEmpty()) {
        val secureStorage = SecureStorage(applicationContext)
        ids.forEach(secureStorage::removeRefreshToken)
      }
      ids
    }
    if (removedIds.isEmpty()) return
    Log.w(tag, "Removed ${removedIds.size} legacy HTTP server profile(s)")
  }

  private fun reconcileAndroidAccounts() {
    val result = accountRegistry.reconcile(DeviceManager.snapshotServerConnectionConfigs())
    when {
      result.restricted -> Log.i(tag, "Android account changes are restricted")
      !result.succeeded -> Log.w(
        tag,
        "Android account reconciliation failed for ${result.failures.size} account(s)"
      )
      else -> Log.d(
        tag,
        "Android accounts reconciled: +${result.created}, ~${result.updated}, -${result.removed}"
      )
    }
  }

  private fun showPrivacyPolicy() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.privacy_policy_title)
      .setMessage(R.string.privacy_policy_summary)
      .setPositiveButton(android.R.string.ok, null)
      .setNeutralButton(R.string.privacy_policy_view_online) { _, _ ->
        try {
          startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
        } catch (_: ActivityNotFoundException) {
          Toast.makeText(this, R.string.privacy_policy_no_browser, Toast.LENGTH_LONG).show()
        }
      }
      .show()
  }

  private fun finishSettings() {
    if (DeviceManager.serverConnectionConfig != null ||
      DeviceManager.getLastServerConnectionConfig() != null ||
      DeviceManager.snapshotServerConnectionConfigs().isNotEmpty()
    ) {
      notifyMediaBrowserServerConfigChanged("settings closed")
    }
    cancelAuthenticatorResponse()
    finish()
  }

  private fun completeAuthenticatorResponse(account: Account?) {
    val response = accountAuthenticatorResponse ?: return
    if (account == null) {
      runCatching {
        response.onError(
          AccountManager.ERROR_CODE_REMOTE_EXCEPTION,
          getString(R.string.shelfdrive_account_registration_failed)
        )
      }
    } else {
      runCatching {
        response.onResult(Bundle().apply {
          putString(AccountManager.KEY_ACCOUNT_NAME, account.name)
          putString(AccountManager.KEY_ACCOUNT_TYPE, account.type)
        })
      }
    }
    accountAuthenticatorResponse = null
  }

  private fun cancelAuthenticatorResponse() {
    val response = accountAuthenticatorResponse ?: return
    runCatching {
      response.onError(
        AccountManager.ERROR_CODE_CANCELED,
        getString(android.R.string.cancel)
      )
    }
    accountAuthenticatorResponse = null
  }

  private fun disconnect() {
    val connectionId = DeviceManager.serverConnectionConfig?.id
      ?: displayedConnectionId
      ?: DeviceManager.getLastServerConnectionConfig()?.id
    if (connectionId != null) {
      signInButton.isEnabled = false
      disconnectButton.isEnabled = false
      serverStatus.setText(R.string.settings_disconnecting)
      accountIoExecutor.execute {
        val removal = accountRegistry.remove(connectionId)
        runOnUiThread {
          if (isFinishing || isDestroyed) return@runOnUiThread
          if (!removal.succeeded) {
            val message = if (removal.status == AccountRegistrationResult.Status.RESTRICTED) {
              R.string.shelfdrive_account_changes_restricted
            } else {
              R.string.shelfdrive_account_removal_failed
            }
            setSigningIn(false)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return@runOnUiThread
          }
          notifyMediaBrowserServerConfigChanged("disconnected")
          showDisconnectedState()
        }
      }
      return
    }
    showDisconnectedState()
  }

  private fun showDisconnectedState() {
    displayedConnectionId = null
    serverStatus.setText(R.string.settings_not_connected)
    serverStatus.setTextColor(getColor(R.color.settings_status_info))
    disconnectButton.isEnabled = false
    serverUrlInput.setText("")
    usernameInput.setText("")
    passwordInput.setText("")
    Toast.makeText(this, R.string.settings_disconnected, Toast.LENGTH_SHORT).show()
  }
}
