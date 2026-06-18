package com.fieldrepository.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.fieldrepository.app.media.MediaSplitter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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

    /** Products the server has linked to a given artisan (covers datasets with >100 total products). */
    suspend fun productsForArtisan(artisanId: String): List<ProductDetailDto> =
        api.products(pageSize = 100, artisanId = artisanId).items

    suspend fun tools(): List<ToolDetailDto> = api.tools(pageSize = 100).items

    /** Artisans a tool is assigned to (many-to-many). */
    suspend fun toolArtisans(toolId: String): List<ArtisanDto> = api.toolArtisans(toolId)

    /** Assign a tool to the given artisans (idempotent). Returns the full updated assignment list. */
    suspend fun assignToolArtisans(toolId: String, artisanIds: List<String>): List<ArtisanDto> =
        api.assignToolArtisans(toolId, ToolArtisanAssignRequest(artisanIds))

    suspend fun unassignToolArtisan(toolId: String, artisanId: String) = api.unassignToolArtisan(toolId, artisanId)

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

    /**
     * Per-part byte budget above which an audio/video file is split before upload, or null for types
     * that are never split. Audio stays under the speech-to-text per-file cap (25 MB) so every part
     * transcribes; video is chunked into reliably-uploadable segments.
     */
    private fun splitLimitFor(mediaType: String): Long? = when (mediaType) {
        "AUDIO" -> 20L * 1024 * 1024
        "VIDEO" -> 96L * 1024 * 1024
        else -> null
    }

    /**
     * True when [uri] is a long audio/video that [uploadMedia] will split into parts. The eager
     * pre-upload skips these (a single staged blob can't be split afterwards) so the split happens at
     * save time instead.
     */
    fun willSplit(context: Context, uri: Uri): Boolean {
        val mimeType = context.contentResolver.getType(uri) ?: return false
        val limit = splitLimitFor(inferMediaType(mimeType)) ?: return false
        return queryContentSize(context, uri) > limit
    }

    /**
     * Upload a captured/selected file. A long audio/video file is first split into time-ordered
     * segments (each within [splitLimitFor]) and uploaded as PART_1, PART_2, … so it never fails as
     * one oversized blob and each audio part stays transcribable; everything else uploads as-is.
     * Splitting is fail-safe: if the media can't be re-muxed it is uploaded whole.
     */
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
        val limit = splitLimitFor(mediaType)
        val size = queryContentSize(context, uri)
        if (limit != null && size > limit) {
            val parts = withContext(Dispatchers.IO) {
                MediaSplitter.split(context, uri, limit, File(context.cacheDir, "field-captures"))
            }
            if (parts.size >= 2) {
                val totalBytes = parts.sumOf { it.length() }.coerceAtLeast(1L)
                var doneBytes = 0L
                var firstResult: MediaFileDto? = null
                try {
                    parts.forEachIndexed { partIndex, part ->
                        val partUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", part)
                        val partSegment = listOfNotNull(customSegment?.takeIf { it.isNotBlank() }, "PART_${partIndex + 1}").joinToString("_")
                        val base = doneBytes
                        val result = uploadResolved(
                            context = context,
                            uri = partUri,
                            mimeType = mimeType,
                            mediaType = mediaType,
                            originalName = originalName,
                            linkedRecordType = linkedRecordType,
                            linkedRecordId = linkedRecordId,
                            caption = caption?.let { "$it (part ${partIndex + 1} of ${parts.size})" },
                            location = location,
                            titleHint = titleHint,
                            batchIndex = batchIndex,
                            processingRequests = processingRequests,
                            stageStep = stageStep,
                            customSegment = partSegment,
                            onProgress = onProgress?.let { cb -> { sent, _ -> cb(base + sent, totalBytes) } }
                        )
                        if (firstResult == null) firstResult = result
                        doneBytes += part.length()
                    }
                } finally {
                    parts.forEach { runCatching { it.delete() } }
                }
                return firstResult!!
            }
        }
        return uploadResolved(
            context = context,
            uri = uri,
            mimeType = mimeType,
            mediaType = mediaType,
            originalName = originalName,
            linkedRecordType = linkedRecordType,
            linkedRecordId = linkedRecordId,
            caption = caption,
            location = location,
            titleHint = titleHint,
            batchIndex = batchIndex,
            processingRequests = processingRequests,
            stageStep = stageStep,
            customSegment = customSegment,
            onProgress = onProgress
        )
    }

    /** Single-object upload (no splitting). Streams straight from the Uri so the heap never holds the file. */
    private suspend fun uploadResolved(
        context: Context,
        uri: Uri,
        mimeType: String,
        mediaType: String,
        originalName: String,
        linkedRecordType: String?,
        linkedRecordId: String?,
        caption: String?,
        location: LocationRequest?,
        titleHint: String?,
        batchIndex: Int,
        processingRequests: List<String>?,
        stageStep: Int?,
        customSegment: String?,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ): MediaFileDto {
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
        // Stream the file straight from the content Uri to object storage — never load it fully into
        // memory — so even multi-hundred-MB videos upload without OOM. The size comes from metadata;
        // if that is unavailable we spool to a temp cache file on disk to obtain an exact length.
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val presign = api.presignMedia(
                MediaPresignRequest(
                    filename = filename,
                    mimeType = mimeType,
                    mediaType = mediaType,
                    sizeBytes = source.size,
                    linkedRecordType = linkedRecordType.blankToNull(),
                    linkedRecordId = linkedRecordId.blankToNull()
                )
            )
            withContext(Dispatchers.IO) {
                putToStorage(presign.uploadUrl, presign.headers, source.size, mimeType, source.open, onProgress)
            }
            return api.completeMedia(
                MediaCompleteRequest(
                    originalFilename = filename,
                    mediaType = mediaType,
                    mimeType = mimeType,
                    sizeBytes = source.size,
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
        } finally {
            source.cleanup()
        }
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
        val source = withContext(Dispatchers.IO) { resolveUploadSource(context, uri) }
        try {
            val provisional = "staged-${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(8)}" +
                (extension?.let { ".$it" } ?: "")
            val presign = api.presignMedia(
                MediaPresignRequest(
                    filename = provisional,
                    mimeType = mimeType,
                    mediaType = mediaType,
                    sizeBytes = source.size
                )
            )
            withContext(Dispatchers.IO) {
                putToStorage(presign.uploadUrl, presign.headers, source.size, mimeType, source.open, null)
            }
            return StagedMedia(
                objectKey = presign.objectKey,
                bucket = presign.bucket,
                publicUrl = presign.publicUrl,
                mimeType = mimeType,
                mediaType = mediaType,
                sizeBytes = source.size,
                extension = extension
            )
        } finally {
            source.cleanup()
        }
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
     * Analyse a grid-sheet photo for one dimension (length/breadth/height) and return the estimated
     * inches, or null if the model couldn't read it. A grid photo is small, so reading it into memory
     * is fine. Used by the "Document using grid" capture to auto-fill the measurement field.
     */
    suspend fun analyzeMeasurement(context: Context, uri: Uri, dimension: String): Double? {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-${dimension}.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, dimension)
        return response.analysis?.valueInches
    }

    /**
     * Analyse a single grid-sheet photo for BOTH length and breadth at once (the object's footprint
     * on the grid). Calls the measurement endpoint with no dimension, which returns the legacy
     * length+breadth pair. Returns (lengthInches, breadthInches); either may be null if unread.
     */
    suspend fun analyzeMeasurementLengthBreadth(context: Context, uri: Uri): Pair<Double?, Double?> {
        val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to open the captured image")
        }
        val part = okhttp3.MultipartBody.Part.createFormData(
            "file",
            "grid-length-breadth.jpg",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        val response = api.analyzeMeasurement(part, null)
        val analysis = response.analysis
        return (analysis?.lengthInches) to (analysis?.breadthInches)
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
        contentLength: Long,
        mimeType: String,
        openStream: () -> InputStream,
        onProgress: ((sent: Long, total: Long) -> Unit)?
    ) {
        val maxAttempts = 3
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                // A fresh stream per attempt so a retry re-reads from the start.
                val body = StreamingRequestBody(contentLength, mimeType.toMediaType(), openStream, onProgress)
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

    /** A re-openable upload source: exact byte size, a fresh stream per attempt, and cleanup. */
    private class UploadSource(val size: Long, val open: () -> InputStream, val cleanup: () -> Unit)

    /** Content-provider SIZE column, or 0 if unknown. */
    private fun queryContentSize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) cursor.getLong(index) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    /**
     * Build an [UploadSource] that streams from disk, not memory. When the provider exposes a SIZE we
     * stream straight from the content Uri (re-opened per retry). When it doesn't, we spool the bytes
     * to a temp cache file (streamed copy, never a giant in-memory array) to learn the exact length,
     * then stream from that file. Either way the heap never holds the whole video.
     */
    private fun resolveUploadSource(context: Context, uri: Uri): UploadSource {
        val size = queryContentSize(context, uri)
        if (size > 0L) {
            return UploadSource(
                size = size,
                open = {
                    context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Unable to open selected media")
                },
                cleanup = {}
            )
        }
        val temp = File.createTempFile("upload-", ".bin", context.cacheDir)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { out -> input.copyTo(out, 64 * 1024) }
            } ?: throw IllegalStateException("Unable to open selected media")
        }.onFailure { temp.delete(); throw it }
        return UploadSource(
            size = temp.length(),
            open = { FileInputStream(temp) },
            cleanup = { runCatching { temp.delete() } }
        )
    }

    /** OkHttp body that streams an InputStream in 64 KB chunks, reporting cumulative bytes written. */
    private class StreamingRequestBody(
        private val length: Long,
        private val contentType: MediaType?,
        private val openStream: () -> InputStream,
        private val onProgress: ((sent: Long, total: Long) -> Unit)?
    ) : RequestBody() {
        override fun contentType(): MediaType? = contentType
        override fun contentLength(): Long = length
        override fun writeTo(sink: BufferedSink) {
            openStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress?.invoke(sent, length)
                }
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
