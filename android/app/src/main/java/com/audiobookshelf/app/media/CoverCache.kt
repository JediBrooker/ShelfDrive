package com.audiobookshelf.app.media

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.data.ServerConnectionConfig
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.device.DeviceManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Per-app cover cache: keeps server-fetched audiobook covers on disk under a
 * server/account namespace and serves them via the app's FileProvider.
 *
 * Exists because Car Media's host process can't authenticate against the
 * audiobookshelf server, so we fetch in-process (where we have the session)
 * and hand Car Media a content:// URI it can read through ContentResolver.
 */
class CoverCache(context: Context) {
  // The cache intentionally lives for the process lifetime through
  // DeviceManager. Never retain a MediaBrowserService or Activity instance.
  private val ctx = context.applicationContext
  private val tag = "CoverCache"
  private val authority = "${ctx.packageName}.fileprovider"
  private val cacheDir: File = File(ctx.cacheDir, "covers").apply { mkdirs() }
  private val diskStore = CoverDiskStore(cacheDir, MAX_TOTAL_CACHE_BYTES, MAX_COVER_BYTES)
  private val coverExecutor = Executors.newFixedThreadPool(3) { task ->
    Thread(task, "ShelfDrive-cover-worker").apply { priority = Thread.NORM_PRIORITY - 1 }
  }
  private val startupMaintenanceComplete = CountDownLatch(1)

  init {
    // Disk scans must not block MediaBrowserService.onCreate. Fetch workers wait
    // for this first queued task so stale .tmp or legacy unscoped files cannot
    // race a new write.
    coverExecutor.execute {
      val files = try {
        val maintenance = diskStore.startupMaintenance()
        Log.d(
          tag,
          "Cover startup maintenance: ${maintenance.files.size} files, " +
            "${maintenance.totalBytes} bytes, removed ${maintenance.removedFiles}"
        )
        maintenance.files
      } catch (error: Exception) {
        Log.w(tag, "Cover startup maintenance failed safely", error)
        emptyList()
      } finally {
        startupMaintenanceComplete.countDown()
      }
      regrantAllExisting(files)
    }
  }

  private fun regrantAllExisting(files: List<File>) {
    // Only this cache needs eager grants. Download files are granted when they
    // are actually returned and can be arbitrarily numerous on an old install.
    files.forEach { f ->
      if (f.length() > 0) {
        val uri = try { toContentUri(f) } catch (_: Exception) { return@forEach }
        trustedBrowserPackages().forEach { pkg ->
          try {
            ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          } catch (_: Exception) { }
        }
      }
    }
    Log.d(tag, "regrantAllExisting: ${files.size} cached covers checked")
  }

  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .callTimeout(25, TimeUnit.SECONDS)
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    // A server-controlled redirect must never carry configured custom headers
    // to another origin. Cover endpoints are expected to respond directly.
    .followRedirects(false)
    .followSslRedirects(false)
    .build()
  private val inFlightCallbacks = mutableMapOf<String, MutableList<() -> Unit>>()
  // Items we've already tried this session — used to avoid notifyChildrenChanged
  // retry-storms when a cover URL keeps returning 4xx/5xx.
  private val attempted: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

  // Deliberately not a data class: generated toString() would expose the
  // access token and custom header values to crash inspection/debug strings.
  private class ScopeSnapshot(
    val namespace: String,
    val serverScheme: String,
    val serverHost: String,
    val serverPort: Int,
    val token: String,
    val customHeaders: Map<String, String>
  ) {
    override fun toString(): String =
      "ScopeSnapshot(scope=<redacted>, credentials=<redacted>)"
  }

  /** Stable identity used by the browse prefetch gate; contains no credentials. */
  internal fun currentNamespace(): String? = activeScope()?.namespace

  /** True if this item was attempted for the current server/account this process lifetime. */
  fun hasAttempted(itemId: String): Boolean {
    val scope = activeScope() ?: return false
    return attempted.contains(requestKey(scope.namespace, itemId))
  }

  fun hasAttempted(
    itemId: String,
    config: ServerConnectionConfig,
    lease: ConnectionLease
  ): Boolean {
    val scope = scopeFor(config, lease) ?: return false
    return attempted.contains(requestKey(scope.namespace, itemId))
  }

  /**
   * Returns a content:// URI if the cover is already on disk, else null.
   * Pre-grants FLAG_GRANT_READ_URI_PERMISSION to known cross-process media
   * consumers (Car Media, Android Auto, Polestar launcher). Without this,
   * those processes hit SecurityException when reading the FileProvider URI.
   */
  fun cachedUri(itemId: String): Uri? {
    val scope = activeScope() ?: return null
    return cachedUri(scope, itemId)
  }

  fun cachedUri(
    itemId: String,
    config: ServerConnectionConfig,
    lease: ConnectionLease
  ): Uri? {
    val scope = scopeFor(config, lease) ?: return null
    return cachedUri(scope, itemId)
  }

  private fun cachedUri(scope: ScopeSnapshot, itemId: String): Uri? {
    val f = diskStore.cachedFile(scope.namespace, itemId) ?: return null
    return try {
      val uri = toContentUri(f)
      trustedBrowserPackages().forEach { pkg ->
        try {
          ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
          // Package may disappear between validation and the grant.
        }
      }
      uri
    } catch (error: RuntimeException) {
      Log.w(tag, "Ignoring invalid cached cover", error)
      null
    }
  }

  /**
   * Fire-and-forget cover download. If already cached or in flight, [onComplete]
   * fires immediately for cached files or after the shared bounded fetch.
   */
  fun fetchAsync(itemId: String, remoteUrl: String, onComplete: () -> Unit) {
    val scope = activeScope()
    fetchAsyncInScope(itemId, remoteUrl, scope, {
      val current = activeScope()
      current != null && current.namespace == scope?.namespace && current.token == scope?.token
    }, onComplete)
  }

  fun fetchAsync(
    itemId: String,
    remoteUrl: String,
    config: ServerConnectionConfig,
    lease: ConnectionLease,
    onComplete: () -> Unit
  ) {
    val scope = scopeFor(config, lease)
    fetchAsyncInScope(itemId, remoteUrl, scope, {
      DeviceManager.getServerConnectionConfig(lease) === config && config.token == scope?.token
    }, onComplete)
  }

  private fun fetchAsyncInScope(
    itemId: String,
    remoteUrl: String,
    scope: ScopeSnapshot?,
    scopeIsCurrent: () -> Boolean,
    onComplete: () -> Unit
  ) {
    val remote = remoteUrl.toHttpUrlOrNull()
    if (remote == null || scope == null || !scope.matches(remote.scheme, remote.host, remote.port)) {
      Log.w(tag, "Ignoring cover outside the active server scope")
      runCatching { onComplete() }
      return
    }
    if (cachedUri(scope, itemId) != null) {
      runCatching(onComplete).onFailure { Log.w(tag, "Cover completion callback failed", it) }
      return
    }
    val scopedRequestKey = requestKey(scope.namespace, itemId)
    synchronized(inFlightCallbacks) {
      val callbacks = inFlightCallbacks[scopedRequestKey]
      if (callbacks != null) {
        callbacks += onComplete
        return
      }
      inFlightCallbacks[scopedRequestKey] = mutableListOf(onComplete)
    }
    coverExecutor.execute {
      var tmp: File? = null
      try {
        startupMaintenanceComplete.await()
        if (!scopeIsCurrent()) throw IOException("Cover owner changed")
        if (!BuildConfig.DEBUG && remote.scheme != "https") {
          throw IOException("Cleartext cover URL rejected")
        }
        val pendingFile = diskStore.tempFile(scope.namespace, itemId)
        tmp = pendingFile
        val requestBuilder = Request.Builder().url(remote)
        requestBuilder.header("Authorization", "Bearer ${scope.token}")
        scope.customHeaders.forEach { (name, value) ->
          if (name.lowercase() !in BLOCKED_HEADERS) requestBuilder.header(name, value)
        }
        val req = requestBuilder.build()
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            Log.w(tag, "Cover fetch failed: HTTP ${resp.code}")
            return@use
          }
          val body = resp.body ?: return@use
          if (body.contentLength() > MAX_COVER_BYTES) {
            throw IOException("Cover exceeds size limit")
          }
          pendingFile.outputStream().use { out ->
            body.byteStream().use { input ->
              val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
              var total = 0L
              while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_COVER_BYTES) throw IOException("Cover exceeds size limit")
                out.write(buffer, 0, read)
              }
            }
          }
          // Commit and LRU pruning happen under one disk-store lock so partial
          // files are never visible and the persistent cache remains bounded.
          if (!scopeIsCurrent()) throw IOException("Cover owner changed")
          if (!diskStore.commit(pendingFile, scope.namespace, itemId)) {
            pendingFile.delete()
            Log.w(tag, "Cover cache commit failed")
          }
        }
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.d(tag, "Cover fetch interrupted for $itemId")
      } catch (e: Exception) {
        Log.w(tag, "Cover fetch failed (${e.javaClass.simpleName})")
      } finally {
        tmp?.let { if (it.exists()) it.delete() }
        attempted.add(scopedRequestKey)
        val callbacks = synchronized(inFlightCallbacks) {
          inFlightCallbacks.remove(scopedRequestKey).orEmpty()
        }
        callbacks.forEach { callback ->
          runCatching(callback).onFailure { Log.w(tag, "Cover completion callback failed", it) }
        }
      }
    }
  }

  private fun toContentUri(f: File): Uri = FileProvider.getUriForFile(ctx, authority, f)

  private fun activeScope(): ScopeSnapshot? {
    val config = DeviceManager.serverConnectionConfig ?: return null
    val lease = DeviceManager.captureConnectionLease(config) ?: return null
    return scopeFor(config, lease)
  }

  private fun scopeFor(
    config: ServerConnectionConfig,
    lease: ConnectionLease
  ): ScopeSnapshot? {
    if (DeviceManager.getServerConnectionConfig(lease) !== config || config.token.isBlank()) {
      return null
    }
    val server = config.address.toHttpUrlOrNull() ?: return null
    val namespace = coverCacheNamespace(config.id, config.userId, config.address) ?: return null
    return ScopeSnapshot(
      namespace = namespace,
      serverScheme = server.scheme.lowercase(),
      serverHost = server.host.lowercase(),
      serverPort = server.port,
      token = config.token,
      customHeaders = config.customHeaders.orEmpty().toMap()
    )
  }

  private fun ScopeSnapshot.matches(scheme: String, host: String, port: Int): Boolean =
    serverScheme == scheme.lowercase() && serverHost == host.lowercase() && serverPort == port

  private fun requestKey(namespace: String, itemId: String): String =
    sha256Hex("$namespace\u0000$itemId")

  private fun trustedBrowserPackages(): List<String> = KNOWN_BROWSER_PACKAGES.filter { packageName ->
    val flags = runCatching {
      ctx.packageManager.getApplicationInfo(packageName, 0).flags
    }.getOrDefault(0)
    flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
  }

  companion object {
    private const val MAX_COVER_BYTES = 10L * 1024L * 1024L
    private const val MAX_TOTAL_CACHE_BYTES = 64L * 1024L * 1024L
    private val BLOCKED_HEADERS = setOf(
      "authorization", "connection", "content-length", "host", "transfer-encoding"
    )
    /**
     * Packages that consume cover URIs across process boundaries and need
     * read permission grants. Mirrors VALID_MEDIA_BROWSERS from
     * PlayerNotificationService for the relevant cross-process readers.
     */
    private val KNOWN_BROWSER_PACKAGES = listOf(
      "com.android.car.media",
      "com.google.android.projection.gearhead",
      "com.google.android.carassistant",
      "com.volvocars.launcher"
    )
  }
}

/** Stable internal namespace for a server profile and user; never expose it diagnostically. */
internal fun coverCacheNamespace(configId: String, userId: String, address: String): String? {
  val server = address.toHttpUrlOrNull() ?: return null
  val normalizedPath = server.encodedPath.trimEnd('/').ifEmpty { "/" }
  val normalizedServer =
    "${server.scheme.lowercase()}://${server.host.lowercase()}:${server.port}$normalizedPath"
  // Length prefixes make the identity unambiguous even if imported profile
  // data contains a delimiter character.
  return listOf(configId, userId, normalizedServer).joinToString("") { value ->
    "${value.length}:$value"
  }
}
