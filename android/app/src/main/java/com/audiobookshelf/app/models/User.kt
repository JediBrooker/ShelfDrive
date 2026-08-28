package com.audiobookshelf.app.models

import com.audiobookshelf.app.data.MediaProgress
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class User(
  val id:String,
  val username: String,
  val mediaProgress:List<MediaProgress>
) {
  /** Keep account identity out of logs and crash reports. */
  override fun toString(): String =
    "User(mediaProgressCount=${mediaProgress.size}, identity=<redacted>)"
}
