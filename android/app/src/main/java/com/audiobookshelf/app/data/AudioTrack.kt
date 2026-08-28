package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class AudioTrack(
        var index: Int,
        var startOffset: Double,
        var duration: Double,
        var title: String,
        var contentUrl: String,
        var mimeType: String,
        var metadata: FileMetadata?,
        var isLocal: Boolean,
        var localFileId: String?,
        // TODO: This should no longer be necessary
        var serverIndex: Int? // Need to know if server track index is different
) {

  @get:JsonIgnore
  val startOffsetMs
    get() = secondsToMillis(startOffset)
  @get:JsonIgnore
  val durationMs
    get() = secondsToMillis(duration)
  @get:JsonIgnore
  val endOffsetMs
    get() = if (Long.MAX_VALUE - startOffsetMs < durationMs) {
      Long.MAX_VALUE
    } else {
      startOffsetMs + durationMs
    }
  @get:JsonIgnore
  val relPath
    get() = metadata?.relPath ?: ""

  @JsonIgnore
  fun getBookChapter(): BookChapter {
    val safeStart = startOffset.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val safeDuration = duration.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    val end = (safeStart + safeDuration).takeIf { it.isFinite() } ?: Double.MAX_VALUE
    return BookChapter(index + 1, safeStart, end, title)
  }

  private fun secondsToMillis(seconds: Double): Long {
    if (!seconds.isFinite() || seconds <= 0.0) return 0L
    val maxSeconds = Long.MAX_VALUE.toDouble() / 1000.0
    return if (seconds >= maxSeconds) Long.MAX_VALUE else (seconds * 1000.0).toLong()
  }

  /** Keep server-supplied titles, URLs, file identifiers, and paths out of diagnostics. */
  override fun toString(): String =
    "AudioTrack(index=$index, startOffset=$startOffset, duration=$duration, " +
      "isLocal=$isLocal, serverIndex=$serverIndex, titleUrlAndPaths=<redacted>)"
}
