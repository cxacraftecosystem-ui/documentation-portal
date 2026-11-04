package com.fieldrepository.app.explorer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ExplorerJobKind { FOLDER, SELECTION, FILE, REPORT, TRANSCRIPTS }

@Serializable
data class ExplorerSource(val path: String, val include: String? = null, val prefix: String? = null)

@Serializable
data class ExplorerItem(
    val path: String,
    val url: String? = null,
    val content: String? = null,
    val mediaId: String? = null,
    val mediaType: String? = null,
    val convertToMp4: Boolean = false,
    val originalPath: String? = null,
    val originalFilename: String? = null,
    val sizeBytes: Long? = null,
)

@Serializable
data class ExplorerRequest(
    val title: String,
    val fileName: String,
    val kind: ExplorerJobKind = ExplorerJobKind.FOLDER,
    val sources: List<ExplorerSource> = emptyList(),
    val selection: ExplorerSelectionBody? = null,
    val items: List<ExplorerItem> = emptyList(),
    val wifiOnly: Boolean = false,
    val single: Boolean = false,
    val expectedBytes: Long? = null,
    val source: String? = null,
    val mimeType: String = if (single) "application/octet-stream" else "application/zip",
)

@Serializable
enum class ExplorerJobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

@Serializable
data class ExplorerResult(
    val location: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val saved: Int = 0,
    val total: Int = 0,
    val failed: List<String> = emptyList(),
    val failedCount: Int = failed.size,
    val truncated: Boolean = false,
    val message: String? = null,
)

@Serializable
data class ExplorerJob(
    val id: String,
    val request: ExplorerRequest,
    val createdAt: Long,
    val state: ExplorerJobState = ExplorerJobState.QUEUED,
    val systemId: Long? = null,
    val result: ExplorerResult? = null,
    val error: String? = null,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val totalFiles: Int = 0,
    val current: String? = null,
    val busySeconds: Int? = null,
    val startedAt: Long? = null,
) {
    val active: Boolean get() = state == ExplorerJobState.QUEUED || state == ExplorerJobState.RUNNING
    val fraction: Float?
        get() = when {
            totalBytes > 0 -> (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            totalFiles > 0 -> (doneFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
            else -> null
        }
}

class ExplorerJobStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false; coerceInputValues = true }
    private val lock = Any()
    private val state = MutableStateFlow(load())

    val jobs: StateFlow<List<ExplorerJob>> = state.asStateFlow()

    fun get(id: String): ExplorerJob? = state.value.firstOrNull { it.id == id }

    fun put(job: ExplorerJob) = update { list -> listOf(job) + list.filterNot { it.id == job.id } }

    fun change(id: String, transform: (ExplorerJob) -> ExplorerJob) =
        update { list -> list.map { if (it.id == id) transform(it) else it } }

    fun progress(id: String, transform: (ExplorerJob) -> ExplorerJob) {
        synchronized(lock) { state.value = state.value.map { if (it.id == id) transform(it) else it } }
    }

    fun remove(id: String) = update { list -> list.filterNot { it.id == id } }

    fun clearFinished() = update { list -> list.filter { it.active } }

    fun clear() = update { emptyList() }

    fun flush() = update { it }

    private fun update(transform: (List<ExplorerJob>) -> List<ExplorerJob>) {
        synchronized(lock) {
            val next = trim(transform(state.value))
            state.value = next
            runCatching {
                file.parentFile?.mkdirs()
                val temp = File(file.parentFile, file.name + ".writing")
                temp.writeText(json.encodeToString(ListSerializer(ExplorerJob.serializer()), next))
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }
        }
    }

    private fun load(): List<ExplorerJob> = runCatching {
        if (!file.isFile) emptyList() else json.decodeFromString(ListSerializer(ExplorerJob.serializer()), file.readText())
    }.getOrDefault(emptyList())

    companion object {
        const val KEEP = 30
        const val FAILED_NAMES = 200

        fun trim(list: List<ExplorerJob>): List<ExplorerJob> {
            val active = list.filter { it.active }.map { it.id }.toSet()
            val finished = list.filterNot { it.active }.take((KEEP - active.size).coerceAtLeast(0)).map { it.id }.toSet()
            return list.filter { it.id in active || it.id in finished }
        }
    }
}
