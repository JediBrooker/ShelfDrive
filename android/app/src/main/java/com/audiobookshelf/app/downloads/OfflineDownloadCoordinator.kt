package com.audiobookshelf.app.downloads

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.LibraryItem
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.FolderScanner
import com.audiobookshelf.app.managers.DbManager
import com.audiobookshelf.app.models.DownloadItem
import com.audiobookshelf.app.models.DownloadItemPart
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID

internal enum class OfflineDownloadState {
  NONE,
  ACTIVE,
  FAILED,
  DOWNLOADED
}

internal enum class OfflineDownloadResult {
  STARTED,
  ALREADY_ACTIVE,
  ALREADY_DOWNLOADED,
  CANCELED,
  REMOVED,
  NOTHING_TO_DO,
  FAILED
}

internal data class OfflineStorageSummary(
  val downloadedBooks: Int,
  val pendingBooks: Int,
  val bytes: Long
)

internal data class OfflineBrowseSnapshot(
  val connectionId: String?,
  val statesByLibraryItemId: Map<String, OfflineDownloadState>,
  val managedLocalIds: Set<String>
) {
  fun state(libraryItemId: String): OfflineDownloadState {
    if (connectionId != null && OfflineDownloadPlanner.localLibraryItemId(
        connectionId,
        libraryItemId
      ) in managedLocalIds
    ) {
      return OfflineDownloadState.DOWNLOADED
    }
    return statesByLibraryItemId[libraryItemId] ?: OfflineDownloadState.NONE
  }
}

/**
 * Durable AAOS download orchestration around Android's system DownloadManager.
 * The platform owns retry/connectivity/reboot behavior. ShelfDrive persists
 * each platform ID, validates the resulting .part file, then atomically
 * publishes it before exposing a LocalLibraryItem to playback.
 */
internal class OfflineDownloadCoordinator(
  context: Context,
  private val directDownloadVerifier: ((DownloadItemPart, ServerConnectionConfig) -> Boolean)? = null
) {
  private val appContext = context.applicationContext
  private val mainHandler = Handler(Looper.getMainLooper())
  private val preflightClient = OkHttpClient.Builder()
    .callTimeout(5, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

  init {
    // A manifest receiver can be the first component created in a fresh
    // process after DownloadManager finishes work.
    DbManager.initialize(appContext)
  }

  fun enqueue(
    libraryItem: LibraryItem,
    owner: ServerConnectionConfig,
    ownerLease: ConnectionLease,
    callback: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit
  ) {
    val pendingAttempt = when (val admission = admitPending(ownerLease, libraryItem.id)) {
      is PendingAdmission.Accepted -> admission.attempt
      PendingAdmission.Duplicate -> {
        mainHandler.post { callback(OfflineDownloadResult.ALREADY_ACTIVE, null) }
        return
      }
      PendingAdmission.Full -> {
        mainHandler.post {
          callback(OfflineDownloadResult.FAILED, OfflinePlanFailure.CAPACITY_EXCEEDED)
        }
        return
      }
    }
    execute(
      callback,
      TRANSFER_EXECUTOR,
      onFinished = {
        releasePending(pendingAttempt)
        broadcastChanged(libraryItem.id)
      }
    ) {
      // Persist a null-platform-ID ownership plan in a short transaction. The
      // authenticated network probes below deliberately run outside this
      // global account/persistence monitor so a slow multi-file book cannot
      // block Disconnect, checkpoints, or Settings for minutes.
      val preparation = synchronized(DeviceManager.connectionPersistenceMonitor) {
        if (pendingAttempt.canceled ||
          DeviceManager.getServerConnectionConfig(ownerLease) !== owner ||
          !DeviceManager.isConnectionLeaseCurrent(ownerLease)
        ) {
          return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.INVALID_SERVER
        }
        val db = DeviceManager.dbManager
        if (managedLocalFor(owner.id, libraryItem.id) != null) {
          return@execute OfflineDownloadResult.ALREADY_DOWNLOADED to null
        }
      // A prior storage loss can leave a deterministic local row pointing at
      // an incomplete managed folder. Remove only that unusable managed copy
      // before retrying so existing final filenames cannot poison the plan.
      val staleManagedLocal = db.getLocalLibraryItem(
        OfflineDownloadPlanner.localLibraryItemId(owner.id, libraryItem.id)
      )?.takeIf { isManagedLocal(it) && !it.hasTracks(null) }
      if (staleManagedLocal != null && !removeManagedLocal(staleManagedLocal)) {
        return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      }
      val existing = managedJobs().firstOrNull {
        it.serverConnectionConfigId == owner.id && it.libraryItemId == libraryItem.id
      }
      if (existing != null && existing.downloadItemParts.none(DownloadItemPart::failed)) {
        return@execute OfflineDownloadResult.ALREADY_ACTIVE to null
      }
      if (existing != null && !removeJob(existing, removePlatformDownloads = true)) {
        return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      }

        when (val plan = OfflineDownloadPlanner.plan(
          appContext,
          libraryItem,
          owner,
          UUID.randomUUID().toString()
        )) {
        is OfflinePlanResult.Rejected ->
          return@execute OfflineDownloadResult.FAILED to plan.reason
        is OfflinePlanResult.Ready -> {
          val item = plan.item
          val existingJobs = managedJobs()
          val outstandingParts = existingJobs.sumOf { it.downloadItemParts.size.toLong() }
          if (existingJobs.size >= MAX_PENDING_BOOKS ||
            outstandingParts + item.downloadItemParts.size > MAX_PENDING_PARTS
          ) {
            return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.CAPACITY_EXCEEDED
          }
          val outstandingBytes = existingJobs.asSequence()
            .flatMap { it.downloadItemParts.asSequence() }
            .filterNot { it.completed || it.failed }
            .fold(0L) { total, part ->
              val remaining = (part.fileSize - part.bytesDownloaded).coerceAtLeast(0L)
              if (Long.MAX_VALUE - total < remaining) Long.MAX_VALUE else total + remaining
            }
          val reservedBytes = if (Long.MAX_VALUE - outstandingBytes < plan.expectedBytes) {
            Long.MAX_VALUE
          } else {
            outstandingBytes + plan.expectedBytes
          }
          if (!OfflineDownloadPlanner.hasStorageFor(appContext, reservedBytes)) {
            return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.INSUFFICIENT_STORAGE
          }
          val folder = File(item.itemFolderPath)
          if (!folder.mkdirs() && !folder.isDirectory) {
            return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
          }
          db.saveLocalFolder(item.localFolder)
          db.saveDownloadItem(item)
          val downloadManager = systemDownloadManager()
            ?: run {
              removeJob(item, removePlatformDownloads = false)
              return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
            }
          val allowedOverMetered =
            DeviceManager.deviceData.deviceSettings?.downloadUsingCellular?.name == "ALWAYS"
          Triple(item, downloadManager, allowedOverMetered)
        }
        }
      }

      val (item, downloadManager, allowedOverMetered) = preparation
      try {
        item.downloadItemParts.forEach { part ->
          // DownloadManager can replay Authorization across redirects. Probe
          // every exact GET endpoint with redirects disabled. No platform row
          // is created until all probes pass, so a fast earlier part cannot be
          // reconciled against a half-registered multi-part plan.
          val planStillOwned = synchronized(DeviceManager.connectionPersistenceMonitor) {
            !pendingAttempt.canceled &&
              DeviceManager.getServerConnectionConfig(ownerLease) === owner &&
              DeviceManager.isConnectionLeaseCurrent(ownerLease) &&
              managedJobs().any { it.id == item.id }
          }
          if (!planStillOwned) {
            throw SecurityException("Download owner is no longer current")
          }
          val directResponse = directDownloadVerifier?.invoke(part, owner)
            ?: verifyDirectDownload(part, owner)
          if (!directResponse) {
            synchronized(DeviceManager.connectionPersistenceMonitor) {
              removeJob(item, removePlatformDownloads = true, downloadManager)
            }
            return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.INVALID_SERVER
          }
        }

        synchronized(DeviceManager.connectionPersistenceMonitor) {
          // Register all already-probed parts as one bounded transaction. A
          // completion broadcast may arrive during this loop, but reconciliation
          // cannot enter the monitor until every ID is durably saved.
          val planStillOwned = managedJobs().any { it.id == item.id }
          if (pendingAttempt.canceled ||
            DeviceManager.getServerConnectionConfig(ownerLease) !== owner ||
            !DeviceManager.isConnectionLeaseCurrent(ownerLease) || !planStillOwned
          ) {
            throw SecurityException("Download owner is no longer current")
          }
          item.downloadItemParts.forEachIndexed { index, part ->
            prepareDestination(part)
            val request = buildRequest(
              part,
              owner,
              item.itemTitle,
              index + 1,
              item.downloadItemParts.size,
              allowedOverMetered
            )
            val platformId = downloadManager.enqueue(request)
            part.downloadId = platformId
          }
          // The null-ID plan was persisted before enqueue. A process death
          // before this single bounded write is recovered by exact source and
          // destination matching, avoiding O(parts²) serialization.
          DeviceManager.dbManager.saveDownloadItem(item)
        }
      } catch (error: RuntimeException) {
        val cleaned = synchronized(DeviceManager.connectionPersistenceMonitor) {
          removeJob(item, removePlatformDownloads = true, downloadManager)
        }
        val reason = if (error is SecurityException && cleaned) {
          OfflinePlanFailure.INVALID_SERVER
        } else {
          OfflinePlanFailure.STORAGE_UNAVAILABLE
        }
        return@execute OfflineDownloadResult.FAILED to reason
      }
      broadcastChanged(item.libraryItemId)
      OfflineDownloadResult.STARTED to null
    }
    broadcastChanged(libraryItem.id)
  }

  fun reconcile(callback: ((OfflineDownloadResult, OfflinePlanFailure?) -> Unit)? = null) {
    execute(callback) {
      synchronized(DeviceManager.connectionPersistenceMonitor) {
        val downloadManager = systemDownloadManager()
          ?: return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
        reconcileJobs(downloadManager, managedJobs(), onlyPlatformId = null)
      }
    }
  }

  /** Completion-broadcast path: touch only the exact platform row that fired. */
  fun reconcile(
    platformDownloadId: Long,
    callback: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit
  ) {
    if (platformDownloadId <= 0L) {
      callback(OfflineDownloadResult.NOTHING_TO_DO, null)
      return
    }
    execute(callback) {
      synchronized(DeviceManager.connectionPersistenceMonitor) {
        val downloadManager = systemDownloadManager()
          ?: return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
        val matchingJobs = managedJobs().filter { item ->
          item.downloadItemParts.any { part ->
            part.downloadId == platformDownloadId ||
              (part.downloadId == null &&
                platformDownloadMatches(downloadManager, platformDownloadId, part))
          }
        }
        reconcileJobs(downloadManager, matchingJobs, platformDownloadId)
      }
    }
  }

  private fun reconcileJobs(
    downloadManager: DownloadManager,
    jobs: List<DownloadItem>,
    onlyPlatformId: Long?
  ): Pair<OfflineDownloadResult, OfflinePlanFailure?> {
    var changedAny = false
    var failedAny = false
    jobs.forEach { item ->
      // Startup can precede retryPendingPurges after a process restart. A job
      // whose owner is already gone must be canceled, never indexed back into
      // the local library with resurrected server identity.
      if (!DeviceManager.isServerConnectionConfigSaved(item.serverConnectionConfigId)) {
        if (!removeJob(item, removePlatformDownloads = true, downloadManager)) {
          failedAny = true
        }
        changedAny = true
        broadcastChanged(item.libraryItemId)
        return@forEach
      }

      var itemChanged = false
      item.downloadItemParts.forEach { part ->
        if (part.completed || part.failed) return@forEach
        var platformId = part.downloadId
        if (onlyPlatformId != null) {
          if (platformId == null &&
            platformDownloadMatches(downloadManager, onlyPlatformId, part)
          ) {
            platformId = onlyPlatformId
            part.downloadId = onlyPlatformId
            itemChanged = true
          }
          if (platformId != onlyPlatformId) return@forEach
        } else if (platformId == null) {
          when (val recovery = recoverPlatformIdStatus(downloadManager, part)) {
            is PlatformIdRecovery.Found -> {
              platformId = recovery.id
              part.downloadId = recovery.id
              itemChanged = true
            }
            PlatformIdRecovery.Missing -> {
              if (markPublished(part, part.fileSize)) {
                itemChanged = true
              } else {
                part.failed = true
                itemChanged = true
                failedAny = true
              }
              return@forEach
            }
            PlatformIdRecovery.Unavailable -> {
              failedAny = true
              return@forEach
            }
          }
        }
        val ownedId = platformId ?: return@forEach
        when (val status = query(downloadManager, ownedId)) {
          is PlatformDownloadStatus.Active -> {
            val progress = if (status.totalBytes > 0L) {
              ((status.downloadedBytes.coerceAtLeast(0L) * 100L) / status.totalBytes)
                .coerceIn(0L, 100L)
            } else {
              0L
            }
            if (part.bytesDownloaded != status.downloadedBytes || part.progress != progress) {
              part.bytesDownloaded = status.downloadedBytes.coerceAtLeast(0L)
              part.progress = progress
              itemChanged = true
            }
          }
          is PlatformDownloadStatus.Success -> {
            if (!markPublished(part, status.totalBytes, status.mimeType)) {
              part.failed = true
              failedAny = true
            }
            itemChanged = true
          }
          PlatformDownloadStatus.Missing -> {
            // Defensive recovery for v131 builds that may have removed the
            // completed DownloadManager row before Paper persisted the rename.
            if (markPublished(part, part.fileSize)) {
              part.downloadId = null
            } else {
              part.failed = true
              part.downloadId = null
              failedAny = true
            }
            itemChanged = true
          }
          PlatformDownloadStatus.Failed -> {
            part.failed = true
            itemChanged = true
            failedAny = true
          }
          PlatformDownloadStatus.Unavailable -> failedAny = true
        }
      }

      // Publication state is durable before the system row is removed. A
      // crash in either direction leaves enough ownership to recover safely.
      if (itemChanged) {
        DeviceManager.dbManager.saveDownloadItem(item)
        changedAny = true
      }

      var cleanupChanged = false
      item.downloadItemParts.forEach { part ->
        val platformId = part.downloadId ?: return@forEach
        if (!part.completed || (onlyPlatformId != null && platformId != onlyPlatformId)) {
          return@forEach
        }
        if (removePlatformRow(downloadManager, platformId)) {
          part.downloadId = null
          cleanupChanged = true
        } else {
          failedAny = true
        }
      }
      if (cleanupChanged) {
        DeviceManager.dbManager.saveDownloadItem(item)
        changedAny = true
      }

      if (item.downloadItemParts.isNotEmpty() &&
        item.downloadItemParts.all {
          it.completed && it.moved && !it.failed && it.downloadId == null
        }
      ) {
        if (indexCompletedItem(item)) {
          DeviceManager.dbManager.removeDownloadItem(item.id)
        } else {
          item.downloadItemParts.first().failed = true
          DeviceManager.dbManager.saveDownloadItem(item)
          failedAny = true
        }
        changedAny = true
      }
      if (itemChanged || cleanupChanged) broadcastChanged(item.libraryItemId)
    }
    return when {
      failedAny -> OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      changedAny -> OfflineDownloadResult.STARTED to null
      else -> OfflineDownloadResult.NOTHING_TO_DO to null
    }
  }

  private fun markPublished(
    part: DownloadItemPart,
    expectedBytes: Long,
    responseMimeType: String? = null
  ): Boolean {
    if (!publishPart(part, expectedBytes, responseMimeType)) return false
    part.completed = true
    part.moved = true
    part.progress = 100L
    part.bytesDownloaded = File(part.finalDestinationPath).length()
    return true
  }

  fun cancel(
    connectionId: String,
    libraryItemId: String,
    callback: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit
  ) {
    val pendingCanceled = cancelPending(connectionId, libraryItemId)
    execute(callback) {
      synchronized(DeviceManager.connectionPersistenceMonitor) {
      val jobs = managedJobs().filter {
        it.serverConnectionConfigId == connectionId && it.libraryItemId == libraryItemId
      }
      if (jobs.isEmpty()) {
        return@execute if (pendingCanceled) {
          OfflineDownloadResult.CANCELED to null
        } else {
          OfflineDownloadResult.NOTHING_TO_DO to null
        }
      }
      val allRemoved = jobs.map {
        removeJob(it, removePlatformDownloads = true)
      }.all { it }
      broadcastChanged(libraryItemId)
      if (allRemoved) OfflineDownloadResult.CANCELED to null
      else OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      }
    }
  }

  fun cancelAll(callback: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit) {
    val pendingCanceled = cancelAllPending()
    execute(callback) {
      synchronized(DeviceManager.connectionPersistenceMonitor) {
      val jobs = managedJobs()
      if (jobs.isEmpty()) {
        return@execute if (pendingCanceled) {
          OfflineDownloadResult.CANCELED to null
        } else {
          OfflineDownloadResult.NOTHING_TO_DO to null
        }
      }
      val allRemoved = jobs.map {
        removeJob(it, removePlatformDownloads = true)
      }.all { it }
      broadcastChanged(null)
      if (allRemoved) OfflineDownloadResult.CANCELED to null
      else OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      }
    }
  }

  fun removeLocal(
    localLibraryItemId: String,
    callback: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit
  ) {
    execute(callback) {
      synchronized(DeviceManager.connectionPersistenceMonitor) {
      val local = DeviceManager.dbManager.getLocalLibraryItem(localLibraryItemId)
        ?: return@execute OfflineDownloadResult.NOTHING_TO_DO to null
      if (!removeManagedLocal(local)) {
        return@execute OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      }
      broadcastChanged(local.libraryItemId)
      OfflineDownloadResult.REMOVED to null
      }
    }
  }

  fun removeAll(callback: (OfflineDownloadResult, OfflinePlanFailure?) -> Unit) {
    execute(callback) {
      synchronized(DeviceManager.connectionPersistenceMonitor) {
      val locals = managedLocalItems()
      if (locals.isEmpty()) return@execute OfflineDownloadResult.NOTHING_TO_DO to null
      val allRemoved = locals.map(::removeManagedLocal).all { it }
      broadcastChanged(null)
      if (allRemoved) OfflineDownloadResult.REMOVED to null
      else OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
      }
    }
  }

  fun state(connectionId: String, libraryItemId: String): OfflineDownloadState {
    if (managedLocalFor(connectionId, libraryItemId) != null) {
      return OfflineDownloadState.DOWNLOADED
    }
    if (hasPending(connectionId, libraryItemId)) return OfflineDownloadState.ACTIVE
    val job = managedJobs().firstOrNull {
      it.serverConnectionConfigId == connectionId && it.libraryItemId == libraryItemId
    } ?: return OfflineDownloadState.NONE
    return if (job.downloadItemParts.any(DownloadItemPart::failed)) {
      OfflineDownloadState.FAILED
    } else {
      OfflineDownloadState.ACTIVE
    }
  }

  fun managedLocalId(connectionId: String, libraryItemId: String): String? =
    managedLocalFor(connectionId, libraryItemId)?.id

  /** One bounded DB/filesystem pass for decorating an entire AAOS browse page. */
  fun browseSnapshot(connectionId: String?): OfflineBrowseSnapshot =
    synchronized(DeviceManager.connectionPersistenceMonitor) {
      val playableLocals = managedLocalItems().filter { it.hasTracks(null) }
      val managedIds = playableLocals.map(LocalLibraryItem::id).toSet()
      val states = mutableMapOf<String, OfflineDownloadState>()
      if (connectionId != null) {
        playableLocals.forEach { local ->
          if (local.serverConnectionConfigId == connectionId) {
            local.libraryItemId?.let { states[it] = OfflineDownloadState.DOWNLOADED }
          }
        }
        managedJobs()
          .filter { it.serverConnectionConfigId == connectionId }
          .forEach { job ->
            states.putIfAbsent(
              job.libraryItemId,
              if (job.downloadItemParts.any(DownloadItemPart::failed)) {
                OfflineDownloadState.FAILED
              } else {
                OfflineDownloadState.ACTIVE
              }
            )
          }
        pendingItems(connectionId).forEach { libraryItemId ->
          states.putIfAbsent(libraryItemId, OfflineDownloadState.ACTIVE)
        }
      }
      OfflineBrowseSnapshot(connectionId, states, managedIds)
    }

  fun isManagedLocal(local: LocalLibraryItem): Boolean =
    local.mediaType == "book" &&
      local.folderId == OfflineDownloadPlanner.MANAGED_FOLDER_ID &&
      OfflineDownloadPlanner.isManagedItemPath(appContext, local.absolutePath)

  fun summary(): OfflineStorageSummary {
    val locals = managedLocalItems()
    val bytes = locals.fold(0L) { total, local ->
      val itemBytes = directorySize(File(local.absolutePath))
      if (Long.MAX_VALUE - total < itemBytes) Long.MAX_VALUE else total + itemBytes
    }
    val activeBooks = managedJobs()
      .map { it.serverConnectionConfigId to it.libraryItemId }
      .toMutableSet()
      .apply { addAll(pendingBookKeys()) }
    return OfflineStorageSummary(locals.size, activeBooks.size, bytes)
  }

  private fun buildRequest(
    part: DownloadItemPart,
    owner: ServerConnectionConfig,
    title: String,
    partNumber: Int,
    partCount: Int,
    allowedOverMetered: Boolean
  ): DownloadManager.Request {
    require(owner.token.isNotBlank())
    require(isOwnedRemoteUrl(part.uri, owner))
    require(isManagedDestination(part.destinationUri))
    return DownloadManager.Request(part.uri)
      .setTitle(title)
      .setDescription("Saving for offline listening ($partNumber of $partCount)")
      .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
      .setAllowedOverMetered(allowedOverMetered)
      .setAllowedOverRoaming(false)
      .setDestinationUri(part.destinationUri)
      .addRequestHeader("Accept-Encoding", "identity")
      .addRequestHeader("Authorization", "Bearer ${owner.token}")
      .also { request ->
        owner.customHeaders.orEmpty().forEach { (name, value) ->
          if (isSafeCustomHeader(name, value)) {
            runCatching { request.addRequestHeader(name, value) }
          }
        }
      }
  }

  /**
   * DownloadManager replays request headers while following redirects. Probe
   * the authenticated endpoint with redirects disabled and refuse platform
   * handoff unless the configured origin serves media directly.
   */
  private fun verifyDirectDownload(
    part: DownloadItemPart,
    owner: ServerConnectionConfig
  ): Boolean {
    if (owner.token.isBlank() || !isOwnedRemoteUrl(part.uri, owner)) return false
    fun requestBuilder() = Request.Builder()
        .url(part.uri.toString())
        .header("Accept-Encoding", "identity")
        .header("Authorization", "Bearer ${owner.token}")
        .also { builder ->
          owner.customHeaders.orEmpty().forEach { (name, value) ->
            if (isSafeCustomHeader(name, value)) builder.addHeader(name, value)
          }
        }
    return runCatching {
      // The platform performs GET, so HEAD is not sufficient: a server can
      // answer HEAD directly yet redirect GET and receive a replayed bearer.
      preflightClient.newCall(
        requestBuilder().get().header("Range", "bytes=0-0").build()
      ).execute().use { response ->
        response.code in 200..299 && response.header("Location") == null &&
          isAllowedDownloadMime(response.header("Content-Type"))
      }
    }.getOrDefault(false)
  }

  private fun isSafeCustomHeader(name: String, value: String): Boolean {
    // DownloadManager replays headers across redirects, so never hand it an
    // arbitrary custom credential. The bearer is unavoidable for the ABS
    // endpoint and is guarded by the no-redirect preflight above.
    val allowed = setOf("accept", "accept-language", "user-agent")
    return name.isNotBlank() && name.lowercase() in allowed &&
      name.none { it <= ' ' || it == ':' || it.code >= 127 } &&
      value.none { it == '\r' || it == '\n' }
  }

  private fun isOwnedRemoteUrl(uri: Uri, owner: ServerConnectionConfig): Boolean {
    val requestUrl = uri.toString().toHttpUrlOrNull() ?: return false
    val ownerUrl = owner.address.toHttpUrlOrNull() ?: return false
    val ownerPrefix = ownerUrl.encodedPath.trimEnd('/') + "/api/items/"
    return requestUrl.scheme == "https" &&
      requestUrl.scheme == ownerUrl.scheme && requestUrl.host == ownerUrl.host &&
      requestUrl.port == ownerUrl.port && requestUrl.username.isBlank() &&
      requestUrl.password.isBlank() && requestUrl.query == null && requestUrl.fragment == null &&
      requestUrl.encodedPath.startsWith(ownerPrefix)
  }

  private fun prepareDestination(part: DownloadItemPart) {
    require(isManagedDestination(part.destinationUri))
    require(OfflineDownloadPlanner.isManagedItemPath(appContext, part.finalDestinationPath))
    val partial = File(requireNotNull(part.destinationUri.path))
    val final = File(part.finalDestinationPath)
    require(!final.exists())
    partial.parentFile?.let { parent -> require(parent.mkdirs() || parent.isDirectory) }
    if (partial.exists()) require(partial.delete())
  }

  private fun publishPart(part: DownloadItemPart, platformTotalBytes: Long): Boolean =
    publishPart(part, platformTotalBytes, null)

  private fun publishPart(
    part: DownloadItemPart,
    platformTotalBytes: Long,
    responseMimeType: String?
  ): Boolean {
    if (!isManagedDestination(part.destinationUri) ||
      !OfflineDownloadPlanner.isManagedItemPath(appContext, part.finalDestinationPath)
    ) return false
    val partial = part.destinationUri.path?.let(::File) ?: return false
    val final = File(part.finalDestinationPath)
    // The process can die after the atomic rename but before Paper records the
    // completed bit. Treat an intact final-only file as already published.
    if (final.isFile && !partial.exists()) {
      val finalBytes = final.length()
      return finalBytes > 0L &&
        (part.fileSize <= 0L || finalBytes == part.fileSize) &&
        (platformTotalBytes <= 0L || finalBytes == platformTotalBytes) &&
        isPlausibleAudio(final, responseMimeType)
    }
    val actualBytes = partial.length()
    if (!partial.isFile || actualBytes <= 0L || final.exists()) return false
    if (part.fileSize > 0L && actualBytes != part.fileSize) return false
    if (platformTotalBytes > 0L && actualBytes != platformTotalBytes) return false
    if (!isPlausibleAudio(partial, responseMimeType)) return false
    return runCatching {
      FileOutputStream(partial, true).use { stream -> stream.fd.sync() }
      try {
        Files.move(partial.toPath(), final.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (_: AtomicMoveNotSupportedException) {
        Files.move(partial.toPath(), final.toPath())
      }
      final.isFile && final.length() == actualBytes
    }.getOrDefault(false)
  }

  /** Reject HTML/JSON error bodies and files with no recognizable audio container. */
  private fun isPlausibleAudio(file: File, responseMimeType: String?): Boolean {
    if (!isAllowedDownloadMime(responseMimeType)) return false
    val header = ByteArray(64)
    val count = runCatching {
      FileInputStream(file).use { it.read(header) }
    }.getOrDefault(-1)
    if (count < 4) return false
    fun ascii(offset: Int, value: String): Boolean =
      offset >= 0 && offset + value.length <= count && value.indices.all { index ->
        header[offset + index].toInt() and 0xff == value[index].code
      }
    val mp3 = ascii(0, "ID3") ||
      ((header[0].toInt() and 0xff) == 0xff && (header[1].toInt() and 0xe0) == 0xe0)
    val isoBmff = (4 until (count - 3)).any { offset -> ascii(offset, "ftyp") }
    val wav = ascii(0, "RIFF") && ascii(8, "WAVE")
    val aiff = ascii(0, "FORM") && (ascii(8, "AIFF") || ascii(8, "AIFC"))
    val ebml = count >= 4 && header.take(4).map { it.toInt() and 0xff } ==
      listOf(0x1a, 0x45, 0xdf, 0xa3)
    val asf = count >= 16 && header.take(16).map { it.toInt() and 0xff } == listOf(
      0x30, 0x26, 0xb2, 0x75, 0x8e, 0x66, 0xcf, 0x11,
      0xa6, 0xd9, 0x00, 0xaa, 0x00, 0x62, 0xce, 0x6c
    )
    return mp3 || isoBmff || wav || aiff || ebml || asf ||
      ascii(0, "OggS") || ascii(0, "fLaC")
  }

  private fun isAllowedDownloadMime(rawMimeType: String?): Boolean {
    val mime = rawMimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
    return mime.isEmpty() || mime.startsWith("audio/") || mime in setOf(
      "application/octet-stream",
      "binary/octet-stream",
      "application/x-download",
      "application/force-download"
    )
  }

  private fun indexCompletedItem(item: DownloadItem): Boolean {
    var indexed = false
    FolderScanner(appContext).scanDownloadItem(item) { scanResult ->
      val local = scanResult?.localLibraryItem
      indexed = local != null && local.hasTracks(null)
    }
    return indexed
  }

  internal fun removeManagedJobsForConnection(connectionId: String): Boolean {
    cancelPendingForConnection(connectionId)
    return managedJobs()
      .filter { it.serverConnectionConfigId == connectionId }
      .map { removeJob(it, removePlatformDownloads = true) }
      .all { it }
  }

  private fun removeJob(
    item: DownloadItem,
    removePlatformDownloads: Boolean,
    providedDownloadManager: DownloadManager? = null
  ): Boolean {
    if (!OfflineDownloadPlanner.isManagedJob(item)) return false
    var ownershipChanged = false
    if (removePlatformDownloads) {
      val downloadManager = providedDownloadManager ?: systemDownloadManager()
      if (downloadManager == null) {
        markCancellationFailed(item)
        return false
      }
      for (part in item.downloadItemParts) {
        var platformId = part.downloadId
        if (platformId == null) {
          when (val recovery = recoverPlatformIdStatus(downloadManager, part)) {
            is PlatformIdRecovery.Found -> {
              platformId = recovery.id
              part.downloadId = recovery.id
              ownershipChanged = true
            }
            PlatformIdRecovery.Missing -> Unit
            PlatformIdRecovery.Unavailable -> {
              if (ownershipChanged) DeviceManager.dbManager.saveDownloadItem(item)
              markCancellationFailed(item)
              return false
            }
          }
        }
        if (platformId != null) {
          if (!removePlatformRow(downloadManager, platformId)) {
            if (ownershipChanged) DeviceManager.dbManager.saveDownloadItem(item)
            markCancellationFailed(item)
            return false
          }
          part.downloadId = null
          ownershipChanged = true
        }
      }
    }
    if (ownershipChanged) DeviceManager.dbManager.saveDownloadItem(item)
    // Never delete a destination while an unconfirmed platform writer may
    // still hold it. The durable job remains available for a later retry.
    if (!deleteManagedDirectory(File(item.itemFolderPath))) {
      markCancellationFailed(item)
      return false
    }
    DeviceManager.dbManager.removeDownloadItem(item.id)
    return true
  }

  private fun markCancellationFailed(item: DownloadItem) {
    item.downloadItemParts.filterNot(DownloadItemPart::completed).forEach { it.failed = true }
    DeviceManager.dbManager.saveDownloadItem(item)
  }

  private fun removeManagedLocal(local: LocalLibraryItem): Boolean {
    if (!isManagedLocal(local)) return false
    if (!deleteManagedDirectory(File(local.absolutePath))) return false
    DeviceManager.dbManager.removeLocalMediaProgress(local.id)
    DeviceManager.dbManager.removeLocalLibraryItem(local.id)
    return true
  }

  private fun managedJobs(): List<DownloadItem> =
    DeviceManager.dbManager.getDownloadItems().filter(OfflineDownloadPlanner::isManagedJob)

  private fun managedLocalItems(): List<LocalLibraryItem> =
    DeviceManager.dbManager.getLocalLibraryItems("book").filter(::isManagedLocal)

  private fun managedLocalFor(connectionId: String, libraryItemId: String): LocalLibraryItem? =
    managedLocalItems().firstOrNull {
      it.serverConnectionConfigId == connectionId && it.libraryItemId == libraryItemId &&
        it.hasTracks(null)
    } ?: DeviceManager.dbManager.getLocalLibraryItem(
      OfflineDownloadPlanner.localLibraryItemId(connectionId, libraryItemId)
    )?.takeIf { isManagedLocal(it) && it.hasTracks(null) }

  private fun isManagedDestination(uri: Uri): Boolean =
    uri.scheme == "file" && uri.path?.let {
      OfflineDownloadPlanner.isManagedItemPath(appContext, it)
    } == true

  private fun deleteManagedDirectory(directory: File): Boolean {
    return OfflineDownloadPlanner.deleteManagedItemDirectory(
      appContext,
      directory.absolutePath
    )
  }

  private fun directorySize(directory: File): Long {
    if (!OfflineDownloadPlanner.isManagedItemPath(appContext, directory.absolutePath) ||
      !directory.exists()
    ) return 0L
    var total = 0L
    runCatching {
      Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
          val size = attrs.size().coerceAtLeast(0L)
          total = if (Long.MAX_VALUE - total < size) Long.MAX_VALUE else total + size
          return FileVisitResult.CONTINUE
        }
      })
    }
    return total
  }

  private fun systemDownloadManager(): DownloadManager? =
    appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

  private fun query(downloadManager: DownloadManager, id: Long): PlatformDownloadStatus {
    val cursor = try {
      downloadManager.query(DownloadManager.Query().setFilterById(id))
    } catch (_: RuntimeException) {
      return PlatformDownloadStatus.Unavailable
    } ?: return PlatformDownloadStatus.Unavailable
    return cursor.use {
      if (!it.moveToFirst()) return@use PlatformDownloadStatus.Missing
      val status = it.long(DownloadManager.COLUMN_STATUS)
      val downloaded = it.long(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR).coerceAtLeast(0L)
      val total = it.long(DownloadManager.COLUMN_TOTAL_SIZE_BYTES).coerceAtLeast(0L)
      val mimeType = it.string(DownloadManager.COLUMN_MEDIA_TYPE)
      when (status.toInt()) {
        DownloadManager.STATUS_SUCCESSFUL -> PlatformDownloadStatus.Success(total, mimeType)
        DownloadManager.STATUS_FAILED -> PlatformDownloadStatus.Failed
        else -> PlatformDownloadStatus.Active(downloaded, total)
      }
    }
  }

  private fun removePlatformRow(downloadManager: DownloadManager, id: Long): Boolean {
    val removed = runCatching { downloadManager.remove(id) }.getOrNull()
    if (removed != null && removed > 0) return true
    return query(downloadManager, id) == PlatformDownloadStatus.Missing
  }

  private fun platformDownloadMatches(
    downloadManager: DownloadManager,
    id: Long,
    part: DownloadItemPart
  ): Boolean {
    val expectedDestination = part.destinationUri.path
      ?.let(::File)
      ?.let { runCatching { it.canonicalFile }.getOrNull() }
      ?: return false
    val cursor = try {
      downloadManager.query(DownloadManager.Query().setFilterById(id))
    } catch (_: RuntimeException) {
      return false
    } ?: return false
    return cursor.use {
      if (!it.moveToFirst()) return@use false
      val source = it.string(DownloadManager.COLUMN_URI)
      val localPath = it.string(DownloadManager.COLUMN_LOCAL_URI)
        ?.let(Uri::parse)
        ?.takeIf { uri -> uri.scheme == "file" }
        ?.path
        ?.let(::File)
        ?.let { file -> runCatching { file.canonicalFile }.getOrNull() }
      source == part.uri.toString() && localPath == expectedDestination
    }
  }

  /** Recover the tiny enqueue-to-Paper process-death window by exact source and destination. */
  private fun recoverPlatformId(
    downloadManager: DownloadManager,
    part: DownloadItemPart
  ): Long? = when (val recovery = recoverPlatformIdStatus(downloadManager, part)) {
    is PlatformIdRecovery.Found -> recovery.id
    PlatformIdRecovery.Missing,
    PlatformIdRecovery.Unavailable -> null
  }

  private fun recoverPlatformIdStatus(
    downloadManager: DownloadManager,
    part: DownloadItemPart
  ): PlatformIdRecovery {
    val expectedDestination = part.destinationUri.path
      ?.let(::File)
      ?.let { runCatching { it.canonicalFile }.getOrNull() }
      ?: return PlatformIdRecovery.Unavailable
    val cursor = try {
      downloadManager.query(DownloadManager.Query())
    } catch (_: RuntimeException) {
      return PlatformIdRecovery.Unavailable
    } ?: return PlatformIdRecovery.Unavailable
    return cursor.use {
      var recovered: Long? = null
      while (it.moveToNext()) {
        val id = it.long(DownloadManager.COLUMN_ID)
        val source = it.string(DownloadManager.COLUMN_URI)
        val localPath = it.string(DownloadManager.COLUMN_LOCAL_URI)
          ?.let(Uri::parse)
          ?.takeIf { uri -> uri.scheme == "file" }
          ?.path
          ?.let(::File)
          ?.let { file -> runCatching { file.canonicalFile }.getOrNull() }
        if (id > 0L && source == part.uri.toString() && localPath == expectedDestination &&
          (recovered == null || id > recovered)
        ) {
          recovered = id
        }
      }
      recovered?.let(PlatformIdRecovery::Found) ?: PlatformIdRecovery.Missing
    }
  }

  private fun Cursor.long(column: String): Long {
    val index = getColumnIndex(column)
    return if (index >= 0) getLong(index) else 0L
  }

  private fun Cursor.string(column: String): String? {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else null
  }

  private fun broadcastChanged(libraryItemId: String?) {
    appContext.sendBroadcast(
      Intent(ACTION_STATE_CHANGED)
        .setPackage(appContext.packageName)
        .putExtra(EXTRA_LIBRARY_ITEM_ID, libraryItemId),
      PERMISSION_STATE_CHANGED
    )
  }

  private fun execute(
    callback: ((OfflineDownloadResult, OfflinePlanFailure?) -> Unit)?,
    executor: ExecutorService = EXECUTOR,
    onFinished: () -> Unit = {},
    operation: () -> Pair<OfflineDownloadResult, OfflinePlanFailure?>
  ) {
    executor.execute {
      val outcome = try {
        runCatching(operation).getOrElse {
          OfflineDownloadResult.FAILED to OfflinePlanFailure.STORAGE_UNAVAILABLE
        }
      } finally {
        runCatching(onFinished)
      }
      callback?.let { cb -> mainHandler.post { cb(outcome.first, outcome.second) } }
    }
  }

  private fun admitPending(lease: ConnectionLease, libraryItemId: String): PendingAdmission =
    synchronized(PENDING_ENQUEUES) {
      val key = PendingEnqueueKey(lease.connectionId, lease.epoch, libraryItemId)
      if (PENDING_ENQUEUES.containsKey(key)) return@synchronized PendingAdmission.Duplicate
      if (PENDING_ENQUEUES.size >= MAX_PENDING_BOOKS) return@synchronized PendingAdmission.Full
      val attempt = PendingEnqueue(key)
      PENDING_ENQUEUES[key] = attempt
      PendingAdmission.Accepted(attempt)
    }

  private fun releasePending(attempt: PendingEnqueue) {
    synchronized(PENDING_ENQUEUES) {
      if (PENDING_ENQUEUES[attempt.key] === attempt) PENDING_ENQUEUES.remove(attempt.key)
    }
  }

  private fun cancelPending(connectionId: String, libraryItemId: String): Boolean =
    synchronized(PENDING_ENQUEUES) {
      PENDING_ENQUEUES.values
        .filter { it.key.connectionId == connectionId && it.key.libraryItemId == libraryItemId }
        .onEach { it.canceled = true }
        .isNotEmpty()
    }

  private fun cancelAllPending(): Boolean = synchronized(PENDING_ENQUEUES) {
    PENDING_ENQUEUES.values.filterNot(PendingEnqueue::canceled)
      .onEach { it.canceled = true }
      .isNotEmpty()
  }

  private fun cancelPendingForConnection(connectionId: String): Boolean =
    synchronized(PENDING_ENQUEUES) {
      PENDING_ENQUEUES.values
        .filter { it.key.connectionId == connectionId && !it.canceled }
        .onEach { it.canceled = true }
        .isNotEmpty()
    }

  private fun hasPending(connectionId: String, libraryItemId: String): Boolean =
    synchronized(PENDING_ENQUEUES) {
      PENDING_ENQUEUES.values.any {
        !it.canceled && it.key.connectionId == connectionId &&
          it.key.libraryItemId == libraryItemId
      }
    }

  private fun pendingItems(connectionId: String): Set<String> =
    synchronized(PENDING_ENQUEUES) {
      PENDING_ENQUEUES.values.asSequence()
        .filter { !it.canceled && it.key.connectionId == connectionId }
        .map { it.key.libraryItemId }
        .toSet()
    }

  private fun pendingBookKeys(): Set<Pair<String, String>> =
    synchronized(PENDING_ENQUEUES) {
      PENDING_ENQUEUES.values.asSequence()
        .filterNot(PendingEnqueue::canceled)
        .map { it.key.connectionId to it.key.libraryItemId }
        .toSet()
    }

  private sealed class PlatformDownloadStatus {
    data class Active(val downloadedBytes: Long, val totalBytes: Long) : PlatformDownloadStatus()
    data class Success(val totalBytes: Long, val mimeType: String?) : PlatformDownloadStatus()
    object Failed : PlatformDownloadStatus()
    object Missing : PlatformDownloadStatus()
    object Unavailable : PlatformDownloadStatus()
  }

  private sealed class PlatformIdRecovery {
    data class Found(val id: Long) : PlatformIdRecovery()
    object Missing : PlatformIdRecovery()
    object Unavailable : PlatformIdRecovery()
  }

  private data class PendingEnqueueKey(
    val connectionId: String,
    val connectionEpoch: Long,
    val libraryItemId: String
  )

  private class PendingEnqueue(val key: PendingEnqueueKey) {
    @Volatile var canceled: Boolean = false
  }

  private sealed class PendingAdmission {
    data class Accepted(val attempt: PendingEnqueue) : PendingAdmission()
    object Duplicate : PendingAdmission()
    object Full : PendingAdmission()
  }

  companion object {
    const val ACTION_STATE_CHANGED =
      "${BuildConfig.APPLICATION_ID}.action.OFFLINE_DOWNLOAD_STATE_CHANGED"
    const val EXTRA_LIBRARY_ITEM_ID = "library_item_id"
    const val PERMISSION_STATE_CHANGED =
      "${BuildConfig.APPLICATION_ID}.permission.OFFLINE_DOWNLOAD_STATE"
    private const val MAX_PENDING_BOOKS = 16
    private const val MAX_PENDING_PARTS = 1_024L
    private val PENDING_ENQUEUES = mutableMapOf<PendingEnqueueKey, PendingEnqueue>()
    private val TRANSFER_EXECUTOR: ExecutorService = Executors.newFixedThreadPool(4) { runnable ->
      Thread(runnable, "ShelfDriveOfflinePreflight").apply { isDaemon = true }
    }
    private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
      Thread(runnable, "ShelfDriveOfflineDownloads").apply { isDaemon = true }
    }
  }
}
