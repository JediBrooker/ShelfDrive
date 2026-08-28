package com.audiobookshelf.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatedBrowserPackageRegistryTest {
  @Test
  fun `records each validated package once`() {
    val registry = ValidatedBrowserPackageRegistry()

    registry.record("com.android.car.media")
    registry.record("com.android.car.media")

    assertEquals(listOf("com.android.car.media"), registry.snapshot())
  }

  @Test
  fun `evicts the least recently accepted package at the bound`() {
    val registry = ValidatedBrowserPackageRegistry(maximumEntries = 2)

    registry.record("com.example.first")
    registry.record("com.example.second")
    registry.record("com.example.first")
    registry.record("com.example.third")

    assertEquals(
      listOf("com.example.first", "com.example.third"),
      registry.snapshot()
    )
  }

  @Test
  fun `ignores malformed or oversized package names`() {
    val registry = ValidatedBrowserPackageRegistry(maximumPackageNameLength = 20)

    registry.record("")
    registry.record("com.example bad")
    registry.record("com.example/bad")
    registry.record("com.example.this.is.too.long")

    assertTrue(registry.snapshot().isEmpty())
  }

  @Test
  fun `snapshot cannot mutate registry state`() {
    val registry = ValidatedBrowserPackageRegistry()
    registry.record("com.example.car")

    val snapshot = registry.snapshot().toMutableList()
    snapshot.clear()

    assertEquals(listOf("com.example.car"), registry.snapshot())
  }
}
