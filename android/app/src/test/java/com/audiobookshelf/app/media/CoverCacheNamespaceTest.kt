package com.audiobookshelf.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverCacheNamespaceTest {
  @Test
  fun `equivalent server addresses produce the same namespace`() {
    val first = coverCacheNamespace("config", "user", "https://EXAMPLE.com/library/")
    val second = coverCacheNamespace("config", "user", "https://example.com:443/library")

    assertEquals(first, second)
  }

  @Test
  fun `profile user server and base path each isolate the namespace`() {
    val baseline = coverCacheNamespace("config-a", "user-a", "https://one.example/library")

    assertNotEquals(baseline, coverCacheNamespace("config-b", "user-a", "https://one.example/library"))
    assertNotEquals(baseline, coverCacheNamespace("config-a", "user-b", "https://one.example/library"))
    assertNotEquals(baseline, coverCacheNamespace("config-a", "user-a", "https://two.example/library"))
    assertNotEquals(baseline, coverCacheNamespace("config-a", "user-a", "https://one.example/other"))
  }

  @Test
  fun `invalid server address cannot create a namespace`() {
    assertNull(coverCacheNamespace("config", "user", "not a URL"))
  }

  @Test
  fun `scope diagnostics never render authentication material`() {
    val snapshotClass = Class.forName(
      "com.audiobookshelf.app.media.CoverCache\$ScopeSnapshot"
    )
    val constructor = snapshotClass.declaredConstructors.single().apply {
      isAccessible = true
    }
    val snapshot = constructor.newInstance(
      "private-config-id|private-user-id|https://private-namespace.example/private-path",
      "https",
      "private.example",
      443,
      "access-secret",
      mapOf("X-Proxy-Key" to "header-secret")
    )

    val rendered = snapshot.toString()

    assertTrue(rendered.contains("credentials=<redacted>"))
    assertTrue(rendered.contains("scope=<redacted>"))
    listOf(
      "private-config-id",
      "private-user-id",
      "private-namespace.example",
      "private-path",
      "private.example",
      "access-secret",
      "X-Proxy-Key",
      "header-secret"
    ).forEach { secret -> assertFalse(rendered.contains(secret)) }
  }
}
