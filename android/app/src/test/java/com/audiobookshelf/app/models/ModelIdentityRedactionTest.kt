package com.audiobookshelf.app.models

import com.audiobookshelf.app.data.LocalFolder
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelIdentityRedactionTest {
  @Test
  fun `download item diagnostics redact identity endpoint title and paths`() {
    val item = DownloadItem(
      id = "private-download-id",
      libraryItemId = "private-library-item-id",
      episodeId = "private-episode-id",
      userMediaProgress = null,
      serverConnectionConfigId = "private-connection-id",
      serverAddress = "https://private.example",
      serverUserId = "private-user-id",
      mediaType = "book",
      itemFolderPath = "/private/library/private-title",
      localFolder = LocalFolder(
        id = "internal-private-folder",
        name = "Private Library",
        contentUrl = "content://private.provider/library",
        basePath = "/private/base",
        absolutePath = "/private/absolute",
        simplePath = "private/simple",
        storageType = "internal",
        mediaType = "book"
      ),
      itemTitle = "Private Title",
      itemSubfolder = "private-subfolder",
      media = MediaType(MediaTypeMetadata("Private Title", false), "/private/cover.jpg"),
      downloadItemParts = mutableListOf()
    )

    val rendered = item.toString()

    assertTrue(rendered.contains("identifiersAndPaths=<redacted>"))
    listOf(
      "private-download-id",
      "private-library-item-id",
      "private-episode-id",
      "private-connection-id",
      "private.example",
      "private-user-id",
      "Private Library",
      "Private Title",
      "private.provider",
      "private-subfolder"
    ).forEach { privateValue -> assertFalse(rendered.contains(privateValue)) }
  }

  @Test
  fun `user diagnostics redact account identity`() {
    val user = User(
      id = "private-user-id",
      username = "private-reviewer",
      mediaProgress = emptyList()
    )

    val rendered = user.toString()

    assertTrue(rendered.contains("identity=<redacted>"))
    assertFalse(rendered.contains("private-user-id"))
    assertFalse(rendered.contains("private-reviewer"))
  }
}
