package com.audiobookshelf.app.downloads

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Reconciles only the exact ShelfDrive-owned platform ID that completed. */
class OfflineDownloadCompleteReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
    val platformId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
    if (platformId <= 0L) return
    val pending = goAsync()
    if (!IN_FLIGHT.add(platformId)) {
      pending.finish()
      return
    }
    val handler = Handler(Looper.getMainLooper())
    val finished = AtomicBoolean(false)
    lateinit var deadline: Runnable
    fun finishOnce() {
      if (!finished.compareAndSet(false, true)) return
      handler.removeCallbacks(deadline)
      IN_FLIGHT.remove(platformId)
      pending.finish()
    }
    // BroadcastReceiver.goAsync still has a short execution budget. The
    // durable platform row and startup reconciliation are the fallback if an
    // OEM stalls binder/filesystem work beyond this deadline.
    deadline = Runnable(::finishOnce)
    handler.postDelayed(deadline, RECEIVER_DEADLINE_MS)
    OfflineDownloadCoordinator(context).reconcile(platformId) { _, _ -> finishOnce() }
  }

  private companion object {
    const val RECEIVER_DEADLINE_MS = 8_000L
    val IN_FLIGHT = ConcurrentHashMap.newKeySet<Long>()
  }
}
