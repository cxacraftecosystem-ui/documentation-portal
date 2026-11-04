package com.fieldrepository.app.explorer.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Collections

class ExplorerOutput(
    val stream: OutputStream,
    private val onFinish: () -> Pair<String, String?>,
    private val onAbort: () -> Unit,
) {
    fun finish(): Pair<String, String?> = onFinish()
    fun abort() {
        runCatching { onAbort() }
    }
}

object ExplorerSaver {
    private const val PREFS = "explorer-pending"
    private const val KEY = "uris"
    private val live: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun open(context: Context, name: String, mimeType: String): ExplorerOutput {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(ExplorerText.saveFailed)
            remember(context, uri.toString())
            val raw = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                forget(context, uri.toString())
                throw IllegalStateException(ExplorerText.saveFailed)
            }
            val stream = BufferedOutputStream(raw, 256 * 1024)
            return ExplorerOutput(
                stream,
                onFinish = {
                    stream.close()
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                    forget(context, uri.toString())
                    val stored = runCatching {
                        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull() ?: name
                    "Downloads/$stored" to uri.toString()
                },
                onAbort = {
                    runCatching { stream.close() }
                    runCatching { resolver.delete(uri, null, null) }
                    forget(context, uri.toString())
                },
            )
        }
        val legacy = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val folder = if (legacy) {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
        }
        folder.mkdirs()
        val target = uniqueFile(folder, name)
        remember(context, Uri.fromFile(target).toString())
        val stream = BufferedOutputStream(FileOutputStream(target), 256 * 1024)
        return ExplorerOutput(
            stream,
            onFinish = {
                stream.close()
                forget(context, Uri.fromFile(target).toString())
                target.absolutePath to null
            },
            onAbort = {
                runCatching { stream.close() }
                target.delete()
                forget(context, Uri.fromFile(target).toString())
            },
        )
    }

    fun uniqueFile(folder: File, name: String): File {
        var candidate = File(folder, name)
        var n = 2
        while (candidate.exists()) {
            candidate = File(folder, ExplorerZipNames.withExtension(name, "") + "_$n" + (ExplorerZipNames.extensionOf(name) ?: ""))
            n++
        }
        return candidate
    }

    fun freeBytes(context: Context): Long? = runCatching {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        minOf(StatFs(dir.absolutePath).availableBytes, StatFs(context.cacheDir.absolutePath).availableBytes)
    }.getOrNull()

    fun shareUri(context: Context, location: String?, uri: String?): Uri? {
        if (!uri.isNullOrBlank()) return Uri.parse(uri)
        val path = location ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return runCatching {
            androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        }.getOrNull()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun remember(context: Context, uri: String) {
        live += uri
        synchronized(this) {
            val set = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
            set += uri
            prefs(context).edit().putStringSet(KEY, set).commit()
        }
    }

    private fun forget(context: Context, uri: String) {
        live -= uri
        synchronized(this) {
            val set = prefs(context).getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
            if (set.remove(uri)) prefs(context).edit().putStringSet(KEY, set).commit()
        }
    }

    fun sweep(context: Context): Int {
        val stale = synchronized(this) {
            prefs(context).getStringSet(KEY, emptySet()).orEmpty().filterNot { it in live }
        }
        var removed = 0
        stale.forEach { raw ->
            val uri = Uri.parse(raw)
            runCatching {
                if (uri.scheme == "file") File(uri.path.orEmpty()).delete() else context.contentResolver.delete(uri, null, null)
                removed++
            }
            forget(context, raw)
        }
        val cache = context.cacheDir
        val cutoff = System.currentTimeMillis() - STALE_MS
        cache.listFiles()?.forEach { f ->
            val legacy = f.isFile && (f.name.endsWith(".zip") || f.name.endsWith(".manifest") || f.name.endsWith(".part")) && f.lastModified() < cutoff
            if (legacy && f.delete()) removed++
        }
        return removed
    }

    const val STALE_MS = 60 * 60 * 1000L
}
