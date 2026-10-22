package com.fieldrepository.app.forms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A form another phone sent (A10): Quick Share, Bluetooth or a file manager opens this app with a
 * `.frwq` or `.dpwq`; the questionnaire screen shows P17 ("Received a form", Not now · Add).
 */
object FormBundleInbox {
    data class Received(val bundle: FormBundle?, val refusal: String?, val fileName: String?)

    val pending = MutableStateFlow<Received?>(null)

    /** Reads a VIEW or SEND intent that carries a form file; anything else is left alone. */
    fun take(context: Context, intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return false
        val name = displayName(context, uri)
        val typed = intent.type?.let { it == FormBundles.MIME || it == "application/vnd.designprototype.questionnaire+gzip" } == true
        if (!typed && !FormBundles.readable(name) && !FormBundles.readable(uri.lastPathSegment)) return false
        pending.value = receive(context, uri, name)
        return true
    }

    fun receive(context: Context, uri: Uri, name: String? = displayName(context, uri)): Received {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > FormBundles.MAX_BYTES) break
                }
                out.toByteArray()
            }
        }.getOrNull()
        return when (val read = FormBundles.read(bytes)) {
            is BundleRead.Ok -> Received(read.bundle, null, name)
            is BundleRead.Refused -> Received(null, read.message, name)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
