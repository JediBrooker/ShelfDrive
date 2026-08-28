package com.audiobookshelf.app.media

import android.os.Bundle
import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceSearchRequestTest {
  @Test
  fun `empty query and extras form a general play request`() {
    val request = VoiceSearchRequest.from("   ", Bundle())

    assertTrue(request.isGeneralRequest)
    assertTrue(request.searchTerms.isEmpty())
  }

  @Test
  fun `all standard media extras are retained and prioritized`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_TITLE, "The Tombs of Atuan")
      putString(MediaStore.EXTRA_MEDIA_ARTIST, "Ursula K. Le Guin")
      putString(MediaStore.EXTRA_MEDIA_ALBUM, "Earthsea")
      putString(MediaStore.EXTRA_MEDIA_PLAYLIST, "Favourites")
      putString(MediaStore.EXTRA_MEDIA_GENRE, "Fantasy")
    }

    val request = VoiceSearchRequest.from("Tombs", extras)

    assertFalse(request.isGeneralRequest)
    assertEquals("The Tombs of Atuan", request.title)
    assertEquals("Ursula K. Le Guin", request.artist)
    assertEquals("Earthsea", request.album)
    assertEquals("Favourites", request.playlist)
    assertEquals("Fantasy", request.genre)
    assertEquals(
      listOf(
        "The Tombs of Atuan",
        "Ursula K. Le Guin",
        "Earthsea",
        "Favourites",
        "Fantasy",
        "Tombs"
      ),
      request.searchTerms
    )
  }

  @Test
  fun `media focus interprets an otherwise untyped query`() {
    val extras = Bundle().apply {
      putString(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
    }

    val request = VoiceSearchRequest.from("Ursula K. Le Guin", extras)

    assertEquals("Ursula K. Le Guin", request.artist)
    assertEquals(MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE, request.mediaFocus)
  }

  @Test
  fun `ranking honors author narrator series genre and punctuation`() {
    val request = VoiceSearchRequest(
      query = "Tombs of Atuan",
      mediaFocus = null,
      title = "The Tombs of Atuan",
      artist = "Ursula K Le Guin",
      album = "Earthsea Cycle",
      genre = "Fantasy",
      playlist = null
    )
    val matching = VoiceSearchCandidate(
      id = "matching",
      title = "The Tombs of Atuan",
      artists = listOf("Ursula K. Le Guin", "Rob Inglis"),
      albums = listOf("Earthsea Cycle"),
      genres = listOf("Fantasy")
    )
    val unrelated = VoiceSearchCandidate(
      id = "unrelated",
      title = "A Brief History of Time",
      artists = listOf("Stephen Hawking"),
      albums = listOf("Science"),
      genres = listOf("Nonfiction")
    )

    assertTrue(request.score(matching) > request.score(unrelated))
    assertEquals(0, request.score(unrelated))
  }
}
