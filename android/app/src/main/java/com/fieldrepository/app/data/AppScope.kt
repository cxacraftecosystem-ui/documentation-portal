package com.fieldrepository.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

data class StagedMedia(
    val objectKey: String,
    val bucket: String,
    val publicUrl: String?,
    val mimeType: String,
    val mediaType: String,
    val sizeBytes: Long,
    val extension: String?
)
