package com.fieldrepository.app.explorer.data

import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExplorerZipNames {
    private val used = HashSet<String>()

    fun claim(path: String): String {
        val clean = clean(path)
        if (used.add(clean.lowercase())) return clean
        val slash = clean.lastIndexOf('/')
        val dir = if (slash >= 0) clean.substring(0, slash + 1) else ""
        val leaf = clean.substring(slash + 1)
        val dot = leaf.lastIndexOf('.').takeIf { it > 0 } ?: leaf.length
        val stem = leaf.substring(0, dot)
        val ext = leaf.substring(dot)
        var n = 2
        while (true) {
            val candidate = "$dir${stem}_$n$ext"
            if (used.add(candidate.lowercase())) return candidate
            n++
        }
    }

    companion object {
        fun clean(path: String): String {
            val parts = path.replace('\\', '/').split('/')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != "." && it != ".." }
                .map { it.replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "-") }
            return parts.joinToString("/").ifEmpty { "file" }
        }

        fun prefixed(prefix: String?, path: String): String =
            if (prefix.isNullOrBlank()) path else "${clean(prefix)}/$path"

        fun withExtension(path: String, extension: String): String {
            val slash = path.lastIndexOf('/')
            val leaf = path.substring(slash + 1)
            val dot = leaf.lastIndexOf('.')
            val stem = if (dot > 0) leaf.substring(0, dot) else leaf
            return path.substring(0, slash + 1) + stem + extension
        }

        fun extensionOf(name: String?): String? {
            val leaf = name?.substringAfterLast('/') ?: return null
            val dot = leaf.lastIndexOf('.')
            if (dot <= 0 || dot == leaf.lastIndex) return null
            val ext = leaf.substring(dot)
            return ext.takeIf { it.length <= 9 && it.drop(1).all { c -> c.isLetterOrDigit() } }?.lowercase()
        }
    }
}

class ExplorerZipWriter(sink: OutputStream) : AutoCloseable {
    private val zip = ZipOutputStream(sink)
    private val names = ExplorerZipNames()
    var entries: Int = 0
        private set

    fun text(path: String, body: String): String {
        val name = names.claim(path)
        zip.setMethod(ZipOutputStream.DEFLATED)
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        entries++
        return name
    }

    fun file(path: String, source: File, compress: Boolean): String {
        val name = names.claim(path)
        val entry = ZipEntry(name)
        if (compress) {
            zip.setMethod(ZipOutputStream.DEFLATED)
        } else {
            entry.method = ZipEntry.STORED
            entry.size = source.length()
            entry.compressedSize = source.length()
            entry.crc = crcOf(source)
        }
        zip.putNextEntry(entry)
        source.inputStream().use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
        entries++
        return name
    }

    override fun close() {
        zip.close()
    }

    companion object {
        fun crcOf(file: File): Long {
            val crc = CRC32()
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    crc.update(buffer, 0, read)
                }
            }
            return crc.value
        }

        fun compressible(kind: ExplorerKind): Boolean =
            kind == ExplorerKind.TEXT || kind == ExplorerKind.TRANSCRIPTS || kind == ExplorerKind.DOCUMENTS || kind == ExplorerKind.OTHER
    }
}
