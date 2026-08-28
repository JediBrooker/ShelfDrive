package com.audiobookshelf.app.server

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import com.audiobookshelf.app.data.*
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.media.MediaEventManager
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.media.SyncResult
import com.audiobookshelf.app.models.User
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.plugins.AbsLogger
import com.audiobookshelf.app.managers.RefreshTokenStorage
import com.audiobookshelf.app.managers.SecureStorage
import com.audiobookshelf.app.player.PlayerNotificationService
import com.audiobookshelf.app.util.SafeJsonObject as JSObject
import com.audiobookshelf.app.util.AppInstanceId
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import okio.Buffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun refreshConfigForRequest(
  requestConfig: ServerConnectionConfig?,
  activeConfig: ServerConnectionConfig?
): ServerConnectionConfig? = requestConfig ?: activeConfig

internal fun updateAccessTokenForConnection(
  deviceData: DeviceData,
  requestConfig: ServerConnectionConfig,
  newAccessToken: String
) {
  requestConfig.token = newAccessToken
  deviceData.serverConnectionConfigs
    .find { it.id == requestConfig.id }
    ?.token = newAccessToken
}

internal fun clearAccessTokenForConnection(
  deviceData: DeviceData,
  requestConfig: ServerConnectionConfig
) {
  requestConfig.token = ""
  deviceData.serverConnectionConfigs
    .find { it.id == requestConfig.id }
    ?.token = ""
  if (deviceData.lastServerConnectionConfigId == requestConfig.id) {
    deviceData.lastServerConnectionConfigId = null
  }
}

internal fun latestAccessTokenForConnection(
  deviceData: DeviceData,
  connectionId: String,
  requestConfig: ServerConnectionConfig,
  activeConfig: ServerConnectionConfig?
): String = deviceData.serverConnectionConfigs
  .find { it.id == connectionId }
  ?.token
  ?: activeConfig?.takeIf { it.id == connectionId }?.token
  ?: requestConfig.token

/**
 * Small synchronized single-flight primitive used to ensure only one refresh
 * request is made per server profile while preserving every original request.
 */
internal class PerConnectionRefreshGate<K, O : Any, T> {
  private data class OwnedRequest<O, T>(val owner: O, val request: T)
  private class Entry<O, T>(
    val leaderOwner: O,
    val requests: MutableList<OwnedRequest<O, T>>
  )

  private val pending = mutableMapOf<K, Entry<O, T>>()

  @Synchronized
  fun join(key: K, owner: O, request: T): Boolean {
    val existing = pending[key]
    if (existing != null) {
      existing.requests.add(OwnedRequest(owner, request))
      return false
    }
    pending[key] = Entry(owner, mutableListOf(OwnedRequest(owner, request)))
    return true
  }

  @Synchronized
  fun drain(key: K, leaderOwner: O): List<T> {
    val entry = pending[key] ?: return emptyList()
    // A canceled callback from a torn-down handler can arrive after a new
    // handler has become leader for the same connection lease. Only the owner
    // of this exact gate entry may complete it.
    if (entry.leaderOwner !== leaderOwner) return emptyList()
    pending.remove(key)
    return entry.requests.map { it.request }
  }

  @Synchronized
  fun isLeader(key: K, owner: O): Boolean = pending[key]?.leaderOwner === owner

  /**
   * Removes work owned by a handler that is shutting down. If that handler was
   * the refresh leader, every follower is also completed with a transient
   * failure so the key is immediately available to a replacement handler.
   */
  @Synchronized
  fun abortOwner(owner: O): List<T> {
    val aborted = mutableListOf<T>()
    val entries = pending.entries.iterator()
    while (entries.hasNext()) {
      val entry = entries.next().value
      if (entry.leaderOwner === owner) {
        aborted.addAll(entry.requests.map { it.request })
        entries.remove()
      } else {
        val requests = entry.requests.iterator()
        while (requests.hasNext()) {
          val request = requests.next()
          if (request.owner === owner) {
            aborted.add(request.request)
            requests.remove()
          }
        }
      }
    }
    return aborted
  }
}

private data class PendingRefreshRequest(
  val retry: (String) -> Unit,
  val fail: (String) -> Unit
)

/** Prevents malformed persisted models from allocating an unbounded JSON body. */
internal class BoundedByteArrayOutputStream(private val maxBytes: Int) :
  ByteArrayOutputStream(minOf(maxBytes, 8_192)) {

  override fun write(value: Int) {
    ensureCapacityFor(1)
    super.write(value)
  }

  override fun write(bytes: ByteArray, offset: Int, length: Int) {
    ensureCapacityFor(length)
    super.write(bytes, offset, length)
  }

  private fun ensureCapacityFor(additionalBytes: Int) {
    if (additionalBytes > maxBytes - count) {
      throw IllegalStateException("JSON payload exceeds safe limit")
    }
  }
}

class ApiHandler(
  var ctx: Context,
  private val secureStorage: RefreshTokenStorage = SecureStorage(ctx),
  requestClient: OkHttpClient? = null,
  shortRequestClient: OkHttpClient? = null
) {
  val tag = "ApiHandler"

  companion object {
    internal const val MAX_RESPONSE_BODY_BYTES = 16L * 1024L * 1024L
    internal const val MAX_JSON_PAYLOAD_BYTES = 16 * 1024 * 1024

    private val BLOCKED_CUSTOM_HEADERS = setOf(
      "authorization",
      "connection",
      "content-length",
      "host",
      "transfer-encoding",
      "x-refresh-token"
    )

    // ApiHandler is instantiated by the service and several Capacitor plugins;
    // this gate is process-wide to prevent parallel refreshes across them. Each
    // entry also tracks its owning handler so teardown cannot strand a key.
    private val REFRESH_GATE =
      PerConnectionRefreshGate<ConnectionLease, Any, PendingRefreshRequest>()

    // For sending data back to the Webview frontend
    lateinit var absDatabaseNotifyListeners:(String, JSObject) -> Unit

    fun checkAbsDatabaseNotifyListenersInitted():Boolean {
      return ::absDatabaseNotifyListeners.isInitialized
    }
  }

  private var defaultClient = requestClient
    ?: OkHttpClient.Builder()
      .callTimeout(8, TimeUnit.SECONDS)
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
  private var pingClient = shortRequestClient
    ?: OkHttpClient.Builder()
      .callTimeout(3, TimeUnit.SECONDS)
      .followRedirects(false)
      .followSslRedirects(false)
      .build()
  private val refreshGateOwner = Any()
  @Volatile private var isShutdown = false
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
  data class LocalSessionsSyncRequestPayload(val sessions:List<PlaybackSession>, val deviceInfo:DeviceInfo)
  @JsonIgnoreProperties(ignoreUnknown = true)
  data class LocalSessionSyncResult(val id:String, val success:Boolean, val progressSynced:Boolean?, val error:String?)
  data class LocalSessionsSyncResponsePayload(val results:List<LocalSessionSyncResult>)

  /** Cancel service-owned network work and release its worker threads. */
  fun shutdown() {
    val abandonedRefreshes = synchronized(this) {
      if (isShutdown) return
      isShutdown = true
      REFRESH_GATE.abortOwner(refreshGateOwner)
    }
    listOf(defaultClient, pingClient).forEach { client ->
      runCatching { client.dispatcher.cancelAll() }
      runCatching { client.connectionPool.evictAll() }
      runCatching { client.cache?.close() }
      runCatching { client.dispatcher.executorService.shutdownNow() }
    }
    abandonedRefreshes.forEach { pending ->
      pending.fail("Authentication refresh was interrupted")
    }
  }

  private fun deliver(cb: (JSObject) -> Unit, value: JSObject) {
    try {
      cb(value)
    } catch (error: Exception) {
      // OkHttp invokes callbacks on a dispatcher thread. Uncaught model/JSON
      // exceptions here terminate the process while Car Media is browsing.
      Log.e(tag, "Response callback failed (${error.javaClass.simpleName}); delivering a typed failure")
      val failure = JSObject().apply {
        put("error", "Invalid or incompatible server response")
      }
      try {
        // Public API consumers already map an error object to their typed safe
        // value (null, false, or an empty list). Retrying only the consumer,
        // not the request, guarantees completion without process death.
        cb(failure)
      } catch (fallbackError: Exception) {
        Log.e(tag, "Response failure callback also failed (${fallbackError.javaClass.simpleName})")
      }
    }
  }

  private fun Request.Builder.addConnectionHeaders(
    config: ServerConnectionConfig?,
    token: String
  ): Request.Builder {
    header("Authorization", "Bearer $token")
    val activeConfig = config ?: DeviceManager.serverConnectionConfig
    activeConfig?.customHeaders.orEmpty().forEach { (name, value) ->
      // Preserve user-configured reverse-proxy headers without allowing them
      // to replace transport-controlled or authentication headers.
      if (name.lowercase() !in BLOCKED_CUSTOM_HEADERS) {
        header(name, value)
      }
    }
    return this
  }

  private fun serializeJsonObject(value: Any): JSObject? {
    return try {
      val output = BoundedByteArrayOutputStream(MAX_JSON_PAYLOAD_BYTES)
      jacksonMapper.writeValue(output, value)
      JSObject(output.toString(Charsets.UTF_8.name()))
    } catch (error: Exception) {
      Log.e(tag, "Unable to serialize bounded JSON (${error.javaClass.simpleName})")
      null
    }
  }

  /** Builds a relative endpoint while encoding path segments and query data. */
  private fun endpoint(
    pathSegments: List<String>,
    queryParameters: List<Pair<String, String>>
  ): String {
    val builder = HttpUrl.Builder().scheme("https").host("shelfdrive.invalid")
    pathSegments.forEach(builder::addPathSegment)
    queryParameters.forEach { (name, value) -> builder.addQueryParameter(name, value) }
    val url = builder.build()
    return buildString {
      append(url.encodedPath)
      url.encodedQuery?.let { append('?').append(it) }
    }
  }

  private fun getRequest(endpoint:String, httpClient:OkHttpClient?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val requestConfig = config ?: DeviceManager.serverConnectionConfig
    val lease = DeviceManager.captureConnectionLease(requestConfig)
    if (requestConfig == null || lease == null) {
      return deliver(cb, JSObject().apply { put("error", "No current server connection") })
    }
    val address = requestConfig.address
    val token = requestConfig.token

    try {
      val request = Request.Builder()
        .url("${address}$endpoint")
        .addConnectionHeaders(requestConfig, token)
        .build()
      makeRequest(request, httpClient, requestConfig, lease) { deliver(cb, it) }
    } catch(e: Exception) {
      Log.w(tag, "Unable to build GET request (${e.javaClass.simpleName})")
      val jsobj = JSObject()
      jsobj.put("error", "Request failed")
      deliver(cb, jsobj)
    }
  }

  private fun postRequest(endpoint:String, payload: JSObject?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val requestConfig = config ?: DeviceManager.serverConnectionConfig
    val lease = DeviceManager.captureConnectionLease(requestConfig)
    if (requestConfig == null || lease == null) {
      return deliver(cb, JSObject().apply { put("error", "No current server connection") })
    }
    val address = requestConfig.address
    val token = requestConfig.token
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload?.toString()?.toRequestBody(mediaType) ?: EMPTY_REQUEST
    val requestUrl = "${address}$endpoint"
    Log.d(tag, "Building POST request")
    try {
      val request = Request.Builder().post(requestBody)
        .url(requestUrl)
        .addConnectionHeaders(requestConfig, token)
        .build()
      makeRequest(request, null, requestConfig, lease) { deliver(cb, it) }
    } catch(e: Exception) {
      Log.w(tag, "Unable to build POST request (${e.javaClass.simpleName})")
      val jsobj = JSObject()
      jsobj.put("error", "Request failed")
      deliver(cb, jsobj)
    }
  }

  private fun patchRequest(
    endpoint: String,
    payload: JSObject,
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    cb: (JSObject) -> Unit
  ) {
    val requestConfig = config
    val lease = DeviceManager.captureConnectionLease(requestConfig)
    if (requestConfig == null || lease == null) {
      return deliver(cb, JSObject().apply { put("error", "No current server connection") })
    }
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    try {
      val request = Request.Builder().patch(requestBody)
        .url("${requestConfig.address}$endpoint")
        .addConnectionHeaders(requestConfig, requestConfig.token)
        .build()
      makeRequest(request, null, requestConfig, lease) { deliver(cb, it) }
    } catch(e: Exception) {
      Log.w(tag, "Unable to build PATCH request (${e.javaClass.simpleName})")
      val jsobj = JSObject()
      jsobj.put("error", "Request failed")
      deliver(cb, jsobj)
    }
  }

  /**
   * Orders request dispatch against account invalidation. OkHttp's enqueue only
   * schedules the call; response and network I/O occur after this short state
   * critical section.
   */
  private fun enqueueIfLeaseCurrent(
    client: OkHttpClient,
    request: Request,
    requestLease: ConnectionLease,
    callback: Callback
  ): Boolean = synchronized(DeviceManager.connectionStateMonitor) {
    if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
      false
    } else {
      client.newCall(request).enqueue(callback)
      true
    }
  }

  private fun makeRequest(
    request: Request,
    httpClient: OkHttpClient?,
    requestConfig: ServerConnectionConfig,
    requestLease: ConnectionLease,
    cb: (JSObject) -> Unit
  ) {
    val client = httpClient ?: defaultClient

    val requestCallback = object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.w(tag, "Server request failed (${e.javaClass.simpleName})")

        val jsobj = JSObject()
        jsobj.put(
          "error",
          if (DeviceManager.isConnectionLeaseCurrent(requestLease)) {
            "Failed to connect"
          } else {
            "Account changed while request was in progress"
          }
        )
        cb(jsobj)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
            sendRefreshError(cb, "Account changed while request was in progress")
            return
          }
          if (it.code == 401) {
            // Handle 401 Unauthorized by attempting token refresh
            AbsLogger.info(tag, "makeRequest: 401 Unauthorized; attempting token refresh")
            handleTokenRefresh(request, httpClient, requestConfig, requestLease, cb)
            return
          }

          if (!it.isSuccessful) {
            val jsobj = JSObject()
            jsobj.put("error", "Unexpected HTTP status ${it.code}")
            cb(jsobj)
            return
          }

          val bodyString = readResponseBody(it)
          if (bodyString == null) {
            val jsobj = JSObject()
            jsobj.put("error", "Missing or oversized response body")
            cb(jsobj)
            return
          }
          if (bodyString == "OK") {
            if (DeviceManager.isConnectionLeaseCurrent(requestLease)) cb(JSObject())
            else sendRefreshError(cb, "Account changed while request was in progress")
          } else {
            try {
              var jsonObj = JSObject()
              if (bodyString.startsWith("[")) {
                val array = JSONArray(bodyString)
                jsonObj.put("value", array)
              } else {
                jsonObj = JSObject(bodyString)
              }
              if (DeviceManager.isConnectionLeaseCurrent(requestLease)) cb(jsonObj)
              else sendRefreshError(cb, "Account changed while request was in progress")
            } catch(je:JSONException) {
              Log.e(tag, "Invalid JSON response")
              val jsobj = JSObject()
              jsobj.put("error", "Invalid response body")
              cb(jsobj)
            }
          }
        }
      }
    }
    if (!enqueueIfLeaseCurrent(client, request, requestLease, requestCallback)) {
      sendRefreshError(cb, "Account changed before request could be sent")
    }
  }

  /** Reads at most [MAX_RESPONSE_BODY_BYTES], including unknown/chunked bodies. */
  private fun readResponseBody(response: Response): String? {
    val body = response.body ?: return null
    val declaredLength = body.contentLength()
    if (declaredLength > MAX_RESPONSE_BODY_BYTES) {
      Log.w(tag, "Rejecting oversized server response")
      return null
    }

    return try {
      val source = body.source()
      val buffer = Buffer()
      var totalBytes = 0L
      while (true) {
        val remaining = MAX_RESPONSE_BODY_BYTES + 1L - totalBytes
        if (remaining <= 0L) {
          Log.w(tag, "Rejecting oversized chunked server response")
          return null
        }
        val read = source.read(buffer, minOf(8_192L, remaining))
        if (read == -1L) {
          val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
          return buffer.readString(charset)
        }
        totalBytes += read
      }
      @Suppress("UNREACHABLE_CODE")
      null
    } catch (error: IOException) {
      Log.w(tag, "Unable to read server response (${error.javaClass.simpleName})")
      null
    }
  }

  /**
   * Handles token refresh when a 401 Unauthorized response is received
   * This function will:
   * 1. Get the refresh token from secure storage for the current server connection
   * 2. Make a request to /auth/refresh endpoint with the refresh token
   * 3. Update the stored tokens with the new access token
   * 4. Retry the original request with the new access token
   * 5. If refresh fails, handle logout
   *
   * @param originalRequest The original request that failed with 401
   * @param httpClient The HTTP client to use for the request
   * @param callback The callback to return the response
   */
  private fun handleTokenRefresh(
    originalRequest: Request,
    httpClient: OkHttpClient?,
    requestConfig: ServerConnectionConfig,
    requestLease: ConnectionLease,
    callback: (JSObject) -> Unit
  ) {
    var joinedLease: ConnectionLease? = null
    try {
      AbsLogger.info(tag, "handleTokenRefresh: Attempting to refresh auth tokens")

      // A startup/fallback request can target a profile other than the active
      // one. Refresh and mutate only the profile that actually received 401.
      val refreshConfig = DeviceManager.getServerConnectionConfig(requestLease)
      val serverConnectionConfigId = requestLease.connectionId
      if (refreshConfig == null || refreshConfig !== requestConfig) {
        AbsLogger.error(tag, "handleTokenRefresh: Unable to refresh auth tokens. No server connection config ID")
        sendRefreshError(callback, "Account changed while request was in progress")
        return
      }
      if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
        sendRefreshError(callback, "Account was removed")
        return
      }

      // A response may arrive after another request already refreshed this
      // profile. Retry that stale request directly instead of starting a
      // redundant refresh or risking a rotated refresh token.
      val requestAccessToken = originalRequest.header("Authorization")
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        .orEmpty()
      val tokenBeforeJoining = synchronized(DeviceManager.connectionStateMonitor) {
        latestAccessTokenForConnection(
          DeviceManager.deviceData,
          serverConnectionConfigId,
          refreshConfig,
          DeviceManager.serverConnectionConfig
        )
      }
      if (tokenBeforeJoining.isNotBlank() &&
        requestAccessToken.isNotBlank() &&
        requestAccessToken != tokenBeforeJoining
      ) {
        retryOriginalRequest(
          originalRequest,
          tokenBeforeJoining,
          httpClient,
          requestLease,
          callback
        )
        return
      }

      val pendingRefresh = PendingRefreshRequest(
        retry = { token ->
          retryOriginalRequest(
            originalRequest,
            token,
            httpClient,
            requestLease,
            callback
          )
        },
        fail = { message -> sendRefreshError(callback, message) }
      )
      val isRefreshLeader = synchronized(this) {
        if (isShutdown) null else REFRESH_GATE.join(
          requestLease,
          refreshGateOwner,
          pendingRefresh
        )
      }
      if (isRefreshLeader == null) {
        pendingRefresh.fail("Authentication refresh was interrupted")
        return
      }
      joinedLease = requestLease
      if (!isRefreshLeader) {
        Log.d(tag, "Token refresh already in progress; request queued")
        return
      }
      val accessTokenAtStart = refreshConfig.token

      // Get refresh token from secure storage
      val refreshToken = synchronized(DeviceManager.connectionPersistenceMonitor) {
        if (DeviceManager.isConnectionLeaseCurrent(requestLease)) {
          secureStorage.getRefreshToken(serverConnectionConfigId)
        } else {
          null
        }
      }
      if (refreshToken.isNullOrEmpty()) {
        AbsLogger.error(tag, "handleTokenRefresh: Unable to refresh auth tokens; no refresh token is available")
        completeRefreshFailure(requestLease, refreshConfig, accessTokenAtStart)
        return
      }

      Log.d(tag, "handleTokenRefresh: Retrieved refresh token, attempting to refresh access token")

      // Create refresh token request
      if (!DeviceManager.isServerAddressAllowed(refreshConfig.address)) {
        completeRefreshFailure(requestLease, refreshConfig, accessTokenAtStart)
        return
      }
      val refreshEndpoint = Uri.parse(refreshConfig.address).buildUpon()
        .appendPath("auth")
        .appendPath("refresh")
        .build()
      val refreshRequest = Request.Builder()
        .url(refreshEndpoint.toString())
        .addHeader("x-refresh-token", refreshToken)
        .addHeader("Content-Type", "application/json")
        .post(EMPTY_REQUEST)
        .addConnectionHeaders(refreshConfig, refreshConfig.token)
        .build()

      // Make the refresh request
      val client = httpClient ?: defaultClient
      val refreshCallback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          Log.e(tag, "handleTokenRefresh: Refresh endpoint unavailable (${e.javaClass.simpleName})")
          AbsLogger.error(tag, "handleTokenRefresh: Failed to connect to refresh endpoint")
          completeRefreshUnavailable(requestLease)
        }

        override fun onResponse(call: Call, response: Response) {
          response.use {
            if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
              completeRefreshUnavailable(requestLease)
              return
            }
            if (!it.isSuccessful) {
              AbsLogger.error(tag, "handleTokenRefresh: Refresh request failed with status ${it.code}")
              if (it.code == 401 || it.code == 403) {
                retryAfterConcurrentRefreshOrClear(
                  requestLease,
                  refreshConfig,
                  accessTokenAtStart
                )
              } else {
                completeRefreshUnavailable(requestLease)
              }
              return
            }

            val bodyString = readResponseBody(it)
            if (bodyString == null) {
              completeRefreshUnavailable(requestLease)
              return
            }
            try {
              val responseJson = JSONObject(bodyString)
              val userObj = responseJson.optJSONObject("user")

              if (userObj == null) {
                AbsLogger.error(tag, "handleTokenRefresh: No user object in refresh response")
                completeRefreshUnavailable(requestLease)
                return
              }

              val newAccessToken = userObj.optString("accessToken")
              val newRefreshToken = userObj.optString("refreshToken")

              if (newAccessToken.isEmpty()) {
                AbsLogger.error(tag, "handleTokenRefresh: No access token in refresh response")
                completeRefreshUnavailable(requestLease)
                return
              }

              Log.d(tag, "handleTokenRefresh: Successfully obtained new access token")

              // Updating durable credentials and draining the refresh gate are
              // one owner-qualified completion. A canceled handler must not
              // mutate a replacement handler's still-current profile.
              completeRefreshWithTokens(
                requestLease,
                newAccessToken,
                newRefreshToken.ifEmpty { refreshToken },
                refreshConfig
              )

            } catch (e: Exception) {
              Log.e(tag, "handleTokenRefresh: Failed to parse refresh response (${e.javaClass.simpleName})")
              AbsLogger.error(tag, "handleTokenRefresh: Failed to parse refresh response")
              completeRefreshUnavailable(requestLease)
            }
          }
        }
      }
      // Account removal and refresh dispatch share this monitor. Cleanup can
      // therefore either prevent the request from being sent or invalidate it
      // immediately afterwards; a request can never be dispatched from a
      // profile that was already removed.
      val refreshEnqueued = enqueueIfLeaseCurrent(
        client,
        refreshRequest,
        requestLease,
        refreshCallback
      )
      if (!refreshEnqueued) {
        completeRefreshUnavailable(requestLease)
      }

    } catch (e: Exception) {
      Log.e(tag, "handleTokenRefresh: Unexpected refresh error (${e.javaClass.simpleName})")
      if (joinedLease == null) {
        handleRefreshUnavailable(callback)
      } else {
        completeRefreshUnavailable(joinedLease)
      }
    }
  }

  /**
   * Updates the stored tokens with new access and refresh tokens
   *
   * @param newAccessToken The new access token
   * @param newRefreshToken The new refresh token (or existing one if not provided)
   */
  private fun updateTokens(
    newAccessToken: String,
    newRefreshToken: String,
    refreshConfig: ServerConnectionConfig,
    requestLease: ConnectionLease
  ): Boolean {
    return try {
      val serverConnectionConfigId = refreshConfig.id
      val updated = synchronized(DeviceManager.connectionPersistenceMonitor) {
        if (DeviceManager.getServerConnectionConfig(requestLease) !== refreshConfig) {
          return@synchronized false
        }

        if (newRefreshToken != secureStorage.getRefreshToken(serverConnectionConfigId)) {
          if (secureStorage.storeRefreshToken(serverConnectionConfigId, newRefreshToken)) {
            Log.d(tag, "updateTokens: Updated refresh token in secure storage")
          } else {
            Log.e(tag, "updateTokens: Could not persist refreshed credential")
            return@synchronized false
          }
        }

        val committed = synchronized(DeviceManager.connectionStateMonitor) {
          if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
            false
          } else {
            val savedConfig = DeviceManager.deviceData.serverConnectionConfigs.find {
              it === refreshConfig && it.token.isNotBlank()
            } ?: return@synchronized false
            updateAccessTokenForConnection(
              DeviceManager.deviceData,
              savedConfig,
              newAccessToken
            )
            if (DeviceManager.serverConnectionConfig === savedConfig) {
              DeviceManager.serverConnectionConfig?.token = newAccessToken
            }
            true
          }
        }
        if (!committed) return@synchronized false
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
        true
      }
      if (!updated) return false
      Log.d(tag, "updateTokens: Updated access token in server connection config")

      // Send access token to Webview frontend
      if (DeviceManager.isConnectionLeaseCurrent(requestLease) &&
        DeviceManager.serverConnectionConfig === refreshConfig &&
        checkAbsDatabaseNotifyListenersInitted()
      ) {
        val tokenJsObject = JSObject()
        tokenJsObject.put("accessToken", newAccessToken)
        absDatabaseNotifyListeners("onTokenRefresh", tokenJsObject)
      } else {
        // Can happen if Webview is never run
        Log.i(tag, "AbsDatabaseNotifyListeners is not initialized so cannot send new access token")
      }
      AbsLogger.info(tag, "updateTokens: Successfully refreshed auth tokens")
      true
    } catch (e: Exception) {
      Log.e(tag, "updateTokens: Failed to update tokens (${e.javaClass.simpleName})")
      AbsLogger.error(tag, "updateTokens: Failed to refresh auth tokens")
      false
    }
  }

  /**
   * Retries the original request with the new access token
   *
   * @param originalRequest The original request to retry
   * @param newAccessToken The new access token to use
   * @param httpClient The HTTP client to use
   * @param callback The callback to return the response
   */
  private fun retryOriginalRequest(
    originalRequest: Request,
    newAccessToken: String,
    httpClient: OkHttpClient?,
    requestLease: ConnectionLease,
    callback: (JSObject) -> Unit
  ) {
    try {
      // Create a new request with the updated authorization header
      val newRequest = originalRequest.newBuilder()
        .removeHeader("Authorization")
        .addHeader("Authorization", "Bearer $newAccessToken")
        .build()

      Log.d(tag, "retryOriginalRequest: Retrying request")

      val retryCallback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          Log.e(tag, "retryOriginalRequest: Retry unavailable (${e.javaClass.simpleName})")
          AbsLogger.error(tag, "retryOriginalRequest: Failed after token refresh")
          val errorObj = JSObject()
          errorObj.put(
            "error",
            if (DeviceManager.isConnectionLeaseCurrent(requestLease)) {
              "Failed to retry request after token refresh"
            } else {
              "Account changed while request was in progress"
            }
          )
          callback(errorObj)
        }

        override fun onResponse(call: Call, response: Response) {
          response.use {
            if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
              sendRefreshError(callback, "Account changed while request was in progress")
              return
            }
            if (!it.isSuccessful) {
              Log.e(tag, "retryOriginalRequest: Retry request failed with status ${it.code}")
              AbsLogger.error(tag, "retryOriginalRequest: Retry request failed with status ${it.code}")
              val errorObj = JSObject()
              errorObj.put("error", "Retry request failed with status ${it.code}")
              callback(errorObj)
              return
            }

            val bodyString = readResponseBody(it)
            if (bodyString == null) {
              val errorObj = JSObject()
              errorObj.put("error", "Missing or oversized retry response body")
              callback(errorObj)
              return
            }
            if (bodyString == "OK") {
              if (DeviceManager.isConnectionLeaseCurrent(requestLease)) callback(JSObject())
              else sendRefreshError(callback, "Account changed while request was in progress")
            } else {
              try {
                var jsonObj = JSObject()
                if (bodyString.startsWith("[")) {
                  val array = JSONArray(bodyString)
                  jsonObj.put("value", array)
                } else {
                  jsonObj = JSObject(bodyString)
                }
                if (DeviceManager.isConnectionLeaseCurrent(requestLease)) callback(jsonObj)
                else sendRefreshError(callback, "Account changed while request was in progress")
              } catch(je:JSONException) {
                Log.e(tag, "retryOriginalRequest: Invalid JSON response")
                val errorObj = JSObject()
                errorObj.put("error", "Invalid response body")
                callback(errorObj)
              }
            }
          }
        }
      }

      // Establish a strict order with account removal. If removal acquires the
      // connection lock first, this retry is never enqueued; if the retry was
      // already enqueued, cleanup still wins every subsequent state mutation.
      val client = httpClient ?: defaultClient
      val enqueued = enqueueIfLeaseCurrent(client, newRequest, requestLease, retryCallback)
      if (!enqueued) {
        sendRefreshError(callback, "Account was removed")
      }

    } catch (e: Exception) {
      Log.e(tag, "retryOriginalRequest: Unexpected retry error (${e.javaClass.simpleName})")
      AbsLogger.error(tag, "retryOriginalRequest: Unexpected error during retry")
      val errorObj = JSObject()
      errorObj.put("error", "Failed to retry request")
      callback(errorObj)
    }
  }

  /**
   * Handles the case when token refresh fails
   * This will clear the current session and notify the callback
   *
   * @param callback The callback to return the error
   */
  private fun clearFailedRefresh(
    failedConfig: ServerConnectionConfig,
    expectedAccessToken: String,
    requestLease: ConnectionLease
  ): String? {
    val serverConnectionConfigId = failedConfig.id
    var authenticationCleared = false
    var newerToken: String? = null
    try {
      Log.d(tag, "handleRefreshFailure: Token refresh rejected, clearing failed profile auth")

      synchronized(DeviceManager.connectionPersistenceMonitor) {
        synchronized(DeviceManager.connectionStateMonitor) state@{
          if (DeviceManager.getServerConnectionConfig(requestLease) !== failedConfig) {
            return@state
          }
          val latestToken = latestAccessTokenForConnection(
            DeviceManager.deviceData,
            serverConnectionConfigId,
            failedConfig,
            DeviceManager.serverConnectionConfig
          )
          if (latestToken.isNotBlank() && latestToken != expectedAccessToken) {
            newerToken = latestToken
            return@state
          }

          DeviceManager.advanceConnectionStateEpoch()
          clearAccessTokenForConnection(DeviceManager.deviceData, failedConfig)
          val retained = DeviceManager.deviceData.serverConnectionConfigs.firstOrNull {
            it !== failedConfig && it.token.isNotBlank() &&
              DeviceManager.isServerAddressAllowed(it.address)
          }
          if (DeviceManager.deviceData.lastServerConnectionConfigId == null) {
            DeviceManager.deviceData.lastServerConnectionConfigId = retained?.id
          }
          if (DeviceManager.serverConnectionConfig === failedConfig) {
            DeviceManager.serverConnectionConfig = retained
          }
          authenticationCleared = true
        }
        if (authenticationCleared) {
          DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
          if (serverConnectionConfigId.isNotBlank()) {
            secureStorage.removeRefreshToken(serverConnectionConfigId)
          }
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "handleRefreshFailure: Error clearing failed profile (${e.javaClass.simpleName})")
    }

    if (authenticationCleared) {
      // AAOS commonly runs without the WebView listener below. Converge a
      // permanent refresh rejection with Settings/account sign-out so Car
      // Media immediately invalidates stale pages and surfaces Sign in.
      PlayerNotificationService.requestBrowseRefresh("authentication expired")
    }

    try {
      if (checkAbsDatabaseNotifyListenersInitted()) {
        val tokenJsObject = JSObject()
        tokenJsObject.put("error", "Token refresh failed")
        if (serverConnectionConfigId.isNotBlank()) {
          tokenJsObject.put("serverConnectionConfigId", serverConnectionConfigId)
        }
        absDatabaseNotifyListeners("onTokenRefreshFailure", tokenJsObject)
      } else {
        // Can happen if Webview is never run
        Log.i(tag, "AbsDatabaseNotifyListeners is not initialized so cannot send token refresh failure notification")
      }
    } catch (error: Exception) {
      Log.e(tag, "Unable to notify token-refresh failure (${error.javaClass.simpleName})")
    }
    return newerToken
  }

  /**
   * A second in-flight refresh can be rejected after the first one rotated the
   * credential. In that case retry with the newer access token instead of
   * clearing a connection that has already recovered.
   */
  private fun retryAfterConcurrentRefreshOrClear(
    requestLease: ConnectionLease,
    failedConfig: ServerConnectionConfig,
    accessTokenAtStart: String
  ) {
    if (DeviceManager.getServerConnectionConfig(requestLease) !== failedConfig) {
      completeRefreshUnavailable(requestLease)
      return
    }
    val latestToken = synchronized(DeviceManager.connectionStateMonitor) {
      latestAccessTokenForConnection(
        DeviceManager.deviceData,
        failedConfig.id,
        failedConfig,
        DeviceManager.serverConnectionConfig
      )
    }
    if (latestToken.isNotBlank() && latestToken != accessTokenAtStart) {
      Log.i(tag, "A concurrent token refresh already succeeded; retrying request")
      completeRefreshSuccess(requestLease, latestToken)
    } else {
      completeRefreshFailure(requestLease, failedConfig, accessTokenAtStart)
    }
  }

  private fun completeRefreshSuccess(requestLease: ConnectionLease, newAccessToken: String) {
    val pendingRequests = synchronized(this) {
      if (isShutdown || !REFRESH_GATE.isLeader(requestLease, refreshGateOwner)) {
        return
      }
      REFRESH_GATE.drain(requestLease, refreshGateOwner)
    }
    if (!DeviceManager.isConnectionLeaseCurrent(requestLease)) {
      pendingRequests.forEach { it.fail("Account was removed") }
      return
    }
    pendingRequests.forEach { it.retry(newAccessToken) }
  }

  private fun completeRefreshFailure(
    requestLease: ConnectionLease,
    failedConfig: ServerConnectionConfig,
    expectedAccessToken: String
  ) {
    val completion = synchronized(this) {
      if (isShutdown || !REFRESH_GATE.isLeader(requestLease, refreshGateOwner)) {
        return
      }
      val newerToken = clearFailedRefresh(failedConfig, expectedAccessToken, requestLease)
      newerToken to REFRESH_GATE.drain(requestLease, refreshGateOwner)
    }
    val newerToken = completion.first
    val pendingRequests = completion.second
    if (newerToken != null) {
      pendingRequests.forEach { pending -> pending.retry(newerToken) }
    } else {
      pendingRequests.forEach { pending ->
        pending.fail("Authentication failed - please login again")
      }
    }
  }

  private fun completeRefreshUnavailable(requestLease: ConnectionLease) {
    val pendingRequests = synchronized(this) {
      if (isShutdown || !REFRESH_GATE.isLeader(requestLease, refreshGateOwner)) {
        return
      }
      REFRESH_GATE.drain(requestLease, refreshGateOwner)
    }
    pendingRequests.forEach { pending ->
      pending.fail("Authentication refresh is temporarily unavailable")
    }
  }

  private fun completeRefreshWithTokens(
    requestLease: ConnectionLease,
    newAccessToken: String,
    newRefreshToken: String,
    refreshConfig: ServerConnectionConfig
  ) {
    val completion = synchronized(this) {
      if (isShutdown || !REFRESH_GATE.isLeader(requestLease, refreshGateOwner)) {
        return
      }
      val updated = updateTokens(
        newAccessToken,
        newRefreshToken,
        refreshConfig,
        requestLease
      )
      updated to REFRESH_GATE.drain(requestLease, refreshGateOwner)
    }
    if (completion.first && DeviceManager.isConnectionLeaseCurrent(requestLease)) {
      completion.second.forEach { it.retry(newAccessToken) }
    } else {
      completion.second.forEach {
        it.fail("Authentication refresh is temporarily unavailable")
      }
    }
  }

  /** Transient refresh/network/server errors preserve credentials for retry. */
  private fun handleRefreshUnavailable(callback: (JSObject) -> Unit) {
    sendRefreshError(callback, "Authentication refresh is temporarily unavailable")
  }

  private fun sendRefreshError(callback: (JSObject) -> Unit, message: String) {
    try {
      val errorObj = JSObject()
      errorObj.put("error", message)
      callback(errorObj)
    } catch (error: Exception) {
      Log.e(tag, "Token-refresh callback failed (${error.javaClass.simpleName})")
    }
  }

  fun getCurrentUser(cb: (User?) -> Unit) {
    getRequest("/api/me", null, null) {
      if (it.has("error")) {
        Log.e(tag, "getCurrentUser failed")
        cb(null)
      } else {
        val user = jacksonMapper.readValue<User>(it.toString())
        cb(user)
      }
    }
  }

  fun getLibraries(cb: (List<Library>) -> Unit) {
    val mapper = jacksonMapper
    getRequest("/api/libraries?include=stats", null,null) {
      val libraries = mutableListOf<Library>()

      var array = JSONArray()
      if (it.has("libraries")) { // TODO: Server 2.2.9 changed to this
        array = it.getJSONArray("libraries")
      } else if (it.has("value")) {
        array = it.getJSONArray("value")
      }

      for (i in 0 until array.length()) {
        libraries.add(mapper.readValue(array.get(i).toString()))
      }
      cb(libraries)
    }
  }

  fun getLibraryPersonalized(libraryItemId:String, cb: (List<LibraryShelfType>?) -> Unit) {
    getRequest(endpoint(listOf("api", "libraries", libraryItemId, "personalized"), emptyList()), null, null) {
      if (it.has("error")) {
        Log.e(tag, "getLibraryPersonalized failed")
        cb(null)
      } else {
        val items = mutableListOf<LibraryShelfType>()
        val array = it.getJSONArray("value")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryShelfType>(array.get(i).toString())
          items.add(item)
        }
        cb(items)
      }
    }
  }

  fun getLibraryItem(libraryItemId:String, cb: (LibraryItem?) -> Unit) {
    getRequest(endpoint(listOf("api", "items", libraryItemId), listOf("expanded" to "1")), null, null) {
      if (it.has("error")) {
        Log.e(tag, "getLibraryItem failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, cb: (LibraryItem?) -> Unit) {
    val queryParameters = mutableListOf("expanded" to "1", "include" to "progress")
    episodeId?.takeIf { it.isNotEmpty() }?.let { queryParameters.add("episode" to it) }
    val requestUrl = endpoint(listOf("api", "items", libraryItemId), queryParameters)
    getRequest(requestUrl, null, null) {
      if (it.has("error")) {
        Log.e(tag, "getLibraryItemWithProgress failed")
        cb(null)
      } else {
        val libraryItem = jacksonMapper.readValue<LibraryItem>(it.toString())
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    getRequest(
      endpoint(
        listOf("api", "libraries", libraryId, "items"),
        listOf("limit" to "100", "minified" to "1")
      ),
      null,
      null
    ) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeries(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/series?minified=1&sort=name&limit=10000", null, null) {
      val items = mutableListOf<LibrarySeriesItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibrarySeriesItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeriesItems(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting items for series")
    val seriesIdBase64 = Base64.encodeToString(seriesId.toByteArray(), Base64.NO_WRAP)
    val requestEndpoint = endpoint(
      listOf("api", "libraries", libraryId, "items"),
      listOf(
        "minified" to "1",
        "sort" to "media.metadata.title",
        "filter" to "series.$seriesIdBase64",
        "limit" to "1000"
      )
    )
    getRequest(requestEndpoint, null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibraryAuthors(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/authors", null, null) {
      val items = mutableListOf<LibraryAuthorItem>()
      if (it.has("authors")) {
        val array = it.getJSONArray("authors")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryAuthorItem>(array.get(i).toString())
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryItemsFromAuthor(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting author items")
    val authorIdBase64 = Base64.encodeToString(authorId.toByteArray(), Base64.NO_WRAP)
    val requestEndpoint = endpoint(
      listOf("api", "libraries", libraryId, "items"),
      listOf(
        "limit" to "1000",
        "minified" to "1",
        "filter" to "authors.$authorIdBase64",
        "sort" to "media.metadata.title",
        "collapseseries" to "1"
      )
    )
    getRequest(requestEndpoint, null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          if (item.collapsedSeries != null) {
            item.collapsedSeries?.libraryId = libraryId
          }
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryCollections(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    Log.d(tag, "Getting collections")
    getRequest("/api/libraries/$libraryId/collections?minified=1&sort=name&limit=1000", null, null) {
      val items = mutableListOf<LibraryCollection>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryCollection>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getSearchResults(libraryId:String, queryString:String, cb: (LibraryItemSearchResultType?) -> Unit) {
    Log.d(tag, "Performing library search")
    val requestEndpoint = endpoint(
      listOf("api", "libraries", libraryId, "search"),
      listOf("q" to queryString)
    )
    getRequest(requestEndpoint, null, null) {
      if (it.has("error")) {
        Log.e(tag, "getSearchResults failed")
        cb(null)
      } else {
        val librarySearchResults = jacksonMapper.readValue<LibraryItemSearchResultType>(it.toString())
        cb(librarySearchResults)
      }
    }
  }

  fun getAllItemsInProgress(cb: (List<ItemInProgress>) -> Unit) {
    getRequest("/api/me/items-in-progress", null, null) {
      val items = mutableListOf<ItemInProgress>()
      if (it.has("libraryItems")) {
        val array = it.getJSONArray("libraryItems")
        for (i in 0 until array.length()) {
          val jsobj = array.get(i) as JSONObject
          val itemInProgress = ItemInProgress.makeFromServerObject(jsobj, jacksonMapper)
          items.add(itemInProgress)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(
    libraryItemId: String,
    episodeId: String?,
    playItemRequestPayload: PlayItemRequestPayload,
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    cb: (PlaybackSession?) -> Unit
  ) {
    val payload = serializeJsonObject(playItemRequestPayload) ?: return cb(null)
    val requestConfig = config ?: return cb(null)
    val requestLease = DeviceManager.captureConnectionLease(requestConfig) ?: return cb(null)

    val playPath = mutableListOf("api", "items", libraryItemId, "play")
    episodeId?.takeIf { it.isNotEmpty() }?.let(playPath::add)
    postRequest(endpoint(playPath, emptyList()), payload, requestConfig) {
      if (it.has("error")) {
        Log.e(tag, "playLibraryItem failed")
        cb(null)
      } else {
        it.put("serverConnectionConfigId", requestConfig.id)
        it.put("serverAddress", requestConfig.address)
        val playbackSession = jacksonMapper.readValue<PlaybackSession>(it.toString())
        playbackSession.connectionLease = requestLease
        cb(playbackSession)
      }
    }
  }

  fun sendProgressSync(
    sessionId: String,
    syncData: MediaProgressSyncData,
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    cb: (Boolean, String?) -> Unit
  ) {
    val payload = serializeJsonObject(syncData)
    if (payload == null) {
      cb(false, "Invalid progress data")
      return
    }

    postRequest(endpoint(listOf("api", "session", sessionId, "sync"), emptyList()), payload, config) {
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, "Progress sync failed")
      } else {
        cb(true, null)
      }
    }
  }

  fun sendLocalProgressSync(
    playbackSession: PlaybackSession,
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    cb: (Boolean, String?) -> Unit
  ) {
    val payload = serializeJsonObject(playbackSession)
    if (payload == null) {
      cb(false, "Invalid progress data")
      return
    }

    postRequest("/api/session/local", payload, config) {
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, "Progress sync failed")
      } else {
        cb(true, null)
      }
    }
  }

  fun updateMediaProgress(
    libraryItemId: String,
    episodeId: String?,
    updatePayload: JSObject,
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    cb: (Boolean) -> Unit
  ) {
    val pathSegments = mutableListOf("api", "me", "progress", libraryItemId)
    episodeId?.takeIf { it.isNotEmpty() }?.let(pathSegments::add)
    patchRequest(endpoint(pathSegments, emptyList()), updatePayload, config) {
      cb(it.getString("error").isNullOrEmpty())
    }
  }

  fun getMediaProgress(libraryItemId:String, episodeId:String?, serverConnectionConfig:ServerConnectionConfig?, cb: (MediaProgress?) -> Unit) {
    val pathSegments = mutableListOf("api", "me", "progress", libraryItemId)
    episodeId?.takeIf { it.isNotEmpty() }?.let(pathSegments::add)

    // TODO: Using ping client here allows for shorter timeout (3 seconds), maybe rename or make diff client for requests requiring quicker response
    getRequest(endpoint(pathSegments, emptyList()), pingClient, serverConnectionConfig) {
      if (it.has("error")) {
        Log.e(tag, "getMediaProgress: Failed to get progress")
        cb(null)
      } else {
        val progress = jacksonMapper.readValue<MediaProgress>(it.toString())
        cb(progress)
      }
    }
  }

  fun getPlaybackSession(
    playbackSessionId: String,
    config: ServerConnectionConfig? = DeviceManager.serverConnectionConfig,
    cb: (PlaybackSession?) -> Unit
  ) {
    Log.d(tag, "Getting playback session")
    val requestConfig = config ?: return cb(null)
    val requestLease = DeviceManager.captureConnectionLease(requestConfig) ?: return cb(null)
    val requestEndpoint = endpoint(listOf("api", "session", playbackSessionId), emptyList())
    getRequest(requestEndpoint, null, requestConfig) {
      val err = it.getString("error")
      if (!err.isNullOrEmpty()) {
        cb(null)
      } else {
        cb(jacksonMapper.readValue<PlaybackSession>(it.toString()).also { session ->
          session.connectionLease = requestLease
        })
      }
    }
  }

  fun pingServer(config:ServerConnectionConfig, cb: (Boolean) -> Unit) {
    Log.d(tag, "Pinging server")
    getRequest("/ping", pingClient, config) {
      val success = it.getString("success")
      if (success.isNullOrEmpty()) {
        Log.d(tag, "Server ping failed")
        cb(false)
      } else {
        Log.d(tag, "Server ping succeeded")
        cb(true)
      }
    }
  }

  fun closePlaybackSession(playbackSessionId:String, config:ServerConnectionConfig?, cb: (Boolean) -> Unit) {
    Log.d(tag, "Closing playback session")
    postRequest(
      endpoint(listOf("api", "session", playbackSessionId, "close"), emptyList()),
      null,
      config
    ) {
      cb(it.getString("error").isNullOrEmpty())
    }
  }

  fun authorize(config:ServerConnectionConfig, cb: (MutableList<MediaProgress>?) -> Unit) {
    Log.d(tag, "Authorizing server connection")
    postRequest("/api/authorize", JSObject(), config) {
      val error = it.getString("error")
      if (!error.isNullOrEmpty()) {
        Log.d(tag, "Server authorization failed")
        cb(null)
      } else {
        val mediaProgressList:MutableList<MediaProgress> = mutableListOf()
        val user = it.getJSObject("user")
        val mediaProgress = user?.getJSONArray("mediaProgress") ?: JSONArray()
        for (i in 0 until mediaProgress.length()) {
          val mediaProg = jacksonMapper.readValue<MediaProgress>(mediaProgress.getJSONObject(i).toString())
          mediaProgressList.add(mediaProg)
        }
        Log.d(tag, "Server authorization succeeded")
        cb(mediaProgressList)
      }
    }
  }

  fun sendSyncLocalSessions(
    playbackSessions: List<PlaybackSession>,
    ownerConfig: ServerConnectionConfig,
    ownerLease: ConnectionLease,
    cb: (List<LocalSessionSyncResult>?, String?) -> Unit
  ) {
    if (playbackSessions.isEmpty() ||
      DeviceManager.getServerConnectionConfig(ownerLease) !== ownerConfig ||
      playbackSessions.any { it.serverConnectionConfigId != ownerConfig.id }
    ) {
      cb(null, "Saved playback sessions no longer belong to this account")
      return
    }
    val deviceId = AppInstanceId.get(ctx)
    val deviceInfo = DeviceInfo(deviceId, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)

    val payload = serializeJsonObject(
      LocalSessionsSyncRequestPayload(playbackSessions, deviceInfo)
    )
    if (payload == null) {
      cb(null, "Invalid saved playback session")
      return
    }
    AbsLogger.info(tag, "Sending saved playback sessions to server")

    postRequest("/api/session/local-all", payload, ownerConfig) {
      if (!it.getString("error").isNullOrEmpty()) {
        AbsLogger.error(tag, "Failed to sync saved playback sessions")
        cb(null, "Saved playback session sync failed")
      } else {
        val response = jacksonMapper.readValue<LocalSessionsSyncResponsePayload>(it.toString())
        response.results.forEach { localSessionSyncResult ->
          playbackSessions.find { ps -> ps.id == localSessionSyncResult.id }?.let { session ->
            if (localSessionSyncResult.progressSynced == true) {
              val syncResult = SyncResult(true, true, "Progress synced on server")
              MediaEventManager.saveEvent(session, syncResult, ownerLease)
              AbsLogger.info(tag, "Saved playback session synced and server progress updated")
            } else if (!localSessionSyncResult.success) {
              AbsLogger.error(tag, "A saved playback session could not be synced")
            } else {
              AbsLogger.info(tag, "Saved playback session synced")
            }
          }
        }
        cb(response.results, null)
      }
    }
  }

  fun syncLocalMediaProgressForUser(cb: () -> Unit) {
    AbsLogger.info(tag, "Starting local media progress sync")
    val syncLease = DeviceManager.captureConnectionLease(DeviceManager.serverConnectionConfig)
      ?: return cb()

    // Get all local media progress for this server
    val allLocalMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress().filter { it.serverConnectionConfigId == DeviceManager.serverConnectionConfigId }
    if (allLocalMediaProgress.isEmpty()) {
      AbsLogger.info(tag, "No local media progress to sync")
      return cb()
    }

    AbsLogger.info(tag, "Local media progress is ready to sync")

    getCurrentUser { user ->
      if (user == null) {
        AbsLogger.error(tag, "Unable to load user for local media progress sync")
      } else {
        // Compare server user progress with local progress
        user.mediaProgress.forEach { mediaProgress ->
          // Get matching local media progress
          allLocalMediaProgress.find { it.isMatch(mediaProgress) }?.let { localMediaProgress ->
            if (mediaProgress.lastUpdate > localMediaProgress.lastUpdate) {
              val progressChanged = mediaProgress.progress != localMediaProgress.progress ||
                mediaProgress.currentTime != localMediaProgress.currentTime ||
                mediaProgress.isFinished != localMediaProgress.isFinished ||
                mediaProgress.ebookProgress != localMediaProgress.ebookProgress
              if (progressChanged) {
                AbsLogger.info(tag, "Local media progress updated from server")
              }

              localMediaProgress.updateFromServerMediaProgress(mediaProgress)

              // Only report sync if progress changed
              if (progressChanged) {
                MediaEventManager.syncEvent(
                  mediaProgress,
                  "Sync on server connection",
                  syncLease
                )
              }
              DeviceManager.dbManager.saveLocalMediaProgress(localMediaProgress)
            } else if (localMediaProgress.lastUpdate > mediaProgress.lastUpdate && localMediaProgress.ebookLocation != null && localMediaProgress.ebookLocation != mediaProgress.ebookLocation) {
              // Patch ebook progress to server
              AbsLogger.info(tag, "Sending newer local ebook progress to server")
              val requestEndpoint = endpoint(
                listOf("api", "me", "progress", mediaProgress.libraryItemId),
                emptyList()
              )
              val updatePayload = JSObject()
              updatePayload.put("ebookLocation", localMediaProgress.ebookLocation)
              updatePayload.put("ebookProgress", localMediaProgress.ebookProgress)
              updatePayload.put("lastUpdate", localMediaProgress.lastUpdate)
              patchRequest(requestEndpoint, updatePayload) { result ->
                if (result.getString("error").isNullOrEmpty()) {
                  AbsLogger.info(tag, "Server ebook progress updated")
                } else {
                  AbsLogger.error(tag, "Server ebook progress update failed")
                }
              }
            }
          }
        }

        AbsLogger.info(tag, "Finished local media progress sync")
      }
      cb()
    }
  }
}
