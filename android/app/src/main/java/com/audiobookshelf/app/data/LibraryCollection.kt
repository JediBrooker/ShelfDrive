package com.audiobookshelf.app.data

import android.content.Context
import android.os.Bundle
import android.support.v4.media.MediaDescriptionCompat
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
class LibraryCollection(
  id:String,
  var libraryId:String,
  var name:String,
  //var userId:String?,
  var description:String?,
  var books:MutableList<LibraryItem>?,
) : LibraryItemWrapper(id) {
  @get:JsonIgnore
  val title get() = name

  @get:JsonIgnore
  val bookCount get() = if (books != null) books!!.size else 0

  @get:JsonIgnore
  val audiobookCount get() = books?.filter { book -> (book.media as Book).getAudioTracks().isNotEmpty() }?.size ?: 0

  @JsonIgnore
  override fun getMediaDescription(progress:MediaProgressWrapper?, ctx: Context): MediaDescriptionCompat {
    val extras = Bundle()

    val mediaId = "__LIBRARY__${libraryId}__COLLECTION__${id}"
    val builder = MediaDescriptionCompat.Builder()
      .setMediaId(mediaId)
      .setTitle(title)
      .setSubtitle("${bookCount} books")
      .setExtras(extras)
    // Use the first book's cover as the collection cover.
    books?.firstOrNull()?.let { builder.setIconUri(it.getCoverUri()) }
    return builder.build()
  }
}
