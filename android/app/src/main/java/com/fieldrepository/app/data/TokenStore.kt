package com.fieldrepository.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

class TokenStore internal constructor(
    private val preferences: SharedPreferences,
    private val sealer: TokenSealer
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("field_repository_auth", Context.MODE_PRIVATE),
        KeystoreTokenSealer.shared
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun getToken(): String? {
        preferences.getString(KEY_SEALED_TOKEN, null)?.let { sealed ->
            opened?.let { (seal, plain) -> if (seal == sealed) return plain }
            return when (val result = sealer.open(sealed)) {
                is OpenResult.Opened -> result.plain.also { opened = sealed to it }
                OpenResult.Unavailable -> null
                OpenResult.Unreadable -> {
                    preferences.edit().remove(KEY_SEALED_TOKEN).apply()
                    null
                }
            }
        }
        val legacy = preferences.getString(KEY_TOKEN, null) ?: return null
        val sealed = sealer.seal(legacy)
        if (sealed != null) {
            preferences.edit().putString(KEY_SEALED_TOKEN, sealed).remove(KEY_TOKEN).apply()
        }
        return legacy
    }

    fun setToken(token: String?) {
        preferences.edit().apply {
            if (token == null) {
                remove(KEY_TOKEN)
                remove(KEY_SEALED_TOKEN)
            } else {
                val sealed = sealer.seal(token)
                if (sealed != null) {
                    putString(KEY_SEALED_TOKEN, sealed)
                    remove(KEY_TOKEN)
                } else {
                    putString(KEY_TOKEN, token)
                    remove(KEY_SEALED_TOKEN)
                }
            }
        }.apply()
    }

    fun getUser(): UserDto? {
        val raw = preferences.getString(KEY_USER, null) ?: return null
        return runCatching { json.decodeFromString(UserDto.serializer(), raw) }.getOrNull()
    }

    fun setUser(user: UserDto?) {
        preferences.edit().apply {
            if (user == null) remove(KEY_USER) else putString(KEY_USER, json.encodeToString(UserDto.serializer(), user))
        }.apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_TOKEN = "jwt"
        const val KEY_SEALED_TOKEN = "jwt_sealed"
        const val KEY_USER = "user"

        @Volatile
        var opened: Pair<String, String>? = null
    }
}

interface TokenSealer {
    fun seal(plain: String): String?
    fun open(sealed: String): OpenResult
}

sealed interface OpenResult {
    data class Opened(val plain: String) : OpenResult

    data object Unreadable : OpenResult

    data object Unavailable : OpenResult
}

class KeystoreTokenSealer(private val alias: String = "session_token_key") : TokenSealer {
    private fun key(): SecretKey? = runCatching {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }.onFailure { runCatching { Log.w(TAG, "Keystore unavailable; the session token stays as it was", it) } }.getOrNull()

    override fun seal(plain: String): String? {
        val key = key() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            "v1:" + Base64.getEncoder().encodeToString(cipher.iv) + ":" + Base64.getEncoder().encodeToString(sealed)
        }.getOrNull()
    }

    override fun open(sealed: String): OpenResult {
        val parts = sealed.split(":")
        if (parts.size != 3 || parts[0] != "v1") return OpenResult.Unreadable
        val iv: ByteArray
        val body: ByteArray
        try {
            iv = Base64.getDecoder().decode(parts[1])
            body = Base64.getDecoder().decode(parts[2])
        } catch (_: IllegalArgumentException) {
            return OpenResult.Unreadable
        }
        val key = try {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            store.getKey(alias, null) as? SecretKey ?: return OpenResult.Unreadable
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Keystore unavailable; the sealed session token is kept for the next read", e) }
            return OpenResult.Unavailable
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }
            OpenResult.Opened(String(cipher.doFinal(body), Charsets.UTF_8))
        } catch (_: AEADBadTagException) {
            OpenResult.Unreadable
        } catch (_: KeyPermanentlyInvalidatedException) {
            OpenResult.Unreadable
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Keystore could not open the session token just now; it is kept", e) }
            OpenResult.Unavailable
        }
    }

    companion object {
        val shared = KeystoreTokenSealer()
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG = "TokenStore"
    }
}
