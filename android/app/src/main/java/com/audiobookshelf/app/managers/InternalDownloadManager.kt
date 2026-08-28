package com.audiobookshelf.app.managers

import android.util.Log
import java.io.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.*

/**
 * Manages the internal download process.
 *
 * @property outputStream The output stream to write the downloaded data.
 * @property progressCallback The callback to report download progress.
 */
class InternalDownloadManager(
        private val outputStream: FileOutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback,
        private val connectionId: String? = null
) : AutoCloseable {

  companion object {
    private val active = java.util.Collections.synchronizedSet(
      mutableSetOf<InternalDownloadManager>()
    )

    fun cancelForConnection(connectionId: String): Boolean {
      val matching = synchronized(active) {
        active.filter { it.connectionId == connectionId }
      }
      return matching.map { manager -> runCatching { manager.close() }.isSuccess }.all { it }
    }
  }

  private val tag = "InternalDownloadManager"
  private val client: OkHttpClient =
          OkHttpClient.Builder()
                  .connectTimeout(30, TimeUnit.SECONDS)
                  .readTimeout(60, TimeUnit.SECONDS)
                  .build()
  private val writer = BinaryFileWriter(outputStream, progressCallback)
  private val started = AtomicBoolean(false)
  private val completed = AtomicBoolean(false)
  private val closed = AtomicBoolean(false)
  @Volatile private var activeCall: Call? = null

  /**
   * Downloads a file from the given URL.
   *
   * @param url The URL to download the file from.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun download(url: String) {
    if (!started.compareAndSet(false, true)) {
      Log.w(tag, "Ignoring duplicate download start")
      return
    }
    if (closed.get()) {
      complete(failed = true)
      return
    }

    val request = try {
      Request.Builder().url(url).addHeader("Accept-Encoding", "identity").build()
    } catch (error: IllegalArgumentException) {
      Log.e(tag, "Download URL is invalid")
      complete(failed = true)
      closeWriter()
      return
    }

    val call = client.newCall(request)
    activeCall = call
    if (connectionId != null) active.add(this)
    call.enqueue(
                    object : Callback {
                      override fun onFailure(call: Call, e: IOException) {
                        activeCall = null
                        Log.e(tag, "Download failed (${e.javaClass.simpleName})")
                        complete(failed = true)
                        closeWriter()
                      }

                      override fun onResponse(call: Call, response: Response) {
                        try {
                          response.use {
                            if (!it.isSuccessful) {
                              Log.e(tag, "Download failed with HTTP ${it.code}")
                              complete(failed = true)
                              return@use
                            }
                            val responseBody = it.body
                            if (responseBody == null) {
                              Log.e(tag, "Download response does not contain a file")
                              complete(failed = true)
                              return@use
                            }
                            val length = responseBody.contentLength().coerceAtLeast(0L)
                            writer.write(responseBody.byteStream(), length)
                            complete(failed = false)
                          }
                        } catch (e: Exception) {
                          Log.e(tag, "Download stream failed (${e.javaClass.simpleName})")
                          complete(failed = true)
                        } finally {
                          activeCall = null
                          closeWriter()
                        }
                      }
                    }
            )
  }

  private fun complete(failed: Boolean) {
    if (!completed.compareAndSet(false, true)) return
    try {
      progressCallback.onComplete(failed)
    } catch (callbackError: Exception) {
      Log.w(tag, "Download completion callback failed (${callbackError.javaClass.simpleName})")
    }
  }

  private fun closeWriter() {
    closed.set(true)
    active.remove(this)
    try {
      writer.close()
    } catch (e: IOException) {
      Log.w(tag, "Could not close download output (${e.javaClass.simpleName})")
    }
  }

  /**
   * Closes the download manager and releases resources.
   *
   * @throws Exception If an error occurs during closing.
   */
  @Throws(Exception::class)
  override fun close() {
    activeCall?.cancel()
    closeWriter()
  }
}

/**
 * Writes binary data to an output stream.
 *
 * @property outputStream The output stream to write the data to.
 * @property progressCallback The callback to report write progress.
 */
class BinaryFileWriter(
        private val outputStream: OutputStream,
        private val progressCallback: DownloadItemManager.InternalProgressCallback
) : AutoCloseable {

  /**
   * Writes data from the input stream to the output stream.
   *
   * @param inputStream The input stream to read the data from.
   * @param length The total length of the data to be written.
   * @return The total number of bytes written.
   * @throws IOException If an I/O error occurs.
   */
  @Throws(IOException::class)
  fun write(inputStream: InputStream, length: Long): Long {
    BufferedInputStream(inputStream).use { input ->
      val dataBuffer = ByteArray(CHUNK_SIZE)
      var totalBytes: Long = 0
      var readBytes: Int
      while (input.read(dataBuffer).also { readBytes = it } != -1) {
        totalBytes += readBytes
        outputStream.write(dataBuffer, 0, readBytes)
        val progress = if (length > 0L) {
          ((totalBytes.coerceAtMost(length).toDouble() / length.toDouble()) * 100.0)
            .toLong()
            .coerceIn(0L, 100L)
        } else {
          0L
        }
        progressCallback.onProgress(totalBytes, progress)
      }
      return totalBytes
    }
  }

  /**
   * Closes the writer and releases resources.
   *
   * @throws IOException If an error occurs during closing.
   */
  @Throws(IOException::class)
  override fun close() {
    outputStream.close()
  }

  companion object {
    private const val CHUNK_SIZE = 8192 // Increased chunk size for better performance
  }
}
