package com.audiobookshelf.app.plugins

import android.util.Log
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.device.DeviceManager
import java.util.UUID

data class AbsLog(
  var id:String,
  var tag:String,
  var level:String,
  var message:String,
  var timestamp:Long
)

/** Native AAOS logger. The dedicated Automotive artifact has no Capacitor bridge. */
object AbsLogger {
  fun log(level:String, tag:String, message:String) {
    // The AAOS release has no in-app diagnostic-log viewer. Avoid persisting
    // listening history and identifiers in production; debug builds retain
    // bounded records for local troubleshooting.
    if (!BuildConfig.DEBUG) return
    val absLog = AbsLog(
      id = UUID.randomUUID().toString(),
      tag = tag,
      level = level,
      message = message,
      timestamp = System.currentTimeMillis()
    )
    DeviceManager.dbManager.saveLog(absLog)
  }

  fun info(tag:String, message:String) {
    Log.i("AbsLogger", message)
    log("info", tag, message)
  }

  fun error(tag:String, message:String) {
    Log.e("AbsLogger", message)
    log("error", tag, message)
  }
}
