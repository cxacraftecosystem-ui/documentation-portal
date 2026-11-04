package com.fieldrepository.app.explorer.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class ExplorerCached(val key: String = "", val fetchedAt: Long = 0, val tree: ExplorerTree = ExplorerTree())

class ExplorerCache(private val root: File, private val owner: String?, private val json: Json = ExplorerJson) {
    private val lock = Any()

    fun fileFor(key: String): File = File(File(root, segment(owner)), "${hash(key)}.json")

    fun read(key: String): ExplorerCached? = synchronized(lock) {
        val file = fileFor(key)
        if (!file.isFile) return@synchronized null
        runCatching { json.decodeFromString(ExplorerCached.serializer(), file.readText()) }
            .getOrNull()
            ?.takeIf { it.key == key }
    }

    fun write(key: String, tree: ExplorerTree, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        runCatching {
            val file = fileFor(key)
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".writing")
            temp.writeText(json.encodeToString(ExplorerCached.serializer(), ExplorerCached(key, now, tree)))
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
        Unit
    }

    fun clear() = synchronized(lock) { runCatching { File(root, segment(owner)).deleteRecursively() }; Unit }

    companion object {
        fun key(path: String, status: String?): String = "tree|${status.orEmpty()}|$path"

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun segment(owner: String?): String = if (owner.isNullOrBlank()) "signed-out" else hash(owner).take(24)
    }
}
