package com.fieldrepository.app.data

import android.content.Context
import kotlinx.serialization.json.Json

class TokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("field_repository_auth", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun getToken(): String? = preferences.getString(KEY_TOKEN, null)

    fun setToken(token: String?) {
        preferences.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
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
        const val KEY_TOKEN = "jwt"
        const val KEY_USER = "user"
    }
}
