package com.audiobookshelf.app.managers

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RefreshTokenStorage {
    fun storeRefreshToken(serverConnectionId: String, refreshToken: String): Boolean
    fun getRefreshToken(serverConnectionId: String): String?
    fun removeRefreshToken(serverConnectionId: String): Boolean
    fun hasRefreshToken(serverConnectionId: String): Boolean
}

class SecureStorage(private val context: Context) : RefreshTokenStorage {
    companion object {
        private const val TAG = "SecureStorage"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "AudiobookshelfRefreshTokens"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_LENGTH = 128
    }

    // Initialize inside each public method's try/catch. Test environments and
    // a small number of devices can lack the AndroidKeyStore provider during
    // early startup; constructing SecureStorage must not crash sign-in.
    private val keyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    /**
     * Encrypts and stores a refresh token for a specific server connection
     */
    override fun storeRefreshToken(serverConnectionId: String, refreshToken: String): Boolean {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val encryptedBytes = cipher.doFinal(refreshToken.toByteArray(Charsets.UTF_8))
            val combined = cipher.iv + encryptedBytes

            val encoded = Base64.encodeToString(combined, Base64.DEFAULT)

            val sharedPrefs = context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE)
            // A true return value means the credential is durable before the
            // caller persists a profile that depends on it.
            val stored = sharedPrefs.edit()
                .putString("refresh_token_$serverConnectionId", encoded)
                .commit()

            if (!stored) Log.e(TAG, "Failed to persist encrypted refresh token")
            stored
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store refresh token (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Retrieves and decrypts a refresh token for a specific server connection
     */
    override fun getRefreshToken(serverConnectionId: String): String? {
        return try {
            val sharedPrefs = context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE)
            val encoded = sharedPrefs.getString("refresh_token_$serverConnectionId", null) ?: return null

            val combined = Base64.decode(encoded, Base64.DEFAULT)
            val iv = combined.copyOfRange(0, IV_LENGTH)
            val encryptedBytes = combined.copyOfRange(IV_LENGTH, combined.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decryptedBytes = cipher.doFinal(encryptedBytes)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve refresh token (${e.javaClass.simpleName})")
            null
        }
    }

    /**
     * Removes a refresh token for a specific server connection
     */
    override fun removeRefreshToken(serverConnectionId: String): Boolean {
        return try {
            val sharedPrefs = context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE)
            // Account removal reports success to Android immediately after
            // this method returns. Use a synchronous commit so process death
            // cannot leave a credential behind after a reported deletion.
            val removed = sharedPrefs.edit()
                .remove("refresh_token_$serverConnectionId")
                .commit()
            if (!removed) Log.e(TAG, "Failed to persist refresh-token removal")
            removed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove refresh token (${e.javaClass.simpleName})")
            false
        }
    }

    /**
     * Checks if a refresh token exists for a specific server connection
     */
    override fun hasRefreshToken(serverConnectionId: String): Boolean {
        return try {
            val sharedPrefs = context.getSharedPreferences("SecureStorage", Context.MODE_PRIVATE)
            sharedPrefs.contains("refresh_token_$serverConnectionId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check refresh token (${e.javaClass.simpleName})")
            false
        }
    }

    private fun getOrCreateKey(): SecretKey {
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            keyStore.getKey(KEY_ALIAS, null) as SecretKey
        } else {
            createKey()
        }
    }

    private fun createKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val keyGenSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(keyGenSpec)
        return keyGenerator.generateKey()
    }
}
