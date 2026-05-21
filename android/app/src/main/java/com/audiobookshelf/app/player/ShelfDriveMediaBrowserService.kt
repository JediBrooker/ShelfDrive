package com.audiobookshelf.app.player

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat

class ShelfDriveMediaBrowserService : MediaBrowserServiceCompat() {
  private val tag = "ShelfDriveMediaBrowser"
  private var hasSessionToken = false

  override fun onCreate() {
    super.onCreate()
    attachActiveSessionToken()
  }

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    return if (attachActiveSessionToken()) {
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

  companion object {
    private const val ROOT_ID = "shelfdrive_active_playback"
  }
}
