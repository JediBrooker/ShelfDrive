package com.audiobookshelf.app.player

import android.net.Uri
import android.os.Bundle
import android.os.ResultReceiver
import android.support.v4.media.session.PlaybackStateCompat
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ext.mediasession.MediaSessionConnector

/**
 * Advertises the standard prepare/play-from actions through
 * MediaSessionConnector while keeping [MediaSessionCallback] as the sole
 * implementation of those commands.
 */
class MediaSessionPlaybackPreparer(
  private val callback: MediaSessionCallback
) : MediaSessionConnector.PlaybackPreparer {

  override fun onCommand(player: Player, command: String, extras: Bundle?, cb: ResultReceiver?): Boolean {
    return false
  }

  override fun getSupportedPrepareActions(): Long {
    return PlaybackStateCompat.ACTION_PREPARE_FROM_MEDIA_ID or
      PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
      PlaybackStateCompat.ACTION_PREPARE_FROM_SEARCH or
      PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
  }

  override fun onPrepare(playWhenReady: Boolean) {
    if (playWhenReady) callback.onPlay() else callback.onPrepare()
  }

  override fun onPrepareFromMediaId(mediaId: String, playWhenReady: Boolean, extras: Bundle?) {
    if (playWhenReady) callback.onPlayFromMediaId(mediaId, extras)
    else callback.onPrepareFromMediaId(mediaId, extras)
  }

  override fun onPrepareFromSearch(query: String, playWhenReady: Boolean, extras: Bundle?) {
    if (playWhenReady) callback.onPlayFromSearch(query, extras)
    else callback.onPrepareFromSearch(query, extras)
  }

  // ShelfDrive doesn't expose play-from-URI; this action is not advertised.
  override fun onPrepareFromUri(uri: Uri, playWhenReady: Boolean, extras: Bundle?) = Unit
}
