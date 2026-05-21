package com.audiobookshelf.app.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import com.audiobookshelf.app.MainActivity
import com.google.android.exoplayer2.Player

class ShelfDriveMediaBridgeService : MediaBrowserServiceCompat() {
  private val tag = "ShelfDriveMediaBrowser"
  private val mainHandler = Handler(Looper.getMainLooper())
  private lateinit var bridgeSession: MediaSessionCompat
  private var lastNativeLaunchAtMs = 0L

  override fun onCreate() {
    super.onCreate()
    instance = this
    createBridgeSession()
    refreshFromActivePlayer()
  }

  override fun onDestroy() {
    if (instance == this) {
      instance = null
    }
    bridgeSession.release()
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? {
    Log.d(tag, "Bridge bound with action=${intent?.action}")
    return super.onBind(intent)
  }

  override fun onGetRoot(
          clientPackageName: String,
          clientUid: Int,
          rootHints: Bundle?
  ): BrowserRoot? {
    Log.i(tag, "Browser root requested by $clientPackageName uid=$clientUid")
    refreshFromActivePlayer()
    openNativeAppForCarMedia(clientPackageName)

    return BrowserRoot(ROOT_ID, null)
  }

  override fun onLoadChildren(
          parentId: String,
          result: Result<MutableList<MediaBrowserCompat.MediaItem>>
  ) {
    Log.d(tag, "Loading children for $parentId")
    refreshFromActivePlayer()
    openNativeAppForBrowseHost()
    result.sendResult(mutableListOf())
  }

  private fun createBridgeSession() {
    val sessionActivityIntent =
            Intent(this, MainActivity::class.java).apply {
              action = Intent.ACTION_MAIN
              addCategory(Intent.CATEGORY_LAUNCHER)
              flags =
                      Intent.FLAG_ACTIVITY_NEW_TASK or
                              Intent.FLAG_ACTIVITY_CLEAR_TOP or
                              Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
    val sessionActivityPendingIntent =
            PendingIntent.getActivity(
                    this,
                    0,
                    sessionActivityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

    bridgeSession =
            MediaSessionCompat(this, tag).apply {
              setSessionActivity(sessionActivityPendingIntent)
              setCallback(
                      object : MediaSessionCompat.Callback() {
                        override fun onPrepare() {
                          openNativeApp()
                        }

                        override fun onPlay() {
                          val activeService = PlayerNotificationService.activeService
                          if (activeService == null) {
                            openNativeApp()
                          } else {
                            activeService.play()
                          }
                          refreshFromActivePlayer()
                        }

                        override fun onPause() {
                          PlayerNotificationService.activeService?.pause()
                          refreshFromActivePlayer()
                        }

                        override fun onSkipToPrevious() {
                          PlayerNotificationService.activeService?.seekBackwardFromMediaSession()
                          refreshFromActivePlayer()
                        }

                        override fun onSkipToNext() {
                          PlayerNotificationService.activeService?.seekForwardFromMediaSession()
                          refreshFromActivePlayer()
                        }

                        override fun onSeekTo(pos: Long) {
                          PlayerNotificationService.activeService?.seekPlayer(pos)
                          refreshFromActivePlayer()
                        }
                      }
              )
              isActive = true
            }

    sessionToken = bridgeSession.sessionToken
  }

  private fun refreshFromActivePlayer() {
    if (!this::bridgeSession.isInitialized) return

    val activeService = PlayerNotificationService.activeService
    val playbackSession = activeService?.currentPlaybackSession
    if (activeService == null || playbackSession == null) {
      setFallbackState()
      return
    }

    try {
      bridgeSession.setMetadata(playbackSession.getMediaMetadataCompat(this))

      val playerState =
              when {
                activeService.currentPlayer.playbackState == Player.STATE_BUFFERING ->
                        PlaybackStateCompat.STATE_BUFFERING
                activeService.currentPlayer.isPlaying -> PlaybackStateCompat.STATE_PLAYING
                else -> PlaybackStateCompat.STATE_PAUSED
              }
      val playbackSpeed =
              if (playerState == PlaybackStateCompat.STATE_PLAYING) {
                activeService.currentPlayer.playbackParameters.speed
              } else {
                0f
              }

      bridgeSession.setPlaybackState(
              PlaybackStateCompat.Builder()
                      .setActions(BRIDGE_ACTIONS)
                      .setState(playerState, activeService.getCurrentTime(), playbackSpeed)
                      .build()
      )
    } catch (error: Exception) {
      Log.w(tag, "Unable to mirror active ShelfDrive playback into bridge session", error)
      setFallbackState()
    }
  }

  private fun setFallbackState() {
    bridgeSession.setMetadata(
            MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "ShelfDrive")
                    .putString(
                            MediaMetadataCompat.METADATA_KEY_ARTIST,
                            "Open ShelfDrive to browse audiobooks"
                    )
                    .build()
    )
    bridgeSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_PLAY)
                    .setState(PlaybackStateCompat.STATE_NONE, 0L, 0f)
                    .build()
    )
  }

  private fun openNativeAppForCarMedia(clientPackageName: String) {
    if (clientPackageName != ANDROID_CAR_MEDIA_PACKAGE) return
    openNativeApp()
  }

  private fun openNativeAppForBrowseHost() {
    openNativeApp()
  }

  private fun openNativeApp() {
    val now = SystemClock.elapsedRealtime()
    if (now - lastNativeLaunchAtMs < NATIVE_LAUNCH_THROTTLE_MS) return
    lastNativeLaunchAtMs = now

    mainHandler.postDelayed({
      val intent =
              Intent(this, MainActivity::class.java).apply {
                addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
              }

      try {
        Log.i(tag, "Opening ShelfDrive native activity from car media bridge")
        startActivity(intent)
      } catch (error: Exception) {
        Log.w(tag, "Unable to open ShelfDrive native activity from car media bridge", error)
      }
    }, NATIVE_LAUNCH_DELAY_MS)
  }

  companion object {
    private var instance: ShelfDriveMediaBridgeService? = null
    private const val ANDROID_CAR_MEDIA_PACKAGE = "com.android.car.media"
    private const val NATIVE_LAUNCH_DELAY_MS = 750L
    private const val NATIVE_LAUNCH_THROTTLE_MS = 3000L
    private const val ROOT_ID = "shelfdrive_active_playback"
    private val BRIDGE_ACTIONS =
            PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SEEK_TO or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT

    fun syncFromActivePlayback() {
      instance?.mainHandler?.post { instance?.refreshFromActivePlayer() }
    }
  }
}
