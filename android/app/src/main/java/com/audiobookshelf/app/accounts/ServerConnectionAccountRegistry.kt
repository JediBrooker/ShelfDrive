package com.audiobookshelf.app.accounts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import com.audiobookshelf.app.data.ServerConnectionConfig
import java.security.MessageDigest

/** Result of a single AccountManager synchronization operation. */
data class AccountRegistrationResult(
  val status: Status,
  val account: Account? = null,
  val detail: String? = null
) {
  enum class Status {
    CREATED,
    UPDATED,
    UNCHANGED,
    REMOVED,
    NOT_FOUND,
    RESTRICTED,
    FAILED
  }

  val succeeded: Boolean
    get() = status != Status.RESTRICTED && status != Status.FAILED

  /** Never render the Account name or failure detail into logs/crash strings. */
  override fun toString(): String =
    "AccountRegistrationResult(status=$status, succeeded=$succeeded, " +
      "hasAccount=${account != null}, hasDetail=${detail != null}, " +
      "identityAndDetail=<redacted>)"
}

/** Summary returned when all saved connections are reconciled with AccountManager. */
data class AccountReconciliationResult(
  val created: Int = 0,
  val updated: Int = 0,
  val unchanged: Int = 0,
  val removed: Int = 0,
  val restricted: Boolean = false,
  val failures: List<String> = emptyList()
) {
  val succeeded: Boolean
    get() = !restricted && failures.isEmpty()

  /** Failure entries can contain account names; expose only aggregate state. */
  override fun toString(): String =
    "AccountReconciliationResult(created=$created, updated=$updated, " +
      "unchanged=$unchanged, removed=$removed, restricted=$restricted, " +
      "failureCount=${failures.size}, failureDetails=<redacted>)"
}

/**
 * Mirrors [ServerConnectionConfig] identities into Android AccountManager.
 *
 * Only non-secret identity metadata is mirrored. The registry never sends a
 * password or auth token to AccountManager and clears legacy password/user-data
 * fields whenever it touches an account.
 */
class ServerConnectionAccountRegistry internal constructor(
  private val accounts: AccountDirectory,
  private val modificationPolicy: AccountModificationPolicy,
  private val connectionDataCleaner: ConnectionDataCleaner
) : SystemAccountRemovalHandler {

  constructor(context: Context) : this(
    AndroidAccountDirectory(AccountManager.get(context.applicationContext)),
    UserRestrictionAccountModificationPolicy(context.applicationContext),
    ShelfDriveConnectionDataCleaner(context.applicationContext)
  )

  fun canModifyAccounts(): Boolean = !modificationPolicy.isModificationDisallowed()

  /** Creates a system account for [config], or refreshes its non-secret metadata. */
  @Synchronized
  fun registerOrUpdate(config: ServerConnectionConfig): AccountRegistrationResult {
    if (modificationPolicy.isModificationDisallowed()) {
      return AccountRegistrationResult(
        AccountRegistrationResult.Status.RESTRICTED,
        detail = "Account changes are disabled for this user"
      )
    }
    return try {
      registerOrUpdateUnchecked(config)
    } catch (error: RuntimeException) {
      AccountRegistrationResult(
        AccountRegistrationResult.Status.FAILED,
        detail = error.javaClass.simpleName
      )
    }
  }

  /**
   * Removes app-private connection data and every system account for
   * [connectionId]. App-private cleanup commits first so a failed durable
   * profile write can never remove the Android account that identifies the
   * still-saved connection. Registration rollback can opt out of cleanup.
   */
  @Synchronized
  fun remove(
    connectionId: String,
    cleanConnectionData: Boolean = true
  ): AccountRegistrationResult {
    if (modificationPolicy.isModificationDisallowed()) {
      return AccountRegistrationResult(
        AccountRegistrationResult.Status.RESTRICTED,
        detail = "Account changes are disabled for this user"
      )
    }
    if (connectionId.isBlank()) {
      return AccountRegistrationResult(
        AccountRegistrationResult.Status.FAILED,
        detail = "Missing connection ID"
      )
    }

    return try {
      val matching = managedAccounts().filter { connectionIdFor(it) == connectionId }
      if (cleanConnectionData && !connectionDataCleaner.removeConnectionData(connectionId)) {
        AccountRegistrationResult(
          AccountRegistrationResult.Status.FAILED,
          detail = "ShelfDrive connection cleanup failed"
        )
      } else if (!matching.all(accounts::remove)) {
        AccountRegistrationResult(
          AccountRegistrationResult.Status.FAILED,
          account = matching.firstOrNull(),
          detail = "AccountManager did not remove every matching account"
        )
      } else {
        AccountRegistrationResult(
          if (matching.isEmpty()) {
            AccountRegistrationResult.Status.NOT_FOUND
          } else {
            AccountRegistrationResult.Status.REMOVED
          },
          account = matching.firstOrNull()
        )
      }
    } catch (error: RuntimeException) {
      AccountRegistrationResult(
        AccountRegistrationResult.Status.FAILED,
        detail = error.javaClass.simpleName
      )
    }
  }

  /**
   * Makes AccountManager exactly mirror [configs]. Orphaned and duplicate
   * system accounts are removed without touching app data because [configs]
   * is the source of truth for this operation.
   */
  @Synchronized
  fun reconcile(configs: Collection<ServerConnectionConfig>): AccountReconciliationResult {
    if (modificationPolicy.isModificationDisallowed()) {
      return AccountReconciliationResult(restricted = true)
    }

    return try {
      val configsById = linkedMapOf<String, ServerConnectionConfig>()
      configs.forEach { config ->
        if (config.id.isNotBlank()) configsById[config.id] = config
      }

      var created = 0
      var updated = 0
      var unchanged = 0
      var removed = 0
      val failures = mutableListOf<String>()

      configsById.values.forEach { config ->
        when (registerOrUpdateUnchecked(config).status) {
          AccountRegistrationResult.Status.CREATED -> created++
          AccountRegistrationResult.Status.UPDATED -> updated++
          AccountRegistrationResult.Status.UNCHANGED -> unchanged++
          AccountRegistrationResult.Status.FAILED -> failures += config.id
          else -> failures += config.id
        }
      }

      val seenConnectionIds = mutableSetOf<String>()
      managedAccounts().forEach { account ->
        val connectionId = connectionIdFor(account)
        val isOrphan = connectionId.isNullOrBlank() || connectionId !in configsById
        val isDuplicate = !connectionId.isNullOrBlank() && !seenConnectionIds.add(connectionId)
        if (isOrphan || isDuplicate) {
          if (accounts.remove(account)) removed++ else failures += account.name
        }
      }

      AccountReconciliationResult(created, updated, unchanged, removed, failures = failures)
    } catch (error: RuntimeException) {
      AccountReconciliationResult(failures = listOf(error.javaClass.simpleName))
    }
  }

  /** Called by the authenticator immediately before a system-initiated removal. */
  override fun onSystemAccountRemoval(account: Account): Boolean {
    if (account.type != ShelfDriveAccountContract.ACCOUNT_TYPE) return false
    if (modificationPolicy.isModificationDisallowed()) return false
    val connectionId = connectionIdFor(account)
    if (connectionId.isNullOrBlank()) return true
    return try {
      connectionDataCleaner.removeConnectionData(connectionId)
    } catch (_: RuntimeException) {
      false
    }
  }

  private fun registerOrUpdateUnchecked(
    config: ServerConnectionConfig
  ): AccountRegistrationResult {
    if (config.id.isBlank()) {
      return AccountRegistrationResult(
        AccountRegistrationResult.Status.FAILED,
        detail = "Missing connection ID"
      )
    }

    val existing = managedAccounts().firstOrNull { connectionIdFor(it) == config.id }
    if (existing != null) {
      val changed = updateSafeMetadata(existing, config)
      return AccountRegistrationResult(
        if (changed) {
          AccountRegistrationResult.Status.UPDATED
        } else {
          AccountRegistrationResult.Status.UNCHANGED
        },
        existing
      )
    }

    val account = Account(accountName(config), ShelfDriveAccountContract.ACCOUNT_TYPE)
    val added = accounts.add(account, safeUserData(config))
    if (!added) {
      // AccountManager may have won a race with another app component. Resolve
      // a successfully created matching account before reporting failure.
      val racedAccount = managedAccounts().firstOrNull { connectionIdFor(it) == config.id }
      return if (racedAccount != null) {
        updateSafeMetadata(racedAccount, config)
        AccountRegistrationResult(AccountRegistrationResult.Status.UPDATED, racedAccount)
      } else {
        AccountRegistrationResult(
          AccountRegistrationResult.Status.FAILED,
          account,
          "AccountManager rejected the account"
        )
      }
    }
    accounts.clearPassword(account)
    clearSensitiveUserData(account)
    return AccountRegistrationResult(AccountRegistrationResult.Status.CREATED, account)
  }

  private fun updateSafeMetadata(
    account: Account,
    config: ServerConnectionConfig
  ): Boolean {
    var changed = false
    val desiredUserData = safeUserData(config)
    desiredUserData.keySet().forEach { key ->
      val desired = desiredUserData.getString(key)
      if (accounts.getUserData(account, key) != desired) {
        accounts.setUserData(account, key, desired)
        changed = true
      }
    }
    accounts.clearPassword(account)
    if (clearSensitiveUserData(account)) changed = true
    return changed
  }

  private fun clearSensitiveUserData(account: Account): Boolean {
    var changed = false
    ShelfDriveAccountContract.SENSITIVE_USER_DATA_KEYS.forEach { key ->
      if (accounts.getUserData(account, key) != null) {
        accounts.setUserData(account, key, null)
        changed = true
      }
    }
    return changed
  }

  private fun safeUserData(config: ServerConnectionConfig) = Bundle().apply {
    putString(ShelfDriveAccountContract.USER_DATA_CONNECTION_ID, config.id)
    putString(ShelfDriveAccountContract.USER_DATA_DISPLAY_NAME, displayName(config))
    putString(
      ShelfDriveAccountContract.USER_DATA_SCHEMA_VERSION,
      ShelfDriveAccountContract.SCHEMA_VERSION
    )
  }

  private fun managedAccounts(): List<Account> =
    accounts.accountsByType(ShelfDriveAccountContract.ACCOUNT_TYPE)

  private fun connectionIdFor(account: Account): String? =
    accounts.getUserData(account, ShelfDriveAccountContract.USER_DATA_CONNECTION_ID)

  private fun accountName(config: ServerConnectionConfig): String {
    val digest = MessageDigest.getInstance("SHA-256")
      .digest(config.id.toByteArray(Charsets.UTF_8))
      .take(6)
      .joinToString("") { byte -> "%02x".format(byte) }
    return "${displayName(config).take(120)} · $digest"
  }

  private fun displayName(config: ServerConnectionConfig): String {
    val configuredName = config.name.trim()
    if (configuredName.isNotEmpty()) return configuredName
    val username = config.username.trim().ifEmpty { "ShelfDrive" }
    val address = config.address.trim().ifEmpty { "server" }
    return "$username @ $address"
  }
}

internal interface AccountDirectory {
  fun accountsByType(type: String): List<Account>
  fun add(account: Account, userData: Bundle): Boolean
  fun remove(account: Account): Boolean
  fun getUserData(account: Account, key: String): String?
  fun setUserData(account: Account, key: String, value: String?)
  fun clearPassword(account: Account)
}

internal class AndroidAccountDirectory(
  private val accountManager: AccountManager
) : AccountDirectory {
  override fun accountsByType(type: String): List<Account> =
    accountManager.getAccountsByType(type).toList()

  override fun add(account: Account, userData: Bundle): Boolean =
    accountManager.addAccountExplicitly(account, null, userData)

  override fun remove(account: Account): Boolean =
    accountManager.removeAccountExplicitly(account)

  override fun getUserData(account: Account, key: String): String? =
    accountManager.getUserData(account, key)

  override fun setUserData(account: Account, key: String, value: String?) {
    accountManager.setUserData(account, key, value)
  }

  override fun clearPassword(account: Account) {
    // setPassword(null) is the documented equivalent of clearing the password
    // and behaves consistently across OEM AccountManager implementations.
    accountManager.setPassword(account, null)
  }
}

internal fun interface AccountModificationPolicy {
  fun isModificationDisallowed(): Boolean
}

internal class UserRestrictionAccountModificationPolicy(
  context: Context
) : AccountModificationPolicy {
  private val userManager = context.getSystemService(UserManager::class.java)

  override fun isModificationDisallowed(): Boolean =
    userManager?.hasUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS) == true
}

internal fun interface ConnectionDataCleaner {
  /** Returns false only when cleanup failed and account removal should be stopped. */
  fun removeConnectionData(connectionId: String): Boolean
}

internal fun interface SystemAccountRemovalHandler {
  /** Returns true when Android may complete removal of [account]. */
  fun onSystemAccountRemoval(account: Account): Boolean
}
