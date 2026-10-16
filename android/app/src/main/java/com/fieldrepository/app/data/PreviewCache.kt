package com.fieldrepository.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Small previews of saved photos and video frames, kept on the device so a record still shows its
 * pictures without a connection.
 *
 * WHY THIS EXISTS. A saved file is fetched through a signed link that stops working after a while,
 * so Coil's own cache cannot be relied on offline: the link it was keyed by is dead, and an image it
 * never saw cannot be fetched at all. Whenever a record is looked at online, the preview tile that
 * loaded (already drawn at tile size, so a few tens of kilobytes) is written here, keyed by the media
 * file's id, which never changes. Offline, the tile shows it instead of an empty square.
 *
 * BOUNDED AND PRIVATE. At most [MAX_BYTES] in all, least recently shown first out. One folder per
 * account, and every folder is deleted at sign-out (`FieldRepository.logout`), so the next person on
 * a shared phone sees none of the previous one's photographs.
 */
object PreviewCache {
    const val MAX_BYTES: Long = 150L * 1024 * 1024

    @Volatile
    private var root: File? = null

    @Volatile
    private var accountOf: () -> String? = { null }

    /** Called once, when the activity builds the repository. */
    fun install(root: File, account: () -> String?) {
        this.root = root
        this.accountOf = account
    }

    /** The cached preview of [mediaId] for the signed-in account, or null. Marks it as just used. */
    fun find(mediaId: String): File? {
        val base = root ?: return null
        val account = accountOf() ?: return null
        return PreviewFiles.find(base, account, mediaId)
    }

    /** Keep [bytes] (an encoded image) as [mediaId]'s preview, then trim to the size cap. */
    fun store(mediaId: String, bytes: ByteArray) {
        val base = root ?: return
        val account = accountOf() ?: return
        PreviewFiles.store(base, account, mediaId, bytes, MAX_BYTES)
    }

    /** Delete every account's previews, off the main thread. Safe before [install]. */
    fun clear(): Job? {
        val base = root ?: return null
        return AppScope.io.launch(Dispatchers.IO) { runCatching { PreviewFiles.clear(base) } }
    }
}

/** The files themselves, against a root directory, so a JVM test can drive them. */
internal object PreviewFiles {
    private const val EXTENSION = ".img"

    private fun safe(part: String): String = part.replace(Regex("[^A-Za-z0-9_-]"), "_").take(80).ifBlank { "_" }

    fun accountDir(root: File, account: String): File = File(root, safe(account))

    fun file(root: File, account: String, mediaId: String): File = File(accountDir(root, account), safe(mediaId) + EXTENSION)

    fun find(root: File, account: String, mediaId: String): File? {
        val target = file(root, account, mediaId)
        if (!target.isFile || target.length() == 0L) return null
        target.setLastModified(System.currentTimeMillis())
        return target
    }

    @Synchronized
    fun store(root: File, account: String, mediaId: String, bytes: ByteArray, maxBytes: Long) {
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        writeFileAtomically(file(root, account, mediaId), bytes)
        trim(root, maxBytes)
    }

    /** Delete least-recently-used previews, across every account folder, until the total fits. */
    @Synchronized
    fun trim(root: File, maxBytes: Long) {
        val files = root.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(EXTENSION) } }
        val entries = files.map { PreviewEntry(it.path, it.length(), it.lastModified()) }
        val doomed = evictionOrder(entries, maxBytes).toSet()
        files.filter { it.path in doomed }.forEach { it.delete() }
    }

    @Synchronized
    fun clear(root: File) {
        root.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    /** Which entries go, oldest use first, so that what is left is at most [maxBytes]. */
    fun evictionOrder(entries: List<PreviewEntry>, maxBytes: Long): List<String> {
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val out = ArrayList<String>()
        for (entry in entries.sortedWith(compareBy({ it.lastUsed }, { it.path }))) {
            if (total <= maxBytes) break
            out += entry.path
            total -= entry.bytes
        }
        return out
    }
}

internal data class PreviewEntry(val path: String, val bytes: Long, val lastUsed: Long)
