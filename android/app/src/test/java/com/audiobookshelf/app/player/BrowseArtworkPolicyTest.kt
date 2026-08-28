package com.audiobookshelf.app.player

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BrowseArtworkPolicyTest {
  private val applicationId = "com.example.shelfdrive"

  @Test
  fun `remote artwork is replaced with a local resource URI`() {
    val source = mediaItem(iconUri = Uri.parse("https://example.test/cover.jpg"))

    val result = BrowseArtworkPolicy.sanitize(listOf(source), applicationId).single()

    assertEquals(
      "android.resource://$applicationId/drawable/icon",
      result.description.iconUri.toString()
    )
    assertNull(result.description.iconBitmap)
  }

  @Test
  fun `content and android resource artwork are preserved`() {
    val content = mediaItem(iconUri = Uri.parse("content://$applicationId.fileprovider/covers/one.jpg"))
    val resource = mediaItem(iconUri = Uri.parse("android.resource://$applicationId/drawable/icon"))

    val result = BrowseArtworkPolicy.sanitize(listOf(content, resource), applicationId)

    assertEquals(content.description.iconUri, result[0].description.iconUri)
    assertEquals(resource.description.iconUri, result[1].description.iconUri)
  }

  @Test
  fun `foreign content URI is replaced instead of leaking an unusable grant`() {
    val source = mediaItem(iconUri = Uri.parse("content://foreign.provider/private/cover.jpg"))

    val result = BrowseArtworkPolicy.sanitize(listOf(source), applicationId).single()

    assertEquals(
      "android.resource://$applicationId/drawable/icon",
      result.description.iconUri.toString()
    )
  }

  @Test
  fun `bitmap artwork is removed while description data is retained`() {
    val extras = Bundle().apply { putString("group", "Recently added") }
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val description = MediaDescriptionCompat.Builder()
      .setMediaId("book-1")
      .setTitle("Book")
      .setSubtitle("Author")
      .setDescription("Description")
      .setIconBitmap(bitmap)
      .setExtras(extras)
      .build()
    val source = MediaBrowserCompat.MediaItem(
      description,
      MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    val result = BrowseArtworkPolicy.sanitize(listOf(source), applicationId).single()

    assertNull(result.description.iconBitmap)
    assertEquals("android.resource://$applicationId/drawable/icon", result.description.iconUri.toString())
    assertEquals("book-1", result.mediaId)
    assertEquals("Book", result.description.title)
    assertEquals("Author", result.description.subtitle)
    assertEquals("Description", result.description.description)
    assertEquals("Recently added", result.description.extras?.getString("group"))
    assertEquals(MediaBrowserCompat.MediaItem.FLAG_PLAYABLE, result.flags)
  }

  @Test
  fun `missing artwork stays missing`() {
    val result = BrowseArtworkPolicy.sanitize(listOf(mediaItem()), applicationId).single()

    assertNull(result.description.iconUri)
    assertNull(result.description.iconBitmap)
  }

  @Test
  fun `oversized ids are omitted and visible text is bounded`() {
    val invalid = MediaBrowserCompat.MediaItem(
      MediaDescriptionCompat.Builder()
        .setMediaId("x".repeat(BrowseArtworkPolicy.MAX_MEDIA_ID_CHARS + 1))
        .setTitle("invalid")
        .build(),
      MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )
    val valid = MediaBrowserCompat.MediaItem(
      MediaDescriptionCompat.Builder()
        .setMediaId("valid")
        .setTitle("t".repeat(BrowseArtworkPolicy.MAX_TEXT_CHARS + 50))
        .setSubtitle("s".repeat(BrowseArtworkPolicy.MAX_TEXT_CHARS + 50))
        .build(),
      MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    val result = BrowseArtworkPolicy.sanitize(listOf(invalid, valid), applicationId)

    assertEquals(1, result.size)
    assertEquals(BrowseArtworkPolicy.MAX_TEXT_CHARS, result.single().description.title!!.length)
    assertEquals(BrowseArtworkPolicy.MAX_TEXT_CHARS, result.single().description.subtitle!!.length)
  }

  @Suppress("DEPRECATION") // Exercise the minSdk-compatible Bundle path.
  @Test
  fun `extras retain bounded primitives and discard parcelables`() {
    val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
    val extras = Bundle().apply {
      putString("label", "x".repeat(1_000))
      putDouble("progress", 0.5)
      putParcelable("untrusted", bitmap)
    }
    val item = MediaBrowserCompat.MediaItem(
      MediaDescriptionCompat.Builder()
        .setMediaId("valid")
        .setTitle("Title")
        .setMediaUri(Uri.parse("https://example.test/audio?token=secret"))
        .setExtras(extras)
        .build(),
      MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
    )

    val result = BrowseArtworkPolicy.sanitize(listOf(item), applicationId).single().description

    assertEquals(256, result.extras!!.getString("label")!!.length)
    assertEquals(0.5, result.extras!!.getDouble("progress"), 0.0)
    assertNull(result.extras!!.getParcelable<Bitmap>("untrusted"))
    assertNull(result.mediaUri)
  }

  private fun mediaItem(iconUri: Uri? = null): MediaBrowserCompat.MediaItem {
    val description = MediaDescriptionCompat.Builder()
      .setMediaId("id")
      .setTitle("Title")
      .setIconUri(iconUri)
      .build()
    return MediaBrowserCompat.MediaItem(
      description,
      MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
    )
  }
}
