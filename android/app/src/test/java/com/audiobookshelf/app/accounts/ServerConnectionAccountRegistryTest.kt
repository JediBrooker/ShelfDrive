package com.audiobookshelf.app.accounts

import android.accounts.Account
import android.os.Bundle
import com.audiobookshelf.app.data.ServerConnectionConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServerConnectionAccountRegistryTest {

  @Test
  fun registerStoresOnlyNonSecretIdentityMetadata() {
    val directory = FakeAccountDirectory()
    val cleaner = FakeConnectionDataCleaner()
    val registry = registry(directory, cleaner = cleaner)
    val config = config(
      id = "connection-1",
      token = "secret-access-token",
      customHeaders = mapOf("Authorization" to "secret-custom-header")
    )

    val result = registry.registerOrUpdate(config)

    assertEquals(AccountRegistrationResult.Status.CREATED, result.status)
    val account = result.account ?: throw AssertionError("Expected an account")
    val stored = directory.userData.getValue(account)
    assertEquals(
      setOf(
        ShelfDriveAccountContract.USER_DATA_CONNECTION_ID,
        ShelfDriveAccountContract.USER_DATA_DISPLAY_NAME,
        ShelfDriveAccountContract.USER_DATA_SCHEMA_VERSION
      ),
      stored.keys
    )
    assertEquals(config.id, stored[ShelfDriveAccountContract.USER_DATA_CONNECTION_ID])
    assertFalse(stored.values.any { it == config.token })
    assertFalse(stored.values.any { it == "secret-custom-header" })
    assertEquals(listOf(account), directory.clearedPasswords)
    assertTrue(cleaner.removedConnectionIds.isEmpty())
  }

  @Test
  fun resultDiagnosticsRedactFallbackAccountNameAndFailureIdentities() {
    val directory = FakeAccountDirectory()
    val registry = registry(directory)
    val registration = registry.registerOrUpdate(config(name = ""))
    val accountName = registration.account?.name
      ?: throw AssertionError("Expected fallback-named account")

    val registrationText = registration.toString()
    assertTrue(registrationText.contains("identityAndDetail=<redacted>"))
    listOf("reviewer", "example.test", accountName).forEach { privateValue ->
      assertFalse(registrationText.contains(privateValue))
    }

    val privateOrphan = Account(
      "private-reviewer @ https://private.example",
      ShelfDriveAccountContract.ACCOUNT_TYPE
    )
    directory.seed(privateOrphan, emptyMap())
    directory.allowRemoval = false

    val reconciliationText = registry.reconcile(emptyList()).toString()
    assertTrue(reconciliationText.contains("failureDetails=<redacted>"))
    assertTrue(reconciliationText.contains("failureCount=2"))
    assertFalse(reconciliationText.contains("private-reviewer"))
    assertFalse(reconciliationText.contains("private.example"))
  }

  @Test
  fun updateRefreshesSafeMetadataAndScrubsLegacySecrets() {
    val directory = FakeAccountDirectory()
    val existing = Account("Old display", ShelfDriveAccountContract.ACCOUNT_TYPE)
    directory.seed(
      existing,
      mapOf(
        ShelfDriveAccountContract.USER_DATA_CONNECTION_ID to "connection-1",
        ShelfDriveAccountContract.USER_DATA_DISPLAY_NAME to "Old display",
        "password" to "legacy-password",
        "refresh_token" to "legacy-refresh-token"
      )
    )
    val registry = registry(directory)

    val result = registry.registerOrUpdate(config(id = "connection-1", name = "New display"))

    assertEquals(AccountRegistrationResult.Status.UPDATED, result.status)
    assertEquals("New display", directory.value(existing, ShelfDriveAccountContract.USER_DATA_DISPLAY_NAME))
    assertNull(directory.value(existing, "password"))
    assertNull(directory.value(existing, "refresh_token"))
    assertEquals(ShelfDriveAccountContract.SCHEMA_VERSION,
      directory.value(existing, ShelfDriveAccountContract.USER_DATA_SCHEMA_VERSION))
    assertTrue(existing in directory.clearedPasswords)
  }

  @Test
  fun restrictionBlocksRegisterRemoveAndReconcileWithoutCleaningAppData() {
    val directory = FakeAccountDirectory()
    val cleaner = FakeConnectionDataCleaner()
    val registry = registry(directory, restricted = true, cleaner = cleaner)

    assertEquals(
      AccountRegistrationResult.Status.RESTRICTED,
      registry.registerOrUpdate(config()).status
    )
    assertEquals(
      AccountRegistrationResult.Status.RESTRICTED,
      registry.remove("connection-1").status
    )
    assertTrue(registry.reconcile(listOf(config())).restricted)
    assertTrue(directory.allAccounts.isEmpty())
    assertTrue(cleaner.removedConnectionIds.isEmpty())
  }

  @Test
  fun reconcileAddsUpdatesAndRemovesOrphanAndDuplicateAccounts() {
    val directory = FakeAccountDirectory()
    val primary = Account("Primary", ShelfDriveAccountContract.ACCOUNT_TYPE)
    val duplicate = Account("Duplicate", ShelfDriveAccountContract.ACCOUNT_TYPE)
    val orphan = Account("Orphan", ShelfDriveAccountContract.ACCOUNT_TYPE)
    directory.seed(primary, mapOf(
      ShelfDriveAccountContract.USER_DATA_CONNECTION_ID to "connection-1",
      ShelfDriveAccountContract.USER_DATA_DISPLAY_NAME to "Stale"
    ))
    directory.seed(duplicate, mapOf(
      ShelfDriveAccountContract.USER_DATA_CONNECTION_ID to "connection-1"
    ))
    directory.seed(orphan, mapOf(
      ShelfDriveAccountContract.USER_DATA_CONNECTION_ID to "removed-connection"
    ))

    val result = registry(directory).reconcile(listOf(
      config(id = "connection-1", name = "Current"),
      config(id = "connection-2", name = "Second")
    ))

    assertTrue(result.succeeded)
    assertEquals(1, result.created)
    assertEquals(1, result.updated)
    assertEquals(2, result.removed)
    assertTrue(primary in directory.allAccounts)
    assertFalse(duplicate in directory.allAccounts)
    assertFalse(orphan in directory.allAccounts)
    assertEquals(
      setOf("connection-1", "connection-2"),
      directory.allAccounts.mapNotNull {
        directory.value(it, ShelfDriveAccountContract.USER_DATA_CONNECTION_ID)
      }.toSet()
    )
  }

  @Test
  fun appRemovalCommitsConnectionCleanupBeforeRemovingSystemAccount() {
    val directory = FakeAccountDirectory()
    val cleaner = FakeConnectionDataCleaner()
    val registry = registry(directory, cleaner = cleaner)
    registry.registerOrUpdate(config())
    cleaner.allowRemoval = false

    val failed = registry.remove("connection-1")

    assertEquals(AccountRegistrationResult.Status.FAILED, failed.status)
    assertEquals(listOf("connection-1"), cleaner.removedConnectionIds)
    assertTrue(directory.removalAttempts.isEmpty())
    assertEquals(1, directory.allAccounts.size)

    cleaner.allowRemoval = true
    val removed = registry.remove("connection-1")

    assertEquals(AccountRegistrationResult.Status.REMOVED, removed.status)
    assertEquals(listOf("connection-1", "connection-1"), cleaner.removedConnectionIds)
    assertEquals(1, directory.removalAttempts.size)
    assertTrue(directory.allAccounts.isEmpty())
  }

  @Test
  fun accountManagerFailureOccursOnlyAfterAppPrivateCleanupCommits() {
    val directory = FakeAccountDirectory()
    val cleaner = FakeConnectionDataCleaner()
    val registry = registry(directory, cleaner = cleaner)
    registry.registerOrUpdate(config())
    directory.allowRemoval = false

    val result = registry.remove("connection-1")

    assertEquals(AccountRegistrationResult.Status.FAILED, result.status)
    assertEquals(listOf("connection-1"), cleaner.removedConnectionIds)
    assertEquals(1, directory.removalAttempts.size)
    assertEquals(1, directory.allAccounts.size)
  }

  @Test
  fun registrationRollbackRemovesSystemAccountWithoutCleaningConnectionData() {
    val directory = FakeAccountDirectory()
    val cleaner = FakeConnectionDataCleaner().apply { allowRemoval = false }
    val registry = registry(directory, cleaner = cleaner)
    registry.registerOrUpdate(config())

    val result = registry.remove("connection-1", cleanConnectionData = false)

    assertEquals(AccountRegistrationResult.Status.REMOVED, result.status)
    assertTrue(cleaner.removedConnectionIds.isEmpty())
    assertEquals(1, directory.removalAttempts.size)
    assertTrue(directory.allAccounts.isEmpty())
  }

  @Test
  fun systemRemovalCleansMatchingConnectionAndHonoursRestriction() {
    val directory = FakeAccountDirectory()
    val account = Account("ShelfDrive", ShelfDriveAccountContract.ACCOUNT_TYPE)
    directory.seed(account, mapOf(
      ShelfDriveAccountContract.USER_DATA_CONNECTION_ID to "connection-1"
    ))
    val cleaner = FakeConnectionDataCleaner()

    assertTrue(registry(directory, cleaner = cleaner).onSystemAccountRemoval(account))
    assertEquals(listOf("connection-1"), cleaner.removedConnectionIds)

    cleaner.removedConnectionIds.clear()
    assertFalse(registry(directory, restricted = true, cleaner = cleaner)
      .onSystemAccountRemoval(account))
    assertTrue(cleaner.removedConnectionIds.isEmpty())
  }

  private fun registry(
    directory: FakeAccountDirectory,
    restricted: Boolean = false,
    cleaner: FakeConnectionDataCleaner = FakeConnectionDataCleaner()
  ) = ServerConnectionAccountRegistry(
    directory,
    AccountModificationPolicy { restricted },
    cleaner
  )

  private fun config(
    id: String = "connection-1",
    name: String = "example.test (reviewer)",
    token: String = "access-token",
    customHeaders: Map<String, String>? = null
  ) = ServerConnectionConfig(
    id = id,
    index = 0,
    name = name,
    address = "https://example.test",
    version = "2.26.0",
    userId = "user-1",
    username = "reviewer",
    token = token,
    customHeaders = customHeaders
  )

  private class FakeConnectionDataCleaner : ConnectionDataCleaner {
    val removedConnectionIds = mutableListOf<String>()
    var allowRemoval = true

    override fun removeConnectionData(connectionId: String): Boolean {
      removedConnectionIds += connectionId
      return allowRemoval
    }
  }

  private class FakeAccountDirectory : AccountDirectory {
    val allAccounts = mutableListOf<Account>()
    val userData = linkedMapOf<Account, MutableMap<String, String>>()
    val clearedPasswords = mutableListOf<Account>()
    val removalAttempts = mutableListOf<Account>()
    var allowRemoval = true

    fun seed(account: Account, values: Map<String, String>) {
      allAccounts += account
      userData[account] = values.toMutableMap()
    }

    fun value(account: Account, key: String): String? = userData[account]?.get(key)

    override fun accountsByType(type: String): List<Account> =
      allAccounts.filter { it.type == type }.toList()

    override fun add(account: Account, userData: Bundle): Boolean {
      if (account in allAccounts) return false
      allAccounts += account
      this.userData[account] = userData.keySet().associateWith {
        userData.getString(it).orEmpty()
      }.toMutableMap()
      return true
    }

    override fun remove(account: Account): Boolean {
      removalAttempts += account
      if (!allowRemoval) return false
      userData.remove(account)
      return allAccounts.remove(account)
    }

    override fun getUserData(account: Account, key: String): String? = value(account, key)

    override fun setUserData(account: Account, key: String, value: String?) {
      val values = userData.getOrPut(account) { mutableMapOf() }
      if (value == null) values.remove(key) else values[key] = value
    }

    override fun clearPassword(account: Account) {
      clearedPasswords += account
    }
  }
}
