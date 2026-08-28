package com.audiobookshelf.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataModelCredentialRedactionTest {
  @Test
  fun `audio track diagnostics redact server supplied title URL and paths`() {
    val track = AudioTrack(
      index = 2,
      startOffset = 3.0,
      duration = 120.0,
      title = "Private Chapter",
      contentUrl = "https://private.example/audio?token=access-secret",
      mimeType = "audio/mpeg",
      metadata = FileMetadata(
        filename = "private-title.mp3",
        ext = ".mp3",
        path = "/private/library/private-title.mp3",
        relPath = "private/private-title.mp3",
        size = 42
      ),
      isLocal = false,
      localFileId = "private-file-id",
      serverIndex = 5
    )

    val rendered = track.toString()

    assertTrue(rendered.contains("titleUrlAndPaths=<redacted>"))
    listOf(
      "Private Chapter",
      "private.example",
      "access-secret",
      "private-title.mp3",
      "/private/library",
      "private-file-id"
    ).forEach { privateValue -> assertFalse(rendered.contains(privateValue)) }
  }

  @Test
  fun `device diagnostics redact app-instance ID and model identity`() {
    val info = DeviceInfo(
      deviceId = "private-app-instance-id",
      manufacturer = "private-manufacturer",
      model = "private-model",
      sdkVersion = 35,
      clientVersion = "0.1"
    )

    val rendered = info.toString()

    assertTrue(rendered.contains("deviceIdentity=<redacted>"))
    assertFalse(rendered.contains("private-app-instance-id"))
    assertFalse(rendered.contains("private-manufacturer"))
    assertFalse(rendered.contains("private-model"))
  }

  @Test
  fun `ebook and local storage diagnostics redact every URL identity and path`() {
    val metadata = FileMetadata(
      filename = "private-title.epub",
      ext = ".epub",
      path = "/private/library/private-title.epub",
      relPath = "private/private-title.epub",
      size = 42
    )
    val ebook = EBookFile(
      ino = "private-ino",
      metadata = metadata,
      ebookFormat = "epub",
      isLocal = false,
      localFileId = "private-file-id",
      contentUrl = "https://private.example/ebook?token=access-secret"
    )
    val localFile = LocalFile(
      id = "private-local-file-id",
      filename = "private-title.epub",
      contentUrl = "content://private.provider/private-title.epub",
      basePath = "/private/base",
      absolutePath = "/private/absolute/private-title.epub",
      simplePath = "private/private-title.epub",
      mimeType = "application/epub+zip",
      size = 42
    )
    val localFolder = LocalFolder(
      id = "private-folder-id",
      name = "Private Library",
      contentUrl = "content://private.provider/library",
      basePath = "/private/base",
      absolutePath = "/private/absolute",
      simplePath = "private/simple",
      storageType = "internal",
      mediaType = "book"
    )
    val localMedia = LocalMediaItem(
      id = "private-local-media-id",
      name = "Private Title",
      mediaType = "book",
      folderId = "private-folder-id",
      contentUrl = "content://private.provider/item",
      simplePath = "private/simple",
      basePath = "/private/base",
      absolutePath = "/private/absolute",
      audioTracks = mutableListOf(),
      ebookFile = ebook,
      localFiles = mutableListOf(localFile),
      coverContentUrl = "content://private.provider/cover",
      coverAbsolutePath = "/private/cover.jpg"
    )
    val folder = Folder("private-server-folder-id", "/private/server/library")

    val rendered = listOf(metadata, ebook, localFile, localFolder, localMedia, folder)
      .joinToString("\n")

    listOf(
      "filenameAndPaths=<redacted>",
      "contentAndPaths=<redacted>",
      "fileIdentityAndPaths=<redacted>",
      "folderIdentityAndPaths=<redacted>",
      "identityAndPaths=<redacted>",
      "identityAndPath=<redacted>"
    ).forEach { marker -> assertTrue(rendered.contains(marker)) }
    listOf(
      "Private Library",
      "Private Title",
      "private-title.epub",
      "private.example",
      "access-secret",
      "private.provider",
      "/private/",
      "private-folder-id",
      "private-local-media-id",
      "private-server-folder-id"
    ).forEach { privateValue -> assertFalse(rendered.contains(privateValue)) }
  }

  @Test
  fun `author diagnostics redact server identity and artwork path`() {
    val author = Author(
      id = "private-author-id",
      name = "Private Author",
      coverPath = "/api/authors/private-author-id/cover?token=access-secret"
    )

    val rendered = author.toString()

    assertTrue(rendered.contains("identityAndPath=<redacted>"))
    assertTrue(rendered.contains("hasCover=true"))
    listOf("private-author-id", "Private Author", "/api/authors", "access-secret")
      .forEach { privateValue -> assertFalse(rendered.contains(privateValue)) }
  }
}
