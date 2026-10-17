package com.fieldrepository.app.ui

import android.content.Context

/*
 * THE ADMIN-VIEW SWITCH, REMEMBERED PER ACCOUNT.
 *
 * The switch lived in composition state only, so a restart or a fresh sign-in put an admin back to
 * the role default every time — OFF for an admin, however often they turned it on (release smoke
 * test 2026-10-17). It is now written on every flip and read back when the account's home screen
 * opens, keyed by the account so two admins sharing a handset each keep their own.
 *
 * Storing it decides nothing about access: `HomeScreen` still ANDs the stored request with the
 * entitlement at every read, so a role lowered since the flag was saved cannot be widened by it.
 */

private const val ADMIN_VIEW_PREFS = "field_repository_admin_view"

/** The preference key for one account's switch. */
fun adminViewKey(userId: String): String = "admin_view:$userId"

/** What the switch opens as: the account's last choice, else the role default (master admin ON). */
fun restoredAdminView(stored: Boolean?, isMasterAdmin: Boolean): Boolean = stored ?: isMasterAdmin

/** The account's last choice, or null when it never flipped the switch on this device. */
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
