package com.audiobookshelf.app.managers

import com.audiobookshelf.app.data.MediaItemEvent
import com.audiobookshelf.app.data.MediaItemHistory
import io.paperdb.Paper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DbManagerHistoryScopeTest {
  private val db = DbManager()

  @Before
  fun setUp() {
    Paper.init(RuntimeEnvironment.getApplication())
    Paper.book("mediaItemHistory").destroy()
  }

  @After
  fun tearDown() {
    Paper.book("mediaItemHistory").destroy()
  }

  @Test
  fun identicalMediaIdsRemainIsolatedAcrossAccountsAndRemoval() {
    val accountA = history("shared-item", "account-a", "Play A")
    val accountB = history("shared-item", "account-b", "Play B")

    db.saveMediaItemHistory(accountA)
    db.saveMediaItemHistory(accountB)

    assertEquals(
      "Play A",
      db.getMediaItemHistory("shared-item", "account-a")?.events?.single()?.name
    )
    assertEquals(
      "Play B",
      db.getMediaItemHistory("shared-item", "account-b")?.events?.single()?.name
    )
    assertEquals(2, db.getAllMediaItemHistory().size)

    db.removeMediaItemHistoryForConnection("account-a")

    assertNull(db.getMediaItemHistory("shared-item", "account-a"))
    assertEquals(
      "Play B",
      db.getMediaItemHistory("shared-item", "account-b")?.events?.single()?.name
    )
  }

  @Test
  fun mediaHistoryRetainsOnlyTheNewestBoundedEvents() {
    val history = history("large-history", "account-a", "event-0")
    for (index in 1..(MAX_MEDIA_ITEM_HISTORY_EVENTS + 20)) {
      history.events += MediaItemEvent(
        name = "event-$index",
        type = "Playback",
        description = "",
        currentTime = 0.0,
        serverSyncAttempted = false,
        serverSyncSuccess = null,
        serverSyncMessage = null,
        timestamp = index.toLong()
      )
    }

    db.saveMediaItemHistory(history)

    val stored = db.getMediaItemHistory("large-history", "account-a")!!
    assertEquals(MAX_MEDIA_ITEM_HISTORY_EVENTS, stored.events.size)
    assertTrue(stored.events.first().timestamp > 0L)
    assertEquals(
      (MAX_MEDIA_ITEM_HISTORY_EVENTS + 20).toLong(),
      stored.events.last().timestamp
    )
  }

  private fun history(id: String, owner: String, eventName: String) = MediaItemHistory(
    id = id,
    mediaDisplayTitle = "Title",
    libraryItemId = id,
    episodeId = null,
    isLocal = false,
    serverConnectionConfigId = owner,
    serverAddress = "https://$owner.example.test",
    serverUserId = "user",
    createdAt = 1L,
    events = mutableListOf(
      MediaItemEvent(
        name = eventName,
        type = "Playback",
        description = "",
        currentTime = 0.0,
        serverSyncAttempted = false,
        serverSyncSuccess = null,
        serverSyncMessage = null,
        timestamp = 1L
      )
    )
  )
}
