package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import android.support.v4.media.MediaMetadataCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.audiobookshelf.app.device.ConnectionLease
import com.audiobookshelf.app.media.MediaProgressSyncData
import com.audiobookshelf.app.player.*
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.MediaMetadata

@JsonIgnoreProperties(ignoreUnknown = true)
class PlaybackSession(
        var id: String,
        var userId: String?,
        var libraryItemId: String?,
        var episodeId: String?,
        var mediaType: String,
        var mediaMetadata: MediaTypeMetadata,
        var deviceInfo: DeviceInfo,
        var chapters: List<BookChapter>,
        var displayTitle: String?,
        var displayAuthor: String?,
        var coverPath: String?,
        var duration: Double,
        var playMethod: Int,
        var startedAt: Long,
        var updatedAt: Long,
        var timeListening: Long,
        var audioTracks: MutableList<AudioTrack>,
        var currentTime: Double,
        var libraryItem: LibraryItem?,
        var localLibraryItem: LocalLibraryItem?,
        var localEpisodeId: String?,
        var serverConnectionConfigId: String?,
        var serverAddress: String?,
        var mediaPlayer: String?
) {

  /** In-process authorization lifetime; deliberately excluded from persistence. */
  @get:JsonIgnore
  @field:Transient
  var connectionLease: ConnectionLease? = null

  /** Paper-only compare-and-delete token; never part of a server payload. */
  @get:JsonIgnore
  var persistenceToken: String? = null

  @get:JsonIgnore
  val isHLS
    get() = playMethod == PLAYMETHOD_TRANSCODE
  @get:JsonIgnore
  val isDirectPlay
    get() = playMethod == PLAYMETHOD_DIRECTPLAY
  @get:JsonIgnore
  val isLocal
    get() = playMethod == PLAYMETHOD_LOCAL
  @get:JsonIgnore
  val isPodcastEpisode
    get() = mediaType == "podcast"
  @get:JsonIgnore
  val currentTimeMs
    get() = secondsToMillis(currentTime)
  @get:JsonIgnore
  val totalDurationMs
    get() = secondsToMillis(getTotalDuration())
  @get:JsonIgnore
  val localLibraryItemId
    get() = localLibraryItem?.id ?: ""
  @get:JsonIgnore
  val localMediaProgressId
    get() =
            if (localEpisodeId.isNullOrEmpty()) localLibraryItemId
            else "$localLibraryItemId-$localEpisodeId"
  @get:JsonIgnore
  val progress
    get(): Double {
      val totalDuration = getTotalDuration()
      if (!currentTime.isFinite() || !totalDuration.isFinite() || totalDuration <= 0.0) return 0.0
      return (currentTime / totalDuration).coerceIn(0.0, 1.0)
    }
  @get:JsonIgnore
  val mediaItemId
    get() = if (!libraryItemId.isNullOrBlank()) {
      if (episodeId.isNullOrEmpty()) libraryItemId ?: "" else "$libraryItemId-$episodeId"
    } else {
      localMediaProgressId.ifBlank { id }
    }

  @JsonIgnore
  fun getCurrentTrackIndex(): Int {
    if (audioTracks.isEmpty()) return 0
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs >= track.startOffsetMs && (track.endOffsetMs > currentTimeMs)) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getNextTrackIndex(): Int {
    if (audioTracks.isEmpty()) return 0
    for (i in 0 until audioTracks.size) {
      val track = audioTracks[i]
      if (currentTimeMs < track.startOffsetMs) {
        return i
      }
    }
    return audioTracks.size - 1
  }

  @JsonIgnore
  fun getChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time >= it.startMs && it.endMs > time }
  }

  @JsonIgnore
  fun getCurrentTrackEndTime(): Long {
    val currentTrack = audioTracks.getOrNull(this.getCurrentTrackIndex()) ?: return 0L
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getNextChapterForTime(time: Long): BookChapter? {
    if (chapters.isEmpty()) return null
    return chapters.find { time < it.startMs } // First chapter where start time is > then time
  }

  @JsonIgnore
  fun getNextTrackEndTime(): Long {
    val currentTrack = audioTracks.getOrNull(this.getNextTrackIndex()) ?: return 0L
    return currentTrack.startOffsetMs + currentTrack.durationMs
  }

  @JsonIgnore
  fun getCurrentTrackTimeMs(): Long {
    val currentTrack = audioTracks.getOrNull(this.getCurrentTrackIndex()) ?: return 0L
    val time = currentTime - currentTrack.startOffset
    return secondsToMillis(time)
  }

  @JsonIgnore
  fun getTrackStartOffsetMs(index: Int): Long {
    if (index < 0 || index >= audioTracks.size) return 0L
    val currentTrack = audioTracks[index]
    return secondsToMillis(currentTrack.startOffset)
  }

  @JsonIgnore
  fun getTotalDuration(): Double {
    val trackTotal = audioTracks.asSequence()
      .map { it.duration }
      .filter { it.isFinite() && it > 0.0 }
      .sum()
    if (trackTotal.isFinite() && trackTotal > 0.0) return trackTotal
    return duration.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
  }

  @JsonIgnore
  fun checkIsServerVersionGte(
    compareVersion: String,
    connectionConfig: ServerConnectionConfig? = DeviceManager.serverConnectionConfig
  ): Boolean {
    val config = connectionConfig ?: return false
    if (config.id != serverConnectionConfigId) return false
    if (compareVersion.isBlank()) return true
    val serverParts = config.version.orEmpty().split('.').map { it.toIntOrNull() ?: 0 }
    val compareParts = compareVersion.split('.').map { it.toIntOrNull() ?: 0 }
    for (index in 0 until maxOf(serverParts.size, compareParts.size)) {
      val serverPart = serverParts.getOrElse(index) { 0 }
      val comparePart = compareParts.getOrElse(index) { 0 }
      if (serverPart != comparePart) return serverPart > comparePart
    }
    return true
  }

  @JsonIgnore
  fun getCoverUri(ctx: Context): Uri {
    val fallback = fallbackCoverUri()
    localLibraryItem?.coverContentUrl?.let { rawCover ->
      val parsed = runCatching { Uri.parse(rawCover) }.getOrNull() ?: return@let
      if (parsed.scheme == "file") {
        return runCatching {
          FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", parsed.toFile())
        }.onFailure { error ->
          Log.w(
            "PlaybackSession",
            "Ignoring stale or inaccessible local cover (${error.javaClass.simpleName})"
          )
        }.getOrDefault(fallback)
      }
      if (parsed.scheme == "content" &&
        parsed.authority == "${BuildConfig.APPLICATION_ID}.fileprovider"
      ) return parsed
      if (parsed.scheme == "android.resource" &&
        parsed.authority == BuildConfig.APPLICATION_ID &&
        parsed.pathSegments.size == 2 &&
        parsed.pathSegments[0] == "drawable" &&
        parsed.pathSegments[1].isNotBlank() &&
        parsed.pathSegments[1].any { !it.isDigit() }
      ) {
        return parsed
      }
      return fallback
    }

    if (coverPath == null) return fallback

    // Prefer the on-disk cover cache (served via FileProvider) when available,
    // so cross-process readers can fetch without our server auth.
    val lease = connectionLease
    val ownerConfig = lease?.let(DeviceManager::getServerConnectionConfig)
    libraryItemId?.takeIf { it.isNotBlank() }?.let { itemId ->
      if (lease != null && ownerConfig != null) {
        DeviceManager.coverCache?.cachedUri(itemId, ownerConfig, lease)?.let { uri -> return uri }
      }
    }

    // AAOS requires playing-item artwork to be local too. CoverCache fetches
    // remote artwork privately and the service republishes metadata once a
    // FileProvider URI is ready; until then use the owned resource fallback.
    return fallback
  }

  @JsonIgnore
  fun getContentUri(
    audioTrack: AudioTrack,
    connectionConfig: ServerConnectionConfig? = DeviceManager.serverConnectionConfig
  ): Uri {
    if (isLocal) return Uri.parse(audioTrack.contentUrl) // Local content url
    val config = connectionConfig ?: return Uri.EMPTY
    if (config.id != serverConnectionConfigId) return Uri.EMPTY
    // As of v2.22.0 tracks use a different endpoint
    // See: https://github.com/advplyr/audiobookshelf/pull/4263
    if (checkIsServerVersionGte("2.22.0", config)) {
      return if (isDirectPlay) {
        Uri.parse("$serverAddress/public/session/$id/track/${audioTrack.index}")
      } else {
        // Transcode uses HlsRouter on server
        Uri.parse("$serverAddress${audioTrack.contentUrl}")
      }
    }
    return Uri.parse("$serverAddress${audioTrack.contentUrl}")
      .buildUpon()
      .appendQueryParameter("token", config.token)
      .build()
  }

  @JsonIgnore
  fun getMediaMetadataCompat(ctx: Context): MediaMetadataCompat {
    val coverUri = getCoverUri(ctx)

    val metadataBuilder =
            MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, displayTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, displayTitle)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_AUTHOR, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, displayAuthor)
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, id)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, coverUri.toString())
                    .putString(MediaMetadataCompat.METADATA_KEY_ART_URI, coverUri.toString())
                    .putString(
                            MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
                            coverUri.toString()
                    )

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getExoMediaMetadata(ctx: Context): MediaMetadata {
    val coverUri = getCoverUri(ctx)

    val metadataBuilder =
            MediaMetadata.Builder()
                    .setTitle(displayTitle)
                    .setDisplayTitle(displayTitle)
                    .setArtist(displayAuthor)
                    .setAlbumArtist(displayAuthor)
                    .setSubtitle(displayAuthor)
                    .setAlbumTitle(displayAuthor)
                    .setDescription(displayAuthor)
                    .setArtworkUri(coverUri)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)

    return metadataBuilder.build()
  }

  @JsonIgnore
  fun getMediaItems(
    ctx: Context,
    connectionConfig: ServerConnectionConfig? = DeviceManager.serverConnectionConfig
  ): List<MediaItem> {
    val mediaItems: MutableList<MediaItem> = mutableListOf()

    for (audioTrack in audioTracks) {
      val mediaMetadata = this.getExoMediaMetadata(ctx)
      val mediaUri = this.getContentUri(audioTrack, connectionConfig)
      if (mediaUri == Uri.EMPTY) continue
      val mimeType = audioTrack.mimeType

      val mediaItem =
              MediaItem.Builder()
                      .setMediaId(id)
                      .setUri(mediaUri)
                      .setMediaMetadata(mediaMetadata)
                      .setMimeType(mimeType)
                      .build()
      mediaItems.add(mediaItem)
    }
    return mediaItems
  }

  @JsonIgnore
  fun clone(): PlaybackSession {
    return PlaybackSession(
            id,
            userId,
            libraryItemId,
            episodeId,
            mediaType,
            mediaMetadata,
            deviceInfo,
            chapters.toList(),
            displayTitle,
            displayAuthor,
            coverPath,
            duration,
            playMethod,
            startedAt,
            updatedAt,
            timeListening,
            audioTracks.toMutableList(),
            currentTime,
            libraryItem,
            localLibraryItem,
            localEpisodeId,
            serverConnectionConfigId,
            serverAddress,
            mediaPlayer
    ).also { copy ->
      copy.connectionLease = connectionLease
      copy.persistenceToken = persistenceToken
    }
  }

  /**
   * Downloaded audio remains usable after disconnect, but no later checkpoint,
   * history row, or local-progress write may resurrect the removed server's
   * identity. Remote sessions are returned unchanged and rejected by their
   * normal lease gates.
   */
  @JsonIgnore
  fun copySanitizedForPersistence(): PlaybackSession = clone().also { copy ->
    if (!copy.isLocal) return@also
    val ownerId = copy.serverConnectionConfigId
    val lease = copy.connectionLease
    val ownerIsCurrent = !ownerId.isNullOrBlank() &&
      lease != null && lease.connectionId == ownerId &&
      DeviceManager.isConnectionLeaseCurrent(lease)
    if (!ownerIsCurrent) {
      copy.userId = null
      copy.libraryItemId = null
      copy.episodeId = null
      copy.libraryItem = null
      copy.localLibraryItem = copy.localLibraryItem?.copyWithoutServerIdentity()
      copy.serverConnectionConfigId = null
      copy.serverAddress = null
      copy.connectionLease = null
    }
  }

  @JsonIgnore
  fun syncData(syncData: MediaProgressSyncData) {
    val listened = syncData.timeListened.coerceAtLeast(0L)
    timeListening = if (Long.MAX_VALUE - timeListening.coerceAtLeast(0L) < listened) {
      Long.MAX_VALUE
    } else {
      timeListening.coerceAtLeast(0L) + listened
    }
    updatedAt = System.currentTimeMillis()
    currentTime = syncData.currentTime
      .takeIf { it.isFinite() }
      ?.coerceIn(0.0, getTotalDuration().takeIf { it > 0.0 } ?: Double.MAX_VALUE)
      ?: 0.0
  }

  @JsonIgnore
  fun getNewLocalMediaProgress(): LocalMediaProgress {
    return LocalMediaProgress(
            localMediaProgressId,
            localLibraryItemId,
            localEpisodeId,
            getTotalDuration(),
            progress,
            currentTime,
            false,
            null,
            null,
            updatedAt,
            startedAt,
            null,
            serverConnectionConfigId,
            serverAddress,
            userId,
            libraryItemId,
            episodeId
    )
  }

  private fun fallbackCoverUri(): Uri =
    Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon")

  private fun secondsToMillis(seconds: Double): Long {
    if (!seconds.isFinite() || seconds <= 0.0) return 0L
    val maxSeconds = Long.MAX_VALUE.toDouble() / 1000.0
    return if (seconds >= maxSeconds) Long.MAX_VALUE else (seconds * 1000.0).toLong()
  }
}
