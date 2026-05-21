package com.audiobookshelf.app.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.audiobookshelf.app.MainActivity

class ShelfDriveMediaBridgeService : MediaBrowserServiceCompat() {
  private val tag = "ShelfDriveMediaBrowser"
  private val mainHandler = Handler(Looper.getMainLooper())
  private var hasSessionToken = false
  private var lastNativeLaunchAtMs = 0L

  override fun onCreate() {
    super.onCreate()
    attachActiveSessionToken()
  }

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    val hasActiveSession = attachActiveSessionToken()
    openNativeAppForCarMedia(clientPackageName)

    return if (hasActiveSession) {
      BrowserRoot(ROOT_ID, null)
    } else {
      Log.w(tag, "Rejecting browser connection with no active ShelfDrive session")
      null
    }
  }

  override fun onLoadChildren(
          parentId: String,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    result.sendResult(mutableListOf())
  }

  private fun attachActiveSessionToken(): Boolean {
    if (hasSessionToken) return true

    val token = PlayerNotificationService.activeMediaSessionToken ?: return false
    sessionToken = token
    hasSessionToken = true
    return true
  }

  private fun openNativeAppForCarMedia(clientPackageName: String) {
    if (clientPackageName != ANDROID_CAR_MEDIA_PACKAGE) return

    val now = SystemClock.elapsedRealtime()
    if (now - lastNativeLaunchAtMs < NATIVE_LAUNCH_THROTTLE_MS) return
    lastNativeLaunchAtMs = now

    mainHandler.postDelayed({
      val intent =
              Intent(this, MainActivity::class.java).apply {
                addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
              }

      try {
        Log.d(tag, "Opening ShelfDrive native activity from car media")
        startActivity(intent)
      } catch (error: Exception) {
        Log.w(tag, "Unable to open ShelfDrive native activity from car media", error)
      }
    }, NATIVE_LAUNCH_DELAY_MS)
  }

  companion object {
    private const val ANDROID_CAR_MEDIA_PACKAGE = "com.android.car.media"
    private const val NATIVE_LAUNCH_DELAY_MS = 750L
    private const val NATIVE_LAUNCH_THROTTLE_MS = 3000L
    private const val ROOT_ID = "shelfdrive_active_playback"
  }
}
