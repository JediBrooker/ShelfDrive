package com.audiobookshelf.app.media

import com.audiobookshelf.app.data.ServerConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConnectionCandidatePolicyTest {
  @Test
  fun selectedProfileIsNotRetriedAsItsOwnFallback() {
    val selected = config("selected")

    val candidates = orderedServerConnectionCandidates(
      selected,
      listOf(selected, config("fallback"), config("third"))
    )

    assertEquals(listOf("selected", "fallback"), candidates.map { it.id })
  }

  @Test
  fun automaticFallbackIsBoundedAndDeduplicated() {
    val selected = config("selected")

    val candidates = orderedServerConnectionCandidates(
      selected,
      listOf(config("fallback"), config("fallback"), config("third"))
    )

    assertEquals(listOf("selected", "fallback"), candidates.map { it.id })
  }

  @Test
  fun noSelectedProfileUsesOnlyOneSavedCandidate() {
    val candidates = orderedServerConnectionCandidates(
      null,
      listOf(config("first"), config("second"))
    )

    assertEquals(listOf("first"), candidates.map { it.id })
  }

  private fun config(id: String) = ServerConnectionConfig(
    id = id,
    index = 0,
    name = id,
    address = "https://$id.example",
    version = null,
    userId = "user",
    username = "username",
    token = "token",
    customHeaders = null
  )
}
