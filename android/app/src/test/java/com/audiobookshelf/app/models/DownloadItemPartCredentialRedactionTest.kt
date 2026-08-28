package com.audiobookshelf.app.models

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadItemPartCredentialRedactionTest {
  @Test
  fun `download item diagnostics never render token bearing URI or private paths`() {
    val part = DownloadItemPart(
      id = "private-id",
      downloadItemId = "private-download-id",
      filename = "private-title.mp3",
      fileSize = 123,
      finalDestinationPath = "/private/library/private-title.mp3",
      serverPath = "/api/items/private-item/file",
      localFolderName = "Private Library",
      localFolderUrl = "content://private.provider/library",
      localFolderId = "private-folder-id",
      ebookFile = null,
      audioTrack = null,
      episode = null,
      completed = false,
      moved = false,
      isMoving = false,
      failed = false,
      uri = Uri.parse("https://private.example/audio?token=access-secret"),
      destinationUri = Uri.parse("content://private.provider/staging"),
      finalDestinationUri = Uri.parse("content://private.provider/final"),
      finalDestinationSubfolder = "private-subfolder",
      downloadId = 42,
      progress = 5,
      bytesDownloaded = 10
    )

    val rendered = part.toString()

    assertTrue(rendered.contains("identifiersAndUris=<redacted>"))
    listOf(
      "private-id",
      "private-title",
      "private.example",
      "access-secret",
      "private.provider",
      "private-subfolder"
    ).forEach { secret -> assertFalse(rendered.contains(secret)) }
  }
}
