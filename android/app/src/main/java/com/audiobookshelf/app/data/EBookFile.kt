package com.audiobookshelf.app.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class EBookFile(
  var ino:String,
  var metadata:FileMetadata?,
  var ebookFormat:String,
  var isLocal:Boolean,
  var localFileId:String?,
  var contentUrl:String?
) {
  /** Keep remote content URLs and local file identity/path data out of diagnostics. */
  override fun toString(): String =
    "EBookFile(ebookFormat=$ebookFormat, isLocal=$isLocal, contentAndPaths=<redacted>)"
}
