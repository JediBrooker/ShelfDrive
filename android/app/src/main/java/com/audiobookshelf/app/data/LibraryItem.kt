package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.utils.MediaConstants
import com.audiobookshelf.app.BuildConfig
import com.audiobookshelf.app.R
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryItem(
  id:String,
  var ino:String,
  var libraryId:String,
  var folderId:String,
  var path:String,
  var relPath:String,
  var mtimeMs:Long,
  var ctimeMs:Long,
  var birthtimeMs:Long,
  var addedAt:Long,
  var updatedAt:Long,
  var lastScan:Long?,
  var scanVersion:String?,
  var isMissing:Boolean,
  var isInvalid:Boolean,
  var mediaType:String,
  var media:MediaType,
  var libraryFiles:MutableList<LibraryFile>?,
  var userMediaProgress:MediaProgress?, // Only included when requesting library item with progress (for downloads)
  var collapsedSeries: CollapsedSeries?,
  var localLibraryItemId:String?, // For Android Auto
  val recentEpisode: PodcastEpisode?  // Podcast episode shelf uses this
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title: String
    get() = collapsedSeries?.title ?: media.metadata.title
  @get:JsonIgnore
  val authorName get() = media.metadata.getAuthorDisplayName()

  @JsonIgnore
  fun getCoverUri(): Uri {
    if (media.coverPath == null) {
      // Named-form android.resource URI: see LibraryAuthorItem.getPortraitUri
      // for why numeric form crashes Car Media's image loader.
      return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon")
    }

    // Prefer the locally-cached file (served via FileProvider) so cross-process
    // readers like Car Media — which don't have our server auth — can render.
    DeviceManager.coverCache?.cachedUri(id)?.let { return it }

    // As of v2.17.0 token is not needed with cover image requests
    if (DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.0") &&
      DeviceManager.isServerAddressAllowed(DeviceManager.serverAddress)
    ) {
      return Uri.parse(DeviceManager.serverAddress).buildUpon()
        .appendPath("api")
        .appendPath("items")
        .appendPath(id)
        .appendPath("cover")
        .build()
    }

    // CoverCache may use authenticated requests privately, but credentials
    // must never be exposed through browse/session metadata.
    return Uri.parse("android.resource://${BuildConfig.APPLICATION_ID}/drawable/icon")
  }

  /**
   * Returns the remote (server) cover URL regardless of cache state, suitable
   * for handing to CoverCache.fetchAsync. Returns null if the item has no cover.
   */
  @JsonIgnore
  fun getRemoteCoverUrl(): String? {
    if (media.coverPath == null) return null
    val address = DeviceManager.serverAddress
    if (!DeviceManager.isServerAddressAllowed(address)) return null
    val builder = Uri.parse(address).buildUpon()
      .appendPath("api")
      .appendPath("items")
      .appendPath(id)
      .appendPath("cover")
    if (!DeviceManager.isServerVersionGreaterThanOrEqualTo("2.17.0")) {
      builder.appendQueryParameter("token", DeviceManager.token)
    }
    return builder.build().toString()
  }

  @JsonIgnore
  fun checkHasTracks():Boolean {
    return media.checkHasTracks()
  }

  @get:JsonIgnore
  val seriesSequence: String
    get() {
      if (mediaType != "podcast") {
        return ((media as? Book)?.metadata as? BookMetadata)
          ?.series
          ?.firstOrNull()
          ?.sequence
          .orEmpty()
      } else {
        return ""
      }
    }

  @get:JsonIgnore
  val seriesSequenceParts: List<String>
    get() {
      if (seriesSequence.isEmpty()) {
        return listOf("")
      }
      return seriesSequence.split(".", limit = 2)
    }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?, showSeriesNumber: Boolean?, groupTitle: String?): MediaDescriptionCompat {
    val extras = Bundle()

    if (collapsedSeries == null) {
      if (localLibraryItemId != null) {
        extras.putLong(
          MediaDescriptionCompat.EXTRA_DOWNLOAD_STATUS,
          MediaDescriptionCompat.STATUS_DOWNLOADED
        )
      }

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
        extras.putLong(
          MediaConstants.METADATA_KEY_IS_EXPLICIT,
          MediaConstants.METADATA_VALUE_ATTRIBUTE_PRESENT
        )
      }
    }
    if (groupTitle !== null) {
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val series = collapsedSeries
    val mediaId = if (localLibraryItemId != null) {
      localLibraryItemId
    } else if (series != null) {
      if (authorId != null) {
        "__LIBRARY__${libraryId}__AUTHOR_SERIES__${authorId}__${series.id}"
      } else {
        "__LIBRARY__${libraryId}__SERIES__${series.id}"
      }
    } else {
      id
    }
    var subtitle = authorName
    if (series != null) {
      subtitle = "${series.numBooks} books"
    }
    var itemTitle = title
    if (showSeriesNumber == true && seriesSequence != "") {
      itemTitle = "$seriesSequence. $itemTitle"
    }
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(itemTitle)
      .setIconUri(getCoverUri())
      .setSubtitle(subtitle)
      .setExtras(extras)
      .build()
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?, showSeriesNumber: Boolean?): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, authorId, showSeriesNumber, null)
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, authorId: String?): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, authorId, null, null)
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    /*
    This is needed so Android auto library hierarchy for author series can be implemented
     */
    return getMediaDescription(progress, ctx, null, null, null)
  }
}
