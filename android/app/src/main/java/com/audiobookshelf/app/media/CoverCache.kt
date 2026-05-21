package com.audiobookshelf.app.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Per-app cover cache: keeps server-fetched audiobook covers on disk under
 * cache/covers/<itemId>.jpg and serves them via the app's FileProvider.
 *
 * Exists because Car Media's host process can't authenticate against the
 * audiobookshelf server, so we fetch in-process (where we have the session)
 * and hand Car Media a content:// URI it can read through ContentResolver.
 */
class CoverCache(private val ctx: Context) {
  private val tag = "CoverCache"
  private val authority = "${ctx.packageName}.fileprovider"
  private val cacheDir: File = File(ctx.cacheDir, "covers").apply { mkdirs() }

  init {
    // Reinstalls revoke URI grants but Car Media's image loader still holds
    // cached URIs from the old install. Re-grant every existing file in our
    // FileProvider-exposed paths so those stale lookups succeed.
    regrantAllExisting()
  }

  private fun regrantAllExisting() {
    val files = mutableListOf<File>()
    cacheDir.collectFilesInto(files)
    File(ctx.filesDir, "downloads").collectFilesInto(files)
    files.forEach { f ->
      if (f.length() > 0) {
        val uri = try { toContentUri(f) } catch (_: Exception) { return@forEach }
        KNOWN_BROWSER_PACKAGES.forEach { pkg ->
          try {
            ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
          } catch (_: Exception) { }
        }
      }
    }
    Log.d(tag, "regrantAllExisting: ${files.size} files re-granted to ${KNOWN_BROWSER_PACKAGES.size} packages")
  }

  private fun File.collectFilesInto(out: MutableList<File>) {
    if (!exists()) return
    if (isFile) {
      out += this
      return
    }
    listFiles()?.forEach { it.collectFilesInto(out) }
  }
  private val httpClient: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()
  private val inFlight: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
  // Items we've already tried this session — used to avoid notifyChildrenChanged
  // retry-storms when a cover URL keeps returning 4xx/5xx.
  private val attempted: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

  /** True if we've already attempted (success or failure) this process lifetime. */
  fun hasAttempted(itemId: String): Boolean = attempted.contains(itemId)

  /**
   * Returns a content:// URI if the cover is already on disk, else null.
   * Pre-grants FLAG_GRANT_READ_URI_PERMISSION to known cross-process media
   * consumers (Car Media, Android Auto, Polestar launcher). Without this,
   * those processes hit SecurityException when reading the FileProvider URI.
   */
  fun cachedUri(itemId: String): Uri? {
    val f = fileFor(itemId)
    if (!f.exists() || f.length() == 0L) return null
    val uri = toContentUri(f)
    KNOWN_BROWSER_PACKAGES.forEach { pkg ->
      try {
        ctx.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
      } catch (_: Exception) {
        // Package may not be installed on this device — ignore.
      }
    }
    return uri
  }

  /**
   * Fire-and-forget cover download. If already cached or in flight, [onComplete]
   * fires immediately (cached) or after the existing fetch (in flight is tracked
   * but we don't queue extra callbacks — keep it simple).
   */
  fun fetchAsync(itemId: String, remoteUrl: String, onComplete: () -> Unit) {
    if (cachedUri(itemId) != null) {
      onComplete()
      return
    }
    synchronized(inFlight) {
      if (!inFlight.add(itemId)) {
        // Already downloading; the in-flight call will trigger its own onComplete.
        // Caller's notify-children path will still pick up the file once written.
        onComplete()
        return
      }
    }
    Thread {
      try {
        val req = Request.Builder().url(remoteUrl).build()
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            Log.w(tag, "Cover fetch $itemId failed: HTTP ${resp.code}")
            return@use
          }
          val body = resp.body ?: return@use
          val tmp = File(cacheDir, "$itemId.tmp")
          tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
          // Atomic rename so partial files are never observed as valid.
          if (!tmp.renameTo(fileFor(itemId))) {
            tmp.delete()
            Log.w(tag, "Cover rename failed for $itemId")
          }
        }
      } catch (e: Exception) {
        Log.w(tag, "Cover fetch $itemId threw ${e.javaClass.simpleName}: ${e.message}")
      } finally {
        synchronized(inFlight) { inFlight.remove(itemId) }
        attempted.add(itemId)
        onComplete()
      }
    }.start()
  }

  private fun fileFor(itemId: String): File = File(cacheDir, "$itemId.jpg")

  private fun toContentUri(f: File): Uri = FileProvider.getUriForFile(ctx, authority, f)

  companion object {
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
