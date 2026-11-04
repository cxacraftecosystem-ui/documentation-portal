package com.fieldrepository.app.ui

import android.content.Context

private const val ADMIN_VIEW_PREFS = "field_repository_admin_view"

fun adminViewKey(userId: String): String = "admin_view:$userId"

fun restoredAdminView(stored: Boolean?, isMasterAdmin: Boolean): Boolean = stored ?: isMasterAdmin

fun readAdminView(context: Context, userId: String): Boolean? = runCatching {
    val prefs = context.getSharedPreferences(ADMIN_VIEW_PREFS, Context.MODE_PRIVATE)
    val key = adminViewKey(userId)
    if (prefs.contains(key)) prefs.getBoolean(key, false) else null
}.getOrNull()

fun writeAdminView(context: Context, userId: String, on: Boolean) {
    runCatching {
        context.getSharedPreferences(ADMIN_VIEW_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(adminViewKey(userId), on)
            .apply()
    }
}
