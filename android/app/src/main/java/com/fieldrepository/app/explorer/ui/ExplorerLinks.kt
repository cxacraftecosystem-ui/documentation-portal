package com.fieldrepository.app.explorer.ui

import android.content.Intent
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.net.URLDecoder

data class ExplorerLinkRequest(val viewId: String?, val path: String?, val at: Long = System.nanoTime())

object ExplorerViewLinks {
    private val state = MutableStateFlow<ExplorerLinkRequest?>(null)
    val requests: StateFlow<ExplorerLinkRequest?> = state.asStateFlow()

    fun parse(url: String, webBase: String = ExplorerPortal.WEB_BASE): ExplorerLinkRequest? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val home = runCatching { URI(webBase).host }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(home, ignoreCase = true)) return null
        if ((uri.path ?: "").trimEnd('/') != "/data") return null
        val query = (uri.rawQuery ?: "").split('&').filter { it.isNotBlank() }.associate { part ->
            val key = part.substringBefore('=')
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            key to value
        }
        return ExplorerLinkRequest(query["viewId"]?.takeIf { it.isNotBlank() }, query["path"]?.takeIf { it.isNotBlank() })
    }

    fun take(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW) return false
        val data = intent.dataString ?: return false
        val request = parse(data) ?: return false
        state.value = request
        return true
    }

    fun request(request: ExplorerLinkRequest) {
        state.value = request
    }

    fun consume() {
        state.value = null
    }
}
