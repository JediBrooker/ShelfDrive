package com.audiobookshelf.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaProgressSafetyTest {
  @Test
  fun `non-finite and out-of-range progress is safe for car metadata`() {
    val progress = localProgress(Double.POSITIVE_INFINITY)
    assertEquals(0.0, progress.normalizedProgress, 0.0)
    assertEquals(0, progress.progressPercent)

    progress.progress = 5.0
    assertEquals(1.0, progress.normalizedProgress, 0.0)
    assertEquals(100, progress.progressPercent)

    progress.progress = -2.0
    assertEquals(0.0, progress.normalizedProgress, 0.0)
    assertEquals(0, progress.progressPercent)
  }

  @Test
  fun `ebook progress is clamped before persistence`() {
    val progress = localProgress(0.0)

    progress.updateEbookProgress("location", Double.NaN)
    assertEquals(0.0, progress.ebookProgress ?: -1.0, 0.0)

    progress.updateEbookProgress("location", 2.0)
    assertEquals(1.0, progress.ebookProgress ?: -1.0, 0.0)
  }

  private fun localProgress(progress: Double) = LocalMediaProgress(
    id = "progress",
    localLibraryItemId = "item",
    localEpisodeId = null,
    duration = 100.0,
    progress = progress,
    currentTime = 0.0,
    isFinished = false,
    ebookLocation = null,
    ebookProgress = null,
    lastUpdate = 0L,
    startedAt = 0L,
    finishedAt = null,
    serverConnectionConfigId = null,
    serverAddress = null,
    serverUserId = null,
    libraryItemId = null,
    episodeId = null
  )
}
