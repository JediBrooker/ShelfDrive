package com.audiobookshelf.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialRedactionTest {
  @Test
  fun `server connection string never contains credentials or identifying endpoint`() {
    val config = ServerConnectionConfig(
      id = "private-id",
      index = 3,
      name = "private.example (reviewer)",
      address = "https://private.example",
      version = "2.22.0",
      userId = "private-user-id",
      username = "reviewer",
      token = "access-secret",
      customHeaders = mapOf("X-Proxy-Key" to "header-secret")
    )

    val rendered = config.toString()

    assertTrue(rendered.contains("credentials=<redacted>"))
    listOf(
      "private-id",
      "private.example",
      "private-user-id",
      "reviewer",
      "access-secret",
      "X-Proxy-Key",
      "header-secret"
    ).forEach { secret -> assertFalse(rendered.contains(secret)) }
  }
}
