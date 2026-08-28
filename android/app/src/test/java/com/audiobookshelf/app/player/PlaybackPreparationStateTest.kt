package com.audiobookshelf.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPreparationStateTest {
  @Test
  fun pendingPreparationIsTrackedUntilCompletionOrCancellation() {
    val state = PlaybackPreparationState()

    assertFalse(state.hasPendingPreparation())
    val generation = state.begin(false)
    assertTrue(state.hasPendingPreparation())

    state.updateDesiredPlayWhenReady(true)
    assertTrue(state.resolve(generation)!!)
    assertFalse(state.hasPendingPreparation())

    state.begin(true)
    assertTrue(state.hasPendingPreparation())
    state.cancel()
    assertFalse(state.hasPendingPreparation())
  }

  @Test
  fun completionUsesLatestDesiredPlaybackState() {
    val state = PlaybackPreparationState()
    val prepareGeneration = state.begin(false)

    state.updateDesiredPlayWhenReady(true)
    assertTrue(state.resolve(prepareGeneration)!!)

    state.updateDesiredPlayWhenReady(false)
    assertNull(state.resolve(prepareGeneration))
  }

  @Test
  fun stalePrepareCompletionIsIgnored() {
    val state = PlaybackPreparationState()
    val staleGeneration = state.begin(true)
    val currentGeneration = state.begin(false)

    assertTrue(currentGeneration > staleGeneration)
    assertNull(state.resolve(staleGeneration))
    assertFalse(state.resolve(currentGeneration)!!)
  }

  @Test
  fun cancelInvalidatesPendingCompletionAndClearsPlaybackIntent() {
    val state = PlaybackPreparationState()
    val pendingGeneration = state.begin(true)

    state.cancel()

    assertNull(state.resolve(pendingGeneration))
    assertFalse(state.desiredPlayWhenReady)
  }

  @Test
  fun onlyCurrentPrepareCanBeCancelled() {
    val state = PlaybackPreparationState()
    val staleGeneration = state.begin(true)
    val currentGeneration = state.begin(true)

    assertFalse(state.cancel(staleGeneration))
    assertTrue(state.isCurrent(currentGeneration))
    assertTrue(state.cancel(currentGeneration))
    assertFalse(state.isCurrent(currentGeneration))
    assertNull(state.resolve(currentGeneration))
  }
}
