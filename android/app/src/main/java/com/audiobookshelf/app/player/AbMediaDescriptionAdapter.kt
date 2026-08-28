package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.media.session.MediaControllerCompat
import android.util.Log
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.bumptech.glide.Glide
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerNotificationManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

class AbMediaDescriptionAdapter (private val controller: MediaControllerCompat, private val playerNotificationService: PlayerNotificationService) : PlayerNotificationManager.MediaDescriptionAdapter {
  private val tag = "MediaDescriptionAdapter"

  private var currentIconUri: Uri? = null
  private var currentBitmap: Bitmap? = null
  private val artworkGeneration = AtomicLong(0L)

  private val serviceJob = SupervisorJob()
  private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

  override fun createCurrentContentIntent(player: Player): PendingIntent? =
    controller.sessionActivity

  override fun getCurrentContentText(player: Player) =
    controller.metadata?.description?.subtitle?.toString().orEmpty()

  override fun getCurrentContentTitle(player: Player) =
    controller.metadata?.description?.title?.toString().orEmpty()

  override fun getCurrentLargeIcon(
    player: Player,
    callback: PlayerNotificationManager.BitmapCallback
  ): Bitmap? {
    val description = controller.metadata?.description
    val albumArtUri = description?.iconUri
      ?: Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon")
    val albumBitmap = description?.iconBitmap

    if (albumBitmap != null) {
      try {
        return if (albumBitmap.width <= MAX_ARTWORK_DIMENSION &&
          albumBitmap.height <= MAX_ARTWORK_DIMENSION
        ) {
          albumBitmap
        } else {
          val scale = minOf(
            MAX_ARTWORK_DIMENSION.toFloat() / albumBitmap.width,
            MAX_ARTWORK_DIMENSION.toFloat() / albumBitmap.height
          )
          Bitmap.createScaledBitmap(
            albumBitmap,
            (albumBitmap.width * scale).toInt().coerceAtLeast(1),
            (albumBitmap.height * scale).toInt().coerceAtLeast(1),
            true
          )
        }
      } catch (error: RuntimeException) {
        Log.w(tag, "Ignoring invalid in-memory artwork", error)
      } catch (memoryError: OutOfMemoryError) {
        Log.e(tag, "In-memory artwork exceeded memory budget", memoryError)
      }
    }

    return if (currentIconUri != albumArtUri || currentBitmap == null) {
      // Cache the bitmap for the current audiobook so that successive calls to
      // `getCurrentLargeIcon` don't cause the bitmap to be recreated.
      currentIconUri = albumArtUri
      currentBitmap = null
      val generation = artworkGeneration.incrementAndGet()

      serviceScope.launch {
        val resolved = resolveUriAsBitmap(albumArtUri)
        if (isActive && generation == artworkGeneration.get() && currentIconUri == albumArtUri) {
          currentBitmap = resolved
          resolved?.let { callback.onBitmap(it) }
        }
      }
      null
    } else {
      currentBitmap
    }
  }

  private suspend fun resolveUriAsBitmap(uri: Uri): Bitmap? {
    return withContext(Dispatchers.IO) {
      try {
        Glide.with(playerNotificationService)
          .asBitmap()
          .load(uri)
          .override(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)
          .submit()
          .get()
      } catch (error: Exception) {
        Log.w(tag, "Artwork load failed; using the local icon", error)
        try {
          Glide.with(playerNotificationService)
            .asBitmap()
            .load(Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon"))
            .override(MAX_ARTWORK_DIMENSION, MAX_ARTWORK_DIMENSION)
            .submit()
            .get()
        } catch (fallbackError: Exception) {
          Log.e(tag, "Fallback artwork load failed", fallbackError)
          null
        } catch (memoryError: OutOfMemoryError) {
          Log.e(tag, "Fallback artwork exceeded memory budget", memoryError)
          null
        }
      } catch (memoryError: OutOfMemoryError) {
        Log.e(tag, "Artwork exceeded memory budget", memoryError)
        null
      }
    }
  }

  fun release() {
    artworkGeneration.incrementAndGet()
    serviceJob.cancel()
    currentIconUri = null
    currentBitmap = null
  }

  private companion object {
    const val MAX_ARTWORK_DIMENSION = 512
  }
}
