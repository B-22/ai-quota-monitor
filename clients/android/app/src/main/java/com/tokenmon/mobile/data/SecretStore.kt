package com.tokenmon.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.secretStore: DataStore<Preferences> by preferencesDataStore(name = "hub_secret")

/** What a read of the stored secret produced. */
sealed interface SecretState {
    data class Loaded(val secret: String) : SecretState
    data object Empty : SecretState
    data object NeedsReentry : SecretState
}

/**
 * Keeps the Hub shared secret encrypted under an Android Keystore key.
 *
 * Why raw Keystore rather than `androidx.security.crypto`: AndroidX deprecated
 * `EncryptedSharedPreferences` and `MasterKey` in `security-crypto:1.1.0`, the
 * current **stable** release. Building the one release today on an API AndroidX
 * has already scheduled for removal is a maintenance liability, and the pattern
 * it is steering toward is the platform one.
 *
 * The scheme is the documented Android Keystore AES-GCM envelope:
 *  - a non-exportable AES-256-GCM key generated inside the Keystore;
 *  - a fresh 12-byte IV per write, stored alongside the ciphertext;
 *  - ciphertext kept in a DataStore preferences file inside the app sandbox.
 *
 * The key is deliberately **not** gated on user authentication. The widget
 * refreshes in the background, and a `setUserAuthenticationRequired(true)` key
 * would make every background refresh fail with `UserNotAuthenticatedException`.
 *
 * Failure handling matters as much as the happy path: a keystore that the user
 * invalidated (device credential reset, restore onto new hardware) cannot decrypt
 * the stored bytes. That is reported as [SecretState.NeedsReentry] so the UI
 * asks for the secret again instead of crashing or silently reporting success.
 */
class SecretStore(context: Context) {

    private val store = context.applicationContext.secretStore

    val state: Flow<SecretState> = store.data.map { prefs ->
        decrypt(prefs[KEY_CIPHERTEXT], prefs[KEY_IV])
    }

    suspend fun read(): SecretState = state.first()

    /** @return true when the value was persisted (or cleared). */
    suspend fun write(secret: String): Boolean {
        val trimmed = secret.trim()
        if (trimmed.isEmpty()) {
            clear()
            return true
        }
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            store.edit { prefs ->
                prefs[KEY_IV] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                prefs[KEY_CIPHERTEXT] = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            }
            true
        }.getOrElse { error ->
            Log.w(TAG, "Could not encrypt secret: ${error.javaClass.simpleName}")
            false
        }
    }

    suspend fun clear() {
        runCatching {
            store.edit { it.remove(KEY_CIPHERTEXT); it.remove(KEY_IV) }
        }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    suspend fun hasSecret(): Boolean = read() is SecretState.Loaded

    private fun decrypt(ciphertext: String?, iv: String?): SecretState {
        if (ciphertext.isNullOrBlank() || iv.isNullOrBlank()) return SecretState.Empty
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP))
            )
            SecretState.Loaded(
                String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
            )
        }.getOrElse { error ->
            // The key is gone or was rotated: these bytes are unrecoverable.
            Log.w(TAG, "Stored secret is undecryptable: ${error.javaClass.simpleName}")
            SecretState.NeedsReentry
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Returns the existing key, creating an AES-256-GCM one on first use. */
    private fun key(): SecretKey {
        keyStore().getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "token_monitor_hub_secret"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        val KEY_CIPHERTEXT = stringPreferencesKey("secret_ciphertext")
        val KEY_IV = stringPreferencesKey("secret_iv")
    }
}
