package com.audiobookshelf.app.managers

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InternalDownloadManagerTest {
  private lateinit var server: MockWebServer
  private lateinit var destination: File

  @Before
  fun setUp() {
    server = MockWebServer().apply { start() }
    destination = File.createTempFile("shelfdrive-download-", ".bin")
  }

  @After
  fun tearDown() {
    server.shutdown()
    destination.delete()
  }

  @Test
  fun `chunked response without content length downloads without dividing by zero`() {
    server.enqueue(MockResponse().setResponseCode(200).setChunkedBody("abcdef", 2))
    val callback = RecordingCallback()

    InternalDownloadManager(FileOutputStream(destination), callback)
      .download(server.url("/audio").toString())

    assertTrue("download did not complete", callback.completed.await(3, TimeUnit.SECONDS))
    assertFalse(callback.failed)
    assertEquals(6L, callback.totalBytes)
    assertEquals(0L, callback.lastProgress)
    assertArrayEquals("abcdef".toByteArray(), destination.readBytes())
  }

  @Test
  fun `http error marks download failed and does not save error body`() {
    server.enqueue(MockResponse().setResponseCode(503).setBody("not media"))
    val callback = RecordingCallback()

    InternalDownloadManager(FileOutputStream(destination), callback)
      .download(server.url("/audio").toString())

    assertTrue("download did not complete", callback.completed.await(3, TimeUnit.SECONDS))
    assertTrue(callback.failed)
    assertEquals(0L, destination.length())
  }

  @Test
  fun `invalid URL fails once and closes destination`() {
    val callback = RecordingCallback()
    val output = FileOutputStream(destination)

    InternalDownloadManager(output, callback).download("not a URL")

    assertTrue(callback.completed.await(1, TimeUnit.SECONDS))
    assertTrue(callback.failed)
    assertEquals(1, callback.completionCount.get())
    try {
      output.write(1)
      throw AssertionError("destination should have been closed")
    } catch (_: IOException) {
      // Expected: malformed persisted/server URLs must not leak the open file handle.
    }
  }

  @Test
  fun `throwing completion callback is not retried as a failure`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
    val completionCount = AtomicInteger(0)
    val completed = CountDownLatch(1)
    val callback = object : DownloadItemManager.InternalProgressCallback {
      override fun onProgress(totalBytesWritten: Long, progress: Long) = Unit

      override fun onComplete(failed: Boolean) {
        completionCount.incrementAndGet()
        completed.countDown()
        throw IllegalStateException("test callback failure")
      }
    }

    InternalDownloadManager(FileOutputStream(destination), callback)
      .download(server.url("/audio").toString())

    assertTrue(completed.await(3, TimeUnit.SECONDS))
    assertEquals(1, completionCount.get())
  }

  @Test
  fun `duplicate start is ignored without racing the destination`() {
    server.enqueue(MockResponse().setResponseCode(200).setBody("audio"))
    val callback = RecordingCallback()
    val manager = InternalDownloadManager(FileOutputStream(destination), callback)

    manager.download(server.url("/first").toString())
    manager.download(server.url("/second").toString())

    assertTrue(callback.completed.await(3, TimeUnit.SECONDS))
    assertFalse(callback.failed)
    assertEquals(1, callback.completionCount.get())
    assertEquals("/first", server.takeRequest(1, TimeUnit.SECONDS)?.path)
    assertEquals(null, server.takeRequest(200, TimeUnit.MILLISECONDS))
  }

  private class RecordingCallback : DownloadItemManager.InternalProgressCallback {
    val completed = CountDownLatch(1)
    @Volatile var failed = false
    @Volatile var totalBytes = 0L
    @Volatile var lastProgress = -1L
    val completionCount = AtomicInteger(0)

    override fun onProgress(totalBytesWritten: Long, progress: Long) {
      totalBytes = totalBytesWritten
      lastProgress = progress
    }

    override fun onComplete(failed: Boolean) {
      this.failed = failed
      completionCount.incrementAndGet()
      completed.countDown()
    }
  }
}
