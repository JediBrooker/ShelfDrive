package com.audiobookshelf.app.util

import android.content.Context
import java.util.UUID

/**
 * Opaque, install-scoped identifier used by the Audiobookshelf session API.
 * It is app-private and excluded from backup/device transfer by the app's
 * backup rules, so it cannot correlate a user across installs or other apps.
 */
object AppInstanceId {
  @Volatile private var cachedId: String? = null

  fun get(context: Context): String {
    cachedId?.let { return it }
    return synchronized(this) {
      cachedId?.let { return@synchronized it }
      val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
      )
      val persisted = prefs.getString(KEY_ID, null)?.takeIf(::isValid)
      val id = persisted ?: UUID.randomUUID().toString().also { generated ->
        // Keep the process identity stable even on a transient low-storage
        // write failure; a later install run may safely generate a new ID.
        runCatching { prefs.edit().putString(KEY_ID, generated).commit() }
      }
      cachedId = id
      id
    }
  }

  private fun isValid(value: String): Boolean =
    value.length <= 64 && runCatching { UUID.fromString(value) }.isSuccess

  internal fun clearMemoryForTest() {
    synchronized(this) { cachedId = null }
  }

  internal const val PREFS_NAME = "ShelfDriveAppInstance"
  internal const val KEY_ID = "install_id"
}
