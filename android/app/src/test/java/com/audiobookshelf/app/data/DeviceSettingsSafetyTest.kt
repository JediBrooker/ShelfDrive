package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSettingsSafetyTest {
  @Test
  fun invalidSeekIncrementsRemainPositiveForExoPlayer() {
    val settings = DeviceSettings.default().apply {
      jumpBackwardsTime = 0
      jumpForwardTime = -10
    }

    assertEquals(1_000L, settings.jumpBackwardsTimeMs)
    assertEquals(1_000L, settings.jumpForwardTimeMs)
  }

  @Test
  fun malformedSleepClockFallsBackWithoutThrowing() {
    val settings = DeviceSettings.default().apply {
      autoSleepTimerStartTime = "broken"
      autoSleepTimerEndTime = "99:not-a-minute"
    }

    assertEquals(22, settings.autoSleepTimerStartHour)
    assertEquals(0, settings.autoSleepTimerStartMinute)
    assertEquals(6, settings.autoSleepTimerEndHour)
    assertEquals(0, settings.autoSleepTimerEndMinute)
  }
}
