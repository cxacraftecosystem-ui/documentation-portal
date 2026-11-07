package com.fieldrepository.app.explorer.ui

import android.content.Intent
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

data class ExplorerLinkRequest(
    val viewId: String?,
    val path: String?,
    val at: Long = System.nanoTime(),
    val go: List<String> = emptyList(),
    val preview: String? = null,
    val seconds: Double? = null,
    val bundle: String? = null,
    val view: String? = null,
    val sheet: String? = null,
    val q: String? = null,
    val hit: String? = null,
    val kind: String? = null,
)

object ExplorerViewLinks {
    private val state = MutableStateFlow<ExplorerLinkRequest?>(null)
    private val VIEWS = setOf("files", "tables", "transcripts")
    val requests: StateFlow<ExplorerLinkRequest?> = state.asStateFlow()

    fun parse(url: String, webBase: String = ExplorerPortal.WEB_BASE): ExplorerLinkRequest? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val home = runCatching { URI(webBase).host }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !uri.host.equals(home, ignoreCase = true)) return null
        val route = (uri.path ?: "").trimEnd('/')
        if (route != "/data" && !route.startsWith("/data/go/")) return null
        val go = if (route.startsWith("/data/go/")) route.removePrefix("/data/go/").split('/').filter { it.isNotBlank() }.map { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } else emptyList()
        if (route.startsWith("/data/go/") && go.size < 2) return null
        val query = (uri.rawQuery ?: "").split('&').filter { it.isNotBlank() }.associate { part ->
            val key = part.substringBefore('=')
            val value = runCatching { URLDecoder.decode(part.substringAfter('=', ""), "UTF-8") }.getOrDefault("")
            key to value
        }
        return ExplorerLinkRequest(
            viewId = query["viewId"]?.takeIf { it.isNotBlank() },
            path = query["path"]?.takeIf { it.isNotBlank() },
            go = go,
            preview = query["preview"]?.takeIf { it.isNotBlank() },
            seconds = query["t"]?.let { seconds(it) },
            bundle = query["bundle"]?.takeIf { it.isNotBlank() },
            view = query["view"]?.takeIf { it in VIEWS },
            sheet = query["sheet"]?.takeIf { it.isNotBlank() },
            q = query["q"]?.takeIf { it.isNotBlank() },
            hit = query["hit"]?.takeIf { it.isNotBlank() },
            kind = query["kind"]?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
        )
    }

    fun seconds(raw: String): Double? {
        val t = raw.trim().removeSuffix("s")
        t.toDoubleOrNull()?.let { return it.coerceAtLeast(0.0) }
        val parts = t.split(':').map { it.toIntOrNull() ?: return null }
        return when (parts.size) {
            2 -> parts[0] * 60.0 + parts[1]
            3 -> parts[0] * 3600.0 + parts[1] * 60.0 + parts[2]
            else -> null
        }
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

    fun consume(request: ExplorerLinkRequest): Boolean = state.compareAndSet(request, null)

    suspend fun serve(request: ExplorerLinkRequest, onFailure: (Throwable) -> Unit, handle: suspend (ExplorerLinkRequest) -> Unit) {
        try {
            handle(request)
        } catch (cancelled: CancellationException) {
            consume(request)
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        }
        consume(request)
    }

    fun viewFailure(failure: Throwable): String = when {
        failure is HttpException && failure.code() in MISSING_CODES -> ExplorerUiText.viewMissing
        failure is IOException -> ExplorerUiText.viewOffline
        else -> ExplorerUiText.viewFailed
    }

    private val MISSING_CODES = setOf(403, 404, 410)

    fun mediaType(served: String?, name: String): String? {
        val said = served?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        return said?.takeIf { it != "OTHER" } ?: mediaTypeOf(name) ?: said
    }
}
