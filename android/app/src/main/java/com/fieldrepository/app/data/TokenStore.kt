package com.fieldrepository.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
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

    /*
     * THE TOKEN IS SEALED WITH A KEY THAT NEVER LEAVES THE ANDROID KEYSTORE (2026-10-17).
     *
     * It used to sit in this XML file as plain text, readable by anything that could read the app's
     * data directory (a rooted phone, a forensic copy, a debuggable build). It is now encrypted with
     * AES-256-GCM under [KeystoreTokenSealer]'s key, which lives in the Keystore (hardware-backed where
     * the phone has it) and cannot be exported, so a copy of this file is useless off the phone.
     *
     * Not `EncryptedSharedPreferences`: androidx.security-crypto is deprecated, and this needs one
     * value sealed, not a whole store.
     *
     * MIGRATION, AND NOBODY IS SIGNED OUT BY IT. A token written by an older build is still under
     * [KEY_TOKEN] in plain text. The first read seals it under [KEY_SEALED_TOKEN] and deletes the
     * plain copy. If the Keystore refuses to work at all (a handful of broken vendor builds, and
     * Robolectric), the token is kept exactly as before rather than lost — being signed out in a
     * village with no signal is the worse failure. A sealed token that no longer opens (the key was
     * wiped with a lock-screen reset) reads as signed out, which is the truth: it cannot be used.
     */
    fun getToken(): String? {
        preferences.getString(KEY_SEALED_TOKEN, null)?.let { sealed ->
            // Every API call reads the token; open each sealed value once per process.
            opened?.let { (seal, plain) -> if (seal == sealed) return plain }
            val plain = sealer.open(sealed)
            if (plain == null) preferences.edit().remove(KEY_SEALED_TOKEN).apply() else opened = sealed to plain
            return plain
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
                    // The Keystore is unusable on this phone; see getToken. Kept as it always was.
                    putString(KEY_TOKEN, token)
                    remove(KEY_SEALED_TOKEN)
                }
            }
        }.apply()
    }

    /** Cached profile so the session survives app minimise/resume and offline relaunches. */
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
        /** Plain text, written by builds before 2026-10-17 and by a phone whose Keystore is broken. */
        const val KEY_TOKEN = "jwt"
        /** `v1:<base64 iv>:<base64 ciphertext+tag>`, sealed by [KeystoreTokenSealer]. */
        const val KEY_SEALED_TOKEN = "jwt_sealed"
        const val KEY_USER = "user"

        /** The last sealed value opened, and what it opened to. */
        @Volatile
        var opened: Pair<String, String>? = null
    }
}

/** Seals a short secret for storage and opens it again; null from either means "could not". */
interface TokenSealer {
    fun seal(plain: String): String?
    fun open(sealed: String): String?
}

/**
 * Seals and opens one short secret with an AES-256-GCM key kept in the Android Keystore. Every
 * method answers null instead of throwing: a phone whose Keystore misbehaves must still be usable.
 */
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

    override fun open(sealed: String): String? {
        val parts = sealed.split(":")
        if (parts.size != 3 || parts[0] != "v1") return null
        val key = key() ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[1])))
            }
            String(cipher.doFinal(Base64.getDecoder().decode(parts[2])), Charsets.UTF_8)
        }.getOrNull()
    }

    companion object {
        val shared = KeystoreTokenSealer()
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG = "TokenStore"
    }
}
