package com.audiobookshelf.app.player

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginBoundBearerInterceptorTest {
  @Test
  fun `bearer is attached only to the configured origin`() {
    val owner = MockWebServer()
    val foreign = MockWebServer()
    owner.enqueue(MockResponse().setBody("owner"))
    foreign.enqueue(MockResponse().setBody("foreign"))
    owner.start()
    foreign.start()
    try {
      val client = OkHttpClient.Builder()
        .addInterceptor(OriginBoundBearerInterceptor(owner.url("/"), "private-token"))
        .build()

      client.newCall(Request.Builder().url(owner.url("/playlist.m3u8")).build())
        .execute().use { assertEquals(200, it.code) }
      client.newCall(
        Request.Builder()
          .url(foreign.url("/segment.aac"))
          // Defense in depth: strip a header inherited from a caller too.
          .header("Authorization", "Bearer should-be-removed")
          .build()
      ).execute().use { assertEquals(200, it.code) }

      assertEquals(
        "Bearer private-token",
        owner.takeRequest().getHeader("Authorization")
      )
      assertNull(foreign.takeRequest().getHeader("Authorization"))
    } finally {
      owner.shutdown()
      foreign.shutdown()
    }
  }

  @Test
  fun `cross-origin redirect cannot carry the bearer`() {
    val owner = MockWebServer()
    val foreign = MockWebServer()
    foreign.enqueue(MockResponse().setBody("redirected"))
    owner.start()
    foreign.start()
    owner.enqueue(
      MockResponse()
        .setResponseCode(302)
        .setHeader("Location", foreign.url("/foreign-playlist.m3u8"))
    )
    try {
      val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(false)
        .addInterceptor(OriginBoundBearerInterceptor(owner.url("/"), "private-token"))
        .build()

      client.newCall(Request.Builder().url(owner.url("/start.m3u8")).build())
        .execute().use { assertEquals(200, it.code) }

      assertEquals(
        "Bearer private-token",
        owner.takeRequest().getHeader("Authorization")
      )
      assertNull(foreign.takeRequest().getHeader("Authorization"))
    } finally {
      owner.shutdown()
      foreign.shutdown()
    }
  }
}
