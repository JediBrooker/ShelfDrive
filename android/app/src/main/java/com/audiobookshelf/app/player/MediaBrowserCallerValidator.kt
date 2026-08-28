package com.audiobookshelf.app.player

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.media.MediaSessionManager

/**
 * Validates callers before exposing the media browse tree.
 *
 * AAOS media-host package names vary by manufacturer, so package-name
 * allowlists reject legitimate car hosts and can be spoofed by unrelated apps.
 * Accept only the app itself, system/platform callers, or callers AndroidX
 * confirms are trusted for media control, after checking package/UID ownership.
 */
internal class MediaBrowserCallerValidator(
  context: Context
) {
  private val packageManager = context.packageManager
  private val ownPackageName = context.packageName
  private val mediaSessionManager =
    MediaSessionManager.getSessionManager(context.applicationContext)

  fun isValid(callingPackage: String, callingUid: Int): Boolean {
    val packagesForUid = packageManager.getPackagesForUid(callingUid)
    if (packagesForUid == null || callingPackage !in packagesForUid) {
      Log.w(TAG, "Rejecting media browser caller with mismatched package/uid: $callingPackage/$callingUid")
      return false
    }

    if (callingPackage == ownPackageName || callingUid == Process.myUid()) return true
    if (callingUid == Process.SYSTEM_UID) return true

    if (packageManager.checkSignatures(callingPackage, "android") ==
      PackageManager.SIGNATURE_MATCH
    ) {
      return true
    }

    val remoteUser = MediaSessionManager.RemoteUserInfo(
      callingPackage,
      UNKNOWN_PID,
      callingUid
    )
    return runCatching {
      mediaSessionManager.isTrustedForMediaControl(remoteUser)
    }.getOrDefault(false)
  }

  private companion object {
    const val TAG = "MediaBrowserCaller"
    const val UNKNOWN_PID = -1
  }
}
