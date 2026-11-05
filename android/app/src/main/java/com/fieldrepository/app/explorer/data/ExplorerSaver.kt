package com.fieldrepository.app.explorer.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.fieldrepository.app.explorer.portal.ExplorerPortal
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.Collections

class ExplorerOutput(
    val stream: OutputStream,
    private val onFinish: () -> Pair<String, String?>,
    private val onAbort: () -> Unit,
    val handle: String? = null,
) {
    fun finish(): Pair<String, String?> = onFinish()
    fun abort() {
        runCatching { onAbort() }
    }
}

object ExplorerFolder {
    private const val PREFS = "explorer-folder"
    private const val KEY = "tree"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): Uri? = runCatching { prefs(context).getString(KEY, null)?.let(Uri::parse) }.getOrNull()

    fun write(context: Context, uri: Uri?) {
        val app = context.applicationContext
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val was = read(app)
        if (uri == null) {
            if (was != null) runCatching { app.contentResolver.releasePersistableUriPermission(was, flags) }
            runCatching { prefs(app).edit().remove(KEY).apply() }
            return
        }
        runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
        if (was != null && was != uri) runCatching { app.contentResolver.releasePersistableUriPermission(was, flags) }
        runCatching { prefs(app).edit().putString(KEY, uri.toString()).apply() }
    }

    fun writable(context: Context): Boolean {
        val uri = read(context) ?: return false
        val granted = runCatching {
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }.getOrDefault(false)
        if (!granted) return false
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.let { it.isDirectory && it.canWrite() } == true }.getOrDefault(false)
    }

    fun label(context: Context): String? {
        val uri = read(context) ?: return null
        return runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }
}

object ExplorerSaver {
    private const val PREFS = "explorer-pending"
    private const val KEY = "uris"
    private val live: MutableSet<String> = Collections.synchronizedSet(HashSet())

    fun open(context: Context, name: String, mimeType: String): ExplorerOutput {
        if (ExplorerFolder.writable(context)) {
            runCatching { openInFolder(context, name, mimeType) }.getOrNull()?.let { return it }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + ExplorerPortal.DOWNLOAD_FOLDER)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(ExplorerText.saveFailed)
            remember(context, uri.toString())
            val raw = resolver.openOutputStream(uri) ?: run {
                resolver.delete(uri, null, null)
                forget(context, uri.toString())
                throw IllegalStateException(ExplorerText.saveFailed)
            }
            return contentOutput(context, uri, raw, name, media = true)
        }
        val target = uniqueFile(legacyFolder(context), name)
        remember(context, Uri.fromFile(target).toString())
        return fileOutput(context, target, FileOutputStream(target))
    }

    private fun openInFolder(context: Context, name: String, mimeType: String): ExplorerOutput? {
        val tree = ExplorerFolder.read(context) ?: return null
        val dir = DocumentFile.fromTreeUri(context, tree) ?: return null
        val doc = dir.createFile(mimeType, name) ?: return null
        val uri = doc.uri
        remember(context, uri.toString())
        val raw = context.contentResolver.openOutputStream(uri, "w") ?: run {
            runCatching { doc.delete() }
            forget(context, uri.toString())
            return null
        }
        return contentOutput(context, uri, raw, name, media = false)
    }

    fun reopen(context: Context, handle: String, at: Long): ExplorerOutput? = runCatching {
        val uri = Uri.parse(handle)
        if (uri.scheme == "file") {
            val file = File(uri.path ?: return null)
            if (!file.isFile || file.length() < at) return null
            RandomAccessFile(file, "rw").use { it.setLength(at) }
            live += handle
            return fileOutput(context, file, FileOutputStream(file, true))
        }
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return null
        val stream = FileOutputStream(pfd.fileDescriptor)
        val channel = stream.channel
        if (channel.size() < at) {
            stream.close()
            pfd.close()
            return null
        }
        channel.truncate(at)
        channel.position(at)
        live += handle
        val closing = object : java.io.FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
            override fun close() {
                runCatching { super.close() }
                runCatching { pfd.close() }
            }
        }
        val name = displayName(context, uri) ?: uri.lastPathSegment.orEmpty()
        contentOutput(context, uri, closing, name, media = uri.authority == MediaStore.AUTHORITY)
    }.getOrNull()

    fun discard(context: Context, handle: String) {
        val uri = Uri.parse(handle)
        runCatching {
            if (uri.scheme == "file") File(uri.path.orEmpty()).delete() else context.contentResolver.delete(uri, null, null)
        }.onFailure {
            runCatching { DocumentFile.fromSingleUri(context, uri)?.delete() }
        }
        forget(context, handle)
    }

    private fun contentOutput(context: Context, uri: Uri, raw: OutputStream, name: String, media: Boolean): ExplorerOutput {
        val resolver = context.contentResolver
        val stream = BufferedOutputStream(raw, 256 * 1024)
        return ExplorerOutput(
            stream,
            onFinish = {
                stream.close()
                if (media) {
                    val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                    resolver.update(uri, done, null, null)
                }
                forget(context, uri.toString())
                val stored = displayName(context, uri) ?: name
                (if (media) location(stored) else folderLocation(context, stored)) to uri.toString()
            },
            onAbort = {
                runCatching { stream.close() }
                discard(context, uri.toString())
            },
            handle = uri.toString(),
        )
    }

    private fun fileOutput(context: Context, target: File, raw: OutputStream): ExplorerOutput {
        val stream = BufferedOutputStream(raw, 256 * 1024)
        val handle = Uri.fromFile(target).toString()
        return ExplorerOutput(
            stream,
            onFinish = {
                stream.close()
                forget(context, handle)
                target.absolutePath to null
            },
            onAbort = {
                runCatching { stream.close() }
                target.delete()
                forget(context, handle)
            },
            handle = handle,
        )
    }

    private fun legacyFolder(context: Context): File {
        val legacy = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val folder = if (legacy) {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), ExplorerPortal.DOWNLOAD_FOLDER)
        } else {
            File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")
        }
        folder.mkdirs()
        return folder
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun folder(): String = "Downloads/${ExplorerPortal.DOWNLOAD_FOLDER}/"

    fun location(name: String): String = folder() + name

    fun location(context: Context, name: String): String =
        if (ExplorerFolder.writable(context)) folderLocation(context, name) else location(name)

    private fun folderLocation(context: Context, name: String): String =
        ExplorerFolder.label(context)?.let { "$it/$name" } ?: name

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

    fun sweep(context: Context, keep: Set<String> = emptySet()): Int {
        val stale = synchronized(this) {
            prefs(context).getStringSet(KEY, emptySet()).orEmpty().filterNot { it in live || it in keep }
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
