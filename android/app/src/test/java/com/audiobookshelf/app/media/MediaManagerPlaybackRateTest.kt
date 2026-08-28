package com.audiobookshelf.app.media

import android.content.Context
import com.audiobookshelf.app.server.ApiHandler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MediaManagerPlaybackRateTest {
  private lateinit var context: Context

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
      .edit()
      .clear()
      .commit()
  }

  @Test
  fun `first playback rate preference is persisted`() {
    val mediaManager = MediaManager(ApiHandler(context), context)

    mediaManager.setSavedPlaybackRate(1.2f)

    val stored = context.getSharedPreferences("CapacitorStorage", Context.MODE_PRIVATE)
      .getString("userSettings", null)
    assertEquals(1.2, JSONObject(stored!!).getDouble("playbackRate"), 0.0001)

    mediaManager.userSettingsPlaybackRate = null
    assertEquals(1.2f, mediaManager.getSavedPlaybackRate(), 0.0001f)
  }
}
