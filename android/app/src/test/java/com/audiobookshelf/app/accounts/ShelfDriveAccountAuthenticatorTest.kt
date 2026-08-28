package com.audiobookshelf.app.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Intent
import com.audiobookshelf.app.SettingsActivity
import com.audiobookshelf.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShelfDriveAccountAuthenticatorTest {

  @Test
  fun buildAccountTypeMatchesAuthenticatorResource() {
    val context: android.app.Application = RuntimeEnvironment.getApplication()

    assertEquals(
      context.getString(R.string.shelfdrive_account_type),
      ShelfDriveAccountContract.ACCOUNT_TYPE
    )
  }

  @Test
  fun getAuthTokenNeverReturnsCredentials() {
    val authenticator = authenticator { true }
    val account = Account("ShelfDrive", ShelfDriveAccountContract.ACCOUNT_TYPE)

    val result = authenticator.getAuthToken(null, account, "any", null)

    assertEquals(AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
      result.getInt(AccountManager.KEY_ERROR_CODE))
    assertFalse(result.containsKey(AccountManager.KEY_AUTHTOKEN))
    assertNull(result.getString(AccountManager.KEY_AUTHTOKEN))
  }

  @Test
  fun addAccountReturnsTheParkedNativeSignInActivity() {
    val authenticator = authenticator { true }

    val result = authenticator.addAccount(
      null,
      ShelfDriveAccountContract.ACCOUNT_TYPE,
      null,
      null,
      null
    )

    @Suppress("DEPRECATION")
    val intent = result.getParcelable<Intent>(AccountManager.KEY_INTENT)
    assertNotNull(intent)
    assertEquals(SettingsActivity::class.java.name, intent?.component?.className)
    assertTrue(intent?.flags?.and(Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    assertFalse(result.containsKey(AccountManager.KEY_AUTHTOKEN))
  }

  @Test
  fun addAccountRejectsAnUnrelatedAccountType() {
    val result = authenticator { true }.addAccount(
      null,
      "example.unrelated",
      null,
      null,
      null
    )

    assertEquals(
      AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION,
      result.getInt(AccountManager.KEY_ERROR_CODE)
    )
    assertFalse(result.containsKey(AccountManager.KEY_INTENT))
  }

  @Test
  fun systemRemovalResultTracksConnectionCleanup() {
    val account = Account("ShelfDrive", ShelfDriveAccountContract.ACCOUNT_TYPE)
    var observed: Account? = null
    val accepting = authenticator {
      observed = it
      true
    }

    val allowed = accepting.getAccountRemovalAllowed(null, account)

    assertTrue(allowed.getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
    assertEquals(account, observed)

    val rejected = authenticator { false }.getAccountRemovalAllowed(null, account)
    assertFalse(rejected.getBoolean(AccountManager.KEY_BOOLEAN_RESULT))
  }

  private fun authenticator(handler: SystemAccountRemovalHandler) =
    ShelfDriveAccountAuthenticator(RuntimeEnvironment.getApplication(), handler)
}
