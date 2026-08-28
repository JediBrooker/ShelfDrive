package com.audiobookshelf.app

import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BadParcelableException
import android.os.Build
import android.text.format.Formatter
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.audiobookshelf.app.accounts.AccountRegistrationResult
import com.audiobookshelf.app.accounts.ServerConnectionAccountRegistry
import com.audiobookshelf.app.accounts.ShelfDriveAccountContract
import com.audiobookshelf.app.data.DeviceSettings
import com.audiobookshelf.app.data.DownloadUsingCellularSetting
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.downloads.OfflineDownloadCoordinator
import com.audiobookshelf.app.downloads.OfflineDownloadResult
import com.audiobookshelf.app.downloads.OfflineDownloadState
import com.audiobookshelf.app.downloads.OfflineStorageSummary
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.server.ApiHandler
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native AAOS-style Settings activity.
 *
 * Reachable via ACTION_APPLICATION_PREFERENCES (Car Media's settings cog).
 *
 * Sections mirror the Vue settings page so users get a consistent experience
 * across phone and car:
 *   - Server: URL / Username / Password + Sign In / Disconnect
 *   - User Interface: bookshelf view, lock orientation, haptic feedback
 *   - Offline Audiobooks: storage status, metered network use, cancel/delete
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
  private val offlineUiExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-offline-settings")
  }
  private val offlineDownloads by lazy {
    OfflineDownloadCoordinator(applicationContext)
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
  private var pendingDisclosureAddress: String? = null
  private val resetSignInDisclosure = Runnable {
    pendingDisclosureAddress = null
    if (this::signInButton.isInitialized && signInButton.isEnabled) {
      signInButton.setText(R.string.car_sign_in_action)
      serverStatus.setTextColor(getColor(R.color.settings_status_info))
      serverStatus.setText(R.string.data_disclosure_expired)
    }
  }

  // Playback
  private lateinit var jumpForwardSpinner: Spinner
  private lateinit var jumpBackwardSpinner: Spinner
  private lateinit var disableAutoRewindSwitch: MaterialSwitch

  // Offline audiobooks
  private lateinit var offlineSummary: TextView
  private lateinit var offlineMeteredSwitch: MaterialSwitch
  private lateinit var offlineCurrentBookSummary: TextView
  private lateinit var downloadCurrentBookButton: TextView
  private lateinit var cancelAllDownloadsButton: TextView
  private lateinit var deleteAllDownloadsButton: TextView
  private var populatingOfflineSettings = false
  private var offlineDownloadReceiverRegistered = false
  private val offlineRefreshQueued = AtomicBoolean(false)
  private val offlineRefreshAgain = AtomicBoolean(false)
  private var currentBookDownloadInFlight = false
  private var currentBookApi: ApiHandler? = null
  private var deleteConfirmationArmed = false
  private val resetDeleteConfirmation = Runnable {
    deleteConfirmationArmed = false
    if (this::deleteAllDownloadsButton.isInitialized) {
      deleteAllDownloadsButton.setText(R.string.settings_offline_delete_all)
      refreshOfflineSummary()
    }
  }
  private val offlineDownloadReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == OfflineDownloadCoordinator.ACTION_STATE_CHANGED) {
        refreshOfflineSummary()
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    setTheme(R.style.SettingsTheme)
    super.onCreate(savedInstanceState)
    accountAuthenticatorResponse = readAuthenticatorResponse()
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
    ContextCompat.registerReceiver(
      this,
      offlineDownloadReceiver,
      IntentFilter(OfflineDownloadCoordinator.ACTION_STATE_CHANGED),
      OfflineDownloadCoordinator.PERMISSION_STATE_CHANGED,
      null,
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
    offlineDownloadReceiverRegistered = true
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
    offlineUiExecutor.shutdownNow()
    currentBookApi?.shutdown()
    currentBookApi = null
    if (this::deleteAllDownloadsButton.isInitialized) {
      deleteAllDownloadsButton.removeCallbacks(resetDeleteConfirmation)
    }
    if (this::signInButton.isInitialized) {
      signInButton.removeCallbacks(resetSignInDisclosure)
    }
    if (offlineDownloadReceiverRegistered) {
      runCatching { unregisterReceiver(offlineDownloadReceiver) }
      offlineDownloadReceiverRegistered = false
    }
    if (isFinishing && !isChangingConfigurations) {
      cancelAuthenticatorResponse()
    }
    super.onDestroy()
  }

  private fun readAuthenticatorResponse(): AccountAuthenticatorResponse? {
    if (intent.action != ShelfDriveAccountContract.ACTION_AUTHENTICATOR_SIGN_IN) return null
    // The public APPLICATION_PREFERENCES alias resolves to this Activity too.
    // Only the authenticator's explicit private-component launch may carry the
    // privileged response binder; otherwise another app could spoof the action
    // and cause ShelfDrive to call into an attacker-controlled Parcelable.
    if (intent.component?.className != SettingsActivity::class.java.name) return null
    return try {
      if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(
          AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE,
          AccountAuthenticatorResponse::class.java
        )
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE)
      }
    } catch (error: BadParcelableException) {
      Log.w(tag, "Ignoring malformed authenticator launch")
      null
    } catch (error: ClassCastException) {
      Log.w(tag, "Ignoring invalid authenticator response type")
      null
    }
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

    offlineSummary = findViewById(R.id.offlineSummary)
    offlineMeteredSwitch = findViewById(R.id.offlineMeteredSwitch)
    offlineCurrentBookSummary = findViewById(R.id.offlineCurrentBookSummary)
    downloadCurrentBookButton = findViewById(R.id.downloadCurrentBookButton)
    cancelAllDownloadsButton = findViewById(R.id.cancelAllDownloadsButton)
    deleteAllDownloadsButton = findViewById(R.id.deleteAllDownloadsButton)
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

    populatingOfflineSettings = true
    offlineMeteredSwitch.isChecked =
      settings.downloadUsingCellular == DownloadUsingCellularSetting.ALWAYS
    populatingOfflineSettings = false
    refreshOfflineSummary()
  }

  private fun wireListeners() {
    signInButton.setOnClickListener { signIn() }
    disconnectButton.setOnClickListener { disconnect() }

    disableAutoRewindSwitch.setOnCheckedChangeListener { _, c -> mutateAndSave { it.disableAutoRewind = c } }
    offlineMeteredSwitch.setOnCheckedChangeListener { _, allowed ->
      if (!populatingOfflineSettings) {
        mutateAndSave {
          it.downloadUsingCellular = if (allowed) {
            DownloadUsingCellularSetting.ALWAYS
          } else {
            DownloadUsingCellularSetting.NEVER
          }
        }
      }
    }
    cancelAllDownloadsButton.setOnClickListener { cancelAllOfflineDownloads() }
    deleteAllDownloadsButton.setOnClickListener { confirmDeleteAllOfflineDownloads() }
    downloadCurrentBookButton.setOnClickListener { downloadCurrentAudiobook() }
  }

  private fun refreshOfflineSummary() {
    offlineRefreshAgain.set(true)
    if (!offlineRefreshQueued.compareAndSet(false, true)) return
    runCatching {
      offlineUiExecutor.execute {
        while (offlineRefreshAgain.getAndSet(false)) {
          val summary = runCatching { offlineDownloads.summary() }.getOrNull()
          val currentBook = runCatching { currentBookUiState() }.getOrNull()
          runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (summary == null) {
              offlineSummary.setText(R.string.settings_offline_summary_unavailable)
              cancelAllDownloadsButton.isEnabled = false
              deleteAllDownloadsButton.isEnabled = false
            } else {
              renderOfflineSummary(summary)
            }
            renderCurrentBookUi(currentBook)
          }
        }
        offlineRefreshQueued.set(false)
        if (offlineRefreshAgain.get()) refreshOfflineSummary()
      }
    }.onFailure {
      offlineRefreshQueued.set(false)
    }
  }

  private fun showDeleteConfirmation(summary: OfflineStorageSummary? = null) {
    deleteConfirmationArmed = true
    deleteAllDownloadsButton.setText(R.string.settings_offline_delete_confirm)
    deleteAllDownloadsButton.removeCallbacks(resetDeleteConfirmation)
    deleteAllDownloadsButton.postDelayed(resetDeleteConfirmation, 10_000L)
    offlineSummary.setText(R.string.settings_offline_delete_message)
    summary?.let {
      cancelAllDownloadsButton.isEnabled = it.pendingBooks > 0
      deleteAllDownloadsButton.isEnabled = it.downloadedBooks > 0
    }
  }

  private fun resetDeleteConfirmationNow() {
    deleteConfirmationArmed = false
    deleteAllDownloadsButton.removeCallbacks(resetDeleteConfirmation)
    deleteAllDownloadsButton.setText(R.string.settings_offline_delete_all)
  }

  private fun renderOfflineSummary(summary: OfflineStorageSummary) {
    if (deleteConfirmationArmed) {
      showDeleteConfirmation(summary)
      return
    }
    offlineSummary.text = getString(
      R.string.settings_offline_summary,
      resources.getQuantityString(
        R.plurals.settings_offline_downloaded_count,
        summary.downloadedBooks,
        summary.downloadedBooks
      ),
      Formatter.formatFileSize(this, summary.bytes),
      resources.getQuantityString(
        R.plurals.settings_offline_active_count,
        summary.pendingBooks,
        summary.pendingBooks
      )
    )
    cancelAllDownloadsButton.isEnabled = summary.pendingBooks > 0
    deleteAllDownloadsButton.isEnabled = summary.downloadedBooks > 0
  }

  private data class CurrentBookUiState(
    val libraryItemId: String,
    val title: String,
    val state: OfflineDownloadState
  )

  /**
   * Custom browse actions are optional on older/OEM AAOS hosts. The durable
   * last-played remote audiobook supplies a parked Settings fallback without
   * turning Settings into a second media browser.
   */
  private fun currentBookUiState(): CurrentBookUiState? {
    val config = DeviceManager.serverConnectionConfig ?: return null
    val session = DeviceManager.deviceData.lastPlaybackSession ?: return null
    val itemId = session.libraryItemId?.takeIf(String::isNotBlank) ?: return null
    if (session.mediaType != "book" || session.serverConnectionConfigId != config.id) return null
    return CurrentBookUiState(
      libraryItemId = itemId,
      title = session.displayTitle?.takeIf(String::isNotBlank)
        ?: getString(R.string.settings_offline_current_book_untitled),
      state = offlineDownloads.state(config.id, itemId)
    )
  }

  private fun renderCurrentBookUi(current: CurrentBookUiState?) {
    if (currentBookDownloadInFlight) return
    if (current == null) {
      offlineCurrentBookSummary.setText(R.string.settings_offline_current_book_none)
      downloadCurrentBookButton.setText(R.string.settings_offline_download_current)
      downloadCurrentBookButton.isEnabled = false
      return
    }
    offlineCurrentBookSummary.text = getString(
      R.string.settings_offline_current_book,
      current.title
    )
    when (current.state) {
      OfflineDownloadState.ACTIVE -> {
        downloadCurrentBookButton.setText(R.string.settings_offline_current_active)
        downloadCurrentBookButton.isEnabled = false
      }
      OfflineDownloadState.DOWNLOADED -> {
        downloadCurrentBookButton.setText(R.string.settings_offline_current_downloaded)
        downloadCurrentBookButton.isEnabled = false
      }
      OfflineDownloadState.NONE,
      OfflineDownloadState.FAILED -> {
        downloadCurrentBookButton.setText(R.string.settings_offline_download_current)
        downloadCurrentBookButton.isEnabled = true
      }
    }
  }

  private fun downloadCurrentAudiobook() {
    if (currentBookDownloadInFlight) return
    val config = DeviceManager.serverConnectionConfig
    val lease = DeviceManager.captureConnectionLease(config)
    val candidate = currentBookUiState()
    if (config == null || lease == null || candidate == null) {
      Toast.makeText(
        applicationContext,
        R.string.settings_offline_current_book_none,
        Toast.LENGTH_SHORT
      ).show()
      refreshOfflineSummary()
      return
    }

    currentBookDownloadInFlight = true
    downloadCurrentBookButton.isEnabled = false
    downloadCurrentBookButton.setText(R.string.settings_offline_current_starting)
    offlineCurrentBookSummary.text = getString(
      R.string.settings_offline_current_book,
      candidate.title
    )

    val api = ApiHandler(applicationContext)
    currentBookApi?.shutdown()
    currentBookApi = api
    api.getLibraryItemWithProgress(candidate.libraryItemId, null, config) { expanded ->
      if (currentBookApi === api) currentBookApi = null
      api.shutdown()
      val verified = expanded?.takeIf {
        it.id == candidate.libraryItemId && it.mediaType == "book" && it.checkHasTracks() &&
          DeviceManager.isConnectionLeaseCurrent(lease)
      }
      if (verified == null) {
        finishCurrentBookDownload(OfflineDownloadResult.FAILED)
      } else {
        offlineDownloads.enqueue(verified, config, lease) { result, _ ->
          finishCurrentBookDownload(result)
        }
      }
    }
  }

  private fun finishCurrentBookDownload(result: OfflineDownloadResult) {
    runOnUiThread {
      currentBookDownloadInFlight = false
      if (isFinishing || isDestroyed) return@runOnUiThread
      val message = when (result) {
        OfflineDownloadResult.STARTED -> R.string.offline_result_started
        OfflineDownloadResult.ALREADY_ACTIVE -> R.string.offline_result_already_active
        OfflineDownloadResult.ALREADY_DOWNLOADED -> R.string.offline_result_already_downloaded
        else -> R.string.settings_offline_action_failed
      }
      Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
      refreshOfflineSummary()
    }
  }

  private fun cancelAllOfflineDownloads() {
    cancelAllDownloadsButton.isEnabled = false
    offlineDownloads.cancelAll { result, _ ->
      val message = when (result) {
        OfflineDownloadResult.CANCELED -> R.string.settings_offline_cancel_success
        OfflineDownloadResult.NOTHING_TO_DO -> R.string.settings_offline_no_active_downloads
        else -> R.string.settings_offline_action_failed
      }
      Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
      if (!isFinishing && !isDestroyed) refreshOfflineSummary()
    }
  }

  private fun confirmDeleteAllOfflineDownloads() {
    if (!deleteConfirmationArmed) {
      showDeleteConfirmation()
      return
    }
    resetDeleteConfirmationNow()
    deleteAllOfflineDownloads()
  }

  private fun deleteAllOfflineDownloads() {
    deleteAllDownloadsButton.isEnabled = false
    PlayerNotificationService.stopAnyManagedOfflinePlayback {
      offlineDownloads.removeAll { result, _ ->
        val message = when (result) {
          OfflineDownloadResult.REMOVED -> R.string.settings_offline_delete_success
          OfflineDownloadResult.NOTHING_TO_DO -> R.string.settings_offline_no_downloads
          else -> R.string.settings_offline_action_failed
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        if (!isFinishing && !isDestroyed) refreshOfflineSummary()
      }
    }
  }

  private fun setupJumpSpinner(spinner: Spinner, current: Int, onChange: (Int) -> Unit) {
    val options = listOf(5, 10, 15, 20, 30, 60, 90)
    val labels = options.map { "${it}s" }
    val adapter = ArrayAdapter(this, R.layout.settings_spinner_item, labels).apply {
      setDropDownViewResource(R.layout.settings_spinner_item)
    }
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

    // AAOS dialog defaults are sized for phones and can violate the car UI's
    // 24sp text / 76dp target minimums. Keep consent inline in the parked-only,
    // scrollable Settings surface: the first tap discloses, the second confirms.
    if (pendingDisclosureAddress != address) {
      pendingDisclosureAddress = address
      signInButton.removeCallbacks(resetSignInDisclosure)
      signInButton.setText(R.string.data_disclosure_continue)
      serverStatus.setTextColor(getColor(R.color.settings_status_info))
      serverStatus.text = buildString {
        append(getString(R.string.data_disclosure_title))
        append("\n\n")
        append(getString(R.string.data_disclosure_message, address))
      }
      signInButton.postDelayed(resetSignInDisclosure, 30_000L)
      return
    }

    pendingDisclosureAddress = null
    signInButton.removeCallbacks(resetSignInDisclosure)
    beginSignIn(address, user, pass)
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
    if (signingIn) {
      pendingDisclosureAddress = null
      signInButton.removeCallbacks(resetSignInDisclosure)
    }
    signInButton.isEnabled = !signingIn
    signInButton.setText(if (signingIn) R.string.settings_signing_in else R.string.car_sign_in_action)
    serverUrlInput.isEnabled = !signingIn
    usernameInput.isEnabled = !signingIn
    passwordInput.isEnabled = !signingIn
    disconnectButton.isEnabled = !signingIn && displayedConnectionId != null
  }

  private fun showSignInFailure(reason: String) {
    pendingDisclosureAddress = null
    if (this::signInButton.isInitialized) {
      signInButton.removeCallbacks(resetSignInDisclosure)
      if (signInButton.isEnabled) signInButton.setText(R.string.car_sign_in_action)
    }
    serverStatus.text = getString(R.string.settings_sign_in_failed_reason, reason)
    serverStatus.setTextColor(getColor(R.color.settings_status_error))
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
    try {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
    } catch (_: ActivityNotFoundException) {
      serverStatus.setText(R.string.privacy_policy_no_browser)
      serverStatus.setTextColor(getColor(R.color.settings_status_info))
    }
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
