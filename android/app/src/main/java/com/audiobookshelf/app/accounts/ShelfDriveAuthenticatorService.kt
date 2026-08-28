package com.audiobookshelf.app.accounts

import android.accounts.AccountManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Binder entry point used by the Android account service. */
class ShelfDriveAuthenticatorService : Service() {
  private val authenticator by lazy {
    ShelfDriveAccountAuthenticator(applicationContext)
  }

  override fun onBind(intent: Intent?): IBinder? =
    if (intent?.action == AccountManager.ACTION_AUTHENTICATOR_INTENT) {
      authenticator.iBinder
    } else {
      null
    }
}
