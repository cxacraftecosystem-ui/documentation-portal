package com.fieldrepository.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

object PreviewCache {
    const val MAX_BYTES: Long = 150L * 1024 * 1024

    @Volatile
    private var root: File? = null

    @Volatile
    private var accountOf: () -> String? = { null }

    data class Ticket(val account: String, val generation: Long)

    fun install(root: File, account: () -> String?) {
        this.root = root
        this.accountOf = account
    }

    fun find(mediaId: String): File? {
        val base = root ?: return null
        val account = accountOf() ?: return null
        return PreviewFiles.find(base, account, mediaId)
    }

    fun ticket(): Ticket? {
        if (root == null) return null
        val account = accountOf() ?: return null
        return Ticket(account, PreviewFiles.generation())
    }

    fun store(ticket: Ticket, mediaId: String, bytes: ByteArray) {
        val base = root ?: return
        PreviewFiles.store(base, ticket.account, mediaId, bytes, MAX_BYTES, ticket.generation)
    }

    fun clear(): Job? {
        PreviewFiles.invalidate()
        val base = root ?: return null
        return AppScope.io.launch(Dispatchers.IO) { runCatching { PreviewFiles.clear(base) } }
    }
}

internal object PreviewFiles {
    private const val EXTENSION = ".img"

    @Volatile
    private var generation = 0L

    private val totals = HashMap<String, Long>()

    fun generation(): Long = generation

    @Synchronized
    fun invalidate() {
        generation++
    }

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
    fun store(
        root: File,
        account: String,
        mediaId: String,
        bytes: ByteArray,
        maxBytes: Long,
        ticketGeneration: Long = generation
    ) {
        if (ticketGeneration != generation) return
        if (bytes.isEmpty() || bytes.size > maxBytes) return
        val key = root.absolutePath
        val target = file(root, account, mediaId)
        val before = totals[key] ?: scanTotal(root)
        val replaced = if (target.isFile) target.length() else 0L
        writeFileAtomically(target, bytes)
        val after = before - replaced + bytes.size
        totals[key] = after
        if (after > maxBytes) trim(root, maxBytes)
    }

    @Synchronized
    fun trim(root: File, maxBytes: Long) {
        val files = previewFiles(root)
        val entries = files.map { PreviewEntry(it.path, it.length(), it.lastModified()) }
        val doomed = evictionOrder(entries, maxBytes).toSet()
        files.filter { it.path in doomed }.forEach { it.delete() }
        totals[root.absolutePath] = entries.filter { it.path !in doomed }.sumOf { it.bytes }
    }

    @Synchronized
    fun clear(root: File) {
        root.listFiles().orEmpty().forEach { it.deleteRecursively() }
        totals[root.absolutePath] = 0L
    }

    private fun previewFiles(root: File): List<File> =
        root.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(EXTENSION) } }

    private fun scanTotal(root: File): Long = previewFiles(root).sumOf { it.length() }

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
