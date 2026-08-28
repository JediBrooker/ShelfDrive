package com.audiobookshelf.app.player

import android.app.*
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.*
import android.os.*
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.SettingsActivity
import com.audiobookshelf.app.accounts.ServerConnectionAccountRegistry
import com.audiobookshelf.app.accounts.ShelfDriveConnectionDataCleaner
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.data.DeviceInfo
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.downloads.OfflineDownloadCoordinator
import com.audiobookshelf.app.downloads.OfflineBrowseSnapshot
import com.audiobookshelf.app.downloads.OfflineDownloadResult
import com.audiobookshelf.app.downloads.OfflineDownloadState
import com.audiobookshelf.app.downloads.OfflinePlanFailure
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.managers.SleepTimerManager
import com.audiobookshelf.app.media.MediaManager
import com.audiobookshelf.app.media.MediaProgressSyncer
import com.audiobookshelf.app.media.getUriToAbsIconDrawable
import com.audiobookshelf.app.media.getUriToDrawable
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.server.ApiHandler
import com.audiobookshelf.app.util.AppInstanceId
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.audio.AudioAttributes
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector.CustomActionProvider
import com.google.android.exoplayer2.ext.mediasession.TimelineQueueNavigator
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory
import com.google.android.exoplayer2.extractor.mp3.Mp3Extractor
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import com.google.android.exoplayer2.upstream.*
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient

const val SLEEP_TIMER_WAKE_UP_EXPIRATION = 120000L // 2m
const val PLAYER_EXO = "exo-player"

// Extends MediaBrowserServiceCompat to act as the Car Media browse provider.
// The manifest registers the android.media.browse.MediaBrowserService
// intent-filter, so AAOS / Android Auto hosts bind here and drive the in-car
// UI through onGetRoot / onLoadChildren and the browseTree below. Caller
// packages are gated by package/UID and trusted-media-controller validation.
class PlayerNotificationService : MediaBrowserServiceCompat() {

  companion object {
    var isClosed = false
    var isUnmeteredNetwork = false
    var hasNetworkConnectivity = false // Not 100% reliable has internet

    @Volatile private var activeInstance: WeakReference<PlayerNotificationService>? = null

    /**
     * Finish the underlying root load before the detached browser request's
     * deadline. The small gap prevents two main-thread timeout callbacks from
     * racing to publish conflicting service state.
     */
    internal const val BROWSE_LOAD_TIMEOUT_MS = 7_500L
    internal const val BROWSE_RESULT_TIMEOUT_MS = 8_000L
    private const val MAX_SAVED_SESSION_RETRIES_PER_START = 50
    private const val MAX_CACHED_BROWSE_ITEMS = 2_000
    private const val OFFLINE_ACTION_TIMEOUT_MS = 8_000L
    private const val ACTION_DOWNLOAD_OFFLINE =
      "com.audiobookshelf.app.action.DOWNLOAD_OFFLINE"
    private const val ACTION_CANCEL_OFFLINE_DOWNLOAD =
      "com.audiobookshelf.app.action.CANCEL_OFFLINE_DOWNLOAD"
    private const val ACTION_REMOVE_OFFLINE_DOWNLOAD =
      "com.audiobookshelf.app.action.REMOVE_OFFLINE_DOWNLOAD"

    private val KNOWN_PLAYBACK_ARTWORK_SYSTEM_PACKAGES = listOf(
      "com.android.systemui",
      "com.android.car.media",
      "com.google.android.carassistant",
      "com.google.android.projection.gearhead",
      "com.volvocars.launcher"
    )

    fun requestBrowseRefresh(reason: String) {
      activeInstance?.get()?.let { service ->
        // Account and token callbacks run off-main. Invalidate generations
        // before posting UI work so an older response cannot republish a
        // removed profile during that queueing window.
        service.invalidateRemoteWorkForBrowseRefresh()
        Handler(Looper.getMainLooper()).post {
          if (service.isServiceAlive()) {
            service.refreshAndroidAutoBrowseTree(reason)
          }
        }
      }
    }

    /**
     * Stop a managed offline book before Settings removes its files. The
     * callback always runs on the main thread after ExoPlayer has released its
     * current media items; when the service is absent there is no live reader.
     */
    fun stopAnyManagedOfflinePlayback(afterStopped: () -> Unit) {
      Handler(Looper.getMainLooper()).post {
        val service = activeInstance?.get()
        if (service != null && !service.serviceDestroyed) {
          val local = service.currentPlaybackSession?.localLibraryItem
          if (local != null && service::offlineDownloads.isInitialized &&
            service.offlineDownloads.isManagedLocal(local)
          ) {
            service.closePlayback(false)
          }
        }
        afterStopped()
      }
    }
  }

  private val tag = "PlayerNotificationServ"
  private val mainHandler = Handler(Looper.getMainLooper())
  @Volatile private var serviceDestroyed = false
  @Volatile private var networkCallbackRegistered = false

  interface ClientEventEmitter {
    fun onPlaybackSession(playbackSession: PlaybackSession)
    fun onPlaybackClosed()
    fun onPlayingUpdate(isPlaying: Boolean)
    fun onMetadata(metadata: PlaybackMetadata)
    fun onSleepTimerEnded(currentPosition: Long)
    fun onSleepTimerSet(sleepTimeRemaining: Int, isAutoSleepTimer: Boolean)
    fun onLocalMediaProgressUpdate(localMediaProgress: LocalMediaProgress)
    fun onPlaybackFailed(errorMessage: String)
    fun onMediaPlayerChanged(mediaPlayer: String)
    fun onProgressSyncFailing()
    fun onProgressSyncSuccess()
    fun onNetworkMeteredChanged(isUnmetered: Boolean)
    fun onMediaItemHistoryUpdated(mediaItemHistory: MediaItemHistory)
    fun onPlaybackSpeedChanged(playbackSpeed: Float)
  }
  var clientEventEmitter: ClientEventEmitter? = null

  private lateinit var ctx: Context
  private lateinit var mediaSessionConnector: MediaSessionConnector
  private lateinit var playerNotificationManager: PlayerNotificationManager
  lateinit var mediaSession: MediaSessionCompat
  private lateinit var transportControls: MediaControllerCompat.TransportControls
  private var mediaSessionCallback: MediaSessionCallback? = null
  private var mediaDescriptionAdapter: AbMediaDescriptionAdapter? = null

  lateinit var mediaManager: MediaManager
  lateinit var apiHandler: ApiHandler
  private lateinit var offlineDownloads: OfflineDownloadCoordinator
  private var offlineDownloadReceiverRegistered = false

  private val offlineDownloadChangedReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (serviceDestroyed || intent?.action != OfflineDownloadCoordinator.ACTION_STATE_CHANGED) return
      val libraryItemId = intent.getStringExtra(OfflineDownloadCoordinator.EXTRA_LIBRARY_ITEM_ID)
      postToMainIfAlive {
        notifyChildrenChanged(DOWNLOADS_ROOT)
        notifyChildrenChanged(AUTO_MEDIA_ROOT)
        libraryItemId?.let { id ->
          synchronized(browseParentsByMediaId) {
            browseParentsByMediaId[id]?.toList().orEmpty()
          }.forEach(::notifyChildrenChanged)
        }
      }
    }
  }

  lateinit var mPlayer: ExoPlayer
  lateinit var currentPlayer: Player
  private lateinit var playerListener: PlayerListener

  lateinit var sleepTimerManager: SleepTimerManager
  lateinit var mediaProgressSyncer: MediaProgressSyncer

  private var notificationId = 10
  private var channelId = "audiobookshelf_channel"

  @Volatile var currentPlaybackSession: PlaybackSession? = null
  private var initialPlaybackRate: Float? = null

  /**
   * Invalidates recovery/renewal/auto-next callbacks when Stop, another
   * prepare, account recovery, or service teardown wins the race.
   */
  private val playbackOperationGeneration = AtomicLong(0L)
  private val playbackListenerGeneration = AtomicLong(0L)
  private val playbackEndLock = Any()
  private var endingPlaybackSession: PlaybackSession? = null
  private data class PlaybackOperationToken(
    val generation: Long,
    val sourceSession: PlaybackSession
  )

  private fun beginPlaybackOperation(session: PlaybackSession): PlaybackOperationToken =
    PlaybackOperationToken(playbackOperationGeneration.incrementAndGet(), session)

  private fun isPlaybackOperationSessionCurrent(token: PlaybackOperationToken): Boolean =
    !serviceDestroyed &&
      playbackOperationGeneration.get() == token.generation &&
      currentPlaybackSession === token.sourceSession

  private fun isPlaybackOperationCurrent(token: PlaybackOperationToken): Boolean =
    isPlaybackOperationSessionCurrent(token) &&
      (token.sourceSession.isLocal || token.sourceSession.connectionLease?.let {
        DeviceManager.isConnectionLeaseCurrent(it)
      } == true)

  private fun invalidatePlaybackOperations() {
    synchronized(playbackEndLock) {
      playbackOperationGeneration.incrementAndGet()
      endingPlaybackSession = null
    }
  }

  /** Claim one terminal transition for an exact session despite duplicate Exo callbacks. */
  private fun beginPlaybackEndOperation(session: PlaybackSession): PlaybackOperationToken? =
    synchronized(playbackEndLock) {
      if (endingPlaybackSession === session) return@synchronized null
      endingPlaybackSession = session
      beginPlaybackOperation(session)
    }

  private var isAndroidAuto = false

  // The following are used for the shake detection
  private var isShakeSensorRegistered: Boolean = false
  private var mSensorManager: SensorManager? = null
  private var mAccelerometer: Sensor? = null
  private var mShakeDetector: ShakeDetector? = null
  private val shakeSensorUnregisterRunnable = Runnable {
    if (serviceDestroyed) return@Runnable
    Log.d(tag, "wake time expired: Unregistering shake sensor")
    mSensorManager?.unregisterListener(mShakeDetector)
    isShakeSensorRegistered = false
  }

  // These are used to trigger reloading if
  private var forceReloadingAndroidAuto: Boolean = false
  private var lastRootLoadSucceeded: Boolean = false
  private var browseTreeLoading: Boolean = false
  private var browseTreeLoadGeneration: Int = 0
  private var browseTreeLoadingConfigId: String? = null
  private var browseTreeReloadQueued: Boolean = false
  private var queuedBrowseHasOfflineMedia: Boolean = false
  private val browseTreeLoadListeners = mutableListOf<(Boolean) -> Unit>()
  private val browseExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-AAOS-browse")
  }
  private val accountMaintenanceExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-account-maintenance")
  }
  private val playbackPersistenceExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-playback-persistence")
  }
  private val playbackHttpClient = OkHttpClient.Builder()
    .followRedirects(true)
    // Never turn an HTTPS media request into cleartext (or vice versa).
    .followSslRedirects(false)
    .build()
  @Volatile private var browseFuture: Future<*>? = null
  private var browseTreeTimeout: Runnable? = null

  // True while the Car Media "sign in" error state is being surfaced because no
  // server connection is configured. Used to throttle redundant state sets/logs.

  fun isBrowseTreeInitialized(): Boolean {
    return this::browseTree.isInitialized
  }

  // Cache latest search so it wont trigger again when returning from series for example
  private var cachedSearch: String = ""
  private var cachedSearchResults: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
  private lateinit var browseResultTree: BrowseResultTree
  private val pendingColdBrowseRestores = PendingBrowseRestoreTracker()
  private var browseEnrichmentGeneration = -1
  private var browseEnrichmentCallbacksRemaining = 0
  @Volatile private var searchGeneration: Int = 0
  private val searchExecutor = Executors.newSingleThreadExecutor { task ->
    Thread(task, "ShelfDrive-AAOS-search")
  }
  @Volatile private var searchFuture: Future<*>? = null
  private val browseCoverPrefetchGate = BrowseCoverPrefetchGate()

  internal fun isServiceAlive(): Boolean = !serviceDestroyed

  private fun getLocalDownloadForCurrentServer(libraryItemId: String): LocalLibraryItem? =
    DeviceManager.dbManager.getLocalLibraryItemByLId(
      libraryItemId,
      DeviceManager.serverConnectionConfigId
    )

  private fun invalidateRemoteWorkForBrowseRefresh() {
    if (serviceDestroyed || !this::mediaManager.isInitialized) return
    mediaManager.checkResetServerItems(forceReset = true)
    mediaSessionCallback?.invalidatePendingPreparation()
    if (this::mediaProgressSyncer.isInitialized) {
      mediaProgressSyncer.invalidateRemovedConnection()
    }
  }

  private fun postToMainIfAlive(action: () -> Unit) {
    if (serviceDestroyed) return
    mainHandler.post {
      if (!serviceDestroyed) action()
    }
  }

  /*
     Service related stuff
  */
  override fun onBind(intent: Intent): IBinder? {
    Log.d(tag, "onBind")

    // Android Auto Media Browser Service
    if (SERVICE_INTERFACE == intent.action) {
      Log.d(tag, "Is Media Browser Service")
      return super.onBind(intent)
    }
    // The dedicated AAOS artifact has no in-process UI client. Expose only the
    // MediaBrowser binder above and reject every arbitrary exported bind.
    return null
  }

  /** Legacy phone-shell type retained for source compatibility; never exported. */
  inner class LocalBinder : Binder() {
    fun getService(): PlayerNotificationService = this@PlayerNotificationService
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    Log.d(tag, "onStartCommand $startId")
    // MediaButtonReceiver and AAOS use the validated MediaBrowser binder. The
    // app has no legitimate started-service path; discard explicit external
    // starts so an arbitrary app cannot keep this exported service alive.
    stopSelfResult(startId)
    return START_NOT_STICKY
  }

  private fun refreshAndroidAutoBrowseTree(reason: String) {
    if (serviceDestroyed || !this::mediaManager.isInitialized) {
      Log.d(tag, "refreshAndroidAutoBrowseTree skipped before mediaManager initialization")
      return
    }

    AbsLogger.info(tag, "refreshAndroidAutoBrowseTree: $reason")
    cachedSearch = ""
    cachedSearchResults.clear()
    synchronized(cachedBrowseItems) { cachedBrowseItems.clear() }
    synchronized(browseParentsByMediaId) { browseParentsByMediaId.clear() }
    browseResultTree.clear()
    clearPendingColdBrowseRestores()
    searchGeneration++
    searchFuture?.cancel(true)
    searchFuture = null
    if (DeviceManager.serverConnectionConfig == null) {
      DeviceManager.serverConnectionConfig =
              DeviceManager.getLastServerConnectionConfig()
    }
    // Account removal and permanent token-refresh rejection both arrive here.
    // Validate the current session's owner before considering an unrelated
    // selected account; account B cannot authorize buffered audio from A.
    if (!publishSignInRequiredForPlaybackIfNeeded(currentPlaybackSession) &&
      DeviceManager.serverConnectionConfig != null
    ) {
      clearFatalPlaybackErrorState()
    }
    mediaManager.checkResetServerItems(forceReset = true)
    forceReloadingAndroidAuto = true
    lastRootLoadSucceeded = false
    notifyChildrenChanged(AUTO_MEDIA_ROOT)
  }

  @Deprecated("Deprecated in Java")
  override fun onStart(intent: Intent?, startId: Int) {
    Log.d(tag, "onStart $startId")
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun createNotificationChannel(channelId: String): String {
    val chan =
            NotificationChannel(
                    channelId,
                    getString(R.string.playback_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            )
    chan.description = getString(R.string.playback_notification_channel_description)
    chan.lightColor = Color.DKGRAY
    chan.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    val service = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    service.createNotificationChannel(chan)
    return channelId
  }

  // detach player
  override fun onDestroy() {
    // Invalidate asynchronous work before releasing any object it can touch.
    // Android/OEM callbacks may already be queued when teardown begins.
    serviceDestroyed = true
    if (offlineDownloadReceiverRegistered) {
      runCatching { unregisterReceiver(offlineDownloadChangedReceiver) }
      offlineDownloadReceiverRegistered = false
    }
    playbackListenerGeneration.incrementAndGet()
    if (this::mPlayer.isInitialized && this::playerListener.isInitialized) {
      runCatching { mPlayer.removeListener(playerListener) }
    }
    invalidatePlaybackOperations()
    browseTreeLoadGeneration++
    searchGeneration++
    browseTreeTimeout?.let(mainHandler::removeCallbacks)
    browseTreeTimeout = null
    mainHandler.removeCallbacks(shakeSensorUnregisterRunnable)
    mainHandler.removeCallbacksAndMessages(null)
    browseFuture?.cancel(true)
    browseFuture = null
    browseExecutor.shutdownNow()
    accountMaintenanceExecutor.shutdownNow()
    // Finish already accepted resume checkpoints after service teardown; the
    // exact connection lease prevents them from reviving a removed account.
    playbackPersistenceExecutor.shutdown()
    searchFuture?.cancel(true)
    searchFuture = null
    searchExecutor.shutdownNow()
    mediaSessionCallback?.release()
    mediaSessionCallback = null
    mediaDescriptionAdapter?.release()
    mediaDescriptionAdapter = null
    browseTreeLoadListeners.clear()
    browseTreeLoading = false
    val pendingBrowserTimeouts = synchronized(browserResultTimeouts) {
      browserResultTimeouts.values.toList().also { browserResultTimeouts.clear() }
    }
    pendingBrowserTimeouts.forEach(mainHandler::removeCallbacks)
    browserResultCallerPackages.clear()
    browserResultConnectionEpochs.clear()
    browserResultConnectionIds.clear()
    completedBrowseResults.clear()
    synchronized(cachedBrowseItems) { cachedBrowseItems.clear() }
    synchronized(browseParentsByMediaId) { browseParentsByMediaId.clear() }
    browserCustomActionLimits.clear()
    if (this::browseResultTree.isInitialized) browseResultTree.clearMemory()
    clearPendingColdBrowseRestores()
    clientEventEmitter = null

    // Stop manager-owned periodic work before the player/session is released.
    // These shutdown paths deliberately do not send a final network sync or
    // restore player volume during teardown.
    if (this::sleepTimerManager.isInitialized) {
      sleepTimerManager.shutdown()
    } else {
      unregisterSensorImmediately()
    }
    if (this::mediaProgressSyncer.isInitialized) {
      mediaProgressSyncer.shutdown()
    }
    if (this::apiHandler.isInitialized) {
      apiHandler.shutdown()
    }

    try {
      if (networkCallbackRegistered) {
        val connectivityManager =
                getSystemService(ConnectivityManager::class.java) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(networkCallback)
        networkCallbackRegistered = false
      }
    } catch (error: Exception) {
      Log.e(tag, "Error unregistering network callback (${error.javaClass.simpleName})")
    }

    Log.d(tag, "onDestroy")
    isClosed = true
    if (activeInstance?.get() === this) {
      activeInstance?.clear()
      activeInstance = null
    }
    if (this::currentPlayer.isInitialized) {
      DeviceManager.widgetUpdater?.onPlayerChanged(this)
    }

    // Android may destroy a service whose OEM-dependent initialization only
    // partially completed. Release only components that were created.
    if (this::playerNotificationManager.isInitialized) {
      playerNotificationManager.setPlayer(null)
    }
    if (this::mPlayer.isInitialized) {
      mPlayer.release()
    }
    if (this::mediaSession.isInitialized) {
      mediaSession.release()
    }
    super.onDestroy()
  }

  // Keep audiobook playback independent from the visible app task. Some car launchers
  // report task removal when returning home, so only stop an idle service here.
  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    Log.d(tag, "onTaskRemoved")

    if (shouldKeepPlaybackServiceAlive()) {
      Log.d(tag, "onTaskRemoved: keeping background playback service alive")
      return
    }

    stopSelf()
  }

  private fun shouldKeepPlaybackServiceAlive(): Boolean {
    if (currentPlaybackSession == null || !this::currentPlayer.isInitialized) {
      return false
    }

    // STATE_READY with playWhenReady=false (user paused) and mediaItemCount>0
    // both persist indefinitely after prepare, so they aren't a signal that
    // playback is active — gate on isPlaying / buffering / intent-to-play.
    return currentPlayer.isPlaying ||
            currentPlayer.playbackState == Player.STATE_BUFFERING ||
            (currentPlayer.playWhenReady && currentPlayer.playbackState == Player.STATE_READY)
  }

  override fun onCreate() {
    Log.d(tag, "onCreate")
    super.onCreate()
    serviceDestroyed = false
    isClosed = false
    ctx = this
    activeInstance = WeakReference(this)

    // Initialize Paper
    DbManager.initialize(ctx)
    offlineDownloads = OfflineDownloadCoordinator(applicationContext)
    ContextCompat.registerReceiver(
      this,
      offlineDownloadChangedReceiver,
      IntentFilter(OfflineDownloadCoordinator.ACTION_STATE_CHANGED),
      OfflineDownloadCoordinator.PERMISSION_STATE_CHANGED,
      null,
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
    offlineDownloadReceiverRegistered = true
    // System DownloadManager may have completed work while ShelfDrive's
    // process was absent. Reconcile before the first Downloads browse.
    offlineDownloads.reconcile()
    browseResultTree = BrowseResultTree(
      persistenceDirectory = File(noBackupFilesDir, "aaos-browse-ranges-v2"),
      scopeIdProvider = {
        currentBrowseServerConfigId()?.let { configId -> "server:$configId" } ?: "offline:none"
      }
    )
    // AccountManager, Keystore, SharedPreferences.commit, and Paper can all
    // perform disk/binder I/O. Keep Automotive service creation responsive;
    // network paths independently reject insecure profiles while maintenance
    // runs on this serialized background lane.
    accountMaintenanceExecutor.execute {
      try {
        ShelfDriveConnectionDataCleaner.retryPendingPurges(applicationContext)
        val removedInsecureConfigIds = synchronized(DeviceManager.connectionPersistenceMonitor) {
          val ids = DeviceManager.removeInsecureServerConnections()
          if (ids.isNotEmpty()) {
            val secureStorage = SecureStorage(applicationContext)
            ids.forEach(secureStorage::removeRefreshToken)
          }
          ids
        }
        if (removedInsecureConfigIds.isNotEmpty()) {
          Log.w(tag, "Removed ${removedInsecureConfigIds.size} legacy HTTP server profile(s)")
          requestBrowseRefresh("insecure profile removed")
        }
        val accountSync = ServerConnectionAccountRegistry(applicationContext)
          .reconcile(DeviceManager.snapshotServerConnectionConfigs())
        if (!accountSync.succeeded && !accountSync.restricted) {
          Log.w(
            tag,
            "Android account reconciliation failed for ${accountSync.failures.size} account(s)"
          )
        }
      } catch (error: Exception) {
        // Maintenance is best-effort and must never become an uncaught worker
        // exception that terminates the Automotive media process after startup.
        Log.e(tag, "Account maintenance failed (${error.javaClass.simpleName})")
      }
    }

    // FileProvider-backed cache used by getCoverUri so cross-process readers
    // (Car Media browse) get content:// URIs they can authenticate against.
    if (DeviceManager.coverCache == null) {
      DeviceManager.coverCache = com.audiobookshelf.app.media.CoverCache(ctx)
    }

    // Initialize API
    apiHandler = ApiHandler(ctx)
    retryQueuedPlaybackSessions()

    // Initialize sleep timer
    sleepTimerManager = SleepTimerManager(this)

    // Initialize Media Progress Syncer
    mediaProgressSyncer = MediaProgressSyncer(this, apiHandler)

    // Initialize shake sensor
    Log.d(tag, "onCreate Register sensor listener ${mAccelerometer?.isWakeUpSensor}")
    initSensor()

    // Initialize media manager
    mediaManager = MediaManager(apiHandler, ctx)

    // Register only after MediaManager exists: Android can invoke this callback
    // immediately for the already-active car network.
    val networkRequest =
            NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
    try {
      val connectivityManager =
              getSystemService(ConnectivityManager::class.java) as ConnectivityManager
      connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
      networkCallbackRegistered = true
    } catch (error: Exception) {
      // Media browsing remains usable for downloaded content and can retry
      // server access when the host requests the root again.
      Log.w(tag, "Unable to register network callback (${error.javaClass.simpleName})")
    }

    channelId =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              createNotificationChannel(channelId)
            } else ""

    // AAOS media apps must not expose their own playback UI. The system media
    // host owns browsing/playback, so the session affordance may only open the
    // parked settings/sign-in surface.
    val sessionActivityIntent =
            Intent(this, SettingsActivity::class.java).apply {
              action = Intent.ACTION_APPLICATION_PREFERENCES
              flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    val sessionActivityPendingIntent =
            PendingIntent.getActivity(
                    this,
                    0,
                    sessionActivityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

    mediaSession =
            MediaSessionCompat(this, tag).apply {
              setSessionActivity(sessionActivityPendingIntent)
              // Activate only after the player and final command callback are
              // installed at the end of onCreate.
              isActive = false
            }

    val mediaController = MediaControllerCompat(ctx, mediaSession.sessionToken)

    // This is for Media Browser
    sessionToken = mediaSession.sessionToken

    val builder = PlayerNotificationManager.Builder(ctx, notificationId, channelId)

    val descriptionAdapter = AbMediaDescriptionAdapter(mediaController, this)
    mediaDescriptionAdapter = descriptionAdapter
    builder.setMediaDescriptionAdapter(descriptionAdapter)
    builder.setNotificationListener(PlayerNotificationListener(this))

    playerNotificationManager = builder.build()
    playerNotificationManager.setMediaSessionToken(mediaSession.sessionToken)
    playerNotificationManager.setUsePlayPauseActions(true)
    playerNotificationManager.setUseNextAction(false)
    playerNotificationManager.setUsePreviousAction(false)
    playerNotificationManager.setUseFastForwardAction(true)
    playerNotificationManager.setUseRewindAction(true)
    playerNotificationManager.setUseChronometer(false)
    playerNotificationManager.setUseStopAction(false)
    playerNotificationManager.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    playerNotificationManager.setPriority(NotificationCompat.PRIORITY_MAX)
    playerNotificationManager.setUseFastForwardActionInCompactView(true)
    playerNotificationManager.setUseRewindActionInCompactView(true)
    playerNotificationManager.setSmallIcon(R.drawable.icon_monochrome)

    // Unknown action
    playerNotificationManager.setBadgeIconType(NotificationCompat.BADGE_ICON_LARGE)

    transportControls = mediaController.transportControls

    mediaSessionConnector = MediaSessionConnector(mediaSession)
    val queueNavigator: TimelineQueueNavigator =
            object : TimelineQueueNavigator(mediaSession) {
              override fun getSupportedQueueNavigatorActions(player: Player): Long {
                return PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or
                        PlaybackStateCompat.ACTION_REWIND or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
              }

              override fun getMediaDescription(
                      player: Player,
                      windowIndex: Int
              ): MediaDescriptionCompat {
                if (currentPlaybackSession == null) {
                  Log.e(tag, "Playback session is not set - returning blank MediaDescriptionCompat")
                  return MediaDescriptionCompat.Builder().build()
                }

                val coverUri = currentPlaybackSession!!.getCoverUri(ctx)


                // Queue metadata crosses the same process boundary as playing
                // metadata. Grant before returning the description so an OEM
                // image loader cannot race the FileProvider permission.
                grantPlaybackArtworkAccess(coverUri)

                val extra = Bundle()
                extra.putString(
                        MediaMetadataCompat.METADATA_KEY_ARTIST,
                        currentPlaybackSession!!.displayAuthor
                )

                val mediaDescriptionBuilder =
                        MediaDescriptionCompat.Builder()
                                .setExtras(extra)
                                .setTitle(currentPlaybackSession!!.displayTitle)

                mediaDescriptionBuilder.setIconUri(coverUri)

                return mediaDescriptionBuilder.build()
              }
            }

    setMediaSessionConnectorPlaybackActions()
    mediaSessionConnector.setQueueNavigator(queueNavigator)
    // Expose commands only after the player is fully initialized so an eager
    // car host cannot hit a lateinit player during service startup.
    initializeMPlayer()
    currentPlayer = mPlayer
    val sessionCallback = MediaSessionCallback(this)
    mediaSessionCallback = sessionCallback
    // The connector uses this adapter to advertise the standard voice/browse
    // actions in PlaybackState; every implementation still delegates to the
    // same lifecycle-safe callback installed below.
    mediaSessionConnector.setPlaybackPreparer(MediaSessionPlaybackPreparer(sessionCallback))
    mediaSession.setCallback(sessionCallback)
    mediaSession.isActive = true
  }

  private data class SavedSessionRetryBatch(
    val sessions: List<PlaybackSession>,
    val ownerConfig: ServerConnectionConfig,
    val ownerLease: ConnectionLease
  )

  /**
   * Failed terminal checkpoints survive process death. Retry a bounded,
   * owner-qualified batch whenever the release media service starts, removing
   * only rows the server explicitly accepted.
   */
  private fun retryQueuedPlaybackSessions() {
    try {
      accountMaintenanceExecutor.execute {
        val batches = try {
          synchronized(DeviceManager.connectionPersistenceMonitor) {
            val savedConnectionIds = DeviceManager.snapshotServerConnectionConfigs()
              .asSequence()
              .map { it.id }
              .filter { it.isNotBlank() }
              .toSet()
            DeviceManager.dbManager
              .getPlaybackSessionsForConnections(
                savedConnectionIds,
                MAX_SAVED_SESSION_RETRIES_PER_START
              )
              .groupBy { it.serverConnectionConfigId }
              .flatMap { (ownerId, sessions) ->
                if (ownerId.isNullOrBlank()) return@flatMap emptyList()
                val ownerConfig = DeviceManager.getServerConnectionConfig(ownerId)
                  ?: return@flatMap emptyList()
                val ownerLease = DeviceManager.captureConnectionLease(ownerConfig)
                  ?: return@flatMap emptyList()
                partitionSavedSessionRetriesByUniqueId(sessions).map { uniqueSessions ->
                  SavedSessionRetryBatch(uniqueSessions, ownerConfig, ownerLease)
                }
              }
          }
        } catch (error: RuntimeException) {
          Log.e(tag, "Unable to load queued playback retries (${error.javaClass.simpleName})")
          emptyList()
        }
        if (!serviceDestroyed && batches.isNotEmpty()) {
          retryNextSavedSessionBatch(java.util.ArrayDeque(batches))
        }
      }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      Log.i(tag, "Queued playback retry ignored after service teardown")
    }
  }

  private fun partitionSavedSessionRetriesByUniqueId(
    sessions: List<PlaybackSession>
  ): List<List<PlaybackSession>> {
    val remaining = sessions.toMutableList()
    val batches = mutableListOf<List<PlaybackSession>>()
    while (remaining.isNotEmpty()) {
      val seenIds = mutableSetOf<String>()
      val uniqueBatch = mutableListOf<PlaybackSession>()
      val iterator = remaining.iterator()
      while (iterator.hasNext()) {
        val session = iterator.next()
        if (seenIds.add(session.id)) {
          uniqueBatch += session
          iterator.remove()
        }
      }
      batches += uniqueBatch
    }
    return batches
  }

  private fun retryNextSavedSessionBatch(
    batches: java.util.ArrayDeque<SavedSessionRetryBatch>
  ) {
    if (serviceDestroyed) return
    val batch = batches.pollFirst() ?: return
    try {
      apiHandler.sendSyncLocalSessions(
        batch.sessions,
        batch.ownerConfig,
        batch.ownerLease
      ) { results, _ ->
        try {
          if (!serviceDestroyed && results != null &&
            DeviceManager.isConnectionLeaseCurrent(batch.ownerLease)
          ) {
            val successfulIds = results.asSequence()
              .filter { it.success }
              .map { it.id }
              .toSet()
            synchronized(DeviceManager.connectionPersistenceMonitor) {
              if (DeviceManager.isConnectionLeaseCurrent(batch.ownerLease)) {
                // Every transport batch has unique IDs, so one response can
                // match at most one exact owner-scoped checkpoint. The Paper
                // token prevents an old success deleting a newer in-flight row.
                batch.sessions.filter { it.id in successfulIds }.forEach {
                  DeviceManager.dbManager.removePlaybackSessionIfUnchanged(it)
                }
              }
            }
          }
        } catch (error: RuntimeException) {
          Log.e(tag, "Unable to apply queued playback retry (${error.javaClass.simpleName})")
        } finally {
          retryNextSavedSessionBatch(batches)
        }
      }
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to start queued playback retry (${error.javaClass.simpleName})")
      retryNextSavedSessionBatch(batches)
    }
  }

  private fun initializeMPlayer() {
    val customLoadControl: LoadControl =
            DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            1000 * 20, // 20s min buffer
                            1000 * 45, // 45s max buffer
                            1000 * 5, // 5s playback start
                            1000 * 20 // 20s playback rebuffer
                    )
                    .build()

    mPlayer =
            ExoPlayer.Builder(this)
                    .setLoadControl(customLoadControl)
                    .setSeekBackIncrementMs(deviceSettings.jumpBackwardsTimeMs)
                    .setSeekForwardIncrementMs(deviceSettings.jumpForwardTimeMs)
                    .build()
    mPlayer.setHandleAudioBecomingNoisy(true)
    playerListener = PlayerListener(this)
    mPlayer.addListener(playerListener)
    val audioAttributes: AudioAttributes =
            AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build()
    mPlayer.setAudioAttributes(audioAttributes, true)

    // attach player to playerNotificationManager
    playerNotificationManager.setPlayer(mPlayer)

    mediaSessionConnector.setPlayer(mPlayer)
  }

  /*
    User callable methods
  */
  fun preparePlayer(
          playbackSession: PlaybackSession,
          playWhenReady: Boolean,
          playbackRate: Float?
  ) {
    // A successfully accepted prepare supersedes every recovery/auto-next
    // operation started for the prior session.
    invalidatePlaybackOperations()
    // Defense in depth for every asynchronous producer of a playback session
    // (voice/browse selection, transcode fallback, next episode, and resume).
    // A remote stream is never allowed to outlive its configured account.
    if (publishSignInRequiredForPlaybackIfNeeded(playbackSession)) return
    val ownerConfig = if (playbackSession.isLocal) {
      DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)?.also {
        playbackSession.connectionLease = DeviceManager.captureConnectionLease(it)
      }
    } else {
      val existingLease = playbackSession.connectionLease
      val config = if (existingLease == null) {
        DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)
      } else {
        DeviceManager.getServerConnectionConfig(existingLease)
      }
      val lease = existingLease ?: DeviceManager.captureConnectionLease(config)
      if (config == null || lease == null ||
        config.id != playbackSession.serverConnectionConfigId ||
        !DeviceManager.trySelectServerConnectionConfig(config, lease)
      ) {
        setSignInRequiredPlaybackState()
        return
      }
      playbackSession.connectionLease = lease
      config
    }
    // Validate HLS credentials before publishing metadata or committing this
    // session. Returning from the media-source branch after commit would leave
    // a half-prepared session visible to the car host.
    val hlsOwnerToken = if (playbackSession.isHLS) {
      ownerConfig?.token?.takeIf { it.isNotBlank() }
    } else {
      null
    }
    if (playbackSession.isHLS && hlsOwnerToken == null) {
      setSignInRequiredPlaybackState()
      return
    }
    val hlsOwnerOrigin: HttpUrl? = if (playbackSession.isHLS) {
      ownerConfig?.address?.toHttpUrlOrNull()
    } else {
      null
    }
    if (playbackSession.isHLS && hlsOwnerOrigin == null) {
      closePlaybackWithError(getString(R.string.car_media_unavailable), playbackSession)
      return
    }
    // TODO: When an item isFinished the currentTime should be reset to 0
    //        will reset the time if currentTime is within 5s of duration (for android auto)
    Log.d(
            tag,
            "Prepare Player Session Current Time=${playbackSession.currentTime}, Duration=${playbackSession.duration}"
    )
    val mediaItems = try {
      playbackSession.getMediaItems(ctx, ownerConfig)
    } catch (error: RuntimeException) {
      Log.e(tag, "Invalid playback session media (${error.javaClass.simpleName})")
      emptyList()
    }
    // Reject malformed/empty sessions before publishing metadata, persisting
    // them, updating the widget, or indexing audioTracks. A bad server payload
    // should become an actionable car-host error, never a process crash.
    if (mediaItems.isEmpty() || mediaItems.size != playbackSession.audioTracks.size) {
      Log.e(tag, "Invalid playback session: missing or malformed audio tracks")
      closePlaybackWithError(getString(R.string.car_media_unavailable), playbackSession)
      return
    }
    var committedNewSession = false
    try {
    clearFatalPlaybackErrorState()
    if (playbackSession.duration - playbackSession.currentTime < 5) {
      Log.d(tag, "Prepare Player Session is finished, so restart it")
      playbackSession.currentTime = 0.0
    }

    isClosed = false

    val metadata = playbackSession.getMediaMetadataCompat(ctx)
    grantPlaybackArtworkAccess(metadata)
    mediaSession.setMetadata(metadata)
    ensurePlaybackCoverCachedThenRefreshMetadata(playbackSession)
    val playbackRateToUse = (playbackRate ?: initialPlaybackRate ?: 1f)
      .takeIf { it.isFinite() && it > 0f && it <= 5f } ?: 1f
    initialPlaybackRate = playbackRateToUse

    // Set actions on Android Auto like jump forward/backward
    setMediaSessionConnectorCustomActions(playbackSession)

    playbackSession.mediaPlayer = getMediaPlayer()

    val playbackLease = playbackSession.connectionLease
    val committed = synchronized(DeviceManager.connectionStateMonitor) {
      if (!playbackSession.isLocal && (
          playbackLease == null || !DeviceManager.isConnectionLeaseCurrent(playbackLease)
        )) {
        false
      } else {
        currentPlaybackSession = playbackSession
        true
      }
    }
    if (!committed) {
      setSignInRequiredPlaybackState()
      return
    }
    committedNewSession = true
    if (mediaProgressSyncer.isTrackingDifferentSession(playbackSession)) {
      // Do not let a paused/listening snapshot from item A sample player B's
      // position while Exo transitions to the newly committed session.
      mediaProgressSyncer.reset()
    }
    // Listener callbacks already queued for session A must never pause, sync,
    // recover, or close newly committed session B. Bind a fresh listener to
    // this exact session before replacing Exo's media source.
    mPlayer.removeListener(playerListener)
    val listenerToken = PlaybackListenerToken(
      playbackListenerGeneration.incrementAndGet(),
      playbackSession
    )
    playerListener = PlayerListener(this, listenerToken)
    mPlayer.addListener(playerListener)

    AbsLogger.info("PlayerNotificationService", "preparePlayer: Started playback session")
    // Notify client
    clientEventEmitter?.onPlaybackSession(playbackSession)

    // Update widget
    DeviceManager.widgetUpdater?.onPlayerChanged(this)

    if (mPlayer == currentPlayer) {
      val mediaSource: MediaSource

      if (playbackSession.isLocal) {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing local media")
        val dataSourceFactory = DefaultDataSource.Factory(ctx)

        val extractorsFactory = DefaultExtractorsFactory()
        extractorsFactory.setConstantBitrateSeekingEnabled(true)

        if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
          // @see
          // https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files
          extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItems[0])
      } else if (!playbackSession.isHLS) {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing direct stream")
        val dataSourceFactory = DefaultHttpDataSource.Factory()

        val extractorsFactory = DefaultExtractorsFactory()
        extractorsFactory.setConstantBitrateSeekingEnabled(true)

        if (DeviceManager.deviceData.deviceSettings?.enableMp3IndexSeeking == true) {
          // @see
          // https://exoplayer.dev/troubleshooting.html#why-is-seeking-inaccurate-in-some-mp3-files
          extractorsFactory.setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        }

        dataSourceFactory.setUserAgent(channelId)
        mediaSource =
                ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                        .createMediaSource(mediaItems[0])
      } else {
        AbsLogger.info("PlayerNotificationService", "preparePlayer: Playing HLS stream")
        val sessionHttpClient = playbackHttpClient.newBuilder()
          .addInterceptor(
            OriginBoundBearerInterceptor(
              checkNotNull(hlsOwnerOrigin),
              checkNotNull(hlsOwnerToken)
            )
          )
          .build()
        val dataSourceFactory = OkHttpDataSource.Factory(sessionHttpClient)
        dataSourceFactory.setUserAgent(channelId)
        mediaSource = HlsMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItems[0])
      }
      mPlayer.setMediaSource(mediaSource)

      // Add remaining media items if multi-track
      if (mediaItems.size > 1) {
        currentPlayer.addMediaItems(mediaItems.subList(1, mediaItems.size))
        Log.d(tag, "currentPlayer total media items ${currentPlayer.mediaItemCount}")

        val currentTrackIndex = playbackSession.getCurrentTrackIndex()
        val currentTrackTime = playbackSession.getCurrentTrackTimeMs()
        Log.d(
                tag,
                "currentPlayer current track index $currentTrackIndex & current track time $currentTrackTime"
        )
        currentPlayer.seekTo(currentTrackIndex, currentTrackTime)
      } else {
        currentPlayer.seekTo(playbackSession.currentTimeMs)
      }

      Log.d(
              tag,
              "Prepare complete for session ${currentPlaybackSession?.displayTitle} | ${currentPlayer.mediaItemCount}"
      )
      currentPlayer.playWhenReady = playWhenReady
      currentPlayer.setPlaybackSpeed(playbackRateToUse)

      val prepared = synchronized(DeviceManager.connectionStateMonitor) {
        if (!playbackSession.isLocal && (
            playbackLease == null || !DeviceManager.isConnectionLeaseCurrent(playbackLease)
          )) {
          false
        } else {
          currentPlayer.prepare()
          true
        }
      }
      if (!prepared) {
        currentPlaybackSession = null
        setSignInRequiredPlaybackState()
      } else {
        persistPlaybackCheckpointAsync(playbackSession, playbackLease)
      }
    }
    } catch (error: Exception) {
      Log.e(tag, "Unable to prepare playback (${error.javaClass.simpleName})")
      if (committedNewSession && currentPlaybackSession === playbackSession) {
        closePlaybackWithError(getString(R.string.car_media_unavailable))
      } else {
        setMediaUnavailablePlaybackState()
      }
    }
  }

  private fun persistPlaybackCheckpointAsync(
    playbackSession: PlaybackSession,
    connectionLease: ConnectionLease?
  ) {
    val checkpoint = playbackSession.clone()
    val checkpointRevision = DeviceManager.reservePlaybackCheckpointRevision()
    try {
      playbackPersistenceExecutor.execute {
        try {
          if (!DeviceManager.setLastPlaybackSession(
              checkpoint,
              connectionLease,
              checkpointRevision
            )) {
            Log.i(tag, "Playback checkpoint was not current enough to persist")
          }
        } catch (error: Exception) {
          // A checkpoint is resumability metadata, never a reason to terminate
          // active Automotive playback.
          Log.e(tag, "Playback checkpoint failed (${error.javaClass.simpleName})")
        }
      }
    } catch (_: java.util.concurrent.RejectedExecutionException) {
      Log.i(tag, "Playback checkpoint ignored after service teardown")
    }
  }

  /** Prepare an already-loaded item only while its account remains valid. */
  internal fun prepareCurrentPlayer() {
    if (publishSignInRequiredForPlaybackIfNeeded(currentPlaybackSession)) return
    val session = currentPlaybackSession ?: return
    val prepared = synchronized(DeviceManager.connectionStateMonitor) {
      if (!session.isLocal && session.connectionLease?.let {
          DeviceManager.isConnectionLeaseCurrent(it)
        } != true
      ) {
        false
      } else {
        currentPlayer.prepare()
        true
      }
    }
    if (!prepared) setSignInRequiredPlaybackState()
  }

  private fun setMediaSessionConnectorCustomActions(playbackSession: PlaybackSession) {
    val mediaItemCount = playbackSession.audioTracks.size
    val customActionProviders =
            mutableListOf(
                    JumpBackwardCustomActionProvider(),
                    JumpForwardCustomActionProvider(),
                    ChangePlaybackSpeedCustomActionProvider() // Will be pushed to far left
            )
    if (mediaItemCount > 1) {
      customActionProviders.addAll(
              listOf(
                      SkipBackwardCustomActionProvider(),
                      SkipForwardCustomActionProvider(),
              )
      )
    }
    mediaSessionConnector.setCustomActionProviders(*customActionProviders.toTypedArray())
  }

  fun setMediaSessionConnectorPlaybackActions() {
    mediaSessionConnector.setEnabledPlaybackActions(configuredPlaybackActions())
  }

  private fun configuredPlaybackActions(): Long {
    var playbackActions =
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PREPARE or
                    PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
                    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                    PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_STOP

    if (deviceSettings.allowSeekingOnMediaControls) {
      playbackActions = playbackActions or PlaybackStateCompat.ACTION_SEEK_TO
    }
    return playbackActions
  }

  internal fun isPlaybackListenerCurrent(token: PlaybackListenerToken?): Boolean =
    !serviceDestroyed && token != null &&
      playbackListenerGeneration.get() == token.generation &&
      currentPlaybackSession === token.sourceSession

  internal fun handlePlayerPlaybackError(
    errorMessage: String,
    sourceListener: PlaybackListenerToken? = null
  ) {
    // On error and was attempting to direct play - fallback to transcode
    currentPlaybackSession?.let { playbackSession ->
      if (sourceListener != null && !isPlaybackListenerCurrent(sourceListener)) {
        Log.d(tag, "Ignoring playback error from a replaced session")
        return
      }
      if (playbackSession.isDirectPlay) {
        val operation = beginPlaybackOperation(playbackSession)
        val ownerConfig = playbackSession.connectionLease?.let {
          DeviceManager.getServerConnectionConfig(it)
        }
        if (ownerConfig == null) {
          closePlaybackWithError(getString(R.string.car_sign_in_required))
          return
        }
        val playItemRequestPayload = getPlayItemRequestPayload(true)
        Log.d(tag, "Fallback to transcode $playItemRequestPayload.mediaPlayer")

        val libraryItemId = playbackSession.libraryItemId ?: "" // Must be true since direct play
        val episodeId = playbackSession.episodeId
        mediaProgressSyncer.stop(false) {
          if (!isPlaybackOperationCurrent(operation)) return@stop
          apiHandler.playLibraryItem(
            libraryItemId,
            episodeId,
            playItemRequestPayload,
            ownerConfig
          ) {
            if (!isPlaybackOperationCurrent(operation)) return@playLibraryItem
            if (it == null) { // Play request failed
              postToMainIfAlive {
                if (isPlaybackOperationCurrent(operation)) {
                  closePlaybackWithError(errorMessage)
                }
              }
            } else {
              postToMainIfAlive {
                if (isPlaybackOperationCurrent(operation)) {
                  preparePlayer(it, true, null)
                }
              }
            }
          }
        }
      } else {
        closePlaybackWithError(errorMessage)
      }
    } ?: closePlaybackWithError(errorMessage)
  }

  private fun closePlaybackWithError(
    errorMessage: String,
    failedSession: PlaybackSession? = currentPlaybackSession
  ) {
    if (serviceDestroyed) return
    clientEventEmitter?.onPlaybackFailed(errorMessage)
    closePlayback(true)
    setPlaybackFailureState(failedSession)
  }

  private fun setPlaybackFailureState(failedSession: PlaybackSession?) {
    if (publishSignInRequiredForPlaybackIfNeeded(failedSession)) return
    setFatalCarPlaybackError(
      messageRes = R.string.voice_search_playback_failed,
      actionLabelRes = R.string.car_open_settings_action,
      errorCode = PlaybackStateCompat.ERROR_CODE_APP_ERROR
    )
  }

  fun handlePlaybackEnded() {
    Log.d(tag, "handlePlaybackEnded")
    if (!isAndroidAuto) return
    val playbackSession = currentPlaybackSession ?: return
    val operation = beginPlaybackEndOperation(playbackSession) ?: run {
      Log.d(tag, "Ignoring duplicate playback completion")
      return
    }

    if (usesPodcastAutoNextForEndedPlayback(playbackSession)) {
      Log.d(tag, "Podcast playback ended on android auto")
      val libraryItem = playbackSession.libraryItem ?: return
      val ownerLease = playbackSession.connectionLease
      val ownerConfig = ownerLease?.let(DeviceManager::getServerConnectionConfig)
      val podcast = libraryItem.media as? Podcast
      if (ownerLease == null || ownerConfig == null || podcast == null) {
        if (podcast == null) {
          Log.e(tag, "Ignoring malformed podcast playback completion")
        }
        closePlaybackWithError(getString(R.string.car_media_unavailable))
        return
      }

      // Need to sync with server to set as finished
      mediaProgressSyncer.finished { finalSync ->
        if (!isPlaybackOperationCurrent(operation)) return@finished
        // Never reload stale server progress and select the just-ended episode
        // when its terminal checkpoint was deferred or rejected.
        if (finalSync?.serverSyncAttempted != true ||
          finalSync.serverSyncSuccess != true
        ) {
          postToMainIfAlive {
            if (isPlaybackOperationCurrent(operation)) {
              closePlayback(false)
              setMediaUnavailablePlaybackState()
            }
          }
          return@finished
        }
        // Need to reload media progress
        if (!DeviceManager.trySelectServerConnectionConfig(ownerConfig, ownerLease)) {
          postToMainIfAlive {
            if (isPlaybackOperationCurrent(operation)) {
              closePlayback(false)
              setSignInRequiredPlaybackState()
            }
          }
          return@finished
        }
        mediaManager.loadServerUserMediaProgress(ownerConfig, ownerLease) { loaded ->
          if (!isPlaybackOperationCurrent(operation)) {
            return@loadServerUserMediaProgress
          }
          if (!DeviceManager.isConnectionLeaseCurrent(ownerLease)) {
            postToMainIfAlive {
              if (isPlaybackOperationCurrent(operation)) {
                closePlayback(false)
                setSignInRequiredPlaybackState()
              }
            }
            return@loadServerUserMediaProgress
          }
          if (!loaded) {
            postToMainIfAlive {
              if (isPlaybackOperationCurrent(operation)) {
                closePlayback(false)
                setMediaUnavailablePlaybackState()
              }
            }
            return@loadServerUserMediaProgress
          }
          val nextEpisode = podcast.getNextUnfinishedEpisode(
            libraryItem.id,
            mediaManager,
            playbackSession.episodeId
          )
          Log.d(tag, "handlePlaybackEnded nextEpisode=$nextEpisode")
          nextEpisode?.let { podcastEpisode ->
            mediaManager.play(
              libraryItem,
              podcastEpisode,
              getPlayItemRequestPayload(false),
              ownerConfig,
              ownerLease
            ) {
              if (!isPlaybackOperationCurrent(operation)) return@play
              if (it == null) {
                Log.e(tag, "Failed to play library item")
                postToMainIfAlive {
                  if (isPlaybackOperationCurrent(operation)) {
                    closePlayback(false)
                    setMediaUnavailablePlaybackState()
                  }
                }
              } else {
                val playbackRate = mediaManager.getSavedPlaybackRate()
                postToMainIfAlive {
                  if (isPlaybackOperationCurrent(operation)) {
                    preparePlayer(it, true, playbackRate)
                  }
                }
              }
            }
          } ?: postToMainIfAlive {
            if (isPlaybackOperationCurrent(operation)) closePlayback(false)
          }
        }
      }
      return
    }

    // Books and downloaded podcasts do not auto-advance. Start the detached
    // terminal checkpoint before removing the ended Exo timeline so service
    // teardown cannot cancel it. With no retained media item, a later Play
    // request goes through normal resume preparation, whose near-end rule
    // explicitly restarts the item from zero.
    try {
      mediaProgressSyncer.finished {
        postToMainIfAlive {
          if (isPlaybackOperationSessionCurrent(operation)) closePlayback(false)
        }
      }
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to finish completed playback (${error.javaClass.simpleName})")
      postToMainIfAlive {
        if (isPlaybackOperationSessionCurrent(operation)) closePlayback(false)
      }
    } finally {
      clearEndedPlayerTimeline(playbackSession)
    }
  }

  private fun clearEndedPlayerTimeline(playbackSession: PlaybackSession) {
    if (currentPlaybackSession !== playbackSession || !this::currentPlayer.isInitialized) return
    try {
      currentPlayer.stop()
      currentPlayer.clearMediaItems()
    } catch (error: RuntimeException) {
      Log.e(tag, "Unable to clear completed playback (${error.javaClass.simpleName})")
    }
  }

  internal fun usesPodcastAutoNextForEndedPlayback(playbackSession: PlaybackSession): Boolean =
    !playbackSession.isLocal &&
      playbackSession.isPodcastEpisode &&
      playbackSession.libraryItem?.media is Podcast

  fun startNewPlaybackSession() {
    currentPlaybackSession?.let { playbackSession ->
      val operation = beginPlaybackOperation(playbackSession)
      val ownerLease = playbackSession.connectionLease
      val ownerConfig = ownerLease?.let(DeviceManager::getServerConnectionConfig)
      if (ownerLease == null || ownerConfig == null ||
        !DeviceManager.trySelectServerConnectionConfig(ownerConfig, ownerLease)
      ) {
        setSignInRequiredPlaybackState()
        return
      }
      Log.i(tag, "Starting new playback session for ${playbackSession.displayTitle}")

      val forceTranscode = playbackSession.isHLS // If already HLS then force
      val playItemRequestPayload = getPlayItemRequestPayload(forceTranscode)

      val libraryItemId = playbackSession.libraryItemId
      if (libraryItemId.isNullOrBlank()) {
        closePlaybackWithError(getString(R.string.voice_search_playback_failed))
        return
      }
      val episodeId = playbackSession.episodeId
      mediaProgressSyncer.stop(false) {
        if (!isPlaybackOperationCurrent(operation)) return@stop
        apiHandler.playLibraryItem(
          libraryItemId,
          episodeId,
          playItemRequestPayload,
          ownerConfig
        ) {
          if (!isPlaybackOperationCurrent(operation)) return@playLibraryItem
          if (it == null) {
            Log.e(tag, "Failed to start new playback session")
            postToMainIfAlive {
              if (isPlaybackOperationCurrent(operation)) {
                closePlaybackWithError(getString(R.string.voice_search_playback_failed))
              }
            }
          } else {
            Log.d(
                    tag,
                    "New playback session response from server with session id ${it.id} for \"${it.displayTitle}\""
            )
            postToMainIfAlive {
              if (isPlaybackOperationCurrent(operation)) {
                preparePlayer(it, true, null)
              }
            }
          }
        }
      }
    }
  }

  fun getCurrentTrackStartOffsetMs(): Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentTrackStartOffset
    } else {
      0
    }
  }

  fun getCurrentTime(): Long {
    return currentPlayer.currentPosition + getCurrentTrackStartOffsetMs()
  }

  fun getCurrentTimeSeconds(): Double {
    return getCurrentTime() / 1000.0
  }

  private fun getBufferedTime(): Long {
    return if (currentPlayer.mediaItemCount > 1) {
      val windowIndex = currentPlayer.currentMediaItemIndex
      val currentTrackStartOffset = currentPlaybackSession?.getTrackStartOffsetMs(windowIndex) ?: 0L
      currentPlayer.bufferedPosition + currentTrackStartOffset
    } else {
      currentPlayer.bufferedPosition
    }
  }

  fun getBufferedTimeSeconds(): Double {
    return getBufferedTime() / 1000.0
  }

  fun getDuration(): Long {
    return currentPlaybackSession?.totalDurationMs ?: 0L
  }

  fun getCurrentPlaybackSessionCopy(): PlaybackSession? {
    return currentPlaybackSession?.clone()
  }

  fun getCurrentBookChapter(): BookChapter? {
    return currentPlaybackSession?.getChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfChapterOrTrack(): Long? {
    return getCurrentBookChapter()?.endMs ?: currentPlaybackSession?.getCurrentTrackEndTime()
  }

  private fun getNextBookChapter(): BookChapter? {
    return currentPlaybackSession?.getNextChapterForTime(this.getCurrentTime())
  }

  fun getEndTimeOfNextChapterOrTrack(): Long? {
    return getNextBookChapter()?.endMs ?: currentPlaybackSession?.getNextTrackEndTime()
  }

  // Called from PlayerListener play event
  // check with server if progress has updated since last play and sync progress update
  fun checkCurrentSessionProgress(seekBackTime: Long): Boolean {
    if (currentPlaybackSession == null) return true

    mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
      if (!DeviceManager.checkConnectivity(ctx)) {
        return true // carry on
      }

      if (playbackSession.isLocal) {

        // Make sure this connection config exists
        val serverConnectionConfig =
                DeviceManager.getServerConnectionConfig(playbackSession.serverConnectionConfigId)
        if (serverConnectionConfig == null) {
          Log.d(
                  tag,
                  "checkCurrentSessionProgress: Local library item server connection config is not saved ${playbackSession.serverConnectionConfigId}"
          )
          return true // carry on
        }

        // Local playback session check if server has updated media progress
        Log.d(
                tag,
                "checkCurrentSessionProgress: Checking if local media progress was updated on server"
        )
        apiHandler.getMediaProgress(
                playbackSession.libraryItemId!!,
                playbackSession.episodeId,
                serverConnectionConfig
        ) { mediaProgress ->
          if (mediaProgress != null &&
                          mediaProgress.lastUpdate > playbackSession.updatedAt &&
                          mediaProgress.currentTime != playbackSession.currentTime
          ) {
            Log.d(
                    tag,
                    "checkCurrentSessionProgress: Media progress was updated since last play time updating from ${playbackSession.currentTime} to ${mediaProgress.currentTime}"
            )
            mediaProgressSyncer.syncFromServerProgress(mediaProgress)

            // Update current playback session stored in PNS since MediaProgressSyncer version is a
            // copy
            mediaProgressSyncer.currentPlaybackSession?.let { updatedPlaybackSession ->
              currentPlaybackSession = updatedPlaybackSession
            }

            postToMainIfAlive {
              seekPlayer(playbackSession.currentTimeMs)
              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              currentPlaybackSession?.let { mediaProgressSyncer.play(it) }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          } else {
            postToMainIfAlive {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              // Should already be playing
              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }
              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      } else {
        // Streaming from server so check if playback session still exists on server
        Log.d(
                tag,
                "checkCurrentSessionProgress: Checking if playback session ${playbackSession.id} for server stream is still available"
        )
        val ownerConfig = playbackSession.connectionLease?.let {
          DeviceManager.getServerConnectionConfig(it)
        }
        if (ownerConfig == null) {
          postToMainIfAlive { setSignInRequiredPlaybackState() }
          return true
        }
        apiHandler.getPlaybackSession(playbackSession.id, ownerConfig) {
          if (it == null) {
            Log.d(
                    tag,
                    "checkCurrentSessionProgress: Playback session does not exist on server - start new playback session"
            )

            postToMainIfAlive {
              currentPlayer.pause()
              startNewPlaybackSession()
            }
          } else {
            Log.d(tag, "checkCurrentSessionProgress: Playback session still available on server")
            postToMainIfAlive {
              if (seekBackTime > 0L) {
                seekBackward(seekBackTime)
              }

              currentPlayer.volume = 1F // Volume on sleep timer might have decreased this
              mediaProgressSyncer.currentPlaybackSession?.let { playbackSession ->
                mediaProgressSyncer.play(playbackSession)
              }

              clientEventEmitter?.onPlayingUpdate(true)
            }
          }
        }
      }
    }
    return false
  }

  fun play() {
    if (publishSignInRequiredForPlaybackIfNeeded(currentPlaybackSession)) return
    if (currentPlayer.isPlaying) {
      Log.d(tag, "Already playing")
      return
    }
    currentPlayer.volume = 1F
    currentPlayer.play()
  }

  fun pause() {
    currentPlayer.pause()
  }

  fun playPause(): Boolean {
    return if (currentPlayer.isPlaying) {
      pause()
      false
    } else {
      play()
      true
    }
  }

  fun seekPlayer(time: Long) {
    val duration = getDuration().coerceAtLeast(0L)
    var timeToSeek = time
    Log.d(tag, "seekPlayer mediaCount = ${currentPlayer.mediaItemCount} | $timeToSeek")
    if (timeToSeek < 0) {
      Log.w(tag, "seekPlayer invalid time $timeToSeek - setting to 0")
      timeToSeek = 0L
    } else if (timeToSeek > duration) {
      Log.w(tag, "seekPlayer invalid time $timeToSeek - setting to MAX - 2000")
      timeToSeek = (duration - 2000L).coerceAtLeast(0L)
    }

    if (currentPlayer.mediaItemCount > 1) {
      currentPlaybackSession?.currentTime = timeToSeek / 1000.0
      val newWindowIndex = currentPlaybackSession?.getCurrentTrackIndex() ?: 0
      val newTimeOffset = currentPlaybackSession?.getCurrentTrackTimeMs() ?: 0
      Log.d(tag, "seekPlayer seekTo $newWindowIndex | $newTimeOffset")
      currentPlayer.seekTo(newWindowIndex, newTimeOffset)
    } else {
      currentPlayer.seekTo(timeToSeek)
    }
  }

  fun skipToPrevious() {
    currentPlayer.seekToPrevious()
  }

  fun skipToNext() {
    currentPlayer.seekToNext()
  }

  fun jumpForward() {
    seekForward(deviceSettings.jumpForwardTimeMs)
  }

  fun jumpBackward() {
    seekBackward(deviceSettings.jumpBackwardsTimeMs)
  }

  fun seekForward(amount: Long) {
    seekPlayer(getCurrentTime() + amount)
  }

  fun seekBackward(amount: Long) {
    seekPlayer(getCurrentTime() - amount)
  }

  fun setPlaybackSpeed(speed: Float) {
    mediaManager.userSettingsPlaybackRate = speed
    currentPlayer.setPlaybackSpeed(speed)

    // Refresh Android Auto actions
    mediaProgressSyncer.currentPlaybackSession?.let { setMediaSessionConnectorCustomActions(it) }
  }

  fun closePlayback(calledOnError: Boolean? = false) {
    Log.d(tag, "closePlayback")
    invalidatePlaybackOperations()
    val closeGeneration = playbackOperationGeneration.get()
    mediaSessionCallback?.invalidatePendingPreparation()
    val closingSession = currentPlaybackSession ?: mediaProgressSyncer.currentPlaybackSession
    val config = closingSession?.connectionLease?.let(DeviceManager::getServerConnectionConfig)

    val isLocal = closingSession?.isLocal ?: mediaProgressSyncer.currentIsLocal
    val currentSessionId = closingSession?.id ?: mediaProgressSyncer.currentSessionId
    val terminalSyncPending = mediaProgressSyncer.listeningTimerRunning ||
      mediaProgressSyncer.isSuspendedForBuffering
    if (terminalSyncPending) {
      Log.i(tag, "About to close playback so stopping media progress syncer first")

      mediaProgressSyncer.stop(
              calledOnError == false
      ) { // If closing on error then do not sync progress (causes exception)
        Log.d(tag, "Media Progress syncer stopped")
        // If not local session then close on server
        if (!isLocal && currentSessionId != "" && config != null) {
          apiHandler.closePlaybackSession(currentSessionId, config) {
            Log.d(tag, "Closed playback session $currentSessionId")
          }
        }
        if (playbackOperationGeneration.get() == closeGeneration &&
          currentPlaybackSession == null
        ) {
          stopSelf()
        }
      }
    } else {
      // If not local session then close on server
      if (!isLocal && currentSessionId != "" && config != null) {
        apiHandler.closePlaybackSession(currentSessionId, config) {
          Log.d(tag, "Closed playback session $currentSessionId")
        }
      }
    }

    try {
      currentPlayer.stop()
      currentPlayer.clearMediaItems()
    } catch (e: Exception) {
      Log.e(tag, "Exception clearing ExoPlayer (${e.javaClass.simpleName})")
    }

    currentPlaybackSession = null
    mediaProgressSyncer.reset()
    clientEventEmitter?.onPlaybackClosed()

    if (this::playerListener.isInitialized) playerListener.resetForSession()
    isClosed = true
    DeviceManager.widgetUpdater?.onPlayerClosed()
    if (!terminalSyncPending) stopSelf()
  }

  /** Implements MediaSession stop while retaining a safe resume checkpoint. */
  fun stopPlaybackForResume() {
    if (serviceDestroyed) return
    val stoppedPositionMs = getCurrentTime().coerceAtLeast(0L)
    currentPlaybackSession?.clone()?.let { checkpoint ->
      checkpoint.currentTime = stoppedPositionMs / 1000.0
      checkpoint.updatedAt = System.currentTimeMillis()
      persistPlaybackCheckpointAsync(checkpoint, checkpoint.connectionLease)
    }

    closePlayback(false)
    if (this::mediaSession.isInitialized) {
      mediaSession.setPlaybackState(
        PlaybackStateCompat.Builder()
          .setActions(configuredPlaybackActions())
          .setState(PlaybackStateCompat.STATE_STOPPED, stoppedPositionMs, 0f)
          .build()
      )
    }
  }

  fun sendClientMetadata(playerState: PlayerState) {
    val duration = currentPlaybackSession?.getTotalDuration() ?: 0.0
    clientEventEmitter?.onMetadata(PlaybackMetadata(duration, getCurrentTimeSeconds(), playerState))
  }

  fun getMediaPlayer(): String {
    return PLAYER_EXO
  }

  fun getDeviceInfo(): DeviceInfo {
    /* EXAMPLE
     manufacturer: Google
     model: Pixel 6
     brand: google
     sdkVersion: 32
     appVersion: 0.9.46-beta
    */
    val deviceId = AppInstanceId.get(ctx)
    return DeviceInfo(
            deviceId,
            Build.MANUFACTURER,
            Build.MODEL,
            Build.VERSION.SDK_INT,
            BuildConfig.VERSION_NAME
    )
  }

  private val deviceSettings
    get() = DeviceManager.deviceData.deviceSettings ?: DeviceSettings.default()

  fun getPlayItemRequestPayload(forceTranscode: Boolean): PlayItemRequestPayload {
    return PlayItemRequestPayload(
            getMediaPlayer(),
            !forceTranscode,
            forceTranscode,
            getDeviceInfo()
    )
  }

  fun getContext(): Context {
    return ctx
  }

  fun alertSyncFailing() {
    clientEventEmitter?.onProgressSyncFailing()
  }

  fun alertSyncSuccess() {
    clientEventEmitter?.onProgressSyncSuccess()
  }

  //
  // MEDIA BROWSER STUFF (ANDROID AUTO)
  //
  private val mediaBrowserCallerValidator by lazy {
    // Lazy evaluation is essential: Service field initializers run before the
    // ContextWrapper is attached and packageManager is then unavailable.
    MediaBrowserCallerValidator(applicationContext)
  }
  private val validatedBrowserPackages = ValidatedBrowserPackageRegistry()
  private val installedSystemArtworkConsumers by lazy {
    KNOWN_PLAYBACK_ARTWORK_SYSTEM_PACKAGES.filter { packageName ->
      val flags = runCatching {
        packageManager.getApplicationInfo(packageName, 0).flags
      }.getOrDefault(0)
      flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }
  }

  private val AUTO_MEDIA_ROOT = "/"
  private val LIBRARIES_ROOT = "__LIBRARIES__"
  private val RECENTLY_ROOT = "__RECENTLY__"
  private val DOWNLOADS_ROOT = "__DOWNLOADS__"
  private val CONTINUE_ROOT = "__CONTINUE__"
  private val SEARCH_RESULTS_ROOT = "__SEARCH_RESULTS__"
  private lateinit var browseTree: BrowseTree
  private val browserResultCallerPackages =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String>())
  private val completedBrowseResults =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Boolean>())
  private val browserResultTimeouts =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Runnable>())
  private val browserResultConnectionEpochs =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, Long>())
  private val browserResultConnectionIds =
    java.util.Collections.synchronizedMap(java.util.WeakHashMap<Any, String?>())
  private data class BrowseItemScope(
    val callerPackage: String,
    val connectionEpoch: Long,
    val connectionId: String?
  )
  private data class CachedBrowseItem(
    var item: MediaBrowserCompat.MediaItem,
    val scopes: MutableSet<BrowseItemScope>
  )
  private val cachedBrowseItems = LinkedHashMap<String, CachedBrowseItem>()
  private val browseParentsByMediaId = mutableMapOf<String, MutableSet<String>>()
  private val browserCustomActionLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()

  private fun rebuildBrowseTree() {
    browseTree =
      BrowseTree(
        this,
        mediaManager.serverItemsInProgress,
        mediaManager.serverLibraries,
        mediaManager.allLibraryPersonalizationsDone
      )
  }

  private fun currentBrowseServerConfigId(): String? =
    DeviceManager.serverConnectionConfig?.id
      ?: DeviceManager.getLastServerConnectionConfig()?.id

  /**
   * Initialize the AAOS browse model once and fan the outcome out to every
   * concurrent subscriber. A timeout and generation check guarantee detached
   * MediaBrowser results are not left pending forever or completed by a stale
   * request after a server switch.
   */
  private fun ensureBrowseTreeLoaded(
    hasOfflineMedia: Boolean,
    forceReload: Boolean,
    cb: (Boolean) -> Unit
  ) {
    if (serviceDestroyed) return
    if (!forceReload && lastRootLoadSucceeded && this::browseTree.isInitialized) {
      cb(true)
      return
    }

    browseTreeLoadListeners += cb
    val requestedConfigId = currentBrowseServerConfigId()
    if (browseTreeLoading) {
      if (forceReload || requestedConfigId != browseTreeLoadingConfigId) {
        browseTreeReloadQueued = true
        queuedBrowseHasOfflineMedia = queuedBrowseHasOfflineMedia || hasOfflineMedia
        Log.i(tag, "Queued Android Auto reload while an older browse load is active")
      }
      return
    }

    browseTreeLoading = true
    forceReloadingAndroidAuto = false
    browseTreeLoadingConfigId = requestedConfigId
    val generation = ++browseTreeLoadGeneration
    val timeout = Runnable {
      if (!serviceDestroyed && browseTreeLoading && generation == browseTreeLoadGeneration) {
        if (browseTreeReloadQueued) {
          restartQueuedBrowseTreeLoad()
          return@Runnable
        }
        Log.e(tag, "Timed out loading Android Auto browse data")
        browseFuture?.cancel(true)
        browseFuture = null
        // Invalidate callbacks from the timed-out profile before another root
        // request starts. Otherwise a late response can silently switch the
        // active account after the browser has already received an error.
        mediaManager.checkResetServerItems(forceReset = true)
        // Downloads can be added or removed during a slow server load; decide
        // from current storage state at the completion boundary.
        if (hasPlayableDownloads()) {
          rebuildBrowseTree()
          clearFatalPlaybackErrorState()
          completeBrowseTreeLoad(generation, true)
          // Timeout recovery exposes offline data only; no enrichment callbacks
          // will run to drain restored-page notifications.
          clearPendingColdBrowseRestores()
        } else {
          setMediaUnavailablePlaybackState()
          completeBrowseTreeLoad(generation, false)
        }
      }
    }
    browseTreeTimeout?.let(mainHandler::removeCallbacks)
    browseTreeTimeout = timeout
    mainHandler.postDelayed(timeout, BROWSE_LOAD_TIMEOUT_MS)

    browseFuture = browseExecutor.submit {
      try {
        mediaManager.loadAndroidAutoItems { connectedToServer ->
          mainHandler.post {
            if (serviceDestroyed || !browseTreeLoading || generation != browseTreeLoadGeneration) {
              return@post
            }
            mainHandler.removeCallbacks(timeout)
            if (browseTreeTimeout === timeout) browseTreeTimeout = null
            if (browseTreeReloadQueued) {
              restartQueuedBrowseTreeLoad()
              return@post
            }
            try {
              // MediaManager may intentionally select the one bounded fallback
              // profile during this load. Accept that resolved profile instead
              // of discarding successful work and starting the whole sequence
              // again. Explicit user/root switches set browseTreeReloadQueued.
              val resolvedConfigId = currentBrowseServerConfigId()
              // Authentication can be revoked while loadAndroidAutoItems is in
              // flight. Never publish its cached server titles or clear the
              // actionable sign-in state after that profile has disappeared.
              if (resolvedConfigId == null) {
                mediaManager.checkResetServerItems(forceReset = true)
                if (hasPlayableDownloads()) {
                  rebuildBrowseTree()
                  clearFatalPlaybackErrorState()
                  completeBrowseTreeLoad(generation, true)
                } else {
                  setSignInRequiredPlaybackState()
                  completeBrowseTreeLoad(generation, false)
                }
                return@post
              }
              browseTreeLoadingConfigId = resolvedConfigId
              val hasServerMedia = mediaManager.serverLibraries.any {
                it.stats?.numAudioFiles != 0
              }
              if ((!connectedToServer || !hasServerMedia) && !hasPlayableDownloads()) {
                setMediaUnavailablePlaybackState()
                completeBrowseTreeLoad(generation, false)
                return@post
              }

              rebuildBrowseTree()
              clearFatalPlaybackErrorState()
              completeBrowseTreeLoad(generation, true)

              if (mediaManager.serverLibraries.isNotEmpty()) {
                beginBrowseEnrichment(generation, callbackCount = 2)
                try {
                  mediaManager.populatePersonalizedDataForAllLibraries {
                    postToMainIfAlive personalized@ {
                      if (generation != browseTreeLoadGeneration ||
                        currentBrowseServerConfigId() != resolvedConfigId ||
                        !lastRootLoadSucceeded
                      ) {
                        finishBrowseEnrichmentCallback(generation)
                        return@personalized
                      }
                      try {
                        rebuildBrowseTree()
                        notifyChildrenChanged(AUTO_MEDIA_ROOT)
                        notifyPendingColdBrowseRestores()
                      } catch (error: Exception) {
                        Log.w(
                          tag,
                          "Unable to publish personalized browse data (${error.javaClass.simpleName})"
                        )
                      } finally {
                        finishBrowseEnrichmentCallback(generation)
                      }
                    }
                  }
                } catch (error: Exception) {
                  Log.w(tag, "Unable to start personalized browse load (${error.javaClass.simpleName})")
                  finishBrowseEnrichmentCallback(generation)
                }
                try {
                  mediaManager.initializeInProgressItems {
                    postToMainIfAlive inProgress@ {
                      if (generation != browseTreeLoadGeneration ||
                        currentBrowseServerConfigId() != resolvedConfigId ||
                        !lastRootLoadSucceeded
                      ) {
                        finishBrowseEnrichmentCallback(generation)
                        return@inProgress
                      }
                      try {
                        rebuildBrowseTree()
                        notifyChildrenChanged(AUTO_MEDIA_ROOT)
                        notifyPendingColdBrowseRestores()
                      } catch (error: Exception) {
                        Log.w(
                          tag,
                          "Unable to publish in-progress browse data (${error.javaClass.simpleName})"
                        )
                      } finally {
                        finishBrowseEnrichmentCallback(generation)
                      }
                    }
                  }
                } catch (error: Exception) {
                  Log.w(tag, "Unable to start in-progress browse load (${error.javaClass.simpleName})")
                  finishBrowseEnrichmentCallback(generation)
                }
              } else {
                clearPendingColdBrowseRestores()
              }
            } catch (error: Exception) {
              Log.e(tag, "Failed to initialize AAOS browse data (${error.javaClass.simpleName})")
              setMediaUnavailablePlaybackState()
              completeBrowseTreeLoad(generation, false)
            }
          }
        }
      } catch (error: Exception) {
        mainHandler.post {
          if (!serviceDestroyed && browseTreeLoading && generation == browseTreeLoadGeneration) {
            mainHandler.removeCallbacks(timeout)
            if (browseTreeTimeout === timeout) browseTreeTimeout = null
            if (browseTreeReloadQueued) {
              restartQueuedBrowseTreeLoad()
              return@post
            }
            Log.e(tag, "Failed to load AAOS browse data (${error.javaClass.simpleName})")
            setMediaUnavailablePlaybackState()
            completeBrowseTreeLoad(generation, false)
          }
        }
      }
    }
  }

  private fun restartQueuedBrowseTreeLoad() {
    if (serviceDestroyed) return
    val hasOfflineMedia = queuedBrowseHasOfflineMedia
    browseTreeTimeout?.let(mainHandler::removeCallbacks)
    browseTreeTimeout = null
    browseFuture?.cancel(true)
    browseFuture = null
    browseTreeLoading = false
    browseTreeLoadingConfigId = null
    browseTreeReloadQueued = false
    queuedBrowseHasOfflineMedia = false
    mediaManager.checkResetServerItems(forceReset = true)

    // Existing result listeners remain queued; this no-op listener simply
    // starts the replacement generation through the same code path.
    ensureBrowseTreeLoaded(hasOfflineMedia, forceReload = true) {}
  }

  private fun completeBrowseTreeLoad(generation: Int, success: Boolean) {
    if (serviceDestroyed || !browseTreeLoading || generation != browseTreeLoadGeneration) return

    browseTreeTimeout?.let(mainHandler::removeCallbacks)
    browseTreeTimeout = null
    browseFuture = null
    browseTreeLoading = false
    browseTreeLoadingConfigId = null
    browseTreeReloadQueued = false
    queuedBrowseHasOfflineMedia = false
    lastRootLoadSucceeded = success
    forceReloadingAndroidAuto = !success
    if (!success) clearPendingColdBrowseRestores()

    val listeners = browseTreeLoadListeners.toList()
    browseTreeLoadListeners.clear()
    listeners.forEach { listener ->
      try {
        listener(success)
      } catch (error: Exception) {
        Log.e(tag, "AAOS browse listener failed (${error.javaClass.simpleName})")
      }
    }
  }

  private fun beginBrowseEnrichment(generation: Int, callbackCount: Int) {
    browseEnrichmentGeneration = generation
    browseEnrichmentCallbacksRemaining = callbackCount.coerceAtLeast(0)
    if (browseEnrichmentCallbacksRemaining == 0) clearPendingColdBrowseRestores()
  }

  private fun finishBrowseEnrichmentCallback(generation: Int) {
    if (generation != browseEnrichmentGeneration) return
    browseEnrichmentCallbacksRemaining = (browseEnrichmentCallbacksRemaining - 1).coerceAtLeast(0)
    if (browseEnrichmentCallbacksRemaining == 0) clearPendingColdBrowseRestores()
  }

  private fun notifyPendingColdBrowseRestores() {
    pendingColdBrowseRestores.snapshot().forEach { parentMediaId ->
      try {
        notifyChildrenChanged(parentMediaId)
      } catch (error: Exception) {
        Log.w(tag, "Unable to notify a restored browse page (${error.javaClass.simpleName})")
      }
    }
  }

  private fun clearPendingColdBrowseRestores() {
    pendingColdBrowseRestores.clear()
    browseEnrichmentGeneration = -1
    browseEnrichmentCallbacksRemaining = 0
  }

  /**
   * Kick off a server cover fetch for the now-playing session if not yet
   * cached. When the fetch settles, rebuild the MediaSession metadata so the
   * Polestar home tile gets a baked-in ALBUM_ART bitmap instead of just a URI
   * Polestar's cross-process image loader can't fetch.
   */
  private fun ensurePlaybackCoverCachedThenRefreshMetadata(playbackSession: PlaybackSession) {
    val cache = DeviceManager.coverCache ?: return
    val itemId = playbackSession.libraryItemId ?: return
    if (playbackSession.localLibraryItem != null) return  // local already has bitmap
    val ownerLease = playbackSession.connectionLease ?: return
    val ownerConfig = DeviceManager.getServerConnectionConfig(ownerLease) ?: return
    if (cache.cachedUri(itemId, ownerConfig, ownerLease) != null) return  // already baked
    if (cache.hasAttempted(itemId, ownerConfig, ownerLease)) return  // don't retry-storm
    val url = Uri.parse(ownerConfig.address).buildUpon()
      .appendPath("api")
      .appendPath("items")
      .appendPath(itemId)
      .appendPath("cover")
      .build()
      .toString()
    cache.fetchAsync(itemId, url, ownerConfig, ownerLease) {
      postToMainIfAlive {
        try {
          if (this::mediaSession.isInitialized &&
            currentPlaybackSession === playbackSession &&
            DeviceManager.getServerConnectionConfig(ownerLease) === ownerConfig
          ) {
            val metadata = playbackSession.getMediaMetadataCompat(ctx)
            grantPlaybackArtworkAccess(metadata)
            mediaSession.setMetadata(metadata)
            Log.d(tag, "Refreshed media metadata with baked cover bitmap for $itemId")
          }
        } catch (e: Exception) {
          Log.w(tag, "Refresh metadata after cover fetch failed (${e.javaClass.simpleName})")
        }
      }
    }
  }

  /**
   * Grants every app-owned FileProvider URI in playing metadata before the
   * MediaSession publishes it to another process.
   */
  private fun grantPlaybackArtworkAccess(metadata: MediaMetadataCompat) {
    listOf(
      MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
      MediaMetadataCompat.METADATA_KEY_ART_URI,
      MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI
    )
      .mapNotNull { key ->
        metadata.getString(key)?.let { rawUri -> runCatching { Uri.parse(rawUri) }.getOrNull() }
      }
      .distinct()
      .forEach(::grantPlaybackArtworkAccess)
  }

  private fun grantPlaybackArtworkAccess(uri: Uri?) {
    if (uri?.scheme != "content" ||
      uri.authority != "${BuildConfig.APPLICATION_ID}.fileprovider"
    ) {
      return
    }

    val targetPackages = LinkedHashSet<String>().apply {
      addAll(installedSystemArtworkConsumers)
      addAll(validatedBrowserPackages.snapshot())
    }
    targetPackages.forEach { packageName ->
      try {
        ctx.grantUriPermission(
          packageName,
          uri,
          Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
      } catch (error: Exception) {
        Log.w(tag, "Unable to grant playback artwork access (${error.javaClass.simpleName})")
      }
    }
  }

  /**
   * Grant FLAG_GRANT_READ_URI_PERMISSION on every content:// iconUri in [items]
   * to the currently-subscribed MediaBrowser caller. Without this, Car Media
   * crashes with SecurityException when its image loader tries to read a
   * FileProvider URI from another UID (Polestar repro: see crash on
   * com.android.car.apps.common.imaging.LocalImageFetcher).
   */
  private fun currentBrowserPackageName(): String? =
    try {
      currentBrowserInfo?.packageName
    } catch (_: Exception) {
      null
    }

  private fun grantCoverUriPermissions(
    items: List<MediaBrowserCompat.MediaItem>,
    callerPackage: String?
  ) {
    if (callerPackage == null) return
    items.forEach { item ->
      val uri = item.description.iconUri
      if (uri != null && uri.scheme == "content") {
        try {
          ctx.grantUriPermission(callerPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } catch (e: Exception) {
        Log.w(tag, "Unable to grant browse artwork access (${e.javaClass.simpleName})")
        }
      }
    }
  }

  /**
   * Parse remote audiobookshelf cover URLs out of [items] and fire downloads
   * for any not yet in the on-disk cache. Once all fetches settle (success or
   * failure), [notifyChildrenChanged] tells Car Media to re-subscribe so the
   * next onLoadChildren returns content:// URIs from the cache.
   *
   * Generic over MediaItem so it works for every branch — Continue, Recent,
   * Libraries subfolders, search, downloads — without needing the source
   * LibraryItem object.
   */
  private val coverUrlIdRegex = "/api/items/([^/?]+)/cover".toRegex()
  private val authorImageUrlIdRegex = "/api/authors/([^/?]+)/image".toRegex()
  private fun prefetchCoversAndNotify(parentMediaId: String, items: List<MediaBrowserCompat.MediaItem>) {
    val cache = DeviceManager.coverCache ?: return
    val namespace = cache.currentNamespace() ?: return
    // Claim before inspecting a potentially huge result set. A host reload for
    // this parent then returns immediately instead of scanning for another batch.
    if (!browseCoverPrefetchGate.claim(namespace, parentMediaId)) return

    val toFetch = items.asSequence().mapNotNull { item ->
      val uri = item.description.iconUri ?: return@mapNotNull null
      val scheme = uri.scheme ?: return@mapNotNull null
      if (scheme != "http" && scheme != "https") return@mapNotNull null
      val uriString = uri.toString()
      val cacheKey = coverUrlIdRegex.find(uriString)?.groupValues?.get(1)
        ?: authorImageUrlIdRegex.find(uriString)?.groupValues?.get(1)?.let { "author_$it" }
        ?: return@mapNotNull null
      // Skip if already cached or we've already tried this process lifetime
      // (latter prevents notifyChildrenChanged retry-storms on failed URLs).
      if (cache.cachedUri(cacheKey) != null || cache.hasAttempted(cacheKey)) return@mapNotNull null
      cacheKey to uriString
    }.distinctBy { it.first }
      // Large libraries can return thousands of items. Warm one small visible
      // batch for this parent/server during the service session. The resulting
      // host reload may consume those files but cannot chain into the next 24;
      // every remaining item keeps its compliant local fallback artwork.
      .take(24)
      .toList()

    if (toFetch.isEmpty()) return

    val remaining = java.util.concurrent.atomic.AtomicInteger(toFetch.size)
    toFetch.forEach { (cacheKey, url) ->
      cache.fetchAsync(cacheKey, url) {
        if (remaining.decrementAndGet() == 0) {
          Log.d(tag, "prefetchCoversAndNotify: notify $parentMediaId (${toFetch.size} covers)")
          postToMainIfAlive { notifyChildrenChanged(parentMediaId) }
        }
      }
    }
  }

  private fun withOfflineBrowseActions(
    items: List<MediaBrowserCompat.MediaItem>,
    callerPackage: String?
  ): MutableList<MediaBrowserCompat.MediaItem> {
    val actionLimit = callerPackage?.let(browserCustomActionLimits::get) ?: 0
    val allOfflineActionIds = offlineBrowseActionIds().toSet()
    val stateSnapshot = if (actionLimit > 0 && this::offlineDownloads.isInitialized) {
      offlineDownloads.browseSnapshot(currentBrowseServerConfigId())
    } else {
      null
    }
    val playableLegacyLocalIds = if (stateSnapshot != null) {
      DeviceManager.dbManager.getLocalLibraryItems().asSequence()
        .filter { it.id !in stateSnapshot.managedLocalIds && it.hasTracks(null) }
        .map(LocalLibraryItem::id)
        .toSet()
    } else {
      emptySet()
    }
    return items.map { item ->
      val description = item.description
      val actionId = if (actionLimit > 0 && stateSnapshot != null &&
        this::mediaManager.isInitialized
      ) {
        // ShelfDrive currently attaches exactly one state-specific action per
        // item, so every positive host per-item limit can represent it.
        offlineActionFor(item, stateSnapshot, playableLegacyLocalIds)
      } else {
        null
      }
      val extras = Bundle(description.extras ?: Bundle()).apply {
        val retained = getStringArrayList(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST
        ).orEmpty().filterNot(allOfflineActionIds::contains).toMutableList()
        actionId?.let(retained::add)
        if (retained.isEmpty()) {
          remove(MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST)
        } else {
          putStringArrayList(
            MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST,
            ArrayList(retained)
          )
        }
      }
      val decorated = MediaDescriptionCompat.Builder()
        .setMediaId(description.mediaId)
        .setTitle(description.title)
        .setSubtitle(description.subtitle)
        .setDescription(description.description)
        .setIconBitmap(description.iconBitmap)
        .setIconUri(description.iconUri)
        .setMediaUri(description.mediaUri)
        .setExtras(extras)
        .build()
      MediaBrowserCompat.MediaItem(decorated, item.flags)
    }.toMutableList()
  }

  private fun offlineActionFor(
    item: MediaBrowserCompat.MediaItem,
    snapshot: OfflineBrowseSnapshot,
    playableLegacyLocalIds: Set<String>
  ): String? {
    if (!item.isPlayable) return null
    val mediaId = item.mediaId ?: return null
    if (mediaId in snapshot.managedLocalIds) {
      return ACTION_REMOVE_OFFLINE_DOWNLOAD
    }
    val remote = resolveRemoteAudiobook(mediaId) ?: return null
    val connectionId = snapshot.connectionId ?: return null
    return when (snapshot.state(remote.id)) {
      OfflineDownloadState.NONE -> {
        val retainedLegacyCopy = remote.localLibraryItemId in playableLegacyLocalIds
        if (!retainedLegacyCopy) ACTION_DOWNLOAD_OFFLINE else null
      }
      OfflineDownloadState.FAILED -> ACTION_DOWNLOAD_OFFLINE
      OfflineDownloadState.ACTIVE -> ACTION_CANCEL_OFFLINE_DOWNLOAD
      OfflineDownloadState.DOWNLOADED -> ACTION_REMOVE_OFFLINE_DOWNLOAD
    }
  }

  private fun resolveRemoteAudiobook(mediaId: String): LibraryItem? {
    val item = mediaManager.getById(mediaId) ?: mediaManager.getFromSearch(mediaId)
    return (item as? LibraryItem)?.takeIf {
      it.mediaType == "book" && it.collapsedSeries == null && it.checkHasTracks()
    }
  }

  private fun rememberBrowseItems(
    parentMediaId: String,
    items: List<MediaBrowserCompat.MediaItem>,
    callerPackage: String?,
    connectionEpoch: Long,
    connectionId: String?
  ) {
    val scope = callerPackage?.let {
      BrowseItemScope(it, connectionEpoch, connectionId)
    } ?: return
    synchronized(cachedBrowseItems) {
      items.forEach { item ->
        val mediaId = item.mediaId ?: return@forEach
        val existing = cachedBrowseItems[mediaId]
        if (existing != null) {
          existing.item = item
          existing.scopes += scope
        } else {
          cachedBrowseItems[mediaId] = CachedBrowseItem(item, mutableSetOf(scope))
        }
        while (cachedBrowseItems.size > MAX_CACHED_BROWSE_ITEMS) {
          cachedBrowseItems.entries.iterator().let { iterator ->
            if (iterator.hasNext()) {
              val removedId = iterator.next().key
              iterator.remove()
              synchronized(browseParentsByMediaId) { browseParentsByMediaId.remove(removedId) }
            }
          }
        }
      }
    }
    synchronized(browseParentsByMediaId) {
      items.forEach { item ->
        item.mediaId?.let { mediaId ->
          browseParentsByMediaId.getOrPut(mediaId) { mutableSetOf() }.add(parentMediaId)
        }
      }
    }
  }

  private fun cachedBrowseItemForCaller(
    mediaId: String,
    callerPackage: String?
  ): MediaBrowserCompat.MediaItem? {
    val caller = callerPackage ?: return null
    val scope = BrowseItemScope(
      caller,
      DeviceManager.currentConnectionStateEpoch(),
      currentBrowseServerConfigId()
    )
    return synchronized(cachedBrowseItems) {
      cachedBrowseItems[mediaId]?.takeIf { scope in it.scopes }?.item
    }
  }

  private fun pruneCachedBrowseScopesForReconnect(callerPackage: String) {
    val currentEpoch = DeviceManager.currentConnectionStateEpoch()
    val removedIds = mutableListOf<String>()
    synchronized(cachedBrowseItems) {
      val iterator = cachedBrowseItems.entries.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        entry.value.scopes.removeAll { scope ->
          scope.callerPackage == callerPackage || scope.connectionEpoch != currentEpoch
        }
        if (entry.value.scopes.isEmpty()) {
          removedIds += entry.key
          iterator.remove()
        }
      }
    }
    if (removedIds.isNotEmpty()) {
      synchronized(browseParentsByMediaId) {
        removedIds.forEach(browseParentsByMediaId::remove)
      }
    }
  }

  private fun hasPlayableDownloads(): Boolean {
    return listOf("book", "podcast").any { mediaType ->
      DeviceManager.dbManager.getLocalLibraryItems(mediaType).any { localItem ->
        if (mediaType == "podcast") {
          (localItem.media as? Podcast)?.episodes.orEmpty().any { episode ->
            localItem.hasTracks(episode)
          }
        } else {
          localItem.hasTracks(null)
        }
      }
    }
  }

  /**
   * A completed local session is itself a valid offline resume source. This is
   * deliberately checked separately from the download index: disconnecting an
   * account strips its server identity, and older app versions may have left a
   * durable session whose local item row cannot be reconstructed until play.
   */
  private fun hasPlayableSavedLocalSession(): Boolean {
    val session = DeviceManager.deviceData.lastPlaybackSession ?: return false
    if (!session.isLocal || session.audioTracks.isEmpty()) return false
    return session.audioTracks.all { track ->
      if (!track.isLocal) return@all false
      val uri = runCatching { Uri.parse(track.contentUrl) }.getOrNull() ?: return@all false
      when (uri.scheme?.lowercase()) {
        ContentResolver.SCHEME_FILE -> uri.path
          ?.let(::File)
          ?.let { file -> file.isFile && file.canRead() }
          ?: false
        ContentResolver.SCHEME_CONTENT -> runCatching {
          contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        }.getOrDefault(false)
        else -> false
      }
    }
  }

  /**
   * Single wrapper for `result.sendResult(items)` that also grants URI read
   * perms to the calling browser AND kicks off cover prefetch + notify.
   * Use everywhere instead of `result.sendResult(items)` so every browse path
   * gets covers without per-branch wiring.
   */
  private fun sendChildren(
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    items: MutableList<MediaBrowserCompat.MediaItem>?,
    parentMediaId: String
  ) {
    val callerPackage = browserResultCallerPackages.remove(result)
    val connectionEpoch = browserResultConnectionEpochs[result]
      ?: DeviceManager.currentConnectionStateEpoch()
    val connectionId = browserResultConnectionIds[result]
    sendChildren(
      result,
      items,
      parentMediaId,
      callerPackage,
      connectionEpoch,
      connectionId
    )
  }

  private fun sendChildren(
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    items: MutableList<MediaBrowserCompat.MediaItem>?,
    parentMediaId: String,
    callerPackage: String?,
    connectionEpoch: Long = DeviceManager.currentConnectionStateEpoch(),
    connectionId: String? = currentBrowseServerConfigId()
  ) {
    if (items.isNullOrEmpty()) {
      completeBrowseResult(result, items, parentMediaId)
      return
    }
    // Preserve the original remote URLs long enough for our in-process cache
    // to fetch them, but never expose those URLs (or bitmaps) to the AAOS host.
    val boundedItems = browseResultTree.replaceChildren(parentMediaId, items)
    prefetchCoversAndNotify(parentMediaId, boundedItems)
    val actionableItems = withOfflineBrowseActions(boundedItems, callerPackage)
    val safeItems = BrowseArtworkPolicy.sanitize(actionableItems)
    rememberBrowseItems(
      parentMediaId,
      safeItems,
      callerPackage,
      connectionEpoch,
      connectionId
    )
    grantCoverUriPermissions(safeItems, callerPackage)
    completeBrowseResult(result, safeItems, parentMediaId)
  }

  /** Completes a detached browse request at most once, including timeouts. */
  private fun completeBrowseResult(
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    items: MutableList<MediaBrowserCompat.MediaItem>?,
    parentMediaId: String
  ): Boolean {
    if (serviceDestroyed) return false
    val firstCompletion = synchronized(completedBrowseResults) {
      completedBrowseResults.put(result, true) == null
    }
    if (!firstCompletion) return false
    browserResultTimeouts.remove(result)?.let(mainHandler::removeCallbacks)
    browserResultCallerPackages.remove(result)
    val expectedEpoch = browserResultConnectionEpochs.remove(result)
    val expectedConnectionId = browserResultConnectionIds.remove(result)
    val offlineSafeItems = items.orEmpty().all { item ->
      val mediaId = item.mediaId.orEmpty()
      mediaId == DOWNLOADS_ROOT || mediaId.startsWith("local")
    }
    val connectionChanged = when {
      parentMediaId == DOWNLOADS_ROOT || parentMediaId.startsWith("local") -> false
      expectedEpoch == null -> false
      expectedConnectionId != null ->
        !DeviceManager.isConnectionStateCurrent(expectedEpoch, expectedConnectionId)
      parentMediaId == AUTO_MEDIA_ROOT -> false
      offlineSafeItems -> false
      else -> true
    }
    val safeResult = if (connectionChanged) {
      if (parentMediaId == AUTO_MEDIA_ROOT) null else mutableListOf()
    } else {
      items
    }
    result.sendResult(safeResult)
    return true
  }

  private fun scheduleBrowseResultTimeout(
    result: Result<MutableList<MediaBrowserCompat.MediaItem>>,
    parentMediaId: String
  ) {
    val timeout = Runnable {
      browserResultTimeouts.remove(result)
      if (serviceDestroyed) return@Runnable
      if (completeBrowseResult(result, mutableListOf(), parentMediaId)) {
        Log.w(tag, "onLoadChildren: Timed out")
        setMediaUnavailablePlaybackState()
      }
    }
    browserResultTimeouts[result] = timeout
    mainHandler.postDelayed(timeout, BROWSE_RESULT_TIMEOUT_MS)
  }

  // Allow known media clients and every trusted/system AAOS host. OEM car
  // media package names vary, so a fixed package allowlist is insufficient.
  private fun isValid(packageName: String, uid: Int): Boolean {
    Log.d(tag, "onGetRoot: Checking package $packageName with uid $uid")
    if (!mediaBrowserCallerValidator.isValid(packageName, uid)) {
      Log.d(tag, "onGetRoot: package $packageName not valid for the media browser service")
      return false
    }
    return true
  }

  /** Cars App Quality requires a fatal, actionable error at an empty root. */
  private fun setSignInRequiredPlaybackState() {
    invalidatePlaybackOperations()
    mediaSessionCallback?.invalidatePendingPreparation()
    // Stop any already-buffered remote audio before publishing the terminal
    // auth state. Publishing afterward keeps the connector's pause update from
    // replacing STATE_ERROR with an ordinary paused state.
    if (this::currentPlayer.isInitialized) currentPlayer.pause()
    setFatalCarPlaybackError(
      messageRes = R.string.car_sign_in_required,
      actionLabelRes = R.string.car_sign_in_action,
      errorCode = PlaybackStateCompat.ERROR_CODE_AUTHENTICATION_EXPIRED
    )
    AbsLogger.info(tag, "onLoadChildren: No server configured — sign-in required")
  }

  /** AAOS natural-end handling owns the one final Finished sync. */
  internal fun handlesEndedPlaybackAsCompletion(): Boolean =
    isAndroidAuto && currentPlaybackSession != null

  /**
   * Prevent an eager AAOS prepare/play/search command from replacing the
   * actionable authentication error with a generic "nothing playable" error.
   * Offline downloads remain playable without a configured server.
   */
  private fun hasSelectedServerForPlayback(): Boolean =
    DeviceManager.captureConnectionLease(DeviceManager.serverConnectionConfig) != null ||
      DeviceManager.captureConnectionLease(
        DeviceManager.getLastServerConnectionConfig()
      ) != null

  /**
   * Returns whether the requested operation requires account recovery.
   * A specific remote session always requires a configured server; a generic
   * browse/search command can still proceed against downloaded media.
   */
  internal fun isSignInRequiredForPlayback(
    playbackSession: PlaybackSession? = null
  ): Boolean {
    if (playbackSession?.isLocal == true) return false
    if (playbackSession != null) {
      val ownerId = playbackSession.serverConnectionConfigId ?: return true
      val existingLease = playbackSession.connectionLease
      if (existingLease != null) {
        return existingLease.connectionId != ownerId ||
          !DeviceManager.isConnectionLeaseCurrent(existingLease)
      }
      val ownerConfig = DeviceManager.getServerConnectionConfig(ownerId)
      return DeviceManager.captureConnectionLease(ownerConfig) == null
    }
    if (hasSelectedServerForPlayback()) return false
    return !hasPlayableDownloads() && !hasPlayableSavedLocalSession()
  }

  internal fun publishSignInRequiredForPlaybackIfNeeded(
    playbackSession: PlaybackSession? = null
  ): Boolean {
    if (!isSignInRequiredForPlayback(playbackSession)) return false
    setSignInRequiredPlaybackState()
    return true
  }

  internal fun setMediaUnavailablePlaybackState() {
    // Token refresh/account removal can race every browse failure path. Never
    // replace the required authentication error with a generic app error.
    if (publishSignInRequiredForPlaybackIfNeeded()) return
    setFatalCarPlaybackError(
      messageRes = R.string.car_media_unavailable,
      actionLabelRes = R.string.car_open_settings_action,
      errorCode = PlaybackStateCompat.ERROR_CODE_APP_ERROR
    )
    AbsLogger.info(tag, "onLoadChildren: Server media unavailable — surfaced actionable error")
  }

  private fun setFatalCarPlaybackError(
    messageRes: Int,
    actionLabelRes: Int,
    errorCode: Int
  ) {
    if (!this::mediaSessionConnector.isInitialized || !this::mediaSession.isInitialized) return

    val settingsIntent =
            Intent(this, SettingsActivity::class.java).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    val resolutionIntent =
            PendingIntent.getActivity(
                    this,
                    0,
                    settingsIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

    val errorExtras =
            Bundle().apply {
              putString(
                      MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL,
                      getString(actionLabelRes)
              )
              putParcelable(
                      MediaConstants.PLAYBACK_STATE_EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT,
                      resolutionIntent
              )
            }

    val message = getString(messageRes)
    mediaSessionConnector.setCustomErrorMessage(
            message,
            errorCode,
            errorExtras
    )

    // MediaSessionConnector preserves a custom error message but leaves the
    // player's ordinary state intact. AAOS requires STATE_ERROR when nothing
    // can be browsed or played, so publish the fatal state explicitly too.
    mediaSession.setPlaybackState(
      PlaybackStateCompat.Builder()
        .setState(PlaybackStateCompat.STATE_ERROR, 0L, 0f)
        .setErrorMessage(errorCode, message)
        .setExtras(errorExtras)
        .build()
    )
  }

  /**
   * Clears a previously-surfaced fatal playback or browse error after recovery.
   * Clearing the connector's custom error republishes the current player state.
   */
  private fun clearFatalPlaybackErrorState() {
    if (this::mediaSessionConnector.isInitialized) {
      mediaSessionConnector.setCustomErrorMessage(null)
    }
  }

  private fun customOfflineBrowseActions(): ArrayList<Bundle> {
    fun action(id: String, label: String, drawableRes: Int) = Bundle().apply {
      val drawableName = resources.getResourceEntryName(drawableRes)
      putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID, id)
      putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_LABEL, label)
      putString(
        MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ICON_URI,
        "android.resource://$packageName/drawable/$drawableName"
      )
    }
    return arrayListOf(
      action(
        ACTION_DOWNLOAD_OFFLINE,
        getString(R.string.offline_action_download),
        R.drawable.ic_offline_download
      ),
      action(
        ACTION_CANCEL_OFFLINE_DOWNLOAD,
        getString(R.string.offline_action_cancel),
        R.drawable.ic_offline_cancel
      ),
      action(
        ACTION_REMOVE_OFFLINE_DOWNLOAD,
        getString(R.string.offline_action_remove),
        R.drawable.ic_offline_remove
      )
    )
  }

  private fun offlineBrowseActionIds(): List<String> = listOf(
    ACTION_DOWNLOAD_OFFLINE,
    ACTION_CANCEL_OFFLINE_DOWNLOAD,
    ACTION_REMOVE_OFFLINE_DOWNLOAD
  )

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    if (serviceDestroyed) return null
    // Verify that the specified package is allowed to access your content
    return if (!isValid(clientPackageName, clientUid)) {
      // No further calls will be made to other media browsing methods.
      null
    } else {
      AbsLogger.info(tag, "onGetRoot: accepted trusted media browser")
      pruneCachedBrowseScopesForReconnect(clientPackageName)
      // Record only after package/UID validation, then immediately grant the
      // currently playing artwork in case this browser connected after the
      // MediaSession metadata was first published.
      validatedBrowserPackages.record(clientPackageName)
      currentPlaybackSession?.let { playbackSession ->
        grantPlaybackArtworkAccess(playbackSession.getCoverUri(ctx))
      }
      // Reset cache if no longer connected to server or server changed
      if (mediaManager.checkResetServerItems()) {
        AbsLogger.info(tag, "onGetRoot: Reset server media cache")
        forceReloadingAndroidAuto = true
        lastRootLoadSucceeded = false
      }

      isAndroidAuto = true

      val extras = Bundle()
      extras.putBoolean(MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_SEARCH_SUPPORTED, true)
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_BROWSABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )
      extras.putInt(
              MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_PLAYABLE,
              MediaConstants.DESCRIPTION_EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM
      )
      val customActionLimit = rootHints?.getInt(
        MediaConstants.BROWSER_ROOT_HINTS_KEY_CUSTOM_BROWSER_ACTION_LIMIT,
        0
      )?.coerceIn(0, offlineBrowseActionIds().size) ?: 0
      browserCustomActionLimits[clientPackageName] = customActionLimit
      if (customActionLimit > 0) {
        extras.putParcelableArrayList(
          MediaConstants.BROWSER_SERVICE_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ROOT_LIST,
          customOfflineBrowseActions()
        )
      }

      BrowserRoot(AUTO_MEDIA_ROOT, extras)
    }
  }

  override fun onLoadChildren(
          parentMediaId: String,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    if (serviceDestroyed) {
      result.sendResult(null)
      return
    }
    currentBrowserPackageName()?.let { callerPackage ->
      browserResultCallerPackages[result] = callerPackage
    }
    browserResultConnectionEpochs[result] = DeviceManager.currentConnectionStateEpoch()
    browserResultConnectionIds[result] = currentBrowseServerConfigId()
    AbsLogger.info(tag, "onLoadChildren: browse request received")

    result.detach()

    // Every detached request must finish within the AAOS 10-second response
    // budget even if an OEM/server callback is lost.
    scheduleBrowseResultTimeout(result, parentMediaId)

    try {

    // Oversized results are exposed behind deterministic browsable range
    // nodes. Generated descendants are already complete, so resolve them
    // before attempting a server/root reload.
    browseResultTree.lookup(parentMediaId)?.let { generatedChildren ->
      sendChildren(result, generatedChildren.toMutableList(), parentMediaId)
      return
    }
    // The range namespace is reserved. A stale or tampered signed route must
    // fail closed instead of falling through as a server-controlled podcast ID.
    if (browseResultTree.isGeneratedIdCandidate(parentMediaId)) {
      sendChildren(result, mutableListOf(), parentMediaId)
      return
    }

    val hasOfflineMedia = parentMediaId == AUTO_MEDIA_ROOT && hasPlayableDownloads()
    val hasSelectedServer = DeviceManager.serverConnectionConfig != null ||
      DeviceManager.getLastServerConnectionConfig() != null

    // AAOS requires a null root result plus STATE_ERROR when authentication is
    // required and no offline content can be used. Returning a browsable but
    // empty Downloads tab makes the app look broken to users and reviewers.
    if (parentMediaId == AUTO_MEDIA_ROOT && !hasSelectedServer) {
      if (!hasOfflineMedia) {
        setSignInRequiredPlaybackState()
        sendChildren(result, null, parentMediaId)
        return
      } else {
        clearFatalPlaybackErrorState()
      }
    }

    // AAOS can restore a deep page before asking for root after a service
    // process restart. Complete this request immediately, initialize in the
    // background, then notify the exact restored page so the host retries it.
    if ((parentMediaId != DOWNLOADS_ROOT && parentMediaId != AUTO_MEDIA_ROOT) &&
      !lastRootLoadSucceeded
    ) {
      pendingColdBrowseRestores.remember(parentMediaId)
      sendChildren(result, mutableListOf(), parentMediaId)
      ensureBrowseTreeLoaded(
        hasOfflineMedia = hasPlayableDownloads(),
        forceReload = forceReloadingAndroidAuto
      ) { success ->
        if (success) notifyChildrenChanged(parentMediaId)
      }
      return
    }

    if (parentMediaId == DOWNLOADS_ROOT) { // Load downloads
      val localBooks = DeviceManager.dbManager.getLocalLibraryItems("book")
      val localPodcasts = DeviceManager.dbManager.getLocalLibraryItems("podcast")
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()

      localBooks.forEach { localLibraryItem ->
        if (localLibraryItem.hasTracks(null)) {
          val progress = DeviceManager.dbManager.getLocalMediaProgress(localLibraryItem.id)
          val description = localLibraryItem.getMediaDescription(progress, ctx)

          localBrowseItems +=
                  MediaBrowserCompat.MediaItem(
                          description,
                          MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                  )
        }
      }

      localPodcasts.forEach { localLibraryItem ->
        if ((localLibraryItem.media as? Podcast)?.episodes.orEmpty().any(localLibraryItem::hasTracks)) {
          val mediaDescription = localLibraryItem.getMediaDescription(null, ctx)
          localBrowseItems +=
                  MediaBrowserCompat.MediaItem(
                          mediaDescription,
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
        }
      }

      sendChildren(result, localBrowseItems, parentMediaId)
    } else if (parentMediaId == CONTINUE_ROOT) {
      val localBrowseItems: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
      mediaManager.serverItemsInProgress.forEach { itemInProgress ->
        val progress: MediaProgressWrapper?
        val mediaDescription: MediaDescriptionCompat
        if (itemInProgress.episode != null) {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            "${itemInProgress.libraryItemWrapper.id}-${itemInProgress.episode.id}"
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id &&
                              it.episodeId == itemInProgress.episode.id
                    }

            // to show download icon
            val localLibraryItem =
                    getLocalDownloadForCurrentServer(
                            itemInProgress.libraryItemWrapper.id
                    )
            localLibraryItem?.let { lli ->
              val localEpisode =
                      (lli.media as? Podcast)?.episodes?.find {
                        it.serverEpisodeId == itemInProgress.episode.id
                      }
              itemInProgress.episode.localEpisodeId = localEpisode?.id
            }
          }
          mediaDescription =
                  itemInProgress.episode.getMediaDescription(
                          itemInProgress.libraryItemWrapper,
                          progress,
                          ctx
                  )
        } else {
          if (itemInProgress.isLocal) {
            progress =
                    DeviceManager.dbManager.getLocalMediaProgress(
                            itemInProgress.libraryItemWrapper.id
                    )
          } else {
            progress =
                    mediaManager.serverUserMediaProgress.find {
                      it.libraryItemId == itemInProgress.libraryItemWrapper.id
                    }

            val localLibraryItem =
                    getLocalDownloadForCurrentServer(
                            itemInProgress.libraryItemWrapper.id
                    )
            (itemInProgress.libraryItemWrapper as? LibraryItem)?.localLibraryItemId =
                    localLibraryItem?.id // To show downloaded icon
          }
          mediaDescription = itemInProgress.libraryItemWrapper.getMediaDescription(progress, ctx)
        }
        localBrowseItems +=
                MediaBrowserCompat.MediaItem(
                        mediaDescription,
                        MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
      }
      sendChildren(result, localBrowseItems, parentMediaId)
    } else if (parentMediaId == AUTO_MEDIA_ROOT) {
      AbsLogger.info(tag, "onLoadChildren: Loading Android Auto items")
      ensureBrowseTreeLoaded(hasOfflineMedia, forceReloadingAndroidAuto) { success ->
        if (!success) {
          sendChildren(result, null, parentMediaId)
          return@ensureBrowseTreeLoaded
        }

        // Personalized and in-progress callbacks may have changed the menu
        // since its initial load, so rebuild the cheap in-memory tree here.
        rebuildBrowseTree()
        val children = browseTree[parentMediaId]
          ?.map { item ->
            Log.d(tag, "Found top menu item: ${item.description.title}")
            MediaBrowserCompat.MediaItem(
              item.description,
              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
            )
          }
          ?.toMutableList()
        sendChildren(result, children, parentMediaId)
      }
    } else if (parentMediaId == LIBRARIES_ROOT || parentMediaId == RECENTLY_ROOT)
    {
      val children = browseTree[parentMediaId]?.map { item ->
        Log.d(tag, "[MENU: $parentMediaId] Showing list item ${item.description.title}")
        MediaBrowserCompat.MediaItem(
          item.description,
          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )
      }
      sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
    } else if (mediaManager.getIsLibrary(parentMediaId)) { // Load library items for library
      Log.d(tag, "Loading items for library $parentMediaId")
      val selectedLibrary = mediaManager.getLibrary(parentMediaId)
      if (selectedLibrary?.mediaType == "podcast") { // Podcasts are browseable
        mediaManager.loadLibraryPodcasts(parentMediaId) { libraryItems ->
          val children =
                  libraryItems?.map { libraryItem ->
                    val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            mediaDescription,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else {
        val children =
                mutableListOf(
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Books")
                                        .setMediaId("__LIBRARY__${parentMediaId}__BOOKS")
                                        .setIconUri(getUriToDrawable(ctx, R.drawable.abs_books_1))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Authors")
                                        .setMediaId("__LIBRARY__${parentMediaId}__AUTHORS")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Series")
                                        .setMediaId("__LIBRARY__${parentMediaId}__SERIES_LIST")
                                        .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        ),
                        MediaBrowserCompat.MediaItem(
                                MediaDescriptionCompat.Builder()
                                        .setTitle("Collections")
                                        .setMediaId("__LIBRARY__${parentMediaId}__COLLECTIONS")
                                        .setIconUri(
                                                getUriToDrawable(
                                                        ctx,
                                                        R.drawable.md_book_multiple_outline
                                                )
                                        )
                                        .build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                )
        if (mediaManager.getHasDiscovery(parentMediaId)) {
          children.add(
                  MediaBrowserCompat.MediaItem(
                          MediaDescriptionCompat.Builder()
                                  .setTitle("Discovery")
                                  .setMediaId("__LIBRARY__${parentMediaId}__DISCOVERY")
                                  .setIconUri(getUriToDrawable(ctx, R.drawable.md_telescope))
                                  .build(),
                          MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                  )
          )
        }
        sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
      }
    } else if (parentMediaId.startsWith(RECENTLY_ROOT)) {
      Log.d(tag, "Browsing recently $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      if (mediaIdParts.size != 3 && mediaIdParts.size != 4) {
        Log.w(tag, "Ignoring malformed Recent media id")
        sendChildren(result, mutableListOf(), parentMediaId)
        return
      }
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        Log.d(tag, "${mediaIdParts[2]} is not library")
        sendChildren(result, mutableListOf(), parentMediaId)
        return
      }
      Log.d(tag, "Mediaparts: ${mediaIdParts.size} | $mediaIdParts")
      if (mediaIdParts.size == 3) {
        mediaManager.getLibraryRecentShelfs(mediaIdParts[2]) { availableShelfs ->
          Log.d(tag, "Found ${availableShelfs.size} shelfs")
          val children: MutableList<MediaBrowserCompat.MediaItem> = mutableListOf()
          for (shelf in availableShelfs) {
            if (shelf.type == "book") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Books")
                                      .setMediaId("${parentMediaId}__BOOK")
                                      .setIconUri(
                                              getUriToDrawable(
                                                      ctx,
                                                      R.drawable.md_book_open_blank_variant_outline
                                              )
                                      )
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "series") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Series")
                                      .setMediaId("${parentMediaId}__SERIES")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "columns"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "episode") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Episodes")
                                      .setMediaId("${parentMediaId}__EPISODE")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "microphone_2"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "podcast") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Podcast")
                                      .setMediaId("${parentMediaId}__PODCAST")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "podcast"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            } else if (shelf.type == "authors") {
              children.add(
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle("Authors")
                                      .setMediaId("${parentMediaId}__AUTHORS")
                                      .setIconUri(getUriToAbsIconDrawable(ctx, "authors"))
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
              )
            }
          }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts.size == 4) {
        mediaManager.getLibraryRecentShelfByType(mediaIdParts[2], mediaIdParts[3]) { shelf ->
          if (shelf === null) {
            sendChildren(result, mutableListOf(), parentMediaId)
          } else {
            if (shelf.type == "book") {
              val children =
                      (shelf as LibraryShelfBookEntity).entities
                        ?.filter { libraryItem -> libraryItem.checkHasTracks() }
                        ?.map { libraryItem ->
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.id
                                }
                        val localLibraryItem =
                                getLocalDownloadForCurrentServer(libraryItem.id)
                        libraryItem.localLibraryItemId = localLibraryItem?.id
                        val description =
                                libraryItem.getMediaDescription(progress, ctx, null, false)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
            } else if (shelf.type == "episode") {
              val episodesWithRecentEpisode =
                      (shelf as LibraryShelfEpisodeEntity).entities?.filter { libraryItem ->
                        libraryItem.recentEpisode !== null
                      }
              val children =
                      episodesWithRecentEpisode?.mapNotNull { libraryItem ->
                        if (libraryItem.media !is Podcast) return@mapNotNull null
                        val recentEpisode = libraryItem.recentEpisode ?: return@mapNotNull null
                        val progress =
                                mediaManager.serverUserMediaProgress.find {
                                  it.libraryItemId == libraryItem.libraryId &&
                                          it.episodeId == recentEpisode.id
                                }

                        // to show download icon
                        val localLibraryItem =
                                getLocalDownloadForCurrentServer(
                                        recentEpisode.id
                                )
                        localLibraryItem?.let { lli ->
                          val localEpisode =
                                  (lli.media as? Podcast)?.episodes?.find {
                                    it.serverEpisodeId == recentEpisode.id
                                  }
                          recentEpisode.localEpisodeId = localEpisode?.id
                        }

                        val description =
                                recentEpisode.getMediaDescription(
                                        libraryItem,
                                        progress,
                                        ctx
                                )
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                        )
                      }
              sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
            } else if (shelf.type == "podcast") {
              val children =
                      (shelf as LibraryShelfPodcastEntity).entities?.map { libraryItem ->
                        val mediaDescription = libraryItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                mediaDescription,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
            } else if (shelf.type == "series") {
              val children =
                      (shelf as LibraryShelfSeriesEntity).entities?.map { librarySeriesItem ->
                        val description = librarySeriesItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
            } else if (shelf.type == "authors") {
              val children =
                      (shelf as LibraryShelfAuthorEntity).entities?.map { authorItem ->
                        val description = authorItem.getMediaDescription(null, ctx)
                        MediaBrowserCompat.MediaItem(
                                description,
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                        )
                      }
              sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
            } else {
              sendChildren(result, mutableListOf(), parentMediaId)
            }
          }
        }
      }
    } else if (parentMediaId.startsWith("__LIBRARY__")) {
      Log.d(tag, "Browsing library $parentMediaId")
      val mediaIdParts = parentMediaId.split("__")
      /*
       MediaIdParts for Library
       1: LIBRARY
       2: mediaId for library
       3: Browsing style (AUTHORS, AUTHOR, AUTHOR_SERIES, SERIES_LIST, SERIES, COLLECTION, COLLECTIONS, DISCOVERY)
       4:
         - Paging: SERIES_LIST, AUTHORS
         - SeriesId: SERIES
         - AuthorId: AUTHOR, AUTHOR_SERIES
         - CollectionId: COLLECTIONS
       5: SeriesId: AUTHOR_SERIES
      */
      if (mediaIdParts.size < 4) {
        Log.w(tag, "Ignoring malformed Library media id")
        sendChildren(result, mutableListOf(), parentMediaId)
        return
      }
      if (!mediaManager.getIsLibrary(mediaIdParts[2])) {
        Log.d(tag, "${mediaIdParts[2]} is not library")
        sendChildren(result, mutableListOf(), parentMediaId)
        return
      }
      Log.d(tag, "$mediaIdParts")
      if (mediaIdParts[3] == "BOOKS") {
        Log.d(tag, "Loading all books from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryBooksWithAudio(mediaIdParts[2]) { libraryItems ->
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            getLocalDownloadForCurrentServer(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts[3] == "SERIES_LIST" && mediaIdParts.size == 5) {
        Log.d(tag, "Loading series from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2], mediaIdParts[4]) { seriesItems ->
          Log.d(tag, "Received ${seriesItems.size} series")

          val seriesLetters =
                  seriesItems
                          .groupingBy { iwb ->
                            iwb.title.take(mediaIdParts[4].length + 1)
                              .uppercase()
                              .ifBlank { "#" }
                          }
                          .eachCount()
          if (seriesItems.size >
                          deviceSettings
                                  .androidAutoBrowseLimitForGrouping &&
                          seriesItems.size > 1 &&
                          seriesLetters.size > 1
          ) {
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter)
                                      .setMediaId("${parentMediaId}${seriesLetter.lastOrNull() ?: '#'}")
                                      .setSubtitle("$seriesCount series")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          }
        }
      } else if (mediaIdParts[3] == "SERIES_LIST") {
        Log.d(tag, "Loading series from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesWithAudio(mediaIdParts[2]) { seriesItems ->
          Log.d(tag, "Received ${seriesItems.size} series")
          if (seriesItems.size >
                          deviceSettings
                                  .androidAutoBrowseLimitForGrouping && seriesItems.size > 1
          ) {
            val seriesLetters =
                    seriesItems.groupingBy { iwb -> iwb.title.firstOrNull()?.uppercaseChar() ?: '#' }.eachCount()
            val children =
                    seriesLetters.map { (seriesLetter, seriesCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(seriesLetter.toString())
                                      .setSubtitle("$seriesCount series")
                                      .setMediaId("${parentMediaId}__${seriesLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          } else {
            val children =
                    seriesItems.map { seriesItem ->
                      val description = seriesItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          }
        }
      } else if (mediaIdParts[3] == "SERIES" && mediaIdParts.size >= 5) {
        Log.d(tag, "Loading items for serie ${mediaIdParts[4]} from library ${mediaIdParts[2]}")
        mediaManager.loadLibrarySeriesItemsWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} library items")
          var items = libraryItems
          if (deviceSettings.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            getLocalDownloadForCurrentServer(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts[3] == "AUTHORS" && mediaIdParts.size == 5) {
        Log.d(tag, "Loading authors from library ${mediaIdParts[2]} with paging ${mediaIdParts[4]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2], mediaIdParts[4]) { authorItems ->
          Log.d(tag, "Received ${authorItems.size} authors")

          val authorLetters =
                  authorItems
                          .groupingBy { iwb ->
                            iwb.name.take(mediaIdParts[4].length + 1)
                              .uppercase()
                              .ifBlank { "#" }
                          }
                          .eachCount()
          if (authorItems.size >
                          deviceSettings
                                  .androidAutoBrowseLimitForGrouping &&
                          authorItems.size > 1 &&
                          authorLetters.size > 1
          ) {
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter)
                                      .setMediaId("${parentMediaId}${authorLetter.lastOrNull() ?: '#'}")
                                      .setSubtitle("$authorCount authors")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          }
        }
      } else if (mediaIdParts[3] == "AUTHORS") {
        Log.d(tag, "Loading authors from library ${mediaIdParts[2]}")
        mediaManager.loadAuthorsWithBooks(mediaIdParts[2]) { authorItems ->
          Log.d(tag, "Received ${authorItems.size} authors")
          if (authorItems.size >
                          deviceSettings
                                  .androidAutoBrowseLimitForGrouping && authorItems.size > 1
          ) {
            val authorLetters =
                    authorItems.groupingBy { iwb -> iwb.name.firstOrNull()?.uppercaseChar() ?: '#' }.eachCount()
            val children =
                    authorLetters.map { (authorLetter, authorCount) ->
                      MediaBrowserCompat.MediaItem(
                              MediaDescriptionCompat.Builder()
                                      .setTitle(authorLetter.toString())
                                      .setSubtitle("$authorCount authors")
                                      .setMediaId("${parentMediaId}__${authorLetter}")
                                      .build(),
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          } else {
            val children =
                    authorItems.map { authorItem ->
                      val description = authorItem.getMediaDescription(null, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    }
            sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
          }
        }
      } else if (mediaIdParts[3] == "AUTHOR" && mediaIdParts.size >= 5) {
        mediaManager.loadAuthorBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) { libraryItems ->
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            getLocalDownloadForCurrentServer(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    if (libraryItem.collapsedSeries != null) {
                      val description =
                              libraryItem.getMediaDescription(progress, ctx, mediaIdParts[4])
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      val description = libraryItem.getMediaDescription(progress, ctx)
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts[3] == "AUTHOR_SERIES" && mediaIdParts.size >= 6) {
        mediaManager.loadAuthorSeriesBooksWithAudio(
                mediaIdParts[2],
                mediaIdParts[4],
                mediaIdParts[5]
        ) { libraryItems ->
          var items = libraryItems
          if (deviceSettings.androidAutoBrowseSeriesSequenceOrder ===
                          AndroidAutoBrowseSeriesSequenceOrderSetting.DESC
          ) {
            items = libraryItems.reversed()
          }
          val children =
                  items.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            getLocalDownloadForCurrentServer(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx, null, true)
                    if (libraryItem.collapsedSeries != null) {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                      )
                    } else {
                      MediaBrowserCompat.MediaItem(
                              description,
                              MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                      )
                    }
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts[3] == "COLLECTIONS") {
        Log.d(tag, "Loading collections from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionsWithAudio(mediaIdParts[2]) { collectionItems ->
          Log.d(tag, "Received ${collectionItems.size} collections")
          val children =
                  collectionItems.map { collectionItem ->
                    val description = collectionItem.getMediaDescription(null, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                    )
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts[3] == "COLLECTION" && mediaIdParts.size >= 5) {
        Log.d(tag, "Loading collection ${mediaIdParts[4]} books from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryCollectionBooksWithAudio(mediaIdParts[2], mediaIdParts[4]) {
                libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} collections")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            getLocalDownloadForCurrentServer(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else if (mediaIdParts[3] == "DISCOVERY") {
        Log.d(tag, "Loading discovery from library ${mediaIdParts[2]}")
        mediaManager.loadLibraryDiscoveryBooksWithAudio(mediaIdParts[2]) { libraryItems ->
          Log.d(tag, "Received ${libraryItems.size} libraryItems for discovery")
          val children =
                  libraryItems.map { libraryItem ->
                    val progress =
                            mediaManager.serverUserMediaProgress.find {
                              it.libraryItemId == libraryItem.id
                            }
                    val localLibraryItem =
                            getLocalDownloadForCurrentServer(libraryItem.id)
                    libraryItem.localLibraryItemId = localLibraryItem?.id
                    val description = libraryItem.getMediaDescription(progress, ctx)
                    MediaBrowserCompat.MediaItem(
                            description,
                            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                    )
                  }
          sendChildren(result, children as MutableList<MediaBrowserCompat.MediaItem>?, parentMediaId)
        }
      } else {
        sendChildren(result, mutableListOf(), parentMediaId)
      }
    } else {
      Log.d(tag, "Loading podcast episodes for podcast $parentMediaId")
      mediaManager.loadPodcastEpisodeMediaBrowserItems(parentMediaId, ctx) { sendChildren(result, it, parentMediaId) }
    }
    } catch (error: Exception) {
      Log.e(tag, "onLoadChildren failed (${error.javaClass.simpleName})")
      setMediaUnavailablePlaybackState()
      sendChildren(result, mutableListOf(), parentMediaId)
    }
  }

  /**
   * Custom browse actions require the service to be able to reload the exact
   * item the host is about to mutate. Only return items produced during this
   * validated browser session; never reconstruct a remote item from an
   * untrusted media ID supplied by the caller.
   */
  override fun onLoadItem(
    itemId: String,
    result: Result<MediaBrowserCompat.MediaItem>
  ) {
    if (serviceDestroyed) {
      result.sendResult(null)
      return
    }
    val callerPackage = currentBrowserPackageName()
    val cached = cachedBrowseItemForCaller(itemId, callerPackage)
    if (cached == null) {
      result.sendResult(null)
      return
    }
    val local = DeviceManager.dbManager.getLocalLibraryItem(itemId)
    if (itemId.startsWith("local") && (local == null || !local.hasTracks(null))) {
      result.sendResult(null)
      return
    }
    val refreshed = BrowseArtworkPolicy.sanitize(
      withOfflineBrowseActions(listOf(cached), callerPackage)
    ).firstOrNull()
    if (refreshed == null) {
      result.sendResult(null)
      return
    }
    grantCoverUriPermissions(listOf(refreshed), callerPackage)
    result.sendResult(refreshed)
  }

  override fun onCustomAction(
    action: String,
    extras: Bundle?,
    result: Result<Bundle>
  ) {
    if (serviceDestroyed) {
      result.sendError(offlineActionResultBundle(null, R.string.offline_result_failed))
      return
    }
    val mediaId = extras?.getString(
      MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_MEDIA_ITEM_ID
    )?.takeIf(String::isNotBlank)
    val callerPackage = currentBrowserPackageName()
    val cached = mediaId?.let { id -> cachedBrowseItemForCaller(id, callerPackage) }
    if (mediaId == null || cached == null) {
      result.sendError(offlineActionResultBundle(mediaId, R.string.offline_result_invalid_item))
      return
    }
    val actionLimit = callerPackage?.let(browserCustomActionLimits::get) ?: 0
    val currentActionIds = if (actionLimit > 0) {
      withOfflineBrowseActions(listOf(cached), callerPackage).firstOrNull()
        ?.description?.extras?.getStringArrayList(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_CUSTOM_BROWSER_ACTION_ID_LIST
        ).orEmpty()
    } else {
      emptyList()
    }
    // Bind the mutation to the action currently attached to this exact item.
    // A delayed/replayed host callback cannot cancel or remove a new state.
    if (action !in offlineBrowseActionIds() || action !in currentActionIds) {
      result.sendError(offlineActionResultBundle(mediaId, R.string.offline_result_invalid_item))
      return
    }

    when (action) {
      ACTION_DOWNLOAD_OFFLINE -> handleOfflineDownload(mediaId, result)
      ACTION_CANCEL_OFFLINE_DOWNLOAD -> handleOfflineCancel(mediaId, result)
      ACTION_REMOVE_OFFLINE_DOWNLOAD -> handleOfflineRemove(mediaId, result)
      else -> result.sendError(
        offlineActionResultBundle(mediaId, R.string.offline_result_invalid_item)
      )
    }
  }

  private fun handleOfflineDownload(mediaId: String, result: Result<Bundle>) {
    val remote = resolveRemoteAudiobook(mediaId)
    val config = DeviceManager.serverConnectionConfig
    val lease = DeviceManager.captureConnectionLease(config)
    if (remote == null) {
      result.sendError(offlineActionResultBundle(mediaId, R.string.offline_result_invalid_item))
      return
    }
    if (config == null || lease == null || config.id != currentBrowseServerConfigId()) {
      result.sendError(
        offlineActionResultBundle(mediaId, R.string.offline_result_sign_in_required)
      )
      return
    }

    runOfflineAction(mediaId, result) { claimMutation, complete ->
      apiHandler.getLibraryItemWithProgress(remote.id, null, config) { expanded ->
        if (!DeviceManager.isConnectionLeaseCurrent(lease)) {
          complete(OfflineDownloadResult.FAILED, OfflinePlanFailure.INVALID_SERVER)
          return@getLibraryItemWithProgress
        }
        val verified = expanded?.takeIf {
          it.id == remote.id && it.mediaType == "book" && it.checkHasTracks()
        }
        if (verified == null) {
          complete(OfflineDownloadResult.FAILED, OfflinePlanFailure.INCOMPLETE_SERVER_METADATA)
        } else if (!claimMutation()) {
          // The AAOS result deadline already won. Never start a hidden late
          // transfer after telling the host that the action failed.
          return@getLibraryItemWithProgress
        } else {
          offlineDownloads.enqueue(verified, config, lease, complete)
        }
      }
    }
  }

  private fun handleOfflineCancel(mediaId: String, result: Result<Bundle>) {
    val remote = resolveRemoteAudiobook(mediaId)
    val config = DeviceManager.serverConnectionConfig
    if (remote == null) {
      result.sendError(offlineActionResultBundle(mediaId, R.string.offline_result_invalid_item))
      return
    }
    if (config == null || config.id != currentBrowseServerConfigId()) {
      result.sendError(
        offlineActionResultBundle(mediaId, R.string.offline_result_sign_in_required)
      )
      return
    }
    runOfflineAction(mediaId, result) { claimMutation, complete ->
      if (!claimMutation()) return@runOfflineAction
      offlineDownloads.cancel(config.id, remote.id, complete)
    }
  }

  private fun handleOfflineRemove(mediaId: String, result: Result<Bundle>) {
    val directLocal = DeviceManager.dbManager.getLocalLibraryItem(mediaId)
      ?.takeIf(offlineDownloads::isManagedLocal)
    val remote = resolveRemoteAudiobook(mediaId)
    val config = DeviceManager.serverConnectionConfig
    val localId = directLocal?.id ?: if (remote != null && config != null) {
      offlineDownloads.managedLocalId(config.id, remote.id)
    } else {
      null
    }
    if (localId == null) {
      result.sendError(offlineActionResultBundle(mediaId, R.string.offline_result_invalid_item))
      return
    }
    runOfflineAction(mediaId, result) { claimMutation, complete ->
      if (!claimMutation()) return@runOfflineAction
      if (currentPlaybackSession?.localLibraryItem?.id == localId) {
        closePlayback(false)
      }
      offlineDownloads.removeLocal(localId, complete)
    }
  }

  /** Complete a detached MediaBrowser action once, even when network or binder callbacks race. */
  private fun runOfflineAction(
    mediaId: String,
    result: Result<Bundle>,
    operation: (
      claimMutation: () -> Boolean,
      complete: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit
    ) -> Unit
  ) {
    result.detach()
    val completed = AtomicBoolean(false)
    val mutationClaimed = AtomicBoolean(false)
    val actionGate = Any()
    lateinit var timeout: Runnable
    val claimMutation = claim@ {
      synchronized(actionGate) {
        if (completed.get()) return@claim false
        mutationClaimed.set(true)
        true
      }
    }
    val complete: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit =
      complete@ { outcome: OfflineDownloadResult, _: OfflinePlanFailure? ->
      mainHandler.post {
        val firstCompletion = synchronized(actionGate) {
          completed.compareAndSet(false, true)
        }
        if (!firstCompletion) return@post
        mainHandler.removeCallbacks(timeout)
        val message = offlineActionMessage(outcome)
        val payload = offlineActionResultBundle(mediaId, message)
        if (outcome == OfflineDownloadResult.FAILED) {
          result.sendError(payload)
        } else {
          result.sendResult(payload)
        }
      }
      Unit
    }
    timeout = Runnable {
      val claimed = synchronized(actionGate) {
        if (!completed.compareAndSet(false, true)) return@Runnable
        mutationClaimed.get()
      }
      if (claimed) {
        result.sendResult(
          offlineActionResultBundle(mediaId, R.string.offline_result_processing)
        )
      } else {
        result.sendError(
          offlineActionResultBundle(mediaId, R.string.offline_result_failed)
        )
      }
    }
    mainHandler.postDelayed(timeout, OFFLINE_ACTION_TIMEOUT_MS)
    try {
      operation(claimMutation, complete)
    } catch (error: Exception) {
      Log.e(tag, "Offline custom action failed (${error.javaClass.simpleName})")
      complete(OfflineDownloadResult.FAILED, OfflinePlanFailure.STORAGE_UNAVAILABLE)
    }
  }

  private fun offlineActionMessage(outcome: OfflineDownloadResult): Int = when (outcome) {
    OfflineDownloadResult.STARTED -> R.string.offline_result_started
    OfflineDownloadResult.ALREADY_ACTIVE -> R.string.offline_result_already_active
    OfflineDownloadResult.ALREADY_DOWNLOADED -> R.string.offline_result_already_downloaded
    OfflineDownloadResult.CANCELED -> R.string.offline_result_canceled
    OfflineDownloadResult.REMOVED -> R.string.offline_result_removed
    OfflineDownloadResult.NOTHING_TO_DO -> R.string.offline_result_nothing_to_do
    OfflineDownloadResult.FAILED -> R.string.offline_result_failed
  }

  private fun offlineActionResultBundle(mediaId: String?, messageRes: Int): Bundle =
    Bundle().apply {
      mediaId?.let {
        putString(MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_REFRESH_ITEM, it)
      }
      putString(
        MediaConstants.EXTRAS_KEY_CUSTOM_BROWSER_ACTION_RESULT_MESSAGE,
        getString(messageRes)
      )
    }

  override fun onSearch(
          query: String,
          extras: Bundle?,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    if (serviceDestroyed) {
      result.sendResult(mutableListOf())
      return
    }
    val callerPackage = currentBrowserPackageName()
    result.detach()
    searchFuture?.cancel(true)
    searchFuture = null
    val requestedConnectionEpoch = DeviceManager.currentConnectionStateEpoch()
    val requestedConfigId = currentBrowseServerConfigId()
    // Search is server-backed. Reject cached and in-flight server titles once
    // the authorizing profile has been removed; local downloads remain
    // available through the Downloads browse node.
    if (requestedConfigId == null) {
      cachedSearch = ""
      cachedSearchResults.clear()
      publishSignInRequiredForPlaybackIfNeeded()
      result.sendResult(mutableListOf())
      return
    }
    if (cachedSearch == query) {
      if (!DeviceManager.isConnectionStateCurrent(
          requestedConnectionEpoch,
          requestedConfigId
        )
      ) {
        cachedSearch = ""
        cachedSearchResults.clear()
        publishSignInRequiredForPlaybackIfNeeded()
        result.sendResult(mutableListOf())
        return
      }
      val cached = withOfflineBrowseActions(cachedSearchResults, callerPackage)
      rememberBrowseItems(
        SEARCH_RESULTS_ROOT,
        cached,
        callerPackage,
        requestedConnectionEpoch,
        requestedConfigId
      )
      grantCoverUriPermissions(cached, callerPackage)
      result.sendResult(cached)
      return
    }

    Log.d(tag, "Search bundle: $extras")
    val generation = ++searchGeneration
    val completed = java.util.concurrent.atomic.AtomicBoolean(false)
    lateinit var timeout: Runnable

    fun complete(items: MutableList<MediaBrowserCompat.MediaItem>) {
      mainHandler.post {
        if (serviceDestroyed || !completed.compareAndSet(false, true)) return@post
        mainHandler.removeCallbacks(timeout)
        if (currentBrowseServerConfigId() != requestedConfigId ||
          !DeviceManager.isConnectionStateCurrent(
            requestedConnectionEpoch,
            requestedConfigId
          )
        ) {
          cachedSearch = ""
          cachedSearchResults.clear()
          publishSignInRequiredForPlaybackIfNeeded()
          result.sendResult(mutableListOf())
          return@post
        }
        val safeItems = try {
          val boundedItems = browseResultTree.replaceChildren(SEARCH_RESULTS_ROOT, items)
          prefetchCoversAndNotify(AUTO_MEDIA_ROOT, boundedItems)
          val actionableItems = withOfflineBrowseActions(boundedItems, callerPackage)
          BrowseArtworkPolicy.sanitize(actionableItems)
        } catch (error: Exception) {
          Log.e(tag, "onSearch result processing failed (${error.javaClass.simpleName})")
          mutableListOf()
        }
        if (generation == searchGeneration) {
          cachedSearch = query
          cachedSearchResults = safeItems.toMutableList()
        }
        rememberBrowseItems(
          SEARCH_RESULTS_ROOT,
          safeItems,
          callerPackage,
          requestedConnectionEpoch,
          requestedConfigId
        )
        grantCoverUriPermissions(safeItems, callerPackage)
        result.sendResult(safeItems)
        Log.d(tag, "onSearch: Done (${safeItems.size} results)")
      }
    }

    timeout = Runnable {
      if (!serviceDestroyed && completed.compareAndSet(false, true)) {
        Log.w(tag, "onSearch: Timed out")
        if (generation == searchGeneration) searchFuture?.cancel(true)
        result.sendResult(mutableListOf())
      }
    }
    mainHandler.postDelayed(timeout, BROWSE_RESULT_TIMEOUT_MS)

    searchFuture = try {
      searchExecutor.submit {
      try {
        val foundBooks = mutableListOf<MediaBrowserCompat.MediaItem>()
        val foundPodcasts = mutableListOf<MediaBrowserCompat.MediaItem>()
        val foundSeries = mutableListOf<MediaBrowserCompat.MediaItem>()
        val foundAuthors = mutableListOf<MediaBrowserCompat.MediaItem>()

        mediaManager.serverLibraries.toList().forEach { serverLibrary ->
          if (Thread.currentThread().isInterrupted || generation != searchGeneration) {
            return@forEach
          }
          if (serverLibrary.stats?.numAudioFiles == 0) return@forEach
          val searchResult = runBlocking {
            mediaManager.doSearch(serverLibrary.id, query)
          }
          searchResult.forEach { (type, items) ->
            when (type) {
              "book" -> foundBooks.addAll(items)
              "podcast" -> foundPodcasts.addAll(items)
              "series" -> foundSeries.addAll(items)
              "authors" -> foundAuthors.addAll(items)
            }
          }
        }

        foundBooks.addAll(foundPodcasts)
        foundBooks.addAll(foundSeries)
        foundBooks.addAll(foundAuthors)
        complete(foundBooks)
      } catch (error: Exception) {
        Log.e(tag, "onSearch failed (${error.javaClass.simpleName})")
        complete(mutableListOf())
      }
      }
    } catch (error: java.util.concurrent.RejectedExecutionException) {
      Log.w(tag, "Search rejected during service teardown")
      complete(mutableListOf())
      null
    }
  }

  //
  // SHAKE SENSOR
  //
  private fun initSensor() {
    // ShakeDetector initialization
    mSensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
    mAccelerometer = mSensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    if (mAccelerometer == null) {
      Log.i(tag, "No accelerometer available; shake-to-reset disabled")
      mShakeDetector = null
      return
    }

    mShakeDetector = ShakeDetector()
    mShakeDetector!!.setOnShakeListener(
            object : ShakeDetector.OnShakeListener {
              override fun onShake(count: Int) {
                Log.d(tag, "PHONE SHAKE! $count")
                sleepTimerManager.handleShake()
              }
            }
    )
  }

  // Shake sensor used for sleep timer
  fun registerSensor() {
    if (isShakeSensorRegistered) {
      Log.i(tag, "Shake sensor already registered")
      return
    }
    val sensorManager = mSensorManager
    val accelerometer = mAccelerometer
    val shakeDetector = mShakeDetector
    if (sensorManager == null || accelerometer == null || shakeDetector == null) {
      Log.i(tag, "Shake sensor unavailable; registration skipped")
      return
    }
    mainHandler.removeCallbacks(shakeSensorUnregisterRunnable)

    Log.d(tag, "Registering shake SENSOR ${mAccelerometer?.isWakeUpSensor}")
    val success =
            sensorManager.registerListener(
                    shakeDetector,
                    accelerometer,
                    SensorManager.SENSOR_DELAY_UI
            )
    if (success) isShakeSensorRegistered = true
  }

  fun unregisterSensor() {
    if (!isShakeSensorRegistered) return

    // Unregister shake sensor after wake up expiration
    mainHandler.removeCallbacks(shakeSensorUnregisterRunnable)
    mainHandler.postDelayed(shakeSensorUnregisterRunnable, SLEEP_TIMER_WAKE_UP_EXPIRATION)
  }

  internal fun unregisterSensorImmediately() {
    mainHandler.removeCallbacks(shakeSensorUnregisterRunnable)
    mSensorManager?.unregisterListener(mShakeDetector)
    isShakeSensorRegistered = false
  }

  private val networkCallback =
          object : ConnectivityManager.NetworkCallback() {
            private fun publishNetworkState(networkCapabilities: NetworkCapabilities?) {
              val nextIsUnmetered =
                      networkCapabilities?.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_NOT_METERED
                      ) == true
              val nextHasConnectivity =
                      networkCapabilities?.hasCapability(
                              NetworkCapabilities.NET_CAPABILITY_VALIDATED
                      ) == true &&
                              networkCapabilities.hasCapability(
                                      NetworkCapabilities.NET_CAPABILITY_INTERNET
                              )

              postToMainIfAlive {
                isUnmeteredNetwork = nextIsUnmetered
                hasNetworkConnectivity = nextHasConnectivity
                Log.i(
                        tag,
                        "Network state changed. hasNetworkConnectivity=$hasNetworkConnectivity | isUnmeteredNetwork=$isUnmeteredNetwork"
                )
                clientEventEmitter?.onNetworkMeteredChanged(isUnmeteredNetwork)
                if (hasNetworkConnectivity) {
                  // A car commonly starts on metered cellular before its active
                  // network is ready. Retry an empty/failed root as soon as any
                  // validated connection arrives, even if the first tree never
                  // initialized.
                  if ((DeviceManager.serverConnectionConfig != null ||
                                    DeviceManager.getLastServerConnectionConfig() != null) &&
                                  !lastRootLoadSucceeded
                  ) {
                    forceReloadingAndroidAuto = true
                    notifyChildrenChanged(AUTO_MEDIA_ROOT)
                  }
                }
              }
            }

            // Network capabilities have changed for the network
            override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
            ) {
              super.onCapabilitiesChanged(network, networkCapabilities)
              publishNetworkState(networkCapabilities)
            }

            override fun onLost(network: Network) {
              super.onLost(network)
              // A request may match more than one network. Re-evaluate the
              // current default rather than blindly reporting offline when a
              // secondary network disappears.
              val connectivityManager =
                      getSystemService(ConnectivityManager::class.java) as ConnectivityManager
              publishNetworkState(
                      connectivityManager.activeNetwork?.let(
                              connectivityManager::getNetworkCapabilities
                      )
              )
            }
          }

  inner class JumpBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_BACKWARD,
                      getContext().getString(R.string.action_jump_backward),
                      R.drawable.exo_icon_rewind
              )
              .build()
    }
  }

  inner class JumpForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_JUMP_FORWARD,
                      getContext().getString(R.string.action_jump_forward),
                      R.drawable.exo_icon_fastforward
              )
              .build()
    }
  }

  inner class SkipForwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_FORWARD,
                      getContext().getString(R.string.action_skip_forward),
                      R.drawable.skip_next_24
              )
              .build()
    }
  }

  inner class SkipBackwardCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_SKIP_BACKWARD,
                      getContext().getString(R.string.action_skip_backward),
                      R.drawable.skip_previous_24
              )
              .build()
    }
  }

  inner class ChangePlaybackSpeedCustomActionProvider : CustomActionProvider {
    override fun onCustomAction(player: Player, action: String, extras: Bundle?) {
      /*
      This does not appear to ever get called. Instead, MediaSessionCallback.onCustomAction() is
      responsible to reacting to a custom action.
       */
    }

    override fun getCustomAction(player: Player): PlaybackStateCompat.CustomAction? {
      val playbackRate = mediaManager.getSavedPlaybackRate()

      // Rounding values in the event a non preset value (.5, 1, 1.2, 1.5, 2, 3) is selected in the
      // phone app
      val drawable: Int =
              when (playbackRate) {
                in 0.5f..0.7f -> R.drawable.ic_play_speed_0_5x
                in 0.8f..1.0f -> R.drawable.ic_play_speed_1_0x
                in 1.1f..1.3f -> R.drawable.ic_play_speed_1_2x
                in 1.4f..1.7f -> R.drawable.ic_play_speed_1_5x
                in 1.8f..2.4f -> R.drawable.ic_play_speed_2_0x
                in 2.5f..3.0f -> R.drawable.ic_play_speed_3_0x
                // anything set above 3 will be show the 3x to save from creating 100 icons
                else -> R.drawable.ic_play_speed_3_0x
              }
      val customActionExtras = Bundle()
      customActionExtras.putFloat("speed", playbackRate)
      return PlaybackStateCompat.CustomAction.Builder(
                      CUSTOM_ACTION_CHANGE_SPEED,
                      getContext().getString(R.string.action_change_speed),
                      drawable
              )
              .setExtras(customActionExtras)
              .build()
    }
  }
}
