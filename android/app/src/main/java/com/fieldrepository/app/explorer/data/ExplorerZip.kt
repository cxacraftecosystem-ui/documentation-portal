package com.fieldrepository.app.explorer.data

import kotlinx.serialization.Serializable
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Calendar
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

class ExplorerZipNames {
    private val used = HashSet<String>()

    fun reserve(name: String) {
        used.add(name.lowercase())
    }

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

@Serializable
data class ExplorerZipRecord(
    val name: String,
    val source: String,
    val method: Int,
    val crc: Long,
    val compressed: Long,
    val size: Long,
    val offset: Long,
    val time: Int,
    val date: Int,
    val tag: String? = null,
)

class ExplorerZipWriter(
    sink: OutputStream,
    start: Long = 0,
    restored: List<ExplorerZipRecord> = emptyList(),
    private val scratch: File? = null,
    private val onEntry: (record: ExplorerZipRecord, committed: Long) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val target = sink
    private val out = BufferedOutputStream(sink, 256 * 1024)
    private val names = ExplorerZipNames().apply { restored.forEach { reserve(it.name) } }
    private val records = ArrayList(restored)
    private var position = start
    private var closed = false

    val entries: Int get() = records.size
    val written: Long get() = position
    val done: List<ExplorerZipRecord> get() = records

    fun text(path: String, body: String, source: String = path, tag: String? = null): String {
        val name = names.claim(path)
        val raw = body.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(raw) }.value
        val packed = ByteArrayOutputStream()
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            DeflaterOutputStream(packed, deflater).use { it.write(raw) }
        } finally {
            deflater.end()
        }
        val bytes = packed.toByteArray()
        put(name, source, DEFLATED, crc, bytes.size.toLong(), raw.size.toLong(), tag) { it.write(bytes) }
        return name
    }

    fun file(path: String, source: File, compress: Boolean, origin: String = path, tag: String? = null): String {
        val name = names.claim(path)
        val crc = crcOf(source)
        val size = source.length()
        if (!compress) {
            put(name, origin, STORED, crc, size, size, tag) { sink -> source.inputStream().use { it.copyTo(sink, 64 * 1024) } }
            return name
        }
        val packed = File(scratch ?: source.absoluteFile.parentFile, source.name + ".deflate")
        try {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                DeflaterOutputStream(FileOutputStream(packed), deflater, 64 * 1024).use { o -> source.inputStream().use { it.copyTo(o, 64 * 1024) } }
            } finally {
                deflater.end()
            }
            val compressed = packed.length()
            if (compressed >= size) {
                put(name, origin, STORED, crc, size, size, tag) { sink -> source.inputStream().use { it.copyTo(sink, 64 * 1024) } }
            } else {
                put(name, origin, DEFLATED, crc, compressed, size, tag) { sink -> packed.inputStream().use { it.copyTo(sink, 64 * 1024) } }
            }
        } finally {
            packed.delete()
        }
        return name
    }

    private fun put(name: String, source: String, method: Int, crc: Long, compressed: Long, size: Long, tag: String?, data: (OutputStream) -> Unit) {
        val (time, date) = dosNow()
        val offset = position
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val big = size >= MAX32 || compressed >= MAX32
        val header = Bytes()
        header.int(LOCAL_SIG)
        header.short(if (big) ZIP64_VERSION else VERSION)
        header.short(UTF8_FLAG)
        header.short(method)
        header.short(time)
        header.short(date)
        header.int(crc)
        header.int(if (big) MAX32 else compressed)
        header.int(if (big) MAX32 else size)
        header.short(nameBytes.size)
        header.short(if (big) 20 else 0)
        header.raw(nameBytes)
        if (big) {
            header.short(ZIP64_EXTRA)
            header.short(16)
            header.long(size)
            header.long(compressed)
        }
        write(header.bytes())
        val counter = Counting(out)
        data(counter)
        position += counter.count
        if (counter.count != compressed) throw IOException("zip entry $name wrote ${counter.count} of $compressed bytes")
        out.flush()
        target.flush()
        val record = ExplorerZipRecord(name, source, method, crc, compressed, size, offset, time, date, tag)
        records += record
        onEntry(record, position)
    }

    private fun write(bytes: ByteArray) {
        out.write(bytes)
        position += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        val start = position
        for (r in records) write(central(r))
        val size = position - start
        val count = records.size.toLong()
        val zip64 = count >= 0xFFFF || start >= MAX32 || size >= MAX32 || records.any { it.size >= MAX32 || it.compressed >= MAX32 || it.offset >= MAX32 }
        if (zip64) {
            val at = position
            val record = Bytes()
            record.int(ZIP64_END_SIG)
            record.long(44)
            record.short(ZIP64_VERSION)
            record.short(ZIP64_VERSION)
            record.int(0)
            record.int(0)
            record.long(count)
            record.long(count)
            record.long(size)
            record.long(start)
            write(record.bytes())
            val locator = Bytes()
            locator.int(ZIP64_LOCATOR_SIG)
            locator.int(0)
            locator.long(at)
            locator.int(1)
            write(locator.bytes())
        }
        val end = Bytes()
        end.int(END_SIG)
        end.short(0)
        end.short(0)
        end.short(if (count >= 0xFFFF) 0xFFFF else count.toInt())
        end.short(if (count >= 0xFFFF) 0xFFFF else count.toInt())
        end.int(if (size >= MAX32) MAX32 else size)
        end.int(if (start >= MAX32) MAX32 else start)
        end.short(0)
        write(end.bytes())
        out.flush()
        out.close()
    }

    fun detach() {
        closed = true
        runCatching { out.flush() }
    }

    private fun central(r: ExplorerZipRecord): ByteArray {
        val nameBytes = r.name.toByteArray(Charsets.UTF_8)
        val extra = Bytes()
        if (r.size >= MAX32) extra.long(r.size)
        if (r.compressed >= MAX32) extra.long(r.compressed)
        if (r.offset >= MAX32) extra.long(r.offset)
        val fields = extra.bytes()
        val big = fields.isNotEmpty()
        val b = Bytes()
        b.int(CENTRAL_SIG)
        b.short(ZIP64_VERSION)
        b.short(if (big) ZIP64_VERSION else VERSION)
        b.short(UTF8_FLAG)
        b.short(r.method)
        b.short(r.time)
        b.short(r.date)
        b.int(r.crc)
        b.int(if (r.compressed >= MAX32) MAX32 else r.compressed)
        b.int(if (r.size >= MAX32) MAX32 else r.size)
        b.short(nameBytes.size)
        b.short(if (big) fields.size + 4 else 0)
        b.short(0)
        b.short(0)
        b.short(0)
        b.int(0)
        b.int(if (r.offset >= MAX32) MAX32 else r.offset)
        b.raw(nameBytes)
        if (big) {
            b.short(ZIP64_EXTRA)
            b.short(fields.size)
            b.raw(fields)
        }
        return b.bytes()
    }

    private class Counting(private val sink: OutputStream) : OutputStream() {
        var count = 0L

        override fun write(b: Int) {
            sink.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            sink.write(b, off, len)
            count += len
        }
    }

    private class Bytes {
        private val buffer = ByteArrayOutputStream()

        fun short(v: Int) {
            buffer.write(v and 0xFF)
            buffer.write((v ushr 8) and 0xFF)
        }

        fun int(v: Long) {
            for (i in 0 until 4) buffer.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun long(v: Long) {
            for (i in 0 until 8) buffer.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun raw(b: ByteArray) = buffer.write(b)

        fun bytes(): ByteArray = buffer.toByteArray()
    }

    companion object {
        const val STORED = 0
        const val DEFLATED = 8
        const val MAX32 = 0xFFFFFFFFL
        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val END_SIG = 0x06054b50L
        private const val ZIP64_END_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val ZIP64_EXTRA = 0x0001
        private const val VERSION = 20
        private const val ZIP64_VERSION = 45
        private const val UTF8_FLAG = 0x0800

        fun dosNow(now: Calendar = Calendar.getInstance()): Pair<Int, Int> {
            val year = (now.get(Calendar.YEAR) - 1980).coerceIn(0, 127)
            val date = (year shl 9) or ((now.get(Calendar.MONTH) + 1) shl 5) or now.get(Calendar.DAY_OF_MONTH)
            val time = (now.get(Calendar.HOUR_OF_DAY) shl 11) or (now.get(Calendar.MINUTE) shl 5) or (now.get(Calendar.SECOND) / 2)
            return time to date
        }

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
