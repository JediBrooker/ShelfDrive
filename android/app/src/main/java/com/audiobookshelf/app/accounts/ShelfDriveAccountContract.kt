package com.audiobookshelf.app.accounts

import com.audiobookshelf.app.BuildConfig

/**
 * Public contract for ShelfDrive accounts exposed through Android's AccountManager.
 *
 * AccountManager is deliberately only an index of ShelfDrive server connections. It
 * must never be used as a credential store: passwords, access tokens, refresh tokens,
 * and custom authentication headers remain in ShelfDrive's existing app-private
 * stores.
 */
object ShelfDriveAccountContract {
  /** Build-specific so debug and Play-signed installs cannot claim each other's accounts. */
  @JvmField
  val ACCOUNT_TYPE = "${BuildConfig.APPLICATION_ID}.account"
  @JvmField
  val ACTION_AUTHENTICATOR_SIGN_IN =
    "${BuildConfig.APPLICATION_ID}.action.AUTHENTICATOR_SIGN_IN"

  const val USER_DATA_CONNECTION_ID = "shelfdrive.connection_id"
  const val USER_DATA_DISPLAY_NAME = "shelfdrive.display_name"
  const val USER_DATA_SCHEMA_VERSION = "shelfdrive.schema_version"
  const val SCHEMA_VERSION = "1"

  internal val SENSITIVE_USER_DATA_KEYS = setOf(
    "password",
    "token",
    "accessToken",
    "access_token",
    "refreshToken",
    "refresh_token",
    "customHeaders",
    "custom_headers"
  )
}
