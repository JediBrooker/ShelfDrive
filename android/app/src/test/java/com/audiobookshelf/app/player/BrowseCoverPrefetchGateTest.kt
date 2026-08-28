package com.audiobookshelf.app.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseCoverPrefetchGateTest {
  @Test
  fun `parent can claim only one batch in a server session`() {
    val gate = BrowseCoverPrefetchGate()

    assertTrue(gate.claim("server-a", "parent"))
    assertFalse(gate.claim("server-a", "parent"))
    assertTrue(gate.claim("server-a", "other-parent"))
    assertTrue(gate.claim("server-b", "parent"))
  }

  @Test
  fun `concurrent reloads grant exactly one batch`() {
    val gate = BrowseCoverPrefetchGate()
    val executor = Executors.newFixedThreadPool(8)
    val start = CountDownLatch(1)

    try {
      val claims = (1..32).map {
        executor.submit<Boolean> {
          start.await()
          gate.claim("server", "parent")
        }
      }
      start.countDown()

      assertEquals(1, claims.count { it.get() })
    } finally {
      executor.shutdownNow()
    }
  }

  @Test
  fun `clearing starts a new service session`() {
    val gate = BrowseCoverPrefetchGate()
    assertTrue(gate.claim("server", "parent"))

    gate.clear()

    assertTrue(gate.claim("server", "parent"))
  }
}
