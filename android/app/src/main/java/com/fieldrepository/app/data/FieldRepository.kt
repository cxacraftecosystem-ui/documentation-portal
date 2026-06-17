package com.fieldrepository.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FieldRepository(
    private val api: FieldRepositoryApi,
    private val tokenStore: TokenStore
) {
    // Generous timeouts (large videos over slow field connections) + automatic connection retry.
    private val storageClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    fun hasToken(): Boolean = !tokenStore.getToken().isNullOrBlank()

    /** Last known signed-in profile, used for instant, persistent login across resumes. */
    fun cachedUser(): UserDto? = tokenStore.getUser()

    suspend fun login(email: String, password: String): UserDto {
        val response = api.login(LoginRequest(email = email.trim(), password = password))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    suspend fun loginWithGoogle(idToken: String): UserDto {
        val response = api.googleLogin(GoogleLoginRequest(googleIdToken = idToken))
        tokenStore.setToken(response.accessToken)
        tokenStore.setUser(response.user)
        return response.user
    }

    fun logout() {
        tokenStore.clear()
    }

    suspend fun currentUser(): UserDto = api.me()

    /** Refresh the profile from the server and update the local cache. */
    suspend fun refreshUser(): UserDto {
        val user = api.me()
        tokenStore.setUser(user)
        return user
    }

    suspend fun stats(): DashboardStats = api.dashboardStats()

    suspend fun users(): List<UserDto> = api.users(pageSize = 100).items

    suspend fun updateUserQuestionnaireAccess(id: String, canManageQuestionnaire: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageQuestionnaire = canManageQuestionnaire))

    suspend fun updateUserCraftAccess(id: String, canManageCrafts: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageCrafts = canManageCrafts))

    suspend fun updateUserWorkshopAccess(id: String, canManageWorkshops: Boolean): UserDto =
        api.updateUser(id, UserUpdateRequest(canManageWorkshops = canManageWorkshops))

    suspend fun artisans(): List<ArtisanDto> = api.artisans(pageSize = 100).items

    suspend fun crafts(): List<CraftDto> = api.crafts(pageSize = 100).items

    suspend fun products(): List<ProductDetailDto> = api.products(pageSize = 100).items

    suspend fun tools(): List<ToolDetailDto> = api.tools(pageSize = 100).items

    suspend fun workshops(): List<WorkshopDetailDto> = api.workshops(pageSize = 100).items

    suspend fun createArtisan(body: ArtisanCreateRequest): ArtisanDto = api.createArtisan(body)

    suspend fun artisan(id: String): ArtisanDetailDto = api.artisan(id)

    suspend fun updateArtisan(id: String, body: ArtisanCreateRequest): ArtisanDetailDto = api.updateArtisan(id, body)

    suspend fun artisanQuestionnaire(id: String): ArtisanQuestionnaireDto = api.artisanQuestionnaire(id)

    suspend fun media(): List<MediaFileDto> = api.media(pageSize = 20).items

    // Admin-only deletes (backend enforces is_admin; 403 otherwise).
    suspend fun deleteArtisan(id: String) = api.deleteArtisan(id)
    suspend fun deleteCraft(id: String) = api.deleteCraft(id)
    suspend fun deleteProduct(id: String) = api.deleteProduct(id)
    suspend fun deleteTool(id: String) = api.deleteTool(id)
    suspend fun deleteWorkshop(id: String) = api.deleteWorkshop(id)
    suspend fun deleteProcess(id: String) = api.deleteProcess(id)
    suspend fun deleteInterview(id: String) = api.deleteInterview(id)

    /** Result of a full-dataset download: where it was saved and how many files succeeded. */
    data class DatasetDownloadResult(val displayLocation: String, val saved: Int, val total: Int, val failed: Int)

    /**
     * Pull the full dataset manifest, then download every media object straight from S3 and zip the
     * whole directory tree to the device's Downloads folder. [onProgress] reports (done, total) as each
     * entry is written so the UI can show real progress. Individual file failures are skipped, not fatal.
     */
    suspend fun downloadDataset(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit
    ): DatasetDownloadResult = withContext(Dispatchers.IO) {
        val manifest = api.datasetManifest()
        val total = manifest.files.size
        val stamp = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val zipName = "FieldRepository_dataset_$stamp.zip"
        val tmp = File(context.cacheDir, zipName)
        var failed = 0
        ZipOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { zip ->
            manifest.files.forEachIndexed { index, f ->
                runCatching {
                    zip.putNextEntry(ZipEntry(f.path))
                    when {
                        f.content != null -> zip.write(f.content.toByteArray(Charsets.UTF_8))
                        f.url != null -> {
                            val request = Request.Builder().url(f.url).build()
                            storageClient.newCall(request).execute().use { resp ->
                                if (resp.isSuccessful) resp.body?.byteStream()?.copyTo(zip) else throw IllegalStateException("HTTP ${resp.code}")
                            }
                        }
                    }
                    zip.closeEntry()
                }.onFailure {
                    failed++
                    runCatching { zip.closeEntry() }
                }
                onProgress(index + 1, total)
            }
        }
        val location = persistZipToDownloads(context, tmp, zipName)
        tmp.delete()
        DatasetDownloadResult(displayLocation = location, saved = total - failed, total = total, failed = failed)
    }

    /** Copy the built zip into the public Downloads collection (MediaStore on Q+, file path below). */
    private fun persistZipToDownloads(context: Context, source: File, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Could not create the download entry")
            resolver.openOutputStream(uri).use { out -> source.inputStream().use { it.copyTo(out!!) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Downloads/$name"
        }
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        val dest = File(downloads, name)
        source.copyTo(dest, overwrite = true)
        return dest.absolutePath
    }

    /** All media attached to a specific record, used by the View Data screen (with transcripts). */
    suspend fun mediaForRecord(linkedRecordType: String, linkedRecordId: String): List<MediaFileDto> =
        api.media(pageSize = 100, linkedRecordType = linkedRecordType, linkedRecordId = linkedRecordId).items

    suspend fun processes(): List<ProcessDetailDto> = api.processes(pageSize = 100).items

    suspend fun process(id: String): ProcessDetailDto = api.process(id)

    suspend fun createProcess(body: ProcessCreateRequest): ProcessDetailDto = api.createProcess(body)

    suspend fun updateProcess(id: String, body: ProcessCreateRequest): ProcessDetailDto = api.updateProcess(id, body)

    suspend fun createCraft(body: CraftCreateRequest): CreatedRecordDto = api.createCraft(body)

    suspend fun craft(id: String): CraftDto = api.craft(id)

    suspend fun updateCraft(id: String, body: CraftCreateRequest): CraftDto = api.updateCraft(id, body)

    suspend fun createWorkshop(body: WorkshopCreateRequest): CreatedRecordDto = api.createWorkshop(body)

    suspend fun workshop(id: String): WorkshopDetailDto = api.workshop(id)

    suspend fun updateWorkshop(id: String, body: WorkshopCreateRequest): WorkshopDetailDto = api.updateWorkshop(id, body)

    suspend fun createProduct(body: ProductCreateRequest): CreatedRecordDto = api.createProduct(body)

    suspend fun product(id: String): ProductDetailDto = api.product(id)

    suspend fun updateProduct(id: String, body: ProductCreateRequest): ProductDetailDto = api.updateProduct(id, body)

    suspend fun createTool(body: ToolCreateRequest): CreatedRecordDto = api.createTool(body)

    suspend fun tool(id: String): ToolDetailDto = api.tool(id)

    suspend fun updateTool(id: String, body: ToolCreateRequest): ToolDetailDto = api.updateTool(id, body)

    suspend fun questionnaireQuestions(): List<QuestionnaireQuestionDto> = api.questionnaireQuestions()

    suspend fun questionnaireSections(): List<QuestionnaireSectionDto> = api.questionnaireSections()

    suspend fun createQuestionnaireSection(body: QuestionnaireSectionCreateRequest): QuestionnaireSectionDto =
        api.createQuestionnaireSection(body)

    suspend fun updateQuestionnaireSection(id: String, body: QuestionnaireSectionUpdateRequest): QuestionnaireSectionDto =
        api.updateQuestionnaireSection(id, body)

    suspend fun deleteQuestionnaireSection(id: String) {
        api.deleteQuestionnaireSection(id)
    }

    suspend fun reorderQuestionnaireSections(sectionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireSections(QuestionnaireSectionReorderRequest(sectionIds))

    suspend fun createQuestionnaireQuestion(body: QuestionnaireQuestionCreateRequest): QuestionnaireQuestionDto =
        api.createQuestionnaireQuestion(body)

    suspend fun updateQuestionnaireQuestion(id: String, body: QuestionnaireQuestionUpdateRequest): QuestionnaireQuestionDto =
        api.updateQuestionnaireQuestion(id, body)

    suspend fun deleteQuestionnaireQuestion(id: String) {
        api.deleteQuestionnaireQuestion(id)
    }

    suspend fun reorderQuestionnaireQuestions(sectionId: String, questionIds: List<String>): List<QuestionnaireSectionDto> =
        api.reorderQuestionnaireQuestions(QuestionnaireQuestionReorderRequest(sectionId, questionIds))

    suspend fun createQuestionnaireInterview(body: QuestionnaireInterviewCreateRequest): CreatedRecordDto =
        api.createQuestionnaireInterview(body)

    suspend fun interviews(): List<QuestionnaireInterviewDetailDto> = api.interviews(pageSize = 100).items

    suspend fun interview(id: String): QuestionnaireInterviewDetailDto = api.interview(id)

    suspend fun updateQuestionnaireInterview(id: String, body: QuestionnaireInterviewUpdateRequest): QuestionnaireInterviewDetailDto =
        api.updateInterview(id, body)

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String? = null,
        batchIndex: Int = 1,
        processingRequests: List<String>? = null,
        stageStep: Int? = null,
        customSegment: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null
    ): MediaFileDto {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val mediaType = inferMediaType(mimeType)
        val resolvedProcessing = processingRequests
            ?: if (mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = titleHint ?: caption,
            mediaType = mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            originalName = originalName
        )
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open selected media")
        }
        val presign = api.presignMedia(
            MediaPresignRequest(
                filename = filename,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = bytes.size.toLong(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull()
            )
        )
        withContext(Dispatchers.IO) {
            putToStorage(presign.uploadUrl, presign.headers, bytes, mimeType, onProgress)
        }
        return api.completeMedia(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = mediaType,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                objectKey = presign.objectKey,
                bucket = presign.bucket,
                url = presign.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = Instant.now().toString(),
                location = location,
                processingRequests = resolvedProcessing
            )
        )
    }

    /**
     * Eager pre-upload: push the bytes to object storage immediately on capture using a provisional
     * key, so the slow network transfer overlaps the time the user spends filling the form. The
     * human-readable, nomenclature-correct filename is applied later in [completeStaged].
     */
    suspend fun preuploadObject(context: Context, uri: Uri): StagedMedia {
        val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
        val originalName = displayName(context, uri) ?: "field-media-${System.currentTimeMillis()}"
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val mediaType = inferMediaType(mimeType)
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open selected media")
        }
        val provisional = "staged-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}" +
            (extension?.let { ".$it" } ?: "")
        val presign = api.presignMedia(
            MediaPresignRequest(
                filename = provisional,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = bytes.size.toLong()
            )
        )
        withContext(Dispatchers.IO) {
            putToStorage(presign.uploadUrl, presign.headers, bytes, mimeType, null)
        }
        return StagedMedia(
            objectKey = presign.objectKey,
            bucket = presign.bucket,
            publicUrl = presign.publicUrl,
            mimeType = mimeType,
            mediaType = mediaType,
            sizeBytes = bytes.size.toLong(),
            extension = extension
        )
    }

    /** Attach an already-uploaded staged object to a saved record, applying the final filename. */
    suspend fun completeStaged(
        staged: StagedMedia,
        linkedRecordType: String?,
        linkedRecordId: String?,
        recordName: String?,
        caption: String?,
        location: LocationRequest?,
        batchIndex: Int = 1,
        stageStep: Int? = null,
        customSegment: String? = null,
        processingRequests: List<String>? = null
    ): MediaFileDto {
        val filename = mediaFilename(
            recordType = linkedRecordType,
            recordName = recordName ?: caption,
            mediaType = staged.mediaType,
            index = batchIndex,
            stageStep = stageStep,
            customSegment = customSegment,
            originalName = "media" + (staged.extension?.let { ".$it" } ?: "")
        )
        val resolvedProcessing = processingRequests
            ?: if (staged.mediaType == "AUDIO") listOf("TRANSCRIPTION") else emptyList()
        return api.completeMedia(
            MediaCompleteRequest(
                originalFilename = filename,
                mediaType = staged.mediaType,
                mimeType = staged.mimeType,
                sizeBytes = staged.sizeBytes,
                objectKey = staged.objectKey,
                bucket = staged.bucket,
                url = staged.publicUrl,
                caption = caption.blankToNull(),
                linkedRecordType = linkedRecordType.blankToNull(),
                linkedRecordId = linkedRecordId.blankToNull(),
                recordedAt = Instant.now().toString(),
                location = location,
                processingRequests = resolvedProcessing
            )
        )
    }

    /** Delete a staged object that was cancelled before save. */
    suspend fun deleteStaged(objectKey: String) {
        api.deleteMediaObject(objectKey)
    }

    /**
     * PUT bytes to object storage with bounded retries and byte-level progress. Transient failures
     * (network drop, or a 5xx from S3 under concurrent load) are retried with linear backoff so a
     * single hiccup never loses an upload; a 4xx (bad signature etc.) fails fast. This is what makes
     * many files — and many researchers uploading at once — resilient. Runs on the calling IO thread.
     */
    private fun putToStorage(
        uploadUrl: String,
        headers: Map<String, String>,
        bytes: ByteArray,
        mimeType: String,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ) {
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                val body = ProgressRequestBody(bytes, mimeType.toMediaType(), onProgress)
                val builder = Request.Builder().url(uploadUrl).put(body)
                headers.forEach { (name, value) -> builder.header(name, value) }
                storageClient.newCall(builder.build()).execute().use { response ->
                    if (response.isSuccessful) return
                    // Client errors (4xx) won't fix themselves — fail immediately.
                    if (response.code < 500) {
                        throw IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                    }
                    lastError = IllegalStateException("Object storage upload failed: HTTP ${response.code}")
                }
            } catch (e: IOException) {
                lastError = e
            }
            if (attempt < maxAttempts) Thread.sleep(800L * attempt)
        }
        throw lastError ?: IllegalStateException("Object storage upload failed")
    }

    /** OkHttp body that streams a byte array in chunks, reporting cumulative bytes written. */
    private class ProgressRequestBody(
        private val bytes: ByteArray,
        private val contentType: MediaType?,
        private val onProgress: ((sent: Long, total: Long) -> Unit)?
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = bytes.size.toLong()
        override fun writeTo(sink: BufferedSink) {
            val total = bytes.size.toLong()
            val chunk = 32 * 1024
            var written = 0
            while (written < bytes.size) {
                val count = minOf(chunk, bytes.size - written)
                sink.write(bytes, written, count)
                written += count
                onProgress?.invoke(written.toLong(), total)
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) return cursor.getString(nameIndex)
        }
        return uri.lastPathSegment
    }

    private fun inferMediaType(mimeType: String): String = when {
        mimeType.startsWith("image/") -> "IMAGE"
        mimeType.startsWith("video/") -> "VIDEO"
        mimeType.startsWith("audio/") -> "AUDIO"
        mimeType == "application/pdf" -> "PDF"
        else -> "DOCUMENT"
    }

    /**
     * Standard field-archive filename:
     * `RECORDTYPE_RecordName_[SEGMENT]_TYPECODE_index_DDMMYYYYHHMMSS.ext`
     * e.g. `ARTISAN_RaviKumar_IMG_1_17062026093015.jpg`,
     *      `TOOL_Chisel_STAGE_STEP_2_VID_1_17062026093015.mp4`,
     *      `PROCESS_Weaving_STEP_1A_VID_1_17062026093015.mp4` (process step, sequential).
     *
     * [customSegment] (when present) replaces the stage segment — used by process steps to encode
     * the step number + per-file label (e.g. `STEP_1A`, `STEP_2_G1`, `PRE`).
     */
    private fun mediaFilename(
        recordType: String?,
        recordName: String?,
        mediaType: String,
        index: Int,
        stageStep: Int?,
        customSegment: String? = null,
        originalName: String
    ): String {
        val extension = originalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val typeCode = when (mediaType) {
            "IMAGE" -> "IMG"
            "AUDIO" -> "AUD"
            "VIDEO" -> "VID"
            "PDF" -> "DOC"
            else -> "DOC"
        }
        val timestamp = java.text.SimpleDateFormat("ddMMyyyyHHmmss", java.util.Locale.US).format(java.util.Date())
        val prefix = safeToken(recordType ?: "MEDIA").uppercase().ifBlank { "MEDIA" }
        val namePart = safeToken(recordName.blankToNull() ?: originalName.substringBeforeLast('.'))
        val segmentPart = customSegment?.trim()?.replace(Regex("[^A-Za-z0-9_]"), "")?.uppercase()?.takeIf { it.isNotBlank() }
            ?: stageStep?.let { "STAGE_STEP_$it" }
        val base = listOfNotNull(prefix, namePart, segmentPart, typeCode, index.toString(), timestamp)
            .joinToString("_")
        return if (extension == null) base else "$base.$extension"
    }

    /** Filename-safe token: strip whitespace and punctuation, preserve case, never empty. */
    private fun safeToken(value: String): String =
        value.trim()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(60)
            .ifBlank { "Record" }
}

private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
