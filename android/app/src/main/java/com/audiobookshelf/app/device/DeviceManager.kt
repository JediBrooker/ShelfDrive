package com.audiobookshelf.app.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.media.CoverCache
import com.audiobookshelf.app.player.PlayerNotificationService
import java.util.concurrent.atomic.AtomicLong
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Interface for widget event handling. */
interface WidgetEventEmitter {
  /**
   * Called when the player state changes.
   * @param pns The PlayerNotificationService instance.
   */
  fun onPlayerChanged(pns: PlayerNotificationService)

  /** Called when the player is closed. */
  fun onPlayerClosed()
}

/**
 * Identifies one in-process lifetime of a saved server profile. Connection IDs
 * are deterministic, so the epoch is required to distinguish remove/re-add of
 * the same address and username from the profile that an old callback used.
 */
data class ConnectionLease(
  val connectionId: String,
  /** Monotonic lifetime token for this exact saved config instance. */
  val epoch: Long
)

/** Singleton object for managing device-related operations. */
object DeviceManager {
  const val tag = "DeviceManager"

  val dbManager: DbManager = DbManager()
  var deviceData: DeviceData = dbManager.getDeviceData()
  /**
   * Serializes selection, credential rotation, and account removal. Network
   * callbacks can retain a ServerConnectionConfig reference after Android's
   * AccountManager removes that profile, so accepting arbitrary late writes
   * here would resurrect a disconnected account.
   */
  internal val connectionStateMonitor = Any()
  /**
   * Serializes durable account/profile writes without holding
   * [connectionStateMonitor] across Paper, Keystore, or SharedPreferences I/O.
   * Writers acquire this monitor first and only briefly enter the state monitor
   * to validate or commit memory state.
   */
  internal val connectionPersistenceMonitor = Any()
  /** Global structural generation used to invalidate browse/search results. */
  private val connectionStateEpoch = AtomicLong(0L)
  private val nextConnectionLifetime = AtomicLong(0L)
  private val nextPlaybackCheckpointRevision = AtomicLong(0L)
  private var committedPlaybackCheckpointRevision = 0L
  private data class SavedConnectionLifetime(
    val config: ServerConnectionConfig,
    val generation: Long
  )
  private val savedConnectionLifetimes = mutableMapOf<String, SavedConnectionLifetime>()
  @Volatile private var activeServerConnectionConfig: ServerConnectionConfig? = null

  /** Must be called while [connectionStateMonitor] is held. */
  private fun lifetimeForLocked(config: ServerConnectionConfig): Long {
    val existing = savedConnectionLifetimes[config.id]
    if (existing?.config === config) return existing.generation
    return nextConnectionLifetime.incrementAndGet().also { generation ->
      savedConnectionLifetimes[config.id] = SavedConnectionLifetime(config, generation)
    }
  }

  /** Must be called while [connectionStateMonitor] is held. */
  private fun isLeaseCurrentLocked(
    lease: ConnectionLease,
    expectedConfig: ServerConnectionConfig? = null
  ): Boolean {
    val savedConfig = deviceData.serverConnectionConfigs.find {
      it.id == lease.connectionId && it.token.isNotBlank()
    } ?: return false
    if (expectedConfig != null && savedConfig !== expectedConfig) return false
    val lifetime = savedConnectionLifetimes[lease.connectionId] ?: return false
    return lifetime.config === savedConfig && lifetime.generation == lease.epoch
  }

  var serverConnectionConfig: ServerConnectionConfig?
    get() = activeServerConnectionConfig
    set(value) {
      trySelectServerConnectionConfig(value)
    }

  /**
   * Select only the current saved instance. Returns false when a callback is
   * holding a removed or permanently unauthenticated profile.
   */
  fun trySelectServerConnectionConfig(
    config: ServerConnectionConfig?,
    lease: ConnectionLease? = null
  ): Boolean =
    synchronized(connectionStateMonitor) {
      if (config == null) {
        activeServerConnectionConfig = null
        return@synchronized true
      }

      val savedConfig = deviceData.serverConnectionConfigs.find {
        it === config && it.token.isNotBlank()
      }
      if (lease != null && (
          lease.connectionId != config.id ||
            !isLeaseCurrentLocked(lease, config)
        )) {
        return@synchronized false
      }
      if (savedConfig == null) {
        if (activeServerConnectionConfig?.id == config.id) {
          activeServerConnectionConfig = null
        }
        false
      } else {
        activeServerConnectionConfig = savedConfig
        true
      }
    }

  fun isServerConnectionConfigSaved(connectionId: String): Boolean =
    synchronized(connectionStateMonitor) {
      connectionId.isNotBlank() && deviceData.serverConnectionConfigs.any {
        it.id == connectionId && it.token.isNotBlank()
      }
    }

  /** Captures a lease only for the exact currently saved config instance. */
  fun captureConnectionLease(config: ServerConnectionConfig?): ConnectionLease? =
    synchronized(connectionStateMonitor) {
      val candidate = config ?: activeServerConnectionConfig ?: return@synchronized null
      val savedConfig = deviceData.serverConnectionConfigs.find {
        it === candidate && it.token.isNotBlank()
      } ?: return@synchronized null
      ConnectionLease(savedConfig.id, lifetimeForLocked(savedConfig))
    }

  fun isConnectionLeaseCurrent(lease: ConnectionLease): Boolean =
    synchronized(connectionStateMonitor) {
      isLeaseCurrentLocked(lease)
    }

  fun getServerConnectionConfig(lease: ConnectionLease): ServerConnectionConfig? =
    synchronized(connectionStateMonitor) {
      if (!isLeaseCurrentLocked(lease)) return@synchronized null
      savedConnectionLifetimes[lease.connectionId]?.config
    }

  /**
   * Returns a stable structural snapshot for callers that need to enumerate
   * saved profiles outside the connection-state critical section. The config
   * objects remain the canonical saved instances so identity-based leases keep
   * working; callers must not mutate them.
   */
  fun snapshotServerConnectionConfigs(): List<ServerConnectionConfig> =
    synchronized(connectionStateMonitor) {
      deviceData.serverConnectionConfigs.toList()
    }

  /** Resolves the last selected profile without exposing the mutable backing list. */
  fun getLastServerConnectionConfig(): ServerConnectionConfig? =
    synchronized(connectionStateMonitor) {
      val lastId = deviceData.lastServerConnectionConfigId ?: return@synchronized null
      deviceData.serverConnectionConfigs.find {
        it.id == lastId && isServerAddressAllowed(it.address)
      }
    }

  fun currentConnectionStateEpoch(): Long = connectionStateEpoch.get()

  internal fun advanceConnectionStateEpoch(): Long = connectionStateEpoch.incrementAndGet()

  fun isConnectionStateCurrent(expectedEpoch: Long, connectionId: String?): Boolean =
    synchronized(connectionStateMonitor) {
      if (expectedEpoch != connectionStateEpoch.get()) return@synchronized false
      if (connectionId == null) return@synchronized true
      deviceData.serverConnectionConfigs.any {
        it.id == connectionId && it.token.isNotBlank()
      }
    }

  val serverConnectionConfigId get() = serverConnectionConfig?.id ?: ""
  val serverConnectionConfigName get() = serverConnectionConfig?.name ?: ""
  val serverConnectionConfigString get() = serverConnectionConfig?.name ?: "No server connection"
  val serverAddress
    get() = serverConnectionConfig?.address ?: ""
  val serverUserId
    get() = serverConnectionConfig?.userId ?: ""
  val token
    get() = serverConnectionConfig?.token ?: ""
  val serverVersion get() = serverConnectionConfig?.version ?: ""
  val isConnectedToServer
    get() = serverConnectionConfig != null

  var widgetUpdater: WidgetEventEmitter? = null

  // Lazily initialized by PlayerNotificationService.onCreate so getCoverUri()
  // callers can opt into the on-disk cover cache (served via FileProvider) for
  // Car Media browse rendering.
  var coverCache: CoverCache? = null

  init {
    Log.d(tag, "Device Manager Singleton invoked")

    // Older persisted records may predate DeviceSettings. Normalize the
    // model once so browse paths never force-unwrap a nullable value.
    deviceData.deviceSettings = deviceData.deviceSettings ?: DeviceSettings.default()

    // Initialize new sleep timer settings and shake sensitivity added in v0.9.61
    if (deviceData.deviceSettings?.autoSleepTimerStartTime == null ||
                    deviceData.deviceSettings?.autoSleepTimerEndTime == null
    ) {
      deviceData.deviceSettings?.autoSleepTimerStartTime = "22:00"
      deviceData.deviceSettings?.autoSleepTimerEndTime = "06:00"
      deviceData.deviceSettings?.sleepTimerLength = 900000L
    }
    if (deviceData.deviceSettings?.shakeSensitivity == null) {
      deviceData.deviceSettings?.shakeSensitivity = ShakeSensitivitySetting.MEDIUM
    }
    // Initialize auto sleep timer auto rewind added in v0.9.64
    if (deviceData.deviceSettings?.autoSleepTimerAutoRewindTime == null) {
      deviceData.deviceSettings?.autoSleepTimerAutoRewindTime = 300000L // 5 minutes
    }
    // Initialize sleep timer almost done chime added in v0.9.81
    if (deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime == null) {
      deviceData.deviceSettings?.enableSleepTimerAlmostDoneChime = false
    }

    // Language added in v0.9.69
    if (deviceData.deviceSettings?.languageCode == null) {
      deviceData.deviceSettings?.languageCode = "en-us"
    }

    if (deviceData.deviceSettings?.downloadUsingCellular == null) {
      deviceData.deviceSettings?.downloadUsingCellular = DownloadUsingCellularSetting.ALWAYS
    }

    if (deviceData.deviceSettings?.streamingUsingCellular == null) {
      deviceData.deviceSettings?.streamingUsingCellular = StreamingUsingCellularSetting.ALWAYS
    }
    if (deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping == null) {
      deviceData.deviceSettings?.androidAutoBrowseLimitForGrouping = 100
    }
    if (deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder == null) {
      deviceData.deviceSettings?.androidAutoBrowseSeriesSequenceOrder =
              AndroidAutoBrowseSeriesSequenceOrderSetting.ASC
    }
    deviceData.deviceSettings?.let { settings ->
      if (settings.jumpBackwardsTime !in 1..3_600) settings.jumpBackwardsTime = 10
      if (settings.jumpForwardTime !in 1..3_600) settings.jumpForwardTime = 10
      settings.androidAutoBrowseLimitForGrouping =
        settings.androidAutoBrowseLimitForGrouping.coerceIn(20, 1_000)
      if (settings.sleepTimerLength <= 0L) settings.sleepTimerLength = 900_000L
      if (settings.autoSleepTimerAutoRewindTime < 0L) {
        settings.autoSleepTimerAutoRewindTime = 300_000L
      }
    }
  }

  /**
   * Encodes the given ID to a Base64 string.
   * @param id The ID to encode.
   * @return The Base64 encoded string.
   */
  fun getBase64Id(id: String): String {
    return android.util.Base64.encodeToString(
            id.toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
    )
  }

  /**
   * Retrieves the server connection configuration for the given ID.
   * @param id The ID of the server connection configuration.
   * @return The ServerConnectionConfig instance or null if not found.
   */
  fun getServerConnectionConfig(id: String?): ServerConnectionConfig? {
    return synchronized(connectionStateMonitor) {
      id ?: return@synchronized null
      deviceData.serverConnectionConfigs.find { config ->
        config.id == id && isServerAddressAllowed(config.address)
      }
    }
  }

  /** Release builds only permit authenticated server traffic over HTTPS. */
  fun isServerAddressAllowed(address: String): Boolean {
    val url = try {
      address.trim().toHttpUrlOrNull()
    } catch (_: RuntimeException) {
      null
    } ?: return false
    val schemeAllowed = url.scheme == "https" || (BuildConfig.DEBUG && url.scheme == "http")
    return schemeAllowed &&
      url.host.isNotBlank() &&
      url.username.isBlank() &&
      url.password.isBlank() &&
      url.query == null &&
      url.fragment == null
  }

  /**
   * Removes legacy cleartext profiles before any background browse request can
   * reuse their access token. Returns IDs so callers can also erase associated
   * encrypted refresh tokens.
   */
  fun removeInsecureServerConnections(
    persistDeviceData: (DeviceData) -> Unit = dbManager::saveDeviceData
  ): List<String> = synchronized(connectionPersistenceMonitor) persistence@{
    val candidate = synchronized(connectionStateMonitor) state@{
      val ids = deviceData.serverConnectionConfigs
        .filterNot { isServerAddressAllowed(it.address) }
        .map { it.id }
      if (ids.isEmpty()) return@state null
      val retained = deviceData.serverConnectionConfigs
        .filterNot { it.id in ids }
        .toMutableList()
      val fallback = retained.firstOrNull {
        it.token.isNotBlank() && isServerAddressAllowed(it.address)
      }
      val nextLastId = deviceData.lastServerConnectionConfigId
        .takeUnless { it in ids }
        ?: fallback?.id
      val nextPlayback = deviceData.lastPlaybackSession
        ?.takeUnless { it.serverConnectionConfigId in ids }
      InsecureConnectionCleanupCandidate(
        ids,
        DeviceData(retained, nextLastId, deviceData.deviceSettings, nextPlayback),
        fallback
      )
    } ?: return@persistence emptyList()

    try {
      persistDeviceData(candidate.deviceData)
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to commit insecure profile removal (${error.javaClass.simpleName})")
      return@persistence emptyList()
    }

    synchronized(connectionStateMonitor) {
      advanceConnectionStateEpoch()
      deviceData = candidate.deviceData
      val activeNow = activeServerConnectionConfig
      activeServerConnectionConfig = when {
        activeNow?.id in candidate.removedIds -> candidate.fallbackActive
        candidate.deviceData.serverConnectionConfigs.any { it === activeNow } -> activeNow
        else -> candidate.fallbackActive
      }
    }

    // Failed progress syncs are queued separately from DeviceData. Purge
    // sessions tied to rejected profiles so an old cleartext endpoint cannot
    // be retried after its credentials/config have been removed.
    try {
      dbManager.getPlaybackSessions()
        .filter { it.serverConnectionConfigId in candidate.removedIds }
        .forEach(dbManager::removePlaybackSession)
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to remove queued sessions for rejected server profiles (${error.javaClass.simpleName})")
    }
    candidate.removedIds
  }

  private data class InsecureConnectionCleanupCandidate(
    val removedIds: List<String>,
    val deviceData: DeviceData,
    val fallbackActive: ServerConnectionConfig?
  )

  /**
   * Check if the currently connected server version is >= compareVersion
   * Abs server only uses major.minor.patch
   * Note: Version is returned in Abs auth payloads starting v2.6.0
   * Note: Version is saved with the server connection config starting after v0.9.81
   *
   * @example
   * serverVersion=2.25.1
   * isServerVersionGreaterThanOrEqualTo("2.26.0") = false
   *
   * serverVersion=2.26.1
   * isServerVersionGreaterThanOrEqualTo("2.26.0") = true
   */
  fun isServerVersionGreaterThanOrEqualTo(compareVersion:String):Boolean {
    if (serverVersion == "") return false
    if (compareVersion == "") return true

    val serverVersionParts = serverVersion.split(".").map { it.toIntOrNull() ?: 0 }
    val compareVersionParts = compareVersion.split(".").map { it.toIntOrNull() ?: 0 }

    // Compare major, minor, and patch components
    for (i in 0 until maxOf(serverVersionParts.size, compareVersionParts.size)) {
      val serverVersionComponent = serverVersionParts.getOrElse(i) { 0 }
      val compareVersionComponent = compareVersionParts.getOrElse(i) { 0 }

      if (serverVersionComponent < compareVersionComponent) {
        return false // Server version is less than compareVersion
      } else if (serverVersionComponent > compareVersionComponent) {
        return true // Server version is greater than compareVersion
      }
    }

    return true // versions are equal in major, minor, and patch
  }

  /**
   * Checks the network connectivity status.
   * @param ctx The context to use for checking connectivity.
   * @return True if connected to the internet, false otherwise.
   */
  fun checkConnectivity(ctx: Context): Boolean {
    val connectivityManager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
              ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
    // AAOS connectivity can be supplied by transports other than Wi-Fi,
    // cellular, or Ethernet (for example an OEM-managed VPN). Capabilities are
    // the transport-agnostic signal that a route is usable for server calls.
    return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  /**
   * Sets the last playback session.
   * @param playbackSession The playback session to set.
   */
  internal fun reservePlaybackCheckpointRevision(): Long =
    nextPlaybackCheckpointRevision.incrementAndGet()

  fun setLastPlaybackSession(
    playbackSession: PlaybackSession,
    connectionLease: ConnectionLease? = null,
    checkpointRevision: Long = reservePlaybackCheckpointRevision()
  ): Boolean = synchronized(connectionPersistenceMonitor) {
    if (checkpointRevision <= committedPlaybackCheckpointRevision) {
      return@synchronized false
    }
    val durableSession = playbackSession.copySanitizedForPersistence()
    var priorSession: PlaybackSession? = null
    val committed = synchronized(connectionStateMonitor) {
      val ownerId = durableSession.serverConnectionConfigId
      if (!durableSession.isLocal || !ownerId.isNullOrBlank()) {
        val lease = connectionLease ?: return@synchronized false
        if (ownerId != lease.connectionId ||
          !isConnectionLeaseCurrent(lease)
        ) {
          return@synchronized false
        }
      }
      priorSession = deviceData.lastPlaybackSession
      deviceData.lastPlaybackSession = durableSession
      true
    }
    if (!committed) return@synchronized false
    try {
      dbManager.saveDeviceData(deviceData)
      committedPlaybackCheckpointRevision = checkpointRevision
      true
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to save playback checkpoint (${error.javaClass.simpleName})")
      synchronized(connectionStateMonitor) {
        if (deviceData.lastPlaybackSession === durableSession) {
          deviceData.lastPlaybackSession = priorSession
        }
      }
      false
    }
  }

}
