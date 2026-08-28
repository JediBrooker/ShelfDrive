package com.audiobookshelf.app.accounts

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.audiobookshelf.app.R
import com.audiobookshelf.app.SettingsActivity

/**
 * Authenticator used only to let Android own the lifecycle of mirrored
 * ShelfDrive accounts. Interactive sign-in stays in the parked-only native
 * settings activity, and this authenticator never returns an auth token.
 */
internal class ShelfDriveAccountAuthenticator internal constructor(
  context: Context,
  private val removalHandler: SystemAccountRemovalHandler
) : AbstractAccountAuthenticator(context) {
  private val appContext = context.applicationContext

  constructor(context: Context) : this(
    context,
    ServerConnectionAccountRegistry(context.applicationContext)
  )

  override fun editProperties(
    response: AccountAuthenticatorResponse?,
    accountType: String?
  ): Bundle = unsupported()

  override fun addAccount(
    response: AccountAuthenticatorResponse?,
    accountType: String?,
    authTokenType: String?,
    requiredFeatures: Array<out String>?,
    options: Bundle?
  ): Bundle {
    if (accountType != ShelfDriveAccountContract.ACCOUNT_TYPE) return unsupported()
    val signInIntent = Intent(appContext, SettingsActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
    }
    return Bundle().apply {
      putParcelable(AccountManager.KEY_INTENT, signInIntent)
    }
  }

  override fun confirmCredentials(
    response: AccountAuthenticatorResponse?,
    account: Account?,
    options: Bundle?
  ): Bundle = unsupported()

  override fun getAuthToken(
    response: AccountAuthenticatorResponse?,
    account: Account?,
    authTokenType: String?,
    options: Bundle?
  ): Bundle = unsupported()

  override fun getAuthTokenLabel(authTokenType: String?): String =
    appContext.getString(R.string.shelfdrive_account_token_label)

  override fun updateCredentials(
    response: AccountAuthenticatorResponse?,
    account: Account?,
    authTokenType: String?,
    options: Bundle?
  ): Bundle = unsupported()

  override fun hasFeatures(
    response: AccountAuthenticatorResponse?,
    account: Account?,
    features: Array<out String>?
  ): Bundle = Bundle().apply {
    putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false)
  }

  override fun getAccountRemovalAllowed(
    response: AccountAuthenticatorResponse?,
    account: Account?
  ): Bundle = Bundle().apply {
    putBoolean(
      AccountManager.KEY_BOOLEAN_RESULT,
      account != null && removalHandler.onSystemAccountRemoval(account)
    )
  }

  private fun unsupported(): Bundle = Bundle().apply {
    putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
    putString(
      AccountManager.KEY_ERROR_MESSAGE,
      appContext.getString(R.string.shelfdrive_account_use_app_settings)
    )
  }
}
