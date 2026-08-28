package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.audiobookshelf.app.player.PLAYMETHOD_LOCAL
import java.io.File
import java.util.*

@JsonIgnoreProperties(ignoreUnknown = true)
class LocalLibraryItem(
  id:String,
  var folderId:String,
  var basePath:String,
  var absolutePath:String,
  var contentUrl:String,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var localFiles:MutableList<LocalFile>,
  var coverContentUrl:String?,
  var coverAbsolutePath:String?,
  var isLocal:Boolean,
  // If local library item is linked to a server item
  var serverConnectionConfigId:String?,
  var serverAddress:String?,
  var serverUserId:String?,
  var libraryItemId:String?
  ) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = media.metadata.title
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()
  @get:JsonIgnore
  val isPodcast get() = mediaType == "podcast"

  @JsonIgnore
  fun getCoverUri(ctx:Context): Uri {
    val fallback = Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon")
    val rawCover = coverContentUrl ?: return fallback
    val parsed = runCatching { Uri.parse(rawCover) }.getOrElse { return fallback }
    if (parsed.scheme == "file") {
      return runCatching {
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", parsed.toFile())
      }.onFailure { error ->
        Log.w(
          "LocalLibraryItem",
          "Ignoring stale or inaccessible local cover (${error.javaClass.simpleName})"
        )
      }.getOrDefault(fallback)
    }
    // Named-form android.resource URI: numeric form crashes Car Media (see
    // LibraryAuthorItem.getPortraitUri for context).
    if (parsed.scheme == "content") return parsed
    return if (parsed.scheme == "android.resource" &&
      parsed.authority == BuildConfig.APPLICATION_ID &&
      parsed.pathSegments.size == 2 &&
      parsed.pathSegments[0] == "drawable" &&
      parsed.pathSegments[1].isNotBlank() &&
      parsed.pathSegments[1].any { !it.isDigit() }
    ) {
      parsed
    } else {
      fallback
    }
  }

  @JsonIgnore
  fun getDuration():Double {
    val total = media.getAudioTracks().asSequence()
      .map { it.duration }
      .filter { it.isFinite() && it > 0.0 }
      .sum()
    return total.takeIf { it.isFinite() } ?: 0.0
  }

  @JsonIgnore
  fun updateFromScan(audioTracks:MutableList<AudioTrack>, _localFiles:MutableList<LocalFile>) {
    localFiles = _localFiles
    media.setAudioTracks(audioTracks)

    if (coverContentUrl != null) {
      if (localFiles.find { it.contentUrl == coverContentUrl } == null) {
        // Cover was removed
        coverContentUrl = null
        coverAbsolutePath = null
        media.coverPath = null
      }
    }
  }

  /**
   * Copies the downloaded item while removing linkage to a server account.
   * Local paths, files, media metadata, and the device-local ID stay playable;
   * the source object is never mutated because playback may still be active.
   */
  @JsonIgnore
  fun copyWithoutServerIdentity(): LocalLibraryItem = LocalLibraryItem(
    id = id,
    folderId = folderId,
    basePath = basePath,
    absolutePath = absolutePath,
    contentUrl = contentUrl,
    isInvalid = isInvalid,
    mediaType = mediaType,
    media = media.copyWithoutServerIdentity(),
    localFiles = localFiles.toMutableList(),
    coverContentUrl = coverContentUrl,
    coverAbsolutePath = coverAbsolutePath,
    isLocal = isLocal,
    serverConnectionConfigId = null,
    serverAddress = null,
    serverUserId = null,
    libraryItemId = null
  )

  /** A podcast's local episode rows can retain the source server episode ID. */
  private fun MediaType.copyWithoutServerIdentity(): MediaType = when (this) {
    is Podcast -> Podcast(
      metadata = metadata as PodcastMetadata,
      coverPath = coverPath,
      tags = tags.toMutableList(),
      episodes = episodes?.map { episode ->
        episode.copy(serverEpisodeId = null)
      }?.toMutableList(),
      autoDownloadEpisodes = autoDownloadEpisodes,
      numEpisodes = numEpisodes
    )
    else -> this
  }

  @JsonIgnore
  fun hasTracks(episode:PodcastEpisode?): Boolean {
    var audioTracks = media.getAudioTracks().toMutableList()
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
    }
    if (audioTracks.size == 0) return false
    audioTracks.forEach {
      // Check that metadata is not null
      val metadata = it.metadata ?: return false
      // Check that file exists
      val file = File(metadata.path)
      if (!file.exists()) {
        return false
      }
    }
    return true
  }

  @JsonIgnore
  fun getPlaybackSession(episode:PodcastEpisode?, deviceInfo:DeviceInfo):PlaybackSession {
    val localEpisodeId = episode?.id
    val sessionId = "${UUID.randomUUID()}"

    // Get current progress for local media
    val mediaProgressId = if (localEpisodeId.isNullOrEmpty()) id else "$id-$localEpisodeId"
    val mediaProgress = DeviceManager.dbManager.getLocalMediaProgress(mediaProgressId)
    val currentTime = mediaProgress?.currentTime ?: 0.0


    val mediaMetadata = media.metadata
    var chapters = if (mediaType == "book") (media as? Book)?.chapters else mutableListOf()
    var audioTracks = media.getAudioTracks().toMutableList()
    val authorName = mediaMetadata.getAuthorDisplayName()
    val displayTitle = episode?.title ?: mediaMetadata.title
    var duration = getDuration()
    if (episode != null) { // Get podcast episode audio track
      episode.audioTrack?.let { at -> mutableListOf(at) }?.let { tracks -> audioTracks = tracks }
      chapters = episode.chapters
      duration = episode.audioTrack?.duration ?: 0.0
      Log.d("LocalLibraryItem", "getPlaybackSession: Got podcast episode audio track ${audioTracks.size}")
    }

    val dateNow = System.currentTimeMillis()
    return PlaybackSession(sessionId,serverUserId,libraryItemId,episode?.serverEpisodeId, mediaType, mediaMetadata, deviceInfo,chapters ?: mutableListOf(), displayTitle, authorName,null,duration,PLAYMETHOD_LOCAL,dateNow,0L,0L, audioTracks,currentTime,null,this,localEpisodeId,serverConnectionConfigId, serverAddress, "exo-player")
  }

  @JsonIgnore
  fun removeLocalFile(localFileId:String) {
    localFiles.removeIf { it.id == localFileId }
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx:Context): MediaDescriptionCompat {
    val coverUri = getCoverUri(ctx)

    val extras = Bundle()
    extras.putLong(
      MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
      MediaDescriptionCompat.STATUS_DOWNLOADED
    )
    if (progress != null) {
      if (progress.isFinished) {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_FULLY_PLAYED
        )
      } else {
        extras.putInt(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
          MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_PARTIALLY_PLAYED
        )
        extras.putDouble(
          MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_PERCENTAGE,
          progress.normalizedProgress
        )
      }
    } else if (mediaType != "podcast") {
      extras.putInt(
        MediaConstants.DESCRIPTION_EXTRAS_KEY_COMPLETION_STATUS,
        MediaConstants.DESCRIPTION_EXTRAS_VALUE_COMPLETION_STATUS_NOT_PLAYED
      )
    }

    if (media.metadata.explicit) {
      extras.putLong(MediaConstants.METADATA_KEY_IS_EXPLICIT, MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT)
    }

    val mediaDescriptionBuilder = MediaDescriptionCompat.Builder()
      .setMediaId(id)
      .setTitle(title)
      .setIconUri(coverUri)
      .setSubtitle(authorName)
      .setExtras(extras)

    return mediaDescriptionBuilder.build()
  }
}
