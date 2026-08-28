package com.audiobookshelf.app.data

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import com.audiobookshelf.app.device.DeviceManager
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class CollapsedSeries(
  id:String,
  var libraryId:String?,
  var name:String,
  //var nameIgnorePrefix:String,
  var sequence:String?,
  var libraryItemIds:MutableList<String>
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name
  @get:JsonIgnore
  val numBooks get() = libraryItemIds.size

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    val extras = Bundle()

    val mediaId = "__LIBRARY__${libraryId}__SERIE__${id}"
    val builder = MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      .setSubtitle("${numBooks} books")
      .setExtras(extras)
    // Synthesize a cover URL from the first library item id. CoverCache
    // intercepts /api/items/<id>/cover URLs, so this benefits from caching
    // and FileProvider serving like every other LibraryItem cover.
    libraryItemIds.firstOrNull()?.let { firstId ->
      val server = DeviceManager.serverAddress
      if (DeviceManager.isServerAddressAllowed(server)) {
        val coverUri = runCatching {
          Uri.parse(server).buildUpon()
            .appendPath("api")
            .appendPath("items")
            .appendPath(firstId)
            .appendPath("cover")
            .build()
        }.getOrNull()
        coverUri?.let(builder::setIconUri)
      }
    }
    return builder.build()
  }
}
