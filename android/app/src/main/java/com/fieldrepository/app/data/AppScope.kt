package com.fieldrepository.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A process-lifetime scope for background work that must outlive a Composable (e.g. finishing an
 * in-flight eager upload, or deleting a staged object after the capture screen is dismissed).
 */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

/** A file pre-uploaded to object storage but not yet attached to a saved record. */
data class StagedMedia(
    val objectKey: String,
    val bucket: String,
    val publicUrl: String?,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val extension: String?
)
