package com.audiobookshelf.app.player

import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import com.audiobookshelf.app.BuildConfig

/**
 * Applies the Android Automotive OS artwork contract to browse results.
 *
 * AAOS only accepts local content:// or android.resource:// icon URIs in the
 * browse hierarchy and does not support icon bitmaps. Keeping this policy at
 * the MediaBrowserService boundary means a newly-added browse path cannot
 * accidentally send a remote URL or a bitmap across Binder.
 */
internal object BrowseArtworkPolicy {
  fun sanitize(
    items: List<MediaBrowserCompat.MediaItem>,
    applicationId: String = BuildConfig.APPLICATION_ID
  ): MutableList<MediaBrowserCompat.MediaItem> =
    items.mapNotNull { item ->
      val mediaId = item.description.mediaId
      if (mediaId.isNullOrBlank() || mediaId.length > MAX_MEDIA_ID_CHARS) {
        return@mapNotNull null
      }
      MediaBrowserCompat.MediaItem(
        sanitize(item.description, applicationId),
        item.flags
      )
    }.toMutableList()

  fun sanitize(
    description: MediaDescriptionCompat,
    applicationId: String = BuildConfig.APPLICATION_ID
  ): MediaDescriptionCompat {
    val originalIconUri = description.iconUri
    val safeIconUri = when {
      originalIconUri != null &&
        originalIconUri.toString().length <= MAX_URI_CHARS &&
        isOwnedLocalUri(originalIconUri, applicationId) -> originalIconUri
      originalIconUri != null || description.iconBitmap != null -> fallbackIconUri(applicationId)
      else -> null
    }

    return MediaDescriptionCompat.Builder()
      .setMediaId(description.mediaId)
      .setTitle(boundedText(description.title))
      .setSubtitle(boundedText(description.subtitle))
      .setDescription(boundedText(description.description))
      .setIconUri(safeIconUri)
      // Browse selection is intentionally media-ID based. Never parcel a
      // server-controlled playback URI to another process.
      .setExtras(description.extras?.let(::boundedExtras))
      // Deliberately do not copy iconBitmap: AAOS does not support it in the
      // browse hierarchy, and multiple bitmaps can exceed Binder's 1 MB limit.
      .build()
  }

  private fun fallbackIconUri(applicationId: String): Uri =
    Uri.parse("android.resource://$applicationId/drawable/icon")

  private fun boundedText(value: CharSequence?): CharSequence? =
    value?.toString()?.take(MAX_TEXT_CHARS)

  @Suppress("DEPRECATION") // Bundle's typed getters require API 33; minSdk is lower.
  private fun boundedExtras(source: Bundle): Bundle {
    val safe = Bundle()
    source.keySet()
      .asSequence()
      .filter { it.length <= MAX_EXTRA_KEY_CHARS }
      .sorted()
      .take(MAX_EXTRAS)
      .forEach { key ->
        when (val value = source.get(key)) {
          is String -> safe.putString(key, value.take(MAX_EXTRA_TEXT_CHARS))
          is CharSequence -> safe.putCharSequence(key, value.toString().take(MAX_EXTRA_TEXT_CHARS))
          is Boolean -> safe.putBoolean(key, value)
          is Int -> safe.putInt(key, value)
          is Long -> safe.putLong(key, value)
          is Float -> if (value.isFinite()) safe.putFloat(key, value)
          is Double -> if (value.isFinite()) safe.putDouble(key, value)
          is ArrayList<*> -> {
            val strings = value.filterIsInstance<String>()
            if (strings.size == value.size) {
              safe.putStringArrayList(
                key,
                ArrayList(
                  strings.take(MAX_EXTRA_LIST_ITEMS).map { it.take(MAX_EXTRA_TEXT_CHARS) }
                )
              )
            }
          }
        }
      }
    return safe
  }

  private fun isOwnedLocalUri(uri: Uri, applicationId: String): Boolean = when (
    uri.scheme?.lowercase()
  ) {
    "content" -> uri.authority == "$applicationId.fileprovider"
    "android.resource" -> uri.authority == applicationId
    else -> false
  }

  internal const val MAX_MEDIA_ID_CHARS = 2_048
  internal const val MAX_TEXT_CHARS = 512
  private const val MAX_URI_CHARS = 2_048
  private const val MAX_EXTRAS = 16
  private const val MAX_EXTRA_LIST_ITEMS = 8
  private const val MAX_EXTRA_KEY_CHARS = 128
  private const val MAX_EXTRA_TEXT_CHARS = 256
}
