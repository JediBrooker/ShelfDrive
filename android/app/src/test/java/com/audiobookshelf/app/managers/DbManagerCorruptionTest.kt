package com.audiobookshelf.app.managers

import android.content.Context
import com.audiobookshelf.app.data.LocalLibraryItem
import com.audiobookshelf.app.data.MediaType
import com.audiobookshelf.app.data.MediaTypeMetadata
import com.audiobookshelf.app.plugins.AbsLog
import io.paperdb.Paper
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbManagerCorruptionTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()
  private val db = DbManager()

  @Before
  fun setUp() {
    Paper.init(context)
    BOOKS.forEach { Paper.book(it).destroy() }
  }

  @After
  fun tearDown() {
    BOOKS.forEach { Paper.book(it).destroy() }
  }

  @Test
  fun `wrong persisted types fall back instead of crashing media startup`() {
    Paper.book("device").write("data", "not device data")
    Paper.book("localLibraryItems").write("bad-item", "not a library item")
    Paper.book("playbackSession").write("bad-session", "not a session")

    assertNotNull(db.getDeviceData().deviceSettings)
    assertTrue(db.getLocalLibraryItems().isEmpty())
    assertTrue(db.getPlaybackSessions().isEmpty())
  }

  @Test
  fun `podcast lookup ignores mismatched persisted media subtype`() {
    db.saveLocalLibraryItem(
      LocalLibraryItem(
        id = "bad-podcast",
        folderId = "folder",
        basePath = "",
        absolutePath = "",
        contentUrl = "",
        isInvalid = false,
        mediaType = "podcast",
        media = MediaType(MediaTypeMetadata("Title", false), null),
        localFiles = mutableListOf(),
        coverContentUrl = null,
        coverAbsolutePath = null,
        isLocal = true,
        serverConnectionConfigId = "server",
        serverAddress = "https://example.test",
        serverUserId = "user",
        libraryItemId = "item"
      )
    )

    assertNull(db.getLocalLibraryItemWithEpisode("episode"))
  }

  @Test
  fun `download lookup never crosses accounts with the same library item id`() {
    db.saveLocalLibraryItem(localItem("download-a", "account-a", "shared-item"))
    db.saveLocalLibraryItem(localItem("download-b", "account-b", "shared-item"))

    assertEquals(
      "download-a",
      db.getLocalLibraryItemByLId("shared-item", "account-a")?.id
    )
    assertEquals(
      "download-b",
      db.getLocalLibraryItemByLId("shared-item", "account-b")?.id
    )
    assertNull(db.getLocalLibraryItemByLId("shared-item", "missing-account"))
    assertNull(db.getLocalLibraryItemByLId("shared-item", ""))
  }

  @Test
  fun `first log save removes malformed expired and excess entries`() {
    val now = System.currentTimeMillis()
    Paper.book("log").write("malformed", "not a log")
    Paper.book("log").write(
      "expired",
      AbsLog("expired", "test", "info", "old", now - 49L * 60L * 60L * 1000L)
    )
    repeat(1_005) { index ->
      val id = "recent-$index"
      Paper.book("log").write(
        id,
        AbsLog(id, "test", "info", "message", now + index)
      )
    }

    db.saveLog(AbsLog("incoming", "test", "info", "new", now + 2_000L))

    val logs = db.getAllLogs()
    assertEquals(1_000, logs.size)
    assertTrue(logs.any { it.id == "incoming" })
    assertTrue(logs.none { it.id == "expired" })
    assertTrue("malformed entry was not pruned", "malformed" !in Paper.book("log").allKeys)
  }

  private fun localItem(id: String, connectionId: String, libraryItemId: String) =
    LocalLibraryItem(
      id = id,
      folderId = "folder",
      basePath = "",
      absolutePath = "",
      contentUrl = "content://test/$id",
      isInvalid = false,
      mediaType = "book",
      media = MediaType(MediaTypeMetadata("Title", false), null),
      localFiles = mutableListOf(),
      coverContentUrl = null,
      coverAbsolutePath = null,
      isLocal = true,
      serverConnectionConfigId = connectionId,
      serverAddress = "https://example.test",
      serverUserId = "user",
      libraryItemId = libraryItemId
    )

  private companion object {
    val BOOKS = listOf("device", "localLibraryItems", "playbackSession", "log")
  }
}
