package com.fieldrepository.app.explorer.data

import java.util.Locale

enum class ExplorerKind(val token: String, val label: String) {
    IMAGES("images", "Images"),
    VIDEOS("videos", "Videos"),
    AUDIOS("audios", "Audio"),
    DOCUMENTS("documents", "Documents"),
    TRANSCRIPTS("transcripts", "Transcripts"),
    TEXT("text", "Text"),
    OTHER("other", "Other");

    companion object {
        fun ofToken(token: String?): ExplorerKind = entries.firstOrNull { it.token == token } ?: OTHER

        fun of(file: ExplorerManifestFile): ExplorerKind = when {
            file.typeToken != null -> ofToken(file.typeToken)
            file.mediaType == "TRANSCRIPT" -> TRANSCRIPTS
            file.content != null && file.mediaId != null -> TRANSCRIPTS
            file.content != null -> TEXT
            else -> ofMediaType(file.mediaType)
        }

        fun ofMediaType(mediaType: String?): ExplorerKind = when (mediaType?.uppercase(Locale.ROOT)) {
            "IMAGE" -> IMAGES
            "VIDEO" -> VIDEOS
            "AUDIO" -> AUDIOS
            "PDF", "DOCUMENT" -> DOCUMENTS
            "TRANSCRIPT" -> TRANSCRIPTS
            else -> OTHER
        }
    }
}

data class ExplorerSize(
    val files: Int = 0,
    val bytes: Long = 0,
    val unknown: Int = 0,
    val byKind: Map<ExplorerKind, ExplorerTypeStats> = emptyMap(),
    val largest: Long = 0,
    val truncated: Boolean = false,
) {
    companion object {
        fun of(stats: ExplorerStats): ExplorerSize = ExplorerSize(
            files = stats.files,
            bytes = stats.bytes,
            unknown = stats.unknownSizeFiles,
            byKind = stats.byType.entries.associate { ExplorerKind.ofToken(it.key) to it.value },
            largest = stats.byType.values.maxOfOrNull { if (it.files > 0) it.bytes / it.files else 0 } ?: 0,
            truncated = stats.truncated,
        )

        fun of(files: Sequence<ExplorerManifestFile>, truncated: Boolean = false): ExplorerSize {
            val kinds = HashMap<ExplorerKind, ExplorerTypeStats>()
            var count = 0
            var bytes = 0L
            var unknown = 0
            var largest = 0L
            for (f in files) {
                count++
                val size = f.sizeBytes ?: f.content?.toByteArray(Charsets.UTF_8)?.size?.toLong()
                if (size == null) unknown++ else bytes += size
                if (size != null && size > largest) largest = size
                val kind = ExplorerKind.of(f)
                val was = kinds[kind] ?: ExplorerTypeStats()
                kinds[kind] = ExplorerTypeStats(was.files + 1, was.bytes + (size ?: 0))
            }
            return ExplorerSize(count, bytes, unknown, kinds, largest, truncated)
        }
    }
}

object ExplorerBytes {
    fun format(size: Long?): String? {
        if (size == null || size < 0) return null
        if (size < 1024) return "$size B"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = size.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    fun count(n: Int, one: String, many: String = one + "s"): String =
        "${String.format(Locale.ROOT, "%,d", n)} ${if (n == 1) one else many}"

    fun line(files: Int, bytes: Long, unknown: Int = 0): String {
        val head = count(files, "file")
        if (files == 0 || unknown >= files) return head
        val size = format(bytes) ?: return head
        return "$head · ${if (unknown > 0) "at least " else ""}$size"
    }

    fun line(size: ExplorerSize): String = line(size.files, size.bytes, size.unknown)

    fun progress(done: Long, total: Long): String {
        val d = format(done) ?: "0 B"
        val t = format(total)
        return if (t == null || total <= 0) d else "$d of $t"
    }

    fun remaining(doneBytes: Long, totalBytes: Long, elapsedMs: Long): String? {
        if (doneBytes <= 0 || totalBytes <= doneBytes || elapsedMs < 3_000) return null
        val rate = doneBytes.toDouble() / elapsedMs
        val leftMs = ((totalBytes - doneBytes) / rate).toLong()
        val minutes = leftMs / 60_000
        return when {
            minutes >= 120 -> "about ${minutes / 60} h left"
            minutes >= 1 -> "about $minutes min left"
            else -> "under a minute left"
        }
    }

    fun spaceNeeded(total: Long, largest: Long, parallel: Int = 1): Long =
        total + largest + (parallel - 1).coerceAtLeast(0) * minOf(largest, ExplorerEngine.BIG_BYTES) + SPACE_MARGIN

    const val SPACE_MARGIN: Long = 64L * 1024L * 1024L
    const val METERED_WARN_BYTES: Long = 50L * 1024L * 1024L
}
