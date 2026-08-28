package com.audiobookshelf.app.util

import android.content.Context
import android.provider.Settings
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppInstanceIdTest {
  private val context: Context
    get() = RuntimeEnvironment.getApplication()

  @Before
  fun setUp() {
    context.getSharedPreferences(AppInstanceId.PREFS_NAME, Context.MODE_PRIVATE)
      .edit().clear().commit()
    AppInstanceId.clearMemoryForTest()
  }

  @After
  fun tearDown() {
    context.getSharedPreferences(AppInstanceId.PREFS_NAME, Context.MODE_PRIVATE)
      .edit().clear().commit()
    AppInstanceId.clearMemoryForTest()
  }

  @Test
  fun `identifier is random install scoped and survives process memory reset`() {
    Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, "hardware-id")

    val first = AppInstanceId.get(context)
    AppInstanceId.clearMemoryForTest()
    val afterRestart = AppInstanceId.get(context)

    assertEquals(first, afterRestart)
    assertEquals(first, UUID.fromString(first).toString())
    assertNotEquals("hardware-id", first)
  }

  @Test
  fun `invalid persisted identifier is rotated instead of reused`() {
    context.getSharedPreferences(AppInstanceId.PREFS_NAME, Context.MODE_PRIVATE)
      .edit().putString(AppInstanceId.KEY_ID, "not-an-install-uuid").commit()

    val id = AppInstanceId.get(context)

    assertNotEquals("not-an-install-uuid", id)
    assertEquals(id, UUID.fromString(id).toString())
  }
}
