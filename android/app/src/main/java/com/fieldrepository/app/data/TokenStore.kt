package com.fieldrepository.app.data

import android.content.Context

class TokenStore(context: Context) {
    private val preferences = context.getSharedPreferences("field_repository_auth", Context.MODE_PRIVATE)

    fun getToken(): String? = preferences.getString(KEY_TOKEN, null)

    fun setToken(token: String?) {
        preferences.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
        }.apply()
    }

    private companion object {
        const val KEY_TOKEN = "jwt"
    }
}
