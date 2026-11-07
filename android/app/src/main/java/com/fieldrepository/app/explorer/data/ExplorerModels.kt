package com.fieldrepository.app.explorer.data

import com.fieldrepository.app.transcript.data.DataTranscriptFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

val ExplorerJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
    coerceInputValues = true
    encodeDefaults = true
}

@Serializable
data class ExplorerCrumb(val name: String = "", val path: String = "")

@Serializable
data class ExplorerInfoField(val label: String = "", val value: String = "")

@Serializable
data class ExplorerCounts(val tools: Int? = null)

@Serializable
data class ExplorerInfo(val title: String = "", val fields: List<ExplorerInfoField> = emptyList(), val counts: ExplorerCounts? = null)

@Serializable
data class ExplorerTaxonomy(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val description: String = "",
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class ExplorerPixels(val width: Int = 0, val height: Int = 0)

@Serializable
data class ExplorerEntry(
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val recordType: String? = null,
    val mediaType: String? = null,
    val mediaId: String? = null,
    val originalFilename: String? = null,
    val url: String? = null,
    val sizeBytes: Long? = null,
    val transcriptAvailable: Boolean = false,
    val content: String? = null,
    val transcriptPath: String? = null,
    val thumbnailPath: String? = null,
    val previewPath: String? = null,
    val downloadPath: String? = null,
    val transcriptFiles: List<DataTranscriptFile> = emptyList(),
    val capturedAt: String? = null,
    val count: Int? = null,
    val bytes: Long? = null,
    val pixels: ExplorerPixels? = null,
    val pages: Int? = null,
) {
    val isFolder: Boolean get() = kind == "folder"
    val key: String get() = if (isFolder) "folder:$path" else "file:${mediaId ?: path}"
    val isTranscriptFile: Boolean get() = !isFolder && mediaType == null && transcriptPath != null
}

@Serializable
data class ExplorerTree(
    val path: String = "",
    val crumbs: List<ExplorerCrumb> = emptyList(),
    val entries: List<ExplorerEntry> = emptyList(),
    val info: ExplorerInfo? = null,
    val truncated: Boolean = false,
    val nextCursor: String? = null,
    val total: Int? = null,
    val taxonomies: List<ExplorerTaxonomy> = emptyList(),
    val taxonomy: String? = null,
    val sort: ExplorerSortApplied? = null,
    val sorts: List<String> = emptyList(),
)

@Serializable
data class ExplorerTranscript(
    val mediaId: String = "",
    val name: String = "",
    val content: String = "",
    val language: String? = null,
)

@Serializable
data class ExplorerLanguage(val code: String = "", val label: String = "", val count: Int = 0)

@Serializable
data class ExplorerLanguages(val languages: List<ExplorerLanguage> = emptyList())

@Serializable
data class ExplorerTypeStats(val files: Int = 0, val bytes: Long = 0)

@Serializable
data class ExplorerStats(
    val files: Int = 0,
    val bytes: Long = 0,
    val unknownSizeFiles: Int = 0,
    val byType: Map<String, ExplorerTypeStats> = emptyMap(),
    val truncated: Boolean = false,
)

@Serializable
data class ExplorerSelectionBody(
    val paths: List<String>,
    val mediaIds: List<String>,
    val include: List<String>?,
    val stream: Boolean,
)

@Serializable
data class ExplorerStatsBody(
    val paths: List<String>,
    val mediaIds: List<String>,
    val include: List<String>?,
)

@Serializable
data class ExplorerManifestFile(
    val path: String = "",
    val url: String? = null,
    val content: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null,
    val sizeBytes: Long? = null,
    val typeToken: String? = null,
    val convertToMp4: Boolean = false,
    val originalPath: String? = null,
    val originalFilename: String? = null,
    val sha256: String? = null,
    val capturedAt: String? = null,
)

@Serializable
data class ExplorerManifest(
    val files: List<ExplorerManifestFile> = emptyList(),
    val totalFiles: Int = 0,
    val totalMedia: Int = 0,
    val totalBytes: Long? = null,
    val truncated: Boolean = false,
    val skippedMedia: Int = 0,
)

@Serializable
data class ExplorerSheet(
    val name: String = "",
    val columns: List<JsonElement> = emptyList(),
    val rows: List<List<JsonElement>> = emptyList(),
    val totalRows: Int? = null,
    val matchedRows: Int? = null,
    val truncated: Boolean = false,
    val rowNumbers: List<Int>? = null,
    val preview: Boolean = false,
    val offset: Int = 0,
) {
    val headers: List<String> get() = columns.map { ExplorerCells.text(it) }
}

@Serializable
data class ExplorerReport(val sheets: List<ExplorerSheet> = emptyList(), val query: String? = null)

object ExplorerCells {
    fun text(cell: JsonElement?): String = when (cell) {
        null, JsonNull -> ""
        is JsonPrimitive -> cell.content
        is JsonArray -> cell.joinToString(", ") { text(it) }
        is JsonObject -> cell.values.joinToString(" ") { text(it) }.trim()
    }
}
