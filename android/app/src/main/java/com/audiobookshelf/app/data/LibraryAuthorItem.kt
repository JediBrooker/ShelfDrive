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
class LibraryAuthorItem(
  id:String,
  var libraryId:String,
  var name:String,
  var description:String?,
  var imagePath:String?,
  var addedAt:Long,
  var updatedAt:Long,
  var numBooks:Int?,
  var libraryItems:MutableList<LibraryItem>?,
  var series:MutableList<LibrarySeriesItem>?
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name

  @get:JsonIgnore
  val bookCount get() = numBooks ?: libraryItems?.size ?: 0

  @JsonIgnore
  fun getPortraitUri(): Uri {
    val fallbackUri = Uri.parse(
      "android.resource://${BuildConfig.APPLICATION_ID}/drawable/md_account_outline"
    )
    if (imagePath == null) {
      // Named-form android.resource:// URI. Numeric-form (".../$resourceId")
      // crashes Car Media's LocalImageFetcher with Resources$NotFoundException
      // because its cross-process resolver only handles drawable/<name>.
      return fallbackUri
    }

    DeviceManager.coverCache?.cachedUri("author_$id")?.let { return it }

    if (!DeviceManager.isServerAddressAllowed(DeviceManager.serverAddress)) {
      return fallbackUri
    }
    return try {
      Uri.parse(DeviceManager.serverAddress).buildUpon()
        .appendPath("api")
        .appendPath("authors")
        .appendPath(id)
        .appendPath("image")
        .build()
    } catch (_: RuntimeException) {
      fallbackUri
    }
  }

  @JsonIgnore
  fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context, groupTitle: String?): MediaDescriptionCompat {
    val extras = Bundle()
    if (groupTitle !== null) {
      extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, groupTitle)
    }

    val mediaId = "__LIBRARY__${libraryId}__AUTHOR__${id}"
    return MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      .setIconUri(getPortraitUri())
      .setSubtitle("${bookCount} books")
      .setExtras(extras)
      .build()
  }

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    return getMediaDescription(progress, ctx, null)
  }
}
