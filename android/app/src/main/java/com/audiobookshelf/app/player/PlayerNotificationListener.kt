package com.audiobookshelf.app.player

import android.app.Notification
import android.util.Log
import com.google.android.exoplayer2.ui.PlayerNotificationManager

class PlayerNotificationListener(var playerNotificationService:PlayerNotificationService) : PlayerNotificationManager.NotificationListener {
  var tag = "PlayerNotificationListener"

  override fun onNotificationPosted(
    notificationId: Int,
    notification: Notification,
    onGoing: Boolean) {

    // AAOS owns the playback surface and binds to the MediaBrowserService.
    // The AAOS-specific media guide only permits foreground services for
    // offline downloads, so keep this as an ordinary media notification.
    Log.d(tag, "Notification posted $notificationId | onGoing=$onGoing")
    PlayerNotificationService.isClosed = false
  }

  override fun onNotificationCancelled(
    notificationId: Int,
    dismissedByUser: Boolean
  ) {
    if (dismissedByUser) {
      Log.d(tag, "onNotificationCancelled dismissed by user")
      playerNotificationService.stopSelf()
    } else {
      Log.d(tag, "onNotificationCancelled not dismissed by user")
    }
  }
}
