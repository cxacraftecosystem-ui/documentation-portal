package com.fieldrepository.app.explorer.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object ExplorerPlace {
    private const val PREFS = "explorer-place"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(owner: String?) = "path:" + ExplorerCache.segment(owner)

    fun read(context: Context, owner: String?): String? = runCatching { prefs(context).getString(key(owner), null) }.getOrNull()

    fun write(context: Context, owner: String?, path: String): Boolean = runCatching {
        val prefs = prefs(context)
        val k = key(owner)
        if (prefs.getString(k, null) == path) return@runCatching false
        prefs.edit().putString(k, path).apply()
        true
    }.getOrDefault(false)
}

data class ExplorerLink(val online: Boolean, val metered: Boolean)

object ExplorerNetwork {
    @Volatile var override: ExplorerLink? = null

    fun now(context: Context): ExplorerLink {
        override?.let { return it }
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return ExplorerLink(true, false)
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        return ExplorerLink(online, manager.isActiveNetworkMetered)
    }

    fun watch(context: Context): Flow<ExplorerLink> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        trySend(now(context))
        if (manager == null || override != null) {
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(now(context)) }
            override fun onLost(network: Network) { trySend(now(context)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(now(context)) }
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}
